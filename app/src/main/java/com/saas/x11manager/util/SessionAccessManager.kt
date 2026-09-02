package com.saas.x11manager.util

/**
 * One entry point for starting the selected graphical session through the user's
 * preferred access method.
 *
 * BOTH deliberately starts Integrated X11 first and then publishes that exact
 * display with x0vncserver. It never starts a second desktop/WM instance.
 */
object SessionAccessManager {
    suspend fun start(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        accessMode: SessionAccessMode,
        vncPort: Int,
        vncPassword: String? = null,
        logger: ContainerLogger? = null
    ): Boolean {
        logger?.i("--- Graphic Access Start ---")
        logger?.i("[CTX] Access method: ${accessMode.label}")
        logger?.i("[CTX] Session: ${session.label}")
        if (accessMode.requiresVnc) logger?.i("[CTX] VNC port: $vncPort")
        logger?.i("")

        // The fix is strictly opt-in. When enabled, revalidate it before the
        // desktop starts so the new session inherits the persisted audio setup.
        // Audio failure is intentionally non-fatal: X11/VNC must remain usable.
        PulseAudioFixManager.ensureIfEnabled(
            containerName = containerName,
            logger = logger
        )

        return when (accessMode) {
            SessionAccessMode.INTEGRATED_X11 -> {
                val slot = X11SessionManager.startX11Session(
                    containerName = containerName,
                    logger = logger
                )
                if (slot == null) {
                    logger?.e("[-] Integrated X11 access failed")
                    false
                } else {
                    logger?.i("[+] Integrated X11 ready on ${slot.describe()}")
                    true
                }
            }

            SessionAccessMode.VNC -> {
                val result = VncServerManager.startStandalone(
                    containerName = containerName,
                    platform = platform,
                    session = session,
                    port = vncPort,
                    password = vncPassword,
                    logger = logger
                )
                if (result.success) {
                    VncConnectionGuide.logAfterSuccessfulStart(
                        containerName = containerName,
                        port = vncPort,
                        password = vncPassword,
                        logger = logger
                    )
                }
                result.success
            }

            SessionAccessMode.BOTH -> {
                val slot = X11SessionManager.startX11Session(
                    containerName = containerName,
                    logger = logger
                )
                if (slot == null) {
                    logger?.e("[-] Integrated X11 could not start; VNC mirror was not attempted")
                    false
                } else {
                    logger?.i("")
                    val mirror = VncServerManager.startMirror(
                        containerName = containerName,
                        platform = platform,
                        session = session,
                        integratedDisplayName = slot.displayName,
                        port = vncPort,
                        password = vncPassword,
                        logger = logger
                    )
                    if (!mirror.success) {
                        logger?.w("[!] VNC mirror failed, but Integrated X11 remains available on ${slot.describe()}")
                        false
                    } else {
                        logger?.i("[+] Integrated X11 and VNC are sharing the same ${slot.describe()} session")
                        VncConnectionGuide.logAfterSuccessfulStart(
                            containerName = containerName,
                            port = vncPort,
                            password = vncPassword,
                            logger = logger
                        )
                        true
                    }
                }
            }
        }
    }
}
