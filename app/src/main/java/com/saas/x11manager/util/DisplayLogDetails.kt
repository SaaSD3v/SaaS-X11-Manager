package com.saas.x11manager.util

/**
 * Shared user-facing display summaries.
 *
 * Keep monitor/VNC logs visually consistent regardless of whether the caller is
 * Home Start, the Screen/Monitors tab, or Free mode. All lines use semantic tags
 * already understood by ConciseLogReducer.
 */
internal object DisplayLogDetails {

    suspend fun x11(
        logger: ContainerLogger?,
        containerName: String? = null,
        monitorNumber: Int,
        displayName: String,
        pid: Int? = null,
        processName: String? = null,
        runtimeDir: String? = null,
        hostSocket: String? = null,
        containerSocket: String? = null,
        state: String? = null
    ) {
        if (logger == null) return
        logger.i(LogLayout.SPACER)
        logger.i("[X11] Monitor details")
        containerName?.let { logger.i("[CONTAINER] • Container: $it") }
        logger.i("[X11] • Monitor: $monitorNumber")
        logger.i("[X11] • Display: $displayName")
        state?.let { logger.i("[X11] • State: $it") }
        pid?.let { logger.i("[X11] • Server PID: $it") }
        processName?.let { logger.i("[X11] • Process: $it") }
        runtimeDir?.let { logger.i("[X11] • Runtime: $it") }
        hostSocket?.let { logger.i("[X11] • Host socket: $it") }
        containerSocket?.let { logger.i("[X11] • Container socket: $it") }
    }

    suspend fun vnc(
        logger: ContainerLogger?,
        containerName: String,
        displayName: String?,
        pid: Int? = null,
        port: Int,
        containerSocket: String? = null,
        state: String? = null
    ) {
        if (logger == null) return
        logger.i(LogLayout.SPACER)
        logger.i("[VNC] Display details")
        logger.i("[CONTAINER] • Container: $containerName")
        displayName?.let { logger.i("[VNC] • Virtual X display: $it") }
        state?.let { logger.i("[VNC] • State: $it") }
        pid?.let { logger.i("[VNC] • Server PID: $it") }
        containerSocket?.let { logger.i("[VNC] • X socket: $it") }
        logger.i("[VNC] • Server port: $port")
    }

    suspend fun freeEnvironment(
        logger: ContainerLogger?,
        component: String,
        displayName: String
    ) {
        if (logger == null) return
        val tag = if (component.equals("VNC", ignoreCase = true)) "VNC" else "X11"
        logger.i(LogLayout.SPACER)
        logger.i("[$tag] Free DISPLAY environment")
        logger.i("[$tag] • Remove DISPLAY: ${FreeX11Runtime.unsetCommand()}")
        logger.i("[$tag] • Replace DISPLAY: ${FreeX11Runtime.replaceCommand(displayName)}")
        // Keep the final line as the exact command the user normally needs.
        logger.i("[$tag] • Set DISPLAY: ${FreeX11Runtime.exportCommand(displayName)}")
    }
}
