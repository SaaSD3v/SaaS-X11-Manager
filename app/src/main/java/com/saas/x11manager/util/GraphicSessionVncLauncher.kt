package com.saas.x11manager.util

/**
 * VNC-specific projection of the existing fixed-display session launcher.
 *
 * The X11-0nly integrated transport remains permanently fixed to :0/X0. TigerVNC,
 * however, owns a private Xvnc DISPLAY selected by VncServerManager. Reuse the
 * existing user/session launcher byte-for-byte except for the two integrated-X11
 * DISPLAY exports so the private VNC display supplied by the caller is preserved.
 * This intentionally reuses the baseline environment already emitted by
 * [GraphicSessionInitFiles.sessionScript] instead of introducing another runtime.
 */
internal fun GraphicSessionInitFiles.vncSessionScript(
    session: GraphicSession,
    shell: String = "/bin/sh"
): String {
    val current = sessionScript(session, shell)
    val vncDisplayGuard =
        "if [ -z \"\${DISPLAY:-}\" ]; then echo \"VNC DISPLAY is not set\" >&2; exit 1; fi\n"

    return current
        .replace("export DISPLAY=:0\n", vncDisplayGuard)
        .replace("export SAAS_HOST_DISPLAY=:0\n", "export SAAS_HOST_DISPLAY=\$DISPLAY\n")
}
