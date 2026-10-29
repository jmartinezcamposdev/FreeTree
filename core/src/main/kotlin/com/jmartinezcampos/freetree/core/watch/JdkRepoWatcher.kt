package com.jmartinezcampos.freetree.core.watch

import com.jmartinezcampos.freetree.core.model.GitDirs
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class JdkRepoWatcher(
    private val onFailure: (Throwable) -> Unit = {},
    private val onWarning: (String) -> Unit = {},
    private val workTreeDirectoryCap: Int = 20_000,
) : RepoWatcher {
    private var service: WatchService? = null
    private var thread: Thread? = null
    private var scope: CoroutineScope? = null
    private var debouncer: Debouncer? = null
    private val stopped = AtomicBoolean(true)

    @Volatile
    var partialWatch: Boolean = false
        private set

    @Volatile
    var lastStartFailure: Throwable? = null
        private set

    @Synchronized
    override fun start(dirs: GitDirs, watchWorkTree: Boolean, onEvent: (WatchEvent) -> Unit) {
        stop()

        stopped.set(false)
        partialWatch = false
        lastStartFailure = null
        val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val debouncer = Debouncer(watchScope, onEmit = onEvent)
        val watchService = try {
            FileSystems.getDefault().newWatchService()
        } catch (e: IOException) {
            watchScope.cancel()
            reportStartFailure(e)
            return
        }

        val registrar = Registrar(watchService, dirs, workTreeDirectoryCap) {
            partialWatch = true
            onWarning("Watching only part of the work tree: directory cap reached.")
        }
        try {
            registrar.registerBase(watchWorkTree)
        } catch (e: IOException) {
            debouncer.close()
            watchScope.cancel()
            watchService.close()
            reportStartFailure(e)
            return
        }

        val watcherThread = Thread(
            { watchLoop(watchService, debouncer, registrar, dirs) },
            "freetree-repo-watcher",
        ).apply {
            isDaemon = true
            start()
        }

        this.service = watchService
        this.scope = watchScope
        this.debouncer = debouncer
        this.thread = watcherThread
    }

    @Synchronized
    override fun stop() {
        stopped.set(true)
        debouncer?.close()
        scope?.cancel()
        service?.close()
        thread?.interrupt()
        if (thread !== Thread.currentThread()) {
            try {
                thread?.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        debouncer = null
        scope = null
        service = null
        thread = null
    }

    private fun reportStartFailure(error: Throwable) {
        stopped.set(true)
        lastStartFailure = error
        onFailure(error)
    }

    private fun watchLoop(
        watchService: WatchService,
        debouncer: Debouncer,
        registrar: Registrar,
        dirs: GitDirs,
    ) {
        while (!stopped.get()) {
            val key: WatchKey = try {
                watchService.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: ClosedWatchServiceException) {
                return
            }

            try {
                val watchedDirectory = key.watchable() as Path
                for (event in key.pollEvents()) {
                    val relative = event.context() as? Path ?: continue
                    val path = watchedDirectory.resolve(relative)
                    if (event.kind() == ENTRY_CREATE && isDirectory(path)) {
                        registrar.registerNewDirectory(path)
                    }
                    EventClassifier.classify(path, dirs)?.let(debouncer::offer)
                }
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: IOException) {
                onFailure(e)
            } finally {
                val valid = try {
                    key.reset()
                } catch (_: ClosedWatchServiceException) {
                    false
                }
                if (!valid) {
                    registrar.forget(key.watchable() as? Path)
                }
            }
        }
    }

    private fun isDirectory(path: Path): Boolean =
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

    private class Registrar(
        private val service: WatchService,
        private val dirs: GitDirs,
        private val workTreeDirectoryCap: Int,
        private val onPartialWatch: () -> Unit,
    ) {
        private val registered = mutableSetOf<Path>()
        private var workTreeDirectories = 0
        private var partial = false
        private val commonDir = dirs.commonDir.toAbsolutePath().normalize()
        private val gitDir = dirs.gitDir.toAbsolutePath().normalize()
        private val workTree = dirs.workTree.toAbsolutePath().normalize()

        fun registerBase(watchWorkTree: Boolean) {
            registerDirectory(commonDir)
            val refs = commonDir.resolve("refs")
            if (isDirectory(refs)) registerTree(refs, countTowardsWorkTree = false)
            registerDirectory(gitDir)
            registerTreeIfPresent(gitDir.resolve("rebase-merge"))
            registerTreeIfPresent(gitDir.resolve("rebase-apply"))
            if (watchWorkTree) {
                registerTree(workTree, countTowardsWorkTree = true)
            }
        }

        fun registerNewDirectory(path: Path) {
            val normalized = path.toAbsolutePath().normalize()
            when {
                normalized.startsWith(commonDir.resolve("refs")) ->
                    registerTree(normalized, countTowardsWorkTree = false)
                normalized.startsWith(gitDir.resolve("rebase-merge")) ||
                    normalized.startsWith(gitDir.resolve("rebase-apply")) ->
                    registerTree(normalized, countTowardsWorkTree = false)
                normalized.startsWith(workTree) -> {
                    val relative = workTree.relativize(normalized)
                    if (relative.any { EXCLUDED_DIRECTORIES.contains(it.toString()) }) return
                    registerTree(normalized, countTowardsWorkTree = true)
                }
            }
        }

        fun forget(path: Path?) {
            if (path != null) registered.remove(path.toAbsolutePath().normalize())
        }

        private fun registerTreeIfPresent(root: Path) {
            if (isDirectory(root)) registerTree(root, countTowardsWorkTree = false)
        }

        private fun registerDirectory(directory: Path) {
            val normalized = directory.toAbsolutePath().normalize()
            if (!isDirectory(normalized)) throw NoSuchFileException(normalized.toString())
            if (!registered.add(normalized)) return
            try {
                normalized.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            } catch (e: NoSuchFileException) {
                registered.remove(normalized)
                throw e
            }
        }

        private fun registerTree(root: Path, countTowardsWorkTree: Boolean) {
            val pending = ArrayDeque<Path>()
            pending.add(root.toAbsolutePath().normalize())
            while (pending.isNotEmpty()) {
                if (partial && countTowardsWorkTree) return
                val directory = pending.removeLast()
                if (!isDirectory(directory)) continue

                if (countTowardsWorkTree && workTreeDirectories >= workTreeDirectoryCap) {
                    partial = true
                    onPartialWatch()
                    return
                }

                if (registered.add(directory)) {
                    try {
                        directory.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                    } catch (_: NoSuchFileException) {
                        // The directory was removed while its tree was being registered.
                        registered.remove(directory)
                        continue
                    }
                    if (countTowardsWorkTree) workTreeDirectories += 1
                }

                try {
                    Files.newDirectoryStream(directory).use { children ->
                        for (child in children) {
                            if (!isDirectory(child)) continue
                            val name = child.fileName?.toString().orEmpty()
                            if (countTowardsWorkTree && EXCLUDED_DIRECTORIES.contains(name)) continue
                            pending.add(child)
                        }
                    }
                } catch (_: IOException) {
                    // A directory can disappear while the tree is being registered.
                }
            }
        }

        private fun isDirectory(path: Path): Boolean =
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

        companion object {
            private val EXCLUDED_DIRECTORIES = setOf(
                ".git",
                "node_modules",
                "build",
                "target",
                ".gradle",
                ".idea",
                "dist",
                "out",
                "__pycache__",
                ".venv",
            )
        }
    }
}
