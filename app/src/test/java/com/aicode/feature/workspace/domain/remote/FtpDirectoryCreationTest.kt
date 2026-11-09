package com.aicode.feature.workspace.domain.remote

import com.aicode.feature.workspace.domain.remote.ftp.FtpSyncClient
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ServerSocket

class FtpDirectoryCreationTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: FtpServer
    private lateinit var root: File
    private var port = 0

    @Before
    fun startServer() {
        root = temp.newFolder("root")
        port = ServerSocket(0).use { it.localPort }
        val users = PropertiesUserManagerFactory().apply {
            file = temp.newFile("users.properties")
            passwordEncryptor = ClearTextPasswordEncryptor()
        }.createUserManager()
        for (writable in listOf(false, true)) {
            users.save(BaseUser().apply {
                name = if (writable) "writer" else "reader"
                password = "password"
                homeDirectory = root.absolutePath
                authorities = if (writable) listOf(WritePermission()) else emptyList()
            })
        }
        server = FtpServerFactory().apply {
            userManager = users
            addListener("default", ListenerFactory().apply {
                serverAddress = "127.0.0.1"
                port = this@FtpDirectoryCreationTest.port
            }.createListener())
        }.createServer()
        server.start()
    }

    @After
    fun stopServer() { if (::server.isInitialized) server.stop() }

    private fun withClient(username: String = "writer", operation: suspend FtpSyncClient.() -> Unit) = runBlocking {
        FtpSyncClient().withConnection("127.0.0.1", port, username, RemoteAuth.Password("password")) {
            operation(this as FtpSyncClient)
        }
    }

    @Test
    fun creates_chinese_directory_with_spaces() {
        withClient { createChildDirectory("/", "项目 folder") }
        assertTrue(File(root, "项目 folder").isDirectory)
    }

    @Test
    fun reports_permission_denied_instead_of_success() {
        val error = assertThrows(IOException::class.java) {
            withClient("reader") { createDirectoryExclusive("/forbidden") }
        }
        assertTrue(error.message.orEmpty().contains("550"))
        assertFalse(File(root, "forbidden").exists())
    }

    @Test
    fun rejects_existing_directory_even_if_created_between_listing_and_mkdir() {
        temp.newFolder("root", "existing")
        assertThrows(IOException::class.java) {
            withClient { createDirectoryExclusive("/existing") }
        }
        assertTrue(File(root, "existing").isDirectory)
    }

    @Test
    fun rejects_existing_file_without_overwriting() {
        File(root, "existing").writeText("keep")
        assertThrows(RemoteDirectoryAlreadyExistsException::class.java) {
            withClient { createChildDirectory("/", "existing") }
        }
        assertTrue(File(root, "existing").readText() == "keep")
    }

    @Test
    fun strict_creation_does_not_create_missing_parents() {
        assertThrows(IOException::class.java) {
            withClient { createDirectoryExclusive("/missing/child") }
        }
        assertFalse(File(root, "missing").exists())
    }

    @Test
    fun reports_directory_listing_failure_instead_of_an_empty_directory() {
        assertThrows(IOException::class.java) {
            withClient { listFiles("/missing") }
        }
    }

    @Test
    fun recursive_creation_remains_available_for_sync() {
        withClient { createDirectory("/nested/child") }
        assertTrue(File(root, "nested/child").isDirectory)
    }
}
