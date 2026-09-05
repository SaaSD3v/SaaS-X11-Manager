package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class GraphicSessionUser(
    val name: String,
    val uid: Int,
    val gid: Int,
    val home: String,
    val shell: String
) {
    val isRoot: Boolean get() = uid == 0 || name == "root"
}

data class GraphicSessionUserSelection(
    val userName: String,
    val createIfMissing: Boolean = false
) {
    companion object {
        val ROOT = GraphicSessionUserSelection("root")
    }
}

data class GraphicSessionUserPreparation(
    val selection: GraphicSessionUserSelection,
    val changed: Boolean
)

/**
 * Keeps graphical user handling deliberately small.
 *
 * The Manager only selects an existing login-capable user or records a request
 * to create a basic user when the graphical session launcher starts. Package
 * installation and system configuration remain root-owned. A non-root session
 * owns its own home and the files it creates there.
 */
object GraphicSessionUserManager {
    private const val SETTINGS_DIR = "/etc/saas-x11-manager"
    private const val SETTINGS_FILE = "$SETTINGS_DIR/session-user"
    private const val SESSION_LAUNCHER = "/usr/local/bin/x11-session.sh"

    // Keep a conservative shell-safe subset while preserving Linux's
    // case-sensitive account names. Existing users such as `SaaS`, and newly
    // created users such as `UserX`, must not be silently rewritten to lower-case.
    private val validUserName = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,31}$")
    private val selectedForStart = ConcurrentHashMap<String, GraphicSessionUserSelection>()

    fun isValidUserName(value: String): Boolean = validUserName.matches(value)

    fun selectForNextStart(containerName: String, selection: GraphicSessionUserSelection) {
        require(isValidUserName(selection.userName)) { "Invalid Linux user name" }
        selectedForStart[containerName] = selection
    }

    fun selectedForNextStart(containerName: String): GraphicSessionUserSelection? =
        selectedForStart[containerName]

    internal fun parsePasswd(lines: List<String>): List<GraphicSessionUser> =
        lines.mapNotNull { line ->
            val parts = line.split(':')
            if (parts.size < 7) return@mapNotNull null
            val name = parts[0]
            val uid = parts[2].toIntOrNull() ?: return@mapNotNull null
            val gid = parts[3].toIntOrNull() ?: return@mapNotNull null
            val home = parts[5]
            val shell = parts[6]
            val loginCapable = shell.isNotBlank() &&
                !shell.endsWith("/false") &&
                !shell.endsWith("/nologin")
            val visible = name == "root" || uid in 1000 until 65000
            if (!visible || !loginCapable || !home.startsWith('/')) return@mapNotNull null
            GraphicSessionUser(name, uid, gid, home, shell)
        }
            .distinctBy { it.name }
            .sortedWith(compareBy<GraphicSessionUser> { it.isRoot }.thenBy { it.uid }.thenBy { it.name })

    suspend fun listUsers(containerName: String): List<GraphicSessionUser> =
        withContext(Dispatchers.IO) {
            val info = ContainerManager.getContainerInfo(containerName) ?: return@withContext emptyList()
            val lines = if (info.isRunning) {
                runContainerCommand(containerName, "cat /etc/passwd 2>/dev/null").out
            } else {
                RootfsAccessor.use(
                    rootfsPath = info.rootfsPath,
                    tag = "users_$containerName"
                ) { root ->
                    val result = Shell.cmd("cat ${shellQuote("$root/etc/passwd")} 2>/dev/null").exec()
                    if (result.isSuccess) result.out else emptyList()
                }.orEmpty()
            }
            parsePasswd(lines)
        }

    /**
     * Persists the selected user and refreshes the generic session launcher
     * before the normal X11 lifecycle begins. Stopped containers are edited
     * through their rootfs; running containers use the DroidSpaces command
     * channel. No container lifecycle is changed here.
     */
    suspend fun prepareForStart(
        containerName: String,
        session: GraphicSession,
        logger: ContainerLogger? = null
    ): GraphicSessionUserPreparation? = withContext(Dispatchers.IO) {
        val info = ContainerManager.getContainerInfo(containerName) ?: run {
            logger?.e("[-] Could not resolve container while preparing graphical user")
            return@withContext null
        }
        val requested = selectedForStart[containerName]
            ?: readPersistedSelection(info)
            ?: GraphicSessionUserSelection.ROOT
        if (!isValidUserName(requested.userName)) {
            logger?.e("[-] Invalid graphical user name: ${requested.userName}")
            return@withContext null
        }

        val previous = readPersistedSelection(info)
        if (!writePersistedSelection(info, requested)) {
            logger?.e("[-] Could not persist graphical user selection for $containerName")
            return@withContext null
        }
        if (!writeCurrentSessionLauncher(info, session)) {
            logger?.e("[-] Could not refresh the user-aware graphical session launcher")
            return@withContext null
        }

        logger?.i("[CTX] Graphic user: ${requested.userName}")
        if (requested.createIfMissing) {
            logger?.i("[CTX] User policy: create basic account if missing")
        } else {
            logger?.i("[CTX] User policy: existing account required")
        }
        logger?.i("[+] User-aware graphical session launcher ready")
        GraphicSessionUserPreparation(requested, previous != requested)
    }

    private fun readPersistedSelection(info: ContainerInfo): GraphicSessionUserSelection? {
        val lines = if (info.isRunning) {
            runContainerCommand(info.name, "cat $SETTINGS_FILE 2>/dev/null").out
        } else {
            RootfsAccessor.use(
                rootfsPath = info.rootfsPath,
                tag = "graphic_user_read_${info.name}"
            ) { root ->
                val result = Shell.cmd("cat ${shellQuote("$root$SETTINGS_FILE")} 2>/dev/null").exec()
                if (result.isSuccess) result.out else emptyList()
            }.orEmpty()
        }
        if (lines.isEmpty()) return null
        val values = lines.mapNotNull { line ->
            val pieces = line.trim().split('=', limit = 2)
            if (pieces.size == 2) pieces[0] to pieces[1] else null
        }.toMap()
        val user = values["user"]?.takeIf(::isValidUserName) ?: return null
        return GraphicSessionUserSelection(
            userName = user,
            createIfMissing = values["create"] == "1"
        )
    }

    private fun writePersistedSelection(
        info: ContainerInfo,
        selection: GraphicSessionUserSelection
    ): Boolean {
        val create = if (selection.createIfMissing) "1" else "0"
        val body = "user=${selection.userName}\ncreate=$create\n"
        return if (info.isRunning) {
            val command =
                "mkdir -p $SETTINGS_DIR && chmod 755 $SETTINGS_DIR && " +
                    "printf '%s' ${shellQuote(body)} > $SETTINGS_FILE && chmod 600 $SETTINGS_FILE"
            runContainerCommand(info.name, command).isSuccess
        } else {
            RootfsAccessor.use(
                rootfsPath = info.rootfsPath,
                tag = "graphic_user_write_${info.name}"
            ) { root ->
                val dir = shellQuote("$root$SETTINGS_DIR")
                val file = shellQuote("$root$SETTINGS_FILE")
                Shell.cmd(
                    "mkdir -p $dir && chmod 755 $dir && " +
                        "printf '%s' ${shellQuote(body)} > $file && chmod 600 $file"
                ).exec().isSuccess
            } ?: false
        }
    }

    private fun writeCurrentSessionLauncher(
        info: ContainerInfo,
        session: GraphicSession
    ): Boolean {
        val shell = when (info.initSystem) {
            InitSystem.OPENRC -> "/bin/sh"
            InitSystem.SYSTEMD -> "/bin/bash"
        }
        val script = GraphicSessionInitFiles.sessionScript(session, shell)
        return if (info.isRunning) {
            val command =
                "mkdir -p /usr/local/bin && " +
                    "printf '%s' ${shellQuote(script)} > $SESSION_LAUNCHER && " +
                    "chmod 755 $SESSION_LAUNCHER"
            runContainerCommand(info.name, command).isSuccess
        } else {
            RootfsAccessor.use(
                rootfsPath = info.rootfsPath,
                tag = "graphic_user_launcher_${info.name}"
            ) { root ->
                val directory = shellQuote("$root/usr/local/bin")
                val launcher = shellQuote("$root$SESSION_LAUNCHER")
                Shell.cmd(
                    "mkdir -p $directory && " +
                        "printf '%s' ${shellQuote(script)} > $launcher && " +
                        "chmod 755 $launcher"
                ).exec().isSuccess
            } ?: false
        }
    }

    private fun runContainerCommand(containerName: String, command: String) =
        Shell.cmd(
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
                "sh -c ${shellQuote(command)}"
        ).exec()

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
