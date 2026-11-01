package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CommitActionsGitTest {
    @Test
    fun checkoutCreateTagAndSafeDeleteWorkAgainstRealGit() = runTest {
        GitTestRepo.create().use { repo ->
            repo.writeFile("README.md", "root\n")
            val root = repo.commitAll("root")
            repo.checkoutNewBranch("topic").requireSuccess()
            val topic = repo.commitFile("topic.txt", "topic\n", "topic")
            repo.checkout("main").requireSuccess()
            val backend = CliGitBackend.open(repo.workTree)

            assertTrue(backend.checkoutLocal("topic") is GitResult.Ok)
            assertEquals("topic", backend.head().branchShortName)

            assertTrue(backend.checkoutDetached(root) is GitResult.Ok)
            assertEquals(root, backend.head().commit)
            assertEquals(null, backend.head().branchShortName)

            assertTrue(backend.createBranchAndCheckout("feature/from-topic", topic) is GitResult.Ok)
            assertEquals("feature/from-topic", backend.head().branchShortName)

            assertTrue(backend.createTag("v-test", root) is GitResult.Ok)
            assertTrue(backend.refs().any { it.fullName == "refs/tags/v-test" && it.commit == root })

            backend.checkoutLocal("main")
            assertTrue(backend.createBranchAndCheckout("delete-me", root) is GitResult.Ok)
            backend.checkoutLocal("main")
            assertTrue(backend.deleteLocalBranch("delete-me", force = false) is GitResult.Ok)
            assertFalse(backend.refs().any { it.shortName == "delete-me" })

            assertTrue(backend.isCommitReachableFromHead(root))
            assertFalse(backend.isCommitReachableFromHead(topic))
            assertEquals(
                setOf("topic", "feature/from-topic"),
                backend.localBranchesContaining(topic).toSet(),
            )
        }
    }

    @Test
    fun remoteCheckoutAndRemoteDeleteWorkAgainstRealGit() = runTest {
        GitTestRepo.create().use { repo ->
            repo.writeFile("README.md", "root\n")
            repo.commitAll("root")
            repo.createBareOrigin("origin")
            repo.push().requireSuccess()

            repo.checkoutNewBranch("remote-topic").requireSuccess()
            repo.commitFile("remote.txt", "remote\n", "remote work")
            repo.push(refspec = "remote-topic", setUpstream = false).requireSuccess()
            repo.checkout("main").requireSuccess()
            val backend = CliGitBackend.open(repo.workTree)

            assertTrue(backend.checkoutRemoteAsLocal("local-topic", "origin/remote-topic") is GitResult.Ok)
            assertEquals("local-topic", backend.head().branchShortName)

            backend.checkoutLocal("main")
            assertTrue(backend.deleteRemoteBranch("origin", "remote-topic") is GitResult.Ok)
            assertFalse(backend.refs().any { it.shortName == "origin/remote-topic" })
        }
    }
}
