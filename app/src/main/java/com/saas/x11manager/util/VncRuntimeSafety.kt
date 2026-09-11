package com.saas.x11manager.util

/** Shell fragments used to make TigerVNC lifecycle ownership fail closed. */
internal object VncRuntimeSafety {
    fun recordLease(stateDir: String, role: String, pidVariable: String): String =
        "${role}_start=\$(awk '{print \$22}' /proc/\$$pidVariable/stat 2>/dev/null); " +
            "case \"\$${role}_start\" in ''|*[!0-9]*) exit 1 ;; esac; " +
            "printf '%s\\n' \"\$$pidVariable\" > $stateDir/$role.pid; " +
            "printf '%s\\n' \"\$${role}_start\" > $stateDir/$role.start"

    /**
     * Stops only PIDs whose recorded process identity still matches the Manager
     * lease. The old implementation paid two unconditional one-second sleeps even
     * when no VNC process existed. Keep the same fail-closed identity checks, but
     * wait only while an owned process is actually alive and poll in short bursts.
     */
    fun stopOwnedRuntime(stateDir: String): String = """
        owned_pid() {
            role="${'$'}1"
            pidfile="$stateDir/${'$'}1.pid"
            startfile="$stateDir/${'$'}1.start"
            [ -f "${'$'}pidfile" ] || return 1
            pid=${'$'}(cat "${'$'}pidfile" 2>/dev/null)
            case "${'$'}pid" in ''|*[!0-9]*) return 1 ;; esac
            kill -0 "${'$'}pid" 2>/dev/null || return 1
            actual=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}pid/stat" 2>/dev/null)
            case "${'$'}actual" in ''|*[!0-9]*) return 1 ;; esac
            expected=${'$'}(cat "${'$'}startfile" 2>/dev/null || true)
            if [ -n "${'$'}expected" ]; then
                [ "${'$'}actual" = "${'$'}expected" ] || return 2
            else
                cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
                case "${'$'}role:${'$'}cmd" in
                    server:*Xtigervnc*|server:*Xvnc*|server:*x0vncserver*|session:*saas-vnc-session*) ;;
                    *) return 2 ;;
                esac
            fi
            if [ "${'$'}role" = server ]; then
                cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
                case "${'$'}cmd" in *Xtigervnc*|*Xvnc*|*x0vncserver*) ;; *) return 2 ;; esac
            fi
            printf '%s\n' "${'$'}pid"
        }

        unsafe=0
        had_owned=0
        for role in session server; do
            pid=${'$'}(owned_pid "${'$'}role"); rc=${'$'}?
            if [ "${'$'}rc" -eq 0 ]; then
                had_owned=1
                kill "${'$'}pid" 2>/dev/null || true
            elif [ "${'$'}rc" -eq 2 ]; then
                unsafe=1
            fi
        done

        # No owned process means there is nothing to wait for. When TERM was sent,
        # poll for at most one second and stop as soon as both leases are gone.
        if [ "${'$'}had_owned" -eq 1 ]; then
            try=0
            while [ "${'$'}try" -lt 10 ]; do
                remaining=0
                for role in session server; do
                    owned_pid "${'$'}role" >/dev/null 2>&1; rc=${'$'}?
                    [ "${'$'}rc" -eq 0 ] && remaining=1
                    [ "${'$'}rc" -eq 2 ] && unsafe=1
                done
                [ "${'$'}remaining" -eq 0 ] && break
                sleep 0.1
                try=${'$'}((try + 1))
            done
        fi

        forced=0
        for role in session server; do
            pid=${'$'}(owned_pid "${'$'}role"); rc=${'$'}?
            if [ "${'$'}rc" -eq 0 ]; then
                forced=1
                kill -9 "${'$'}pid" 2>/dev/null || true
            elif [ "${'$'}rc" -eq 2 ]; then
                unsafe=1
            fi
        done

        # SIGKILL normally completes immediately. Bound the verification wait and
        # skip it entirely when no forced kill was necessary.
        if [ "${'$'}forced" -eq 1 ]; then
            try=0
            while [ "${'$'}try" -lt 5 ]; do
                remaining=0
                for role in session server; do
                    owned_pid "${'$'}role" >/dev/null 2>&1; rc=${'$'}?
                    [ "${'$'}rc" -eq 0 ] && remaining=1
                    [ "${'$'}rc" -eq 2 ] && unsafe=1
                done
                [ "${'$'}remaining" -eq 0 ] && break
                sleep 0.1
                try=${'$'}((try + 1))
            done
        fi

        for role in session server; do
            owned_pid "${'$'}role" >/dev/null 2>&1; rc=${'$'}?
            [ "${'$'}rc" -eq 0 ] && unsafe=1
            [ "${'$'}rc" -eq 2 ] && unsafe=1
        done
        if [ "${'$'}unsafe" -eq 0 ]; then
            rm -rf "$stateDir"
            exit 0
        fi
        exit 1
    """.trimIndent()

    fun listeningPort(port: Int): String =
        "hex=\$(printf '%04X' $port); " +
            "for table in /proc/net/tcp /proc/net/tcp6; do " +
            "[ -r \"\$table\" ] || continue; " +
            "while read -r sl local remote state rest; do " +
            "[ \"\$state\" = 0A ] || continue; " +
            "case \"\$local\" in *:\$hex) exit 0 ;; esac; " +
            "done < \"\$table\"; done; exit 1"

    fun stopIntegratedGraphicService(): String = """
        if command -v systemctl >/dev/null 2>&1; then
            systemctl stop x11-session.service setup-x11-socket.service >/dev/null 2>&1 || true
            ! systemctl is-active --quiet x11-session.service &&
                ! systemctl is-active --quiet setup-x11-socket.service
        elif command -v rc-service >/dev/null 2>&1; then
            rc-service x11-session stop >/dev/null 2>&1 || true
            ! rc-service x11-session status >/dev/null 2>&1
        else
            true
        fi
    """.trimIndent()
}
