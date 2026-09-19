package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell

/**
 * Executes host-side helpers inside the real Termux application context.
 *
 * Android app identity is more than a numeric UID. Starting an Android-facing
 * backend with `su <termux-uid> -c` changes the uid but keeps the root/su
 * execution context. AAudio/OpenSL ES and EGL/VirGL must instead be launched by
 * Termux itself, exactly like a command started from the normal Termux shell.
 */
internal object TermuxAppCommand {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val RUN_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val RUN_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val RUN_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_SH = "$TERMUX_PREFIX/bin/sh"
    private const val STATE = "$TERMUX_HOME/.saas-x11-manager/termux-exec"
    private const val COMMANDS = "$STATE/commands"
    private const val POLICY_CACHE_MARKER = "$STATE/allow-external-apps.cache-v1"

    internal data class Result(
        val exitCode: Int,
        val out: List<String>,
        val err: List<String>
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    private data class Owner(val uid: Int, val gid: Int)

    @Volatile private var prepared = false

    fun execBlocking(label: String, command: String, timeoutMs: Long = 15_000L): Result {
        val owner = owner()
            ?: return Result(125, emptyList(), listOf("Termux owner could not be resolved"))
        if (!prepare(owner)) {
            return Result(126, emptyList(), listOf("Termux RunCommandService could not be prepared"))
        }

        val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val token = "$safeLabel-${System.nanoTime()}"
        val launcher = "$COMMANDS/$token.sh"
        val stdout = "$COMMANDS/$token.out"
        val stderr = "$COMMANDS/$token.err"
        val status = "$COMMANDS/$token.rc"

        val launcherBody = """
            #!$TERMUX_SH
            export LC_ALL=C
            export HOME=${q(TERMUX_HOME)}
            export PREFIX=${q(TERMUX_PREFIX)}
            export TMPDIR=${q("$TERMUX_PREFIX/tmp")}
            export PATH=${q("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
            (
            $command
            ) >${q(stdout)} 2>${q(stderr)}
            rc=${'$'}?
            printf '%s\n' "${'$'}rc" > ${q(status)}
            exit 0
        """.trimIndent() + "\n"

        val create = """
            mkdir -p ${q(COMMANDS)} || exit 20
            rm -f ${q(launcher)} ${q(stdout)} ${q(stderr)} ${q(status)} 2>/dev/null || true
            printf '%s' ${q(launcherBody)} > ${q(launcher)} || exit 21
            chown ${owner.uid}:${owner.gid} ${q(COMMANDS)} ${q(launcher)} || exit 22
            chmod 700 ${q(COMMANDS)} ${q(launcher)} || exit 23
            restorecon -RF ${q(COMMANDS)} >/dev/null 2>&1 || true
        """.trimIndent()

        val created = try { Shell.cmd(create).exec() } catch (e: Exception) {
            return Result(127, emptyList(), listOf(e.message ?: e.javaClass.simpleName))
        }
        if (!created.isSuccess) {
            return Result(127, created.out.toList(), created.err.toList())
        }

        val component = "$TERMUX_PACKAGE/$RUN_SERVICE"
        val dispatch = """
            am startservice --user 0 -n ${q(component)} \
                -a ${q(RUN_ACTION)} \
                --es ${q(RUN_PATH)} ${q(launcher)} \
                --es ${q(RUN_WORKDIR)} ${q(TERMUX_HOME)} \
                --ez ${q(RUN_BACKGROUND)} true
        """.trimIndent()

        val dispatched = try { Shell.cmd(dispatch).exec() } catch (_: Exception) { null }
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(500L) * 1_000_000L
        while (System.nanoTime() < deadline) {
            val result = try {
                Shell.cmd(
                    "if [ -s ${q(status)} ]; then " +
                        "printf '__RC__'; cat ${q(status)}; " +
                        "printf '__OUT__\\n'; cat ${q(stdout)} 2>/dev/null || true; " +
                        "printf '__ERR__\\n'; cat ${q(stderr)} 2>/dev/null || true; " +
                        "else exit 1; fi"
                ).exec()
            } catch (_: Exception) {
                null
            }

            if (result?.isSuccess == true) {
                val raw = result.out.toList()
                val rcLine = raw.firstOrNull { it.startsWith("__RC__") }
                val rc = rcLine?.removePrefix("__RC__")?.trim()?.toIntOrNull() ?: 255
                val outIndex = raw.indexOfFirst { it == "__OUT__" }
                val errIndex = raw.indexOfFirst { it == "__ERR__" }
                val out = if (outIndex >= 0 && errIndex > outIndex) {
                    raw.subList(outIndex + 1, errIndex)
                } else emptyList()
                val err = if (errIndex >= 0) raw.drop(errIndex + 1) else emptyList()
                cleanup(launcher, stdout, stderr, status)
                return Result(rc, out, err)
            }
            Thread.sleep(100)
        }

        val diag = buildList {
            add("Timed out waiting for Termux RunCommandService: $safeLabel")
            dispatched?.out?.filter { it.isNotBlank() }?.let(::addAll)
            dispatched?.err?.filter { it.isNotBlank() }?.let(::addAll)
        }
        cleanup(launcher, stdout, stderr, status)
        return Result(124, emptyList(), diag)
    }

    private fun prepare(owner: Owner): Boolean {
        if (prepared) return true
        synchronized(this) {
            if (prepared) return true
            val context = X11Application.instance
            val propsDir = "$TERMUX_HOME/.termux"
            val props = "$propsDir/termux.properties"
            val command = """
                test -x ${q(TERMUX_SH)} || exit 30
                mkdir -p ${q(STATE)} ${q(COMMANDS)} ${q(propsDir)} || exit 31
                chown ${owner.uid}:${owner.gid} ${q(STATE)} ${q(COMMANDS)} ${q(propsDir)} 2>/dev/null || true
                chmod 700 ${q(STATE)} ${q(COMMANDS)} ${q(propsDir)} 2>/dev/null || true
                refresh=0
                if ! grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
 ${q(props)} 2>/dev/null; then
                    tmp=${q("$props.saas-manager.tmp")}
                    : > "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}tmp" || exit 32
                    found=0
                    if [ -f ${q(props)} ]; then
                        while IFS= read -r line || [ -n "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}line" ]; do
                            case "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}line" in
                                allow-external-apps=*)
                                    if [ "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}found" -eq 0 ]; then
                                        printf '%s\n' 'allow-external-apps=true' >> "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}tmp"
                                        found=1
                                    fi
                                    ;;
                                *) printf '%s\n' "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}line" >> "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}tmp" ;;
                            esac
                        done < ${q(props)}
                    fi
                    [ "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}found" -eq 1 ] || printf '%s\n' 'allow-external-apps=true' >> "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}tmp"
                    mv -f "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}tmp" ${q(props)} || exit 33
                    refresh=1
                fi
                [ -f ${q(POLICY_CACHE_MARKER)} ] || refresh=1
                chown ${owner.uid}:${owner.gid} ${q(props)} 2>/dev/null || true
                chmod 600 ${q(props)} 2>/dev/null || true
                restorecon -RF ${q(STATE)} ${q(propsDir)} >/dev/null 2>&1 || true
                pm grant ${q(context.packageName)} ${q(RUN_PERMISSION)} >/dev/null 2>&1 || true

                if [ "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}refresh" -eq 1 ]; then
                    # TermuxAppSharedProperties is cached by the Java app process.
                    # Kill only exact cmdline=com.termux; never kill same-UID
                    # shells, PulseAudio or VirGL renderer processes.
                    for p in /proc/[0-9]*; do
                        [ -r "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}p/cmdline" ] || continue
                        cmd=${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}(tr '\000' '\n' < "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}p/cmdline" 2>/dev/null | sed -n '1p')
                        [ "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}cmd" = ${q(TERMUX_PACKAGE)} ] || continue
                        pid=${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}{p##*/}
                        kill -9 "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}pid" 2>/dev/null || true
                    done
                    i=0
                    while [ "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}i" -lt 30 ]; do
                        live=0
                        for p in /proc/[0-9]*; do
                            [ -r "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}p/cmdline" ] || continue
                            cmd=${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}(tr '\000' '\n' < "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}p/cmdline" 2>/dev/null | sed -n '1p')
                            [ "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}cmd" = ${q(TERMUX_PACKAGE)} ] && { live=1; break; }
                        done
                        [ "${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}live" -eq 0 ] && break
                        sleep 0.1
                        i=${'
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
}((i + 1))
                    done
                    : > ${q(POLICY_CACHE_MARKER)} || exit 34
                    chown ${owner.uid}:${owner.gid} ${q(POLICY_CACHE_MARKER)} 2>/dev/null || true
                    chmod 600 ${q(POLICY_CACHE_MARKER)} 2>/dev/null || true
                fi

                grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
 ${q(props)}
            """.trimIndent()
            prepared = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            return prepared
        }
    }

    private fun owner(): Owner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.split('|')?.let { parts ->
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                val gid = parts.getOrNull(1)?.toIntOrNull() ?: return@let null
                if (uid > 0 && gid > 0) Owner(uid, gid) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup(vararg paths: String) {
        try {
            Shell.cmd("rm -f ${paths.joinToString(" ") { q(it) }} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
