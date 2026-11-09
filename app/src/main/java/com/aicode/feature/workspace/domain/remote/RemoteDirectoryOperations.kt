package com.aicode.feature.workspace.domain.remote

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class InvalidRemoteDirectoryNameException : IllegalArgumentException("Invalid directory name")

class RemoteDirectoryAlreadyExistsException(val directoryName: String) :
    IllegalStateException("A file or directory named $directoryName already exists")

object RemoteDirectoryNames {
    fun isValid(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && trimmed != "." && trimmed != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }
    }

    fun childPath(parentPath: String, name: String): String {
        if (!isValid(name)) throw InvalidRemoteDirectoryNameException()
        return "${parentPath.trimEnd('/')}/${name.trim()}"
    }
}

/** 浏览器只创建一级目录；同名文件和目录都拒绝，协议层仍需检查并发创建的冲突。 */
internal suspend fun RemoteSyncClient.createChildDirectory(parentPath: String, name: String): String {
    val childPath = RemoteDirectoryNames.childPath(parentPath, name)
    if (listFiles(parentPath).any { it.name == name.trim() }) {
        throw RemoteDirectoryAlreadyExistsException(name.trim())
    }
    currentCoroutineContext().ensureActive()
    createDirectoryExclusive(childPath)
    return childPath
}

/** 临时浏览连接：连接失败、操作失败或取消时都释放客户端，不影响已有同步连接。 */
internal suspend fun <T> RemoteSyncClient.withConnection(
    host: String,
    port: Int,
    username: String,
    auth: RemoteAuth,
    operation: suspend RemoteSyncClient.() -> T
): T {
    try {
        connect(host, port, username, auth)
        currentCoroutineContext().ensureActive()
        return operation()
    } finally {
        withContext(NonCancellable) { runCatching { disconnect() } }
    }
}
