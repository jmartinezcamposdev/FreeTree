package com.jmartinezcampos.freetree.app.ui

import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import java.nio.file.Path

internal class UiFixture(
    val backend: FakeGitBackend,
    val manager: SessionManager,
) : AutoCloseable {
    override fun close() {
        manager.close()
    }
}

internal fun uiFixture(
    commits: List<RawCommit>,
    refs: List<GitRef> = emptyList(),
    head: HeadInfo = HeadInfo(null, null),
    status: WorkingStatus = WorkingStatus(null, null, 0, 0, false, emptyList()),
    commitDetails: Map<String, com.jmartinezcampos.freetree.core.model.CommitDetail> = emptyMap(),
    changedFiles: Map<String, List<FileChange>> = emptyMap(),
    diffs: Map<DiffTarget, DiffText> = emptyMap(),
    path: String = "/repo/ui",
): UiFixture {
    val backend = FakeGitBackend(
        commits = commits,
        refs = refs,
        head = head,
        status = status,
        commitDetails = commitDetails,
        changedFiles = changedFiles,
        diffs = diffs,
    )
    val manager = SessionManager(
        backendFactory = { backend },
        watcherFactory = { FakeWatcher() },
        persistence = InMemorySessionPersistence(),
        restore = false,
    )
    manager.openRepo(Path.of(path))
    return UiFixture(backend, manager)
}
