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

    // Preserve Linux case-sensitive names such as SaaS and UserX.
    private val validUserName = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,31}$")
    private val selectedForStart = ConcurrentHashMap<String, GraphicSessionUserSelection>()

    fun isValidUserName(value: String): Boolean = validUserName.matches(value)

    fun selectForNextStart(containerName: String, selection: GraphicSessionUserSelection) {
        require(isValidUserName(selection.userName)) { "Invalid Linux user name" }
        selectedForStart[containerName] = selection
    }

    fun selectedForNextStart(containerName: String): GraphicSessionUserSelection? =
        selectedForStart[containerName]

    internal fun consumePreparedSelection(
        containerName: String,
        selection: GraphicSessionUserSelection
    ): Boolean = selectedForStart.remove(containerName, selection)

    suspend fun currentSelection(containerName: String): GraphicSessionUserSelection? =
        withContext(Dispatchers.IO) {
            selectedForStart[containerName]?.let { return@withContext it }
            val info = ContainerManager.getContainerInfo(containerName)
                ?: return@withContext null
            readPersistedSelection(info)
        }

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

    suspend fun prepareForStart(
        containerName: String,
        session: GraphicSession,
        logger: ContainerLogger? = null
    ): GraphicSessionUserPreparation? = withContext(Dispatchers.IO) {
        val info = ContainerManager.getContainerInfo(containerName) ?: run {
            logger?.e("[-] Could not resolve container while preparing graphical user")
            return@withContext null
        }

        // Persisted selection used to be read twice here: once to choose the
        // effective account and again to compute rollback state. On stopped image
        // rootfs that meant a second mount/unmount cycle in the same Start. Keep
        // one immutable read and use it for both decisions.
        val persisted = readPersistedSelection(info)
        val requested = selectedForStart[containerName]
            ?: persisted
            ?: GraphicSessionUserSelection.ROOT
        if (!isValidUserName(requested.userName)) {
            logger?.e("[-] Invalid graphical user name: ${requested.userName}")
            return@withContext null
        }

        val previous = persisted
        // The launcher is derived state. Commit it first and make the persisted
        // selection the final source-of-truth write. Both files use same-directory
        // temp + rename so a process interruption never exposes a truncated file.
        if (!writeCurrentSessionLauncher(info, session, requested)) {
            logger?.e("[-] Could not refresh the graphical session launcher")
            return@withContext null
        }
        if (!writePersistedSelection(info, requested)) {
            // The launcher was already replaced, so restore the previous effective
            // selection before aborting. This keeps a failed Start from leaving a
            // half-applied user/launcher pair behind.
            val rollbackSelection = previous ?: GraphicSessionUserSelection.ROOT
            if (!writeCurrentSessionLauncher(info, session, rollbackSelection)) {
                logger?.w("[!] Could not roll back the graphical launcher after selection persistence failed")
            }
            logger?.e("[-] Could not persist graphical user selection for $containerName")
            return@withContext null
        }

        // It really is a next-Start override: consume only the exact pending value
        // that was successfully prepared. A newer concurrent choice is preserved.
        consumePreparedSelection(containerName, requested)

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
                    "tmp=$SETTINGS_FILE.tmp.\$\$; " +
                    "trap 'rm -f \"\$tmp\"' EXIT HUP INT TERM; " +
                    "printf '%s' ${shellQuote(body)} > \"\$tmp\" && chmod 600 \"\$tmp\" && " +
                    "if command -v cmp >/dev/null 2>&1 && cmp -s \"\$tmp\" $SETTINGS_FILE 2>/dev/null; then " +
                    "rm -f \"\$tmp\"; else mv -f \"\$tmp\" $SETTINGS_FILE; fi"
            runContainerCommand(info.name, command).isSuccess
        } else {
            RootfsAccessor.use(
                rootfsPath = info.rootfsPath,
                tag = "graphic_user_write_${info.name}"
            ) { root ->
                val dir = shellQuote("$root$SETTINGS_DIR")
                val file = "$root$SETTINGS_FILE"
                Shell.cmd(
                    "mkdir -p $dir && chmod 755 $dir && " +
                        "tmp=${shellQuote(file)}.tmp.\$\$; " +
                        "trap 'rm -f \"\$tmp\"' EXIT HUP INT TERM; " +
                        "printf '%s' ${shellQuote(body)} > \"\$tmp\" && chmod 600 \"\$tmp\" && " +
                        "if command -v cmp >/dev/null 2>&1 && cmp -s \"\$tmp\" ${shellQuote(file)} 2>/dev/null; then " +
                        "rm -f \"\$tmp\"; else mv -f \"\$tmp\" ${shellQuote(file)}; fi"
                ).exec().isSuccess
            } ?: false
        }
    }

    /**
     * OpenRC root sessions keep the direct launcher that predates graphical-user
     * selection and was the stable Alpine path. Non-root OpenRC sessions still
     * need the user-aware launcher, while systemd keeps its current user-aware
     * path so the already-working Debian behavior is not changed.
     */
    private fun writeCurrentSessionLauncher(
        info: ContainerInfo,
        session: GraphicSession,
        selection: GraphicSessionUserSelection
    ): Boolean {
        val shell = when (info.initSystem) {
            InitSystem.OPENRC -> "/bin/sh"
            InitSystem.SYSTEMD -> "/bin/bash"
        }
        val useOpenRcRootBaseline =
            info.initSystem == InitSystem.OPENRC &&
                selection.userName == "root" &&
                !selection.createIfMissing
        val script = if (useOpenRcRootBaseline) {
            GraphicSessionInitFiles.rootSessionScript(session, shell)
        } else {
            GraphicSessionInitFiles.sessionScript(session, shell)
        }

        val openRcFiles = if (info.initSystem == InitSystem.OPENRC) mapOf(
            "/etc/init.d/x11-setup" to GraphicSessionInitFiles.openRcSetupService(),
            "/etc/init.d/x11-session" to GraphicSessionInitFiles.openRcSessionService(session)
        ) else emptyMap()
        fun refreshServices(root: String): String = buildString {
            for ((path, content) in openRcFiles) {
                append("mkdir -p ${shellQuote("$root/etc/init.d")} ${shellQuote("$root/var/log")} && ")
                append("printf '%s' ${shellQuote(content)} > ${shellQuote("$root$path.tmp")} && ")
                append("chmod 755 ${shellQuote("$root$path.tmp")} && ")
                append("mv ${shellQuote("$root$path.tmp")} ${shellQuote("$root$path")} && ")
            }
            append("true")
        }

        return if (info.isRunning) {
            val command =
                refreshServices("") + " && " +
                    "mkdir -p /usr/local/bin && " +
                    "tmp=$SESSION_LAUNCHER.tmp.\$\$; " +
                    "trap 'rm -f \"\$tmp\"' EXIT HUP INT TERM; " +
                    "printf '%s' ${shellQuote(script)} > \"\$tmp\" && " +
                    "chmod 755 \"\$tmp\" && " +
                    "if command -v cmp >/dev/null 2>&1 && cmp -s \"\$tmp\" $SESSION_LAUNCHER 2>/dev/null; then " +
                    "rm -f \"\$tmp\"; else mv -f \"\$tmp\" $SESSION_LAUNCHER; fi"
            runContainerCommand(info.name, command).isSuccess
        } else {
            RootfsAccessor.use(
                rootfsPath = info.rootfsPath,
                tag = "graphic_user_launcher_${info.name}"
            ) { root ->
                val directory = shellQuote("$root/usr/local/bin")
                val launcher = "$root$SESSION_LAUNCHER"
                Shell.cmd(
                    refreshServices(root) + " && " +
                        "mkdir -p $directory && " +
                        "tmp=${shellQuote(launcher)}.tmp.\$\$; " +
                        "trap 'rm -f \"\$tmp\"' EXIT HUP INT TERM; " +
                        "printf '%s' ${shellQuote(script)} > \"\$tmp\" && " +
                        "chmod 755 \"\$tmp\" && " +
                        "if command -v cmp >/dev/null 2>&1 && cmp -s \"\$tmp\" ${shellQuote(launcher)} 2>/dev/null; then " +
                        "rm -f \"\$tmp\"; else mv -f \"\$tmp\" ${shellQuote(launcher)}; fi"
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
