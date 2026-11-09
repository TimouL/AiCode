# 远程目标目录新建功能实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在远程目录选择器创建一级子目录，成功后进入新目录，确认后回填挂载表单。

**Architecture:** UI 使用生命周期内的挂起调用加载与创建目录；ViewModel 转换用户可见错误；Repository 解析现有连接并使用临时客户端。新增严格的单级创建接口，保留同步使用的递归创建行为，并在异常和取消时清理连接。

**Tech Stack:** Kotlin、Compose、Coroutines、SSHJ SFTP、Apache Commons Net FTP、JUnit。

### Task 1: 目录校验与协议创建

- 新增 `app/src/main/java/com/aicode/feature/workspace/domain/remote/RemoteDirectoryOperations.kt`。
- 修改 `RemoteSyncClient.kt`、`sftp/SftpSyncClient.kt`、`ftp/FtpSyncClient.kt`。
- 先增加 `RemoteDirectoryOperationsTest.kt` 与 `FtpDirectoryCreationTest.kt`，验证非法名称、中文和空格、根目录路径、同名文件和目录、FTP 权限拒绝与单级创建。
- 运行 `./gradlew :app:testUniversalDebugUnitTest --tests '*RemoteDirectoryOperationsTest' --tests '*FtpDirectoryCreationTest'`，观察缺失功能导致失败，再实现并运行通过。
- 创建路径为父路径去除末尾 `/` 后拼接 `/` 和去除两端空格的名称。原始名称含路径分隔符、控制字符，或去空格后为空、`.`、`..` 时拒绝。
- SFTP 使用 `mkdir`；FTP 检查 `makeDirectory` 返回值，失败抛出包含服务端回复的异常。

### Task 2: 连接生命周期与界面

- 修改 `domain/repository/RemoteRepository.kt`、`presentation/remote/RemoteServerViewModel.kt`、`RemoteServerScreen.kt`、`RemoteDialogs.kt`。
- 增加连接清理的测试，覆盖创建成功、操作失败、连接失败和协程取消。
- 浏览与创建回调改为挂起函数，浏览使用 `LaunchedEffect(connectionId, currentPath)`，创建使用 `rememberCoroutineScope`；过期或关闭后的请求不会更新弹窗。
- 新建名称使用 `AppTextField` 和弹窗配色，创建时禁止重复提交、导航和关闭；失败保留输入，成功进入返回路径。
- 浏览失败仍可返回上一级或重试；只有加载成功时可新建和确认选择。

### Task 3: 文案、文档和验证

- 修改 `app/src/main/res/values/strings.xml`、`app/src/main/res/values-en/strings.xml`、`docs-site/docs/guide/sync.md`。
- 运行相关单元测试与 `./gradlew :app:assembleUniversalDebug`，检查路径回填、空目录创建与资源引用。
- 自查完整 diff，验证所有接口实现和调用点已同步；将验证后的功能改动应用回用户工作区，保留已有 `.gitignore` 修改。
