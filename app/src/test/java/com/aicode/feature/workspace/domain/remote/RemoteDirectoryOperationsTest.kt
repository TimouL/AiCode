package com.aicode.feature.workspace.domain.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RemoteDirectoryOperationsTest {
    @Test
    fun rejects_empty_names_traversal_separators_and_control_characters() {
        listOf("", "  ", ".", " .. ", "a/b", "a\\b", "a\u0000b", "a\nb", "folder\r\n").forEach { name ->
            assertFalse("Accepted $name", RemoteDirectoryNames.isValid(name))
            assertThrows(InvalidRemoteDirectoryNameException::class.java) {
                RemoteDirectoryNames.childPath("/parent/", name)
            }
        }
    }

    @Test
    fun accepts_chinese_spaces_and_hidden_directory_names() {
        listOf("项目", "my project", ".config", "  项目  ").forEach {
            assertTrue(RemoteDirectoryNames.isValid(it))
        }
        assertEquals("/项目", RemoteDirectoryNames.childPath("/", " 项目 "))
        assertEquals("/parent/my project", RemoteDirectoryNames.childPath("/parent///", "my project"))
        assertEquals("relative/child", RemoteDirectoryNames.childPath("relative/", "child"))
    }

    @Test
    fun creates_one_child_in_current_directory() = runTest {
        val client = FakeClient()
        assertEquals("/项目", client.createChildDirectory("/", " 项目 "))
        assertEquals(listOf("/项目"), client.created)
    }

    @Test
    fun rejects_existing_files_and_directories_before_creating() = runTest {
        for (isDirectory in listOf(false, true)) {
            val client = FakeClient(listOf(RemoteFileInfo("existing", isDirectory, 0, 0)))
            try {
                client.createChildDirectory("/parent/", "existing")
                error("Expected name conflict")
            } catch (e: RemoteDirectoryAlreadyExistsException) {
                assertEquals("existing", e.directoryName)
            }
            assertTrue(client.created.isEmpty())
        }
    }

    @Test
    fun does_not_create_if_parent_listing_fails() = runTest {
        val client = FakeClient(listFailure = IOException("Permission denied"))
        try {
            client.createChildDirectory("/parent/", "child")
            error("Expected listing failure")
        } catch (e: IOException) {
            assertEquals("Permission denied", e.message)
        }
        assertTrue(client.created.isEmpty())
    }

    @Test
    fun closes_connection_after_success() = runTest {
        val client = FakeClient()
        val result = client.withConnection("host", 22, "user", RemoteAuth.Password("password")) {
            createChildDirectory("/", "child")
        }
        assertEquals("/child", result)
        assertEquals(listOf("connect", "list", "create", "disconnect"), client.events)
    }

    @Test
    fun closes_connection_after_connection_failure() = runTest {
        val client = FakeClient(connectFailure = IOException("Unable to connect"))
        try {
            client.withConnection("host", 22, "user", RemoteAuth.Password("password")) { error("Must not run") }
            error("Expected connection failure")
        } catch (e: IOException) {
            assertEquals("Unable to connect", e.message)
        }
        assertEquals(listOf("connect", "disconnect"), client.events)
    }

    @Test
    fun closes_connection_after_creation_failure() = runTest {
        val client = FakeClient(createFailure = IOException("Permission denied"))
        try {
            client.withConnection("host", 22, "user", RemoteAuth.Password("password")) {
                createChildDirectory("/", "child")
            }
            error("Expected creation failure")
        } catch (e: IOException) {
            assertEquals("Permission denied", e.message)
        }
        assertEquals("disconnect", client.events.last())
    }

    @Test
    fun propagates_cancellation_and_closes_connection() = runTest {
        val client = FakeClient(createFailure = CancellationException("Cancelled"))
        try {
            client.withConnection("host", 22, "user", RemoteAuth.Password("password")) {
                createChildDirectory("/", "child")
            }
            error("Expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("Cancelled", e.message)
        }
        assertEquals("disconnect", client.events.last())
    }

    @Test
    fun closes_connection_even_when_the_calling_job_is_cancelled() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = FakeClient(createStarted = started)
        val job = launch {
            client.withConnection("host", 22, "user", RemoteAuth.Password("password")) {
                createChildDirectory("/", "child")
            }
        }
        started.await()
        job.cancelAndJoin()
        assertEquals("disconnect", client.events.last())
    }

    private class FakeClient(
        val files: List<RemoteFileInfo> = emptyList(),
        val connectFailure: Exception? = null,
        val listFailure: Exception? = null,
        val createFailure: Exception? = null,
        val createStarted: CompletableDeferred<Unit>? = null
    ) : RemoteSyncClient {
        val created = mutableListOf<String>()
        val events = mutableListOf<String>()
        override suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth) {
            events += "connect"
            connectFailure?.let { throw it }
        }
        override suspend fun disconnect() {
            delay(1)
            events += "disconnect"
        }
        override suspend fun listFiles(remotePath: String): List<RemoteFileInfo> {
            events += "list"
            listFailure?.let { throw it }
            return files
        }
        override suspend fun createDirectoryExclusive(remotePath: String) {
            events += "create"
            createStarted?.let { it.complete(Unit); awaitCancellation() }
            createFailure?.let { throw it }
            created += remotePath
        }
        override suspend fun createDirectory(remotePath: String) = error("Recursive create must not be used")
        override suspend fun downloadFile(remotePath: String, localPath: String) = Unit
        override suspend fun uploadFile(localPath: String, remotePath: String) = Unit
        override suspend fun delete(remotePath: String) = Unit
        override suspend fun isConnected() = true
        override suspend fun reconnect(host: String, port: Int, username: String, auth: RemoteAuth) = Unit
    }
}
