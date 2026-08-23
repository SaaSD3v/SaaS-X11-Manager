package com.saas.x11manager.util

/**
 * Wayland compositors supported through the Manager's existing integrated X11
 * transport. Applications still talk native Wayland to these compositors; only
 * the compositor's outer presentation backend is X11.
 */
internal object WaylandGraphicSessionSupport {

    private val specs = linkedMapOf(
        GraphicSession.WESTON to compositorSpec(
            session = GraphicSession.WESTON,
            executable = "weston",
            desktopName = "Weston",
            body = westonBody()
        ),
        GraphicSession.LABWC to compositorSpec(
            session = GraphicSession.LABWC,
            executable = "labwc",
            desktopName = "labwc",
            body = wlrootsBody("labwc")
        ),
        GraphicSession.SWAY to compositorSpec(
            session = GraphicSession.SWAY,
            executable = "sway",
            desktopName = "sway",
            body = wlrootsBody("sway")
        ),
        GraphicSession.CAGE to compositorSpec(
            session = GraphicSession.CAGE,
            executable = "cage",
            desktopName = "Cage",
            body = wlrootsBody("cage -- foot")
        ),
        GraphicSession.WAYFIRE to compositorSpec(
            session = GraphicSession.WAYFIRE,
            executable = "wayfire",
            desktopName = "Wayfire",
            body = wlrootsBody("wayfire")
        )
    )

    val installableSessions: List<GraphicSession>
        get() = specs.keys.toList()

    fun specFor(session: GraphicSession): GraphicSessionSupportSpec? = specs[session]

    private fun compositorSpec(
        session: GraphicSession,
        executable: String,
        desktopName: String,
        body: String
    ): GraphicSessionSupportSpec {
        val launcher = "/usr/local/bin/${session.startCommand}"
        val script = """#!/bin/sh
set -eu

export HOME="${'$'}{HOME:-/root}"
export USER="${'$'}{USER:-root}"
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP="$desktopName"
export XDG_SESSION_DESKTOP="$desktopName"
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp/runtime-root}"
mkdir -p "${'$'}XDG_RUNTIME_DIR"
chmod 700 "${'$'}XDG_RUNTIME_DIR"

if [ -z "${'$'}{DISPLAY:-}" ]; then
    echo "Manager host X11 display is not set" >&2
    exit 1
fi

# A nested compositor must not accidentally choose an existing Wayland parent.
unset WAYLAND_DISPLAY
rm -f "${'$'}XDG_RUNTIME_DIR"/wayland-* 2>/dev/null || true

$body
"""

        return GraphicSessionSupportSpec(
            session = session,
            postInstallCommands = listOf(
                GraphicSessionProvisionCommand(
                    title = "Creating ${session.label} Wayland launcher",
                    command = writeScriptCommand(launcher, script)
                )
            ),
            verificationCommands = listOf(
                GraphicSessionProvisionCommand(
                    title = "Checking ${session.label} compositor executable",
                    command = "command -v $executable >/dev/null"
                ),
                GraphicSessionProvisionCommand(
                    title = "Checking ${session.label} Wayland launcher",
                    command = "test -x $launcher && /bin/sh -n $launcher"
                )
            )
        )
    }

    /**
     * Weston has an explicit X11 backend. Try the default accelerated renderer
     * first, and only fall back to Pixman if Weston exits before creating its
     * Wayland socket. This is capability-driven and does not inspect versions.
     */
    private fun westonBody(): String = """
SOCKET="${'$'}{SAAS_WAYLAND_SOCKET:-wayland-0}"
XWAYLAND_ARG=""
if weston --help 2>&1 | grep -q -- '--xwayland'; then
    XWAYLAND_ARG="--xwayland"
fi

run_weston() {
    renderer_arg="${'$'}1"
    if [ -n "${'$'}renderer_arg" ]; then
        weston --backend=x11 --socket="${'$'}SOCKET" --fullscreen ${'$'}XWAYLAND_ARG "${'$'}renderer_arg" &
    else
        weston --backend=x11 --socket="${'$'}SOCKET" --fullscreen ${'$'}XWAYLAND_ARG &
    fi
    pid=${'$'}!
    attempt=0
    while [ "${'$'}attempt" -lt 6 ]; do
        if [ -S "${'$'}XDG_RUNTIME_DIR/${'$'}SOCKET" ]; then
            wait "${'$'}pid"
            return ${'$'}?
        fi
        if ! kill -0 "${'$'}pid" 2>/dev/null; then
            wait "${'$'}pid" 2>/dev/null || true
            return 1
        fi
        attempt=${'$'}((attempt + 1))
        sleep 1
    done
    kill "${'$'}pid" 2>/dev/null || true
    wait "${'$'}pid" 2>/dev/null || true
    return 1
}

if run_weston ""; then
    exit 0
fi

echo "Weston accelerated renderer did not become ready; retrying with Pixman" >&2
exec weston --backend=x11 --socket="${'$'}SOCKET" --fullscreen ${'$'}XWAYLAND_ARG --renderer=pixman
""".trim()

    /**
     * wlroots compositors can select the X11 backend through environment
     * capabilities. The first attempt lets wlroots choose its renderer; if the
     * compositor dies before publishing any Wayland socket, retry with Pixman.
     */
    private fun wlrootsBody(command: String): String = """
export WLR_BACKENDS=x11
export WLR_X11_OUTPUTS=1
export WLR_RENDERER_ALLOW_SOFTWARE=1

run_nested() {
    /bin/sh -c '$command' &
    pid=${'$'}!
    attempt=0
    while [ "${'$'}attempt" -lt 6 ]; do
        for socket in "${'$'}XDG_RUNTIME_DIR"/wayland-*; do
            [ -S "${'$'}socket" ] || continue
            wait "${'$'}pid"
            return ${'$'}?
        done
        if ! kill -0 "${'$'}pid" 2>/dev/null; then
            wait "${'$'}pid" 2>/dev/null || true
            return 1
        fi
        attempt=${'$'}((attempt + 1))
        sleep 1
    done
    kill "${'$'}pid" 2>/dev/null || true
    wait "${'$'}pid" 2>/dev/null || true
    return 1
}

if run_nested; then
    exit 0
fi

echo "Default wlroots renderer did not become ready; retrying with Pixman" >&2
export WLR_RENDERER=pixman
exec /bin/sh -c '$command'
""".trim()

    private fun writeScriptCommand(path: String, script: String): String =
        "cat > $path <<'SAAS_WAYLAND_EOF'\n" +
            script.trimEnd() + "\n" +
            "SAAS_WAYLAND_EOF\n" +
            "chmod 755 $path"
}

/** One registry keeps old X11 support stable while Wayland grows independently. */
object GraphicSessionRegistry {
    val installableSessions: List<GraphicSession>
        get() = GraphicSessionSupport.installableSessions +
            WaylandGraphicSessionSupport.installableSessions

    internal fun specFor(session: GraphicSession): GraphicSessionSupportSpec? =
        GraphicSessionSupport.specFor(session) ?: WaylandGraphicSessionSupport.specFor(session)
}
