package com.saas.x11manager.util

/** Shell fragments executed by both the init service and the runtime controller. */
internal object X11SessionCommands {
    fun socketSetup(): String = """
        test -d /usr/.X11-unix || exit 1
        mkdir -p /tmp/.X11-unix /tmp/runtime-root || exit 1
        chmod 700 /tmp/runtime-root || exit 1
        # A mountpoint can still refer to an earlier display. Verify its source.
        if ! [ /usr/.X11-unix -ef /tmp/.X11-unix ]; then
            mount --bind /usr/.X11-unix /tmp/.X11-unix || exit 1
        fi
        [ /usr/.X11-unix -ef /tmp/.X11-unix ]
    """.trimIndent()

    fun probeFunction(): String = """
        x11_probe() {
            if command -v xset >/dev/null 2>&1; then
                DISPLAY="${'$'}display" timeout 2 xset q
            elif command -v xdpyinfo >/dev/null 2>&1; then
                DISPLAY="${'$'}display" timeout 2 xdpyinfo
            else
                return 127
            fi
        }
    """.trimIndent() + "\n"

    // OpenRC keeps a crashed service marked started. `start` is then a no-op.
    // Only reset its bookkeeping when OpenRC reports crashed AND its PID is dead.
    fun recoverCrashedOpenRc(): String = """
        rc-service x11-session status >/dev/null 2>&1
        session_status=${'$'}?
        if [ "${'$'}session_status" -eq 32 ]; then
            session_pid=${'$'}(cat /run/x11-session.pid 2>/dev/null || true)
            case "${'$'}session_pid" in ''|*[!0-9]*|0|1) session_pid='' ;; esac
            if [ -z "${'$'}session_pid" ] || ! kill -0 "${'$'}session_pid" 2>/dev/null; then
                rc-service x11-session zap >/dev/null 2>&1 || exit 1
                printf '%s\n' '__SAAS_X11_ACTION__=crashed-state-cleared'
            fi
        fi
    """.trimIndent() + "\n"
}
