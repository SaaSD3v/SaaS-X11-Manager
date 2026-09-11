package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class X11ServerStatus { Running, Stopped }

data class X11MonitorInfo(
    val slot: X11DisplaySlot,
    val status: X11ServerStatus,
    val pid: Int?,
    val containerName: String? = null
) {
    val monitorNumber: Int get() = slot.monitorNumber
    val displayName: String get() = slot.displayName
}

/** Result of one complete Integrated X11 Start transaction. */
internal data class X11SessionStartResult(
    val slot: X11DisplaySlot,
    val runtimeStatus: ContainerStatus,
    val containerPid: Int?,
    val commandReady: Boolean,
    val graphicSessionReady: Boolean
)

/**
 * Owns the lifecycle of Manager-integrated X11 monitors.
 *
 * Design rules:
 * - `:N` is a recyclable monitor number, never a permanent container property.
 * - one slot owns one isolated runtime directory and one expected XN socket.
 * - running containers reserve their slot through the Manager bind in container.config.
 * - an externally-stopped container does not kill a still-running monitor; the monitor
 *   becomes unowned and remains visible until it is adopted or explicitly stopped.
 * - stopping an unowned monitor removes its complete runtime directory.
 * - stopping a monitor that still belongs to a running container removes every runtime
 *   artifact but preserves the empty bind anchor so it can be restarted without
 *   restarting the container.
 * - stopped-container X11 binds are reconciled away before a display number is reused.
 * - monitor observation is read-only; destructive reconciliation is restricted to
 *   explicit lifecycle/start-stop paths.
 */
object X11SessionManager {

    private data class ServerLease(
        val slot: X11DisplaySlot,
        val pid: Int,
        val reused: Boolean
    )

    private val pendingLeaseLock = Any()
    private val pendingContainerLeases = mutableSetOf<String>()

    private fun setPendingLease(containerName: String, pending: Boolean) {
        synchronized(pendingLeaseLock) {
            if (pending) pendingContainerLeases.add(containerName)
            else pendingContainerLeases.remove(containerName)
        }
    }

    private fun pendingLeasesSnapshot(): Set<String> =
        synchronized(pendingLeaseLock) { pendingContainerLeases.toSet() }

    private fun parsePids(lines: List<String>): List<Int> = lines
        .flatMap { it.trim().split(Regex("\\s+")) }
        .mapNotNull { it.toIntOrNull() }
        .filter { it > 0 }
        .distinct()

    /**
     * app_process keeps /proc/PID/comm as "main" on some Android releases even
     * though --nice-name is correctly visible to pidof/ps. A whole-/proc fallback
     * was both incorrect on those devices and extremely expensive. Runtime slots
     * are discovered from their own display-N directories, while a known slot's
     * process is resolved directly through pidof.
     */
    private fun getProcessPids(processName: String): List<Int> {
        return try {
            val result = Shell.cmd("pidof ${shellQuote(processName)} 2>/dev/null").exec()
            parsePids(result.out)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getLiveServerPids(displaySlot: X11DisplaySlot): List<Int> =
        getProcessPids(displaySlot.processName).distinct()

    private fun socketTableLines(): List<String> {
        return try {
            val result = Shell.cmd("cat /proc/net/unix 2>/dev/null").exec()
            if (result.isSuccess) result.out else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hasKernelSocket(socketPath: String, lines: List<String> = socketTableLines()): Boolean =
        UnixSocketTableParser.findInode(lines, socketPath) != null ||
            UnixSocketTableParser.findInode(lines, "@$socketPath") != null

    private fun hasSocketFile(displaySlot: X11DisplaySlot): Boolean {
        return try {
            Shell.cmd("test -S ${shellQuote(displaySlot.socketFile)}").exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun hasLiveSocket(displaySlot: X11DisplaySlot): Boolean =
        hasSocketFile(displaySlot) && hasKernelSocket(displaySlot.socketFile)

    private fun hasLiveSocket(
        displaySlot: X11DisplaySlot,
        socketTable: List<String>
    ): Boolean = hasSocketFile(displaySlot) && hasKernelSocket(displaySlot.socketFile, socketTable)

    private fun prepareRuntimeDirectory(displaySlot: X11DisplaySlot): Boolean {
        return try {
            Shell.cmd(
                "mkdir -p ${shellQuote(displaySlot.socketDir)} && " +
                    "chmod 1777 ${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
                    "${shellQuote(displaySlot.runtimeDir)} ${shellQuote(displaySlot.socketDir)}"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /** Removes artifacts that can never belong to this isolated display slot. */
    private fun sanitizeUnexpectedArtifacts(displaySlot: X11DisplaySlot) {
        try {
            val socketDir = shellQuote(displaySlot.socketDir)
            val runtimeDir = shellQuote(displaySlot.runtimeDir)
            val expectedSocket = shellQuote(displaySlot.socketFile)
            val expectedLock = shellQuote(displaySlot.lockFile)
            Shell.cmd(
                "expected_socket=$expectedSocket; " +
                    "for f in $socketDir/X*; do " +
                    "[ -e \"\$f\" ] || [ -S \"\$f\" ] || continue; " +
                    "[ \"\$f\" = \"\$expected_socket\" ] || rm -f \"\$f\"; done; " +
                    "expected_lock=$expectedLock; " +
                    "for f in $runtimeDir/.X*-lock; do " +
                    "[ -e \"\$f\" ] || continue; " +
                    "[ \"\$f\" = \"\$expected_lock\" ] || rm -f \"\$f\"; done"
            ).exec()
        } catch (_: Exception) {
            // Reconciliation is best effort; authoritative start/stop verification follows.
        }
    }

    /**
     * Clears one stopped server runtime. If a running container still has this
     * socket directory bind-mounted, preserve the same directory inode and remove
     * only its contents. Otherwise remove the complete display-N directory.
     */
    private fun clearSlotRuntime(displaySlot: X11DisplaySlot, preserveBindAnchor: Boolean) {
        try {
            if (!preserveBindAnchor) {
                Shell.cmd("rm -rf ${shellQuote(displaySlot.runtimeDir)} 2>/dev/null").exec()
                return
            }

            val runtime = shellQuote(displaySlot.runtimeDir)
            val socket = shellQuote(displaySlot.socketDir)
            val base = shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)
            Shell.cmd(
                "runtime=$runtime; socket=$socket; base=$base; " +
                    "mkdir -p \"\$socket\" || exit 1; " +
                    "for item in \"\$socket\"/* \"\$socket\"/.[!.]* \"\$socket\"/..?*; do " +
                    "[ -e \"\$item\" ] || [ -L \"\$item\" ] || continue; " +
                    "rm -rf \"\$item\"; done; " +
                    "for item in \"\$runtime\"/* \"\$runtime\"/.[!.]* \"\$runtime\"/..?*; do " +
                    "[ -e \"\$item\" ] || [ -L \"\$item\" ] || continue; " +
                    "[ \"\$item\" = \"\$socket\" ] && continue; " +
                    "rm -rf \"\$item\"; done; " +
                    "chmod 1777 \"\$base\" \"\$runtime\" \"\$socket\""
            ).exec()
        } catch (_: Exception) {
            // Verified by the caller where cleanup is part of a user operation.
        }
    }

    private fun hasCachedXkbConfig(): Boolean {
        val root = shellQuote(Constants.INTEGRATED_X11_XKB_DIR)
        return try {
            Shell.cmd(
                "test -d $root/rules && test -d $root/symbols && test -d $root/keycodes"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun stageXkbConfig(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean {
        if (hasCachedXkbConfig()) {
            logger?.i("[+] Reusing cached XKB configuration")
            return true
        }

        val info = ContainerManager.getContainerInfo(containerName) ?: run {
            logger?.e("[-] Cannot resolve container rootfs for XKB data")
            return false
        }

        val staged = RootfsAccessor.use(
            rootfsPath = info.rootfsPath,
            tag = "xkb_$containerName"
        ) { root ->
            val x11Path = "$root/usr/share/X11/xkb"
            val alternatePath = "$root/usr/share/xkeyboard-config-2"
            val source = when {
                Shell.cmd("test -d ${shellQuote(x11Path)}").exec().isSuccess -> x11Path
                Shell.cmd("test -d ${shellQuote(alternatePath)}").exec().isSuccess -> alternatePath
                else -> null
            } ?: return@use false

            val destination = Constants.INTEGRATED_X11_XKB_DIR
            val temporary = "$destination.tmp.${android.os.Process.myPid()}"
            val result = Shell.cmd(
                "rm -rf ${shellQuote(temporary)} && " +
                    "mkdir -p ${shellQuote(temporary)} && " +
                    "cp -a ${shellQuote("$source/.")} ${shellQuote("$temporary/")} && " +
                    "chmod -R a+rX ${shellQuote(temporary)} && " +
                    "rm -rf ${shellQuote(destination)} && " +
                    "mv ${shellQuote(temporary)} ${shellQuote(destination)}"
            ).exec()
            if (!result.isSuccess) {
                Shell.cmd("rm -rf ${shellQuote(temporary)} 2>/dev/null").exec()
            }
            result.isSuccess
        } ?: false

        if (staged && hasCachedXkbConfig()) {
            logger?.i("[+] Cached XKB configuration from $containerName")
            return true
        }

        logger?.e(
            "[-] XKB configuration was not found in $containerName; expected /usr/share/X11/xkb"
        )
        return false
    }

    private fun killPids(pids: Collection<Int>) {
        val targets = pids.filter { it > 0 }.distinct()
        if (targets.isNotEmpty()) {
            Shell.cmd("kill -9 ${targets.joinToString(" ")} 2>/dev/null").exec()
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun formatPids(pids: Collection<Int>): String =
        pids.filter { it > 0 }.distinct().joinToString(",").ifEmpty { "none" }

    private suspend fun logServerContext(
        displaySlot: X11DisplaySlot,
        containerName: String?,
        logger: ContainerLogger?
    ) {
        logger?.i("[CTX] Monitor: ${displaySlot.monitorNumber}")
        logger?.i("[CTX] Display: ${displaySlot.displayName}")
        logger?.i("[CTX] Process: ${displaySlot.processName}")
        logger?.i("[CTX] Runtime: ${displaySlot.runtimeDir}")
        logger?.i("[CTX] Socket: ${displaySlot.socketFile}")
        logger?.i("[CTX] Lock: ${displaySlot.lockFile}")
        logger?.i("[CTX] Server log: ${displaySlot.logFile}")
        logger?.i("[CTX] Container owner: ${containerName ?: "none (raw monitor)"}")
    }

    /**
     * The runtime directory is the authoritative discovery anchor for current
     * Manager servers. This avoids scanning every /proc/PID/cmdline/environ on
     * every Compose refresh, which is prohibitively slow on some Android kernels.
     */
    private fun discoverRuntimeSlots(): List<X11DisplaySlot> {
        val base = shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)
        return try {
            val result = Shell.cmd(
                "for dir in $base/display-*; do " +
                    "[ -d \"\$dir\" ] || continue; " +
                    "name=\${dir##*/}; printf '%s\\n' \"\$name\"; done"
            ).exec()
            if (!result.isSuccess) return emptyList()
            result.out
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("display-")) return@mapNotNull null
                    trimmed.removePrefix("display-").toIntOrNull()
                }
                .filter { it >= 0 }
                .distinct()
                .sorted()
                .map(::X11DisplaySlot)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Removes only runtime layouts no longer produced by the current code. */
    private suspend fun cleanupLegacyRuntime(logger: ContainerLogger? = null) {
        val base = Constants.INTEGRATED_X11_RUNTIME_DIR
        val legacyRootProcessAlive = getProcessPids("saas-x11").isNotEmpty()

        // Old container-scoped layouts can be detected directly from the kernel
        // UNIX socket table. Do not walk every process environment looking for
        // TMPDIR: that single scan measured >10s on real hardware.
        val socketTable = socketTableLines()
        val legacyContainerSocketAlive = socketTable.any { line ->
            line.contains("$base/containers/")
        }

        if (!legacyRootProcessAlive) {
            Shell.cmd(
                "rm -rf ${shellQuote("$base/.X11-unix")} ${shellQuote("$base/server.log")} " +
                    "${shellQuote(base)}/.X*-lock 2>/dev/null"
            ).exec()
        }
        if (!legacyContainerSocketAlive) {
            Shell.cmd(
                "rm -rf ${shellQuote("$base/containers")} " +
                    "${shellQuote("$base/.saas-primary")} " +
                    "${shellQuote("$base/.saas-container-primary")} 2>/dev/null"
            ).exec()
        }

        if (logger != null && (!legacyRootProcessAlive || !legacyContainerSocketAlive)) {
            logger.i("[+] Legacy X11 runtime cleanup checked")
        }
    }

    private fun runningAssignments(containers: List<ContainerInfo>): Map<Int, String> =
        buildMap {
            containers
                .asSequence()
                .filter { it.isRunning }
                .forEach { container ->
                    ContainerConfigManager.displaySlotFromBindMounts(container.bindMounts)
                        ?.let { slot -> putIfAbsent(slot.number, container.name) }
                }
        }

    /**
     * Reconciles persistent container config with real runtime state.
     * It intentionally never kills a healthy unowned monitor: this is how a
     * monitor survives an external DroidSpaces/CLI container stop.
     *
     * IMPORTANT: this is maintenance, not observation. UI refreshes must call
     * getMonitors(), which is deliberately read-only.
     */
    private suspend fun reconcileRuntime(
        containers: List<ContainerInfo>,
        logger: ContainerLogger? = null
    ) {
        val pending = pendingLeasesSnapshot()
        if (logger != null) {
            logger.i("--- X11 Runtime Reconciliation ---")
            logger.i("[CTX] Containers discovered: ${containers.size}")
            logger.i("[CTX] Pending Manager starts: ${pending.joinToString(",").ifEmpty { "none" }}")
        }

        var releasedBindings = 0
        for (container in containers) {
            if (container.isRunning || container.name in pending) continue
            if (!ContainerConfigManager.hasManagerX11Bind(container.bindMounts)) continue
            if (ContainerConfigManager.clearManualX11Config(container.name, logger)) {
                releasedBindings++
            }
        }

        cleanupLegacyRuntime(logger)

        val assignments = runningAssignments(containers)
        val slotNumbers = buildSet {
            addAll(assignments.keys)
            addAll(discoverRuntimeSlots().map { it.number })
        }
        val socketTable = socketTableLines()

        var staleSlots = 0
        for (number in slotNumbers.sorted()) {
            val slot = X11DisplaySlot(number)
            sanitizeUnexpectedArtifacts(slot)

            val pids = getLiveServerPids(slot)
            val liveSocket = hasLiveSocket(slot, socketTable)
            val preserveAnchor = number in assignments

            if (pids.isNotEmpty() && !liveSocket) {
                logger?.w("[!] ${slot.processName} has no live ${slot.socketFile}; removing stale server")
                killPids(pids)
                clearSlotRuntime(slot, preserveAnchor)
                staleSlots++
                continue
            }

            if (pids.isEmpty()) {
                if (hasSocketFile(slot) || !preserveAnchor) {
                    clearSlotRuntime(slot, preserveAnchor)
                } else if (preserveAnchor) {
                    clearSlotRuntime(slot, preserveBindAnchor = true)
                }
                staleSlots++
            }
        }

        if (logger != null) {
            logger.i("[CTX] Released stopped-container binds: $releasedBindings")
            logger.i("[CTX] Reconciled inactive/stale slots: $staleSlots")
            logger.i("[+] X11 runtime reconciliation complete")
            logger.i("")
        }
    }

    suspend fun reconcileRuntimeState(logger: ContainerLogger? = null) =
        withContext(Dispatchers.IO) {
            reconcileRuntime(ContainerManager.listContainers(), logger)
        }

    internal fun selectDisplaySlot(
        runningAssignedDisplayNumbers: Collection<Int>,
        reusableActiveDisplayNumbers: Collection<Int> = emptyList()
    ): X11DisplaySlot {
        val occupied = runningAssignedDisplayNumbers.filter { it >= 0 }.toSet()
        val reusable = reusableActiveDisplayNumbers
            .asSequence()
            .filter { it >= 0 && it !in occupied }
            .minOrNull()
        return reusable?.let(::X11DisplaySlot)
            ?: X11DisplayAllocator.firstFree(occupied)
    }

    private suspend fun selectDisplaySlotForContainer(
        containerName: String,
        containers: List<ContainerInfo>
    ): X11DisplaySlot {
        val target = containers.firstOrNull { it.name == containerName }
            ?: ContainerManager.getContainerInfo(containerName)

        if (target?.isRunning == true) {
            return ContainerConfigManager.displaySlotFromBindMounts(target.bindMounts)
                ?: throw IllegalStateException(
                    "Running container $containerName has no Manager display slot; stop it before starting X11"
                )
        }

        val assignments = runningAssignments(containers)
        val socketTable = socketTableLines()
        val reusableActive = discoverRuntimeSlots()
            .filter { slot ->
                slot.number !in assignments &&
                    getLiveServerPids(slot).isNotEmpty() &&
                    hasLiveSocket(slot, socketTable)
            }
            .map { it.number }

        return selectDisplaySlot(assignments.keys, reusableActive)
    }

    internal fun buildIntegratedServerCommand(
        apkPath: String,
        displaySlot: X11DisplaySlot
    ): String =
        "TMPDIR=${shellQuote(displaySlot.runtimeDir)} " +
            "XKB_CONFIG_ROOT=${shellQuote(Constants.INTEGRATED_X11_XKB_DIR)} " +
            "CLASSPATH=${shellQuote(apkPath)} " +
            "/system/bin/app_process -Xnoimage-dex2oat / " +
            "--nice-name=${displaySlot.processName} " +
            "com.termux.x11.CmdEntryPoint ${displaySlot.displayName} " +
            ">${shellQuote(displaySlot.logFile)} 2>&1 & echo ${'$'}!"

    /**
     * Waits for process + filesystem socket + kernel UNIX socket in one root shell.
     * The previous Kotlin loop spawned up to ~120 separate shell commands over a
     * ten-second timeout. Keeping the polling next to /proc turns that into one
     * bounded shell transaction without weakening any readiness condition.
     */
    private fun waitForIntegratedServerReady(displaySlot: X11DisplaySlot): List<Int> {
        val process = shellQuote(displaySlot.processName)
        val socket = shellQuote(displaySlot.socketFile)
        val command = """
            process=$process
            socket=$socket
            attempt=0
            while [ "${'$'}attempt" -lt 100 ]; do
                pids=${'$'}(pidof "${'$'}process" 2>/dev/null || true)
                if [ -n "${'$'}pids" ] && [ -S "${'$'}socket" ]; then
                    if grep -Fq " ${'$'}socket" /proc/net/unix 2>/dev/null ||
                       grep -Fq " @${'$'}socket" /proc/net/unix 2>/dev/null; then
                        printf '%s\n' "${'$'}pids"
                        exit 0
                    fi
                fi
                attempt=${'$'}((attempt + 1))
                [ "${'$'}attempt" -ge 100 ] && break
                sleep 0.1
            done
            exit 1
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) parsePids(result.out) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serverInfo(
        displaySlot: X11DisplaySlot,
        containerName: String? = null,
        socketTable: List<String>? = null
    ): X11MonitorInfo {
        val live = getLiveServerPids(displaySlot)
        val running = live.isNotEmpty() && if (socketTable == null) {
            hasLiveSocket(displaySlot)
        } else {
            hasLiveSocket(displaySlot, socketTable)
        }
        return X11MonitorInfo(
            slot = displaySlot,
            status = if (running) X11ServerStatus.Running else X11ServerStatus.Stopped,
            pid = if (running) live.first() else null,
            containerName = containerName
        )
    }

    suspend fun getMonitors(): List<X11MonitorInfo> =
        getMonitors(ContainerManager.listContainers())

    /**
     * Read-only monitor observation for Compose/runtime snapshots. Never mutate
     * config, kill a server or remove a runtime merely because a UI refresh saw
     * a transient state.
     */
    suspend fun getMonitors(containers: List<ContainerInfo>): List<X11MonitorInfo> =
        withContext(Dispatchers.IO) {
            val assignments = runningAssignments(containers)
            val slotNumbers = buildSet {
                addAll(assignments.keys)
                addAll(discoverRuntimeSlots().map { it.number })
            }
            val socketTable = socketTableLines()

            slotNumbers
                .sorted()
                .map { number ->
                    serverInfo(
                        displaySlot = X11DisplaySlot(number),
                        containerName = assignments[number],
                        socketTable = socketTable
                    )
                }
                .filter { it.containerName != null || it.status == X11ServerStatus.Running }
        }

    suspend fun getServerStatus(): X11ServerStatus = withContext(Dispatchers.IO) {
        if (getMonitors().any { it.status == X11ServerStatus.Running }) {
            X11ServerStatus.Running
        } else {
            X11ServerStatus.Stopped
        }
    }

    suspend fun getServerPid(): Int? = withContext(Dispatchers.IO) {
        getMonitors().firstOrNull { it.status == X11ServerStatus.Running }?.pid
    }

    suspend fun getServerStatus(displaySlot: X11DisplaySlot): X11ServerStatus =
        withContext(Dispatchers.IO) { serverInfo(displaySlot).status }

    suspend fun getServerPid(displaySlot: X11DisplaySlot): Int? =
        withContext(Dispatchers.IO) { serverInfo(displaySlot).pid }

    suspend fun getDisplayForContainer(containerName: String): X11DisplaySlot? =
        withContext(Dispatchers.IO) {
            val info = ContainerManager.getContainerInfo(containerName) ?: return@withContext null
            if (!info.isRunning) return@withContext null
            ContainerConfigManager.displaySlotFromBindMounts(info.bindMounts)
        }

    suspend fun ensureContainerGraphicSession(
        containerName: String,
        displaySlot: X11DisplaySlot,
        logger: ContainerLogger? = null
    ): Boolean = GraphicSessionRuntimeController.ensureRunning(
        containerName = containerName,
        displaySlot = displaySlot,
        logger = logger
    )

    suspend fun stopContainerGraphicSession(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = GraphicSessionRuntimeController.stop(containerName, logger)

    private suspend fun startIntegratedServerTracked(
        displaySlot: X11DisplaySlot,
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<ServerLease> = withContext(Dispatchers.IO) {
        val operationStartedAt = System.nanoTime()
        try {
            logger?.i("--- Integrated X11 Server Start ---")
            logServerContext(displaySlot, containerName, logger)
            logger?.i("[CTX] XKB root: ${Constants.INTEGRATED_X11_XKB_DIR}")
            logger?.i("[*] Inspecting existing server state...")

            sanitizeUnexpectedArtifacts(displaySlot)
            val liveBefore = getLiveServerPids(displaySlot)
            val socketBefore = hasLiveSocket(displaySlot)
            logger?.i("[CTX] Existing server PIDs: ${formatPids(liveBefore)}")
            logger?.i("[CTX] Existing live socket: ${if (socketBefore) "present" else "absent"}")

            if (socketBefore && liveBefore.isNotEmpty()) {
                val pid = liveBefore.first()
                logger?.i("[+] Reusing active ${displaySlot.describe()} (PID=$pid)")
                logger?.i("[CTX] Server lease: adopted/reused")
                logger?.i("[CTX] Start duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")
                return@withContext Result.success(ServerLease(displaySlot, pid, reused = true))
            }

            if (liveBefore.isNotEmpty()) {
                logger?.w("[!] Stale ${displaySlot.processName} process found without a live socket; restarting it")
                killPids(liveBefore)
            }

            val runningOwners = runningAssignments(ContainerManager.listContainers())
            clearSlotRuntime(displaySlot, preserveBindAnchor = displaySlot.number in runningOwners)

            logger?.i("[*] Preparing isolated runtime directory...")
            if (!prepareRuntimeDirectory(displaySlot)) {
                return@withContext Result.failure(
                    IllegalStateException("Could not prepare runtime directory for ${displaySlot.describe()}")
                )
            }
            logger?.i("[+] Runtime directory ready: ${displaySlot.runtimeDir}")

            if (hasCachedXkbConfig()) {
                logger?.i("[+] Shared XKB cache ready")
            } else {
                logger?.i("[*] Shared XKB cache is missing; staging configuration...")
                if (containerName.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Integrated X11 needs XKB data from a configured container before its first start"
                        )
                    )
                }
                if (!stageXkbConfig(containerName, logger)) {
                    return@withContext Result.failure(
                        IllegalStateException("Could not prepare XKB data for integrated X11")
                    )
                }
            }

            val apkPath = X11Application.instance.applicationInfo.sourceDir
            if (apkPath.isNullOrBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Could not resolve SaaS X11 Manager APK path")
                )
            }

            logger?.i("[CTX] Manager APK: $apkPath")
            logger?.i("[CTX] Entrypoint: com.termux.x11.CmdEntryPoint ${displaySlot.displayName}")
            logger?.i("[CTX] Process nice-name: ${displaySlot.processName}")
            logger?.i("[*] Launching integrated X11 app_process...")

            val launchStartedAt = System.nanoTime()
            val launch = Shell.cmd(buildIntegratedServerCommand(apkPath, displaySlot)).exec()
            val capturedPid = launch.out.asReversed()
                .firstNotNullOfOrNull { it.trim().toIntOrNull() }
            logger?.i("[CTX] Launcher exit code: ${launch.code}")
            logger?.i("[CTX] Captured launcher PID: ${capturedPid ?: "none"}")
            logger?.i("[*] Waiting up to 10s for X11 process and socket...")

            val live = waitForIntegratedServerReady(displaySlot)
            if (live.isNotEmpty()) {
                val pid = if (capturedPid != null && capturedPid in live) capturedPid else live.first()
                logger?.i("[+] ${displaySlot.describe()} ready (PID=$pid)")
                logger?.i("[+] X11 socket: ${displaySlot.socketFile}")
                logger?.i("[CTX] Live server PIDs: ${formatPids(live)}")
                logger?.i("[CTX] Server readiness: ${(System.nanoTime() - launchStartedAt) / 1_000_000L}ms")
                logger?.i("[CTX] Server lease: new")
                logger?.i("[CTX] Total start duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")
                return@withContext Result.success(ServerLease(displaySlot, pid, reused = false))
            }

            val liveAfter = getLiveServerPids(displaySlot)
            logger?.e("[-] X11 server readiness timed out")
            logger?.e("[-] Final live PIDs: ${formatPids(liveAfter)}")
            logger?.e("[-] Server log: ${displaySlot.logFile}")
            killPids(liveAfter)
            val preserve = displaySlot.number in runningAssignments(ContainerManager.listContainers())
            clearSlotRuntime(displaySlot, preserve)
            logger?.i("[+] Timed-out X11 runtime cleaned")
            Result.failure(
                IllegalStateException(
                    "${displaySlot.describe()} did not create a live ${displaySlot.socketFile}; see ${displaySlot.logFile}"
                )
            )
        } catch (e: Exception) {
            logger?.e("[-] Integrated X11 error on ${displaySlot.describe()}: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun startIntegratedServer(
        displaySlot: X11DisplaySlot,
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<Int> = startIntegratedServerTracked(
        displaySlot = displaySlot,
        containerName = containerName,
        logger = logger
    ).map { it.pid }

    private suspend fun rollbackServer(lease: ServerLease, logger: ContainerLogger? = null) {
        if (lease.reused) {
            logger?.i("[CTX] Rollback skipped: ${lease.slot.describe()} existed before this operation")
            return
        }
        logger?.i("--- Integrated X11 Server Rollback ---")
        logger?.i("[CTX] Monitor: ${lease.slot.monitorNumber}")
        logger?.i("[CTX] Display: ${lease.slot.displayName}")
        logger?.i("[CTX] PID: ${lease.pid}")
        logger?.i("[*] Killing newly-created X11 server after failed session start...")
        killPids(listOf(lease.pid))
        clearSlotRuntime(lease.slot, preserveBindAnchor = false)
        logger?.i("[+] Rolled back ${lease.slot.describe()} after failed session start")
    }

    suspend fun stopIntegratedServer(
        displaySlot: X11DisplaySlot,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val operationStartedAt = System.nanoTime()
        try {
            logger?.i("--- Integrated X11 Server Stop ---")
            val containers = ContainerManager.listContainers()
            val owner = runningAssignments(containers)[displaySlot.number]
            logServerContext(displaySlot, owner, logger)
            logger?.i("[*] Inspecting server state before stop...")

            val livePids = getLiveServerPids(displaySlot)
            val socketBefore = hasSocketFile(displaySlot)
            logger?.i("[CTX] Live PIDs before stop: ${formatPids(livePids)}")
            logger?.i("[CTX] Socket file before stop: ${if (socketBefore) "present" else "absent"}")

            if (livePids.isNotEmpty()) {
                logger?.i("[*] Sending SIGKILL to server PIDs: ${formatPids(livePids)}")
                killPids(livePids)
            } else {
                logger?.i("[+] No live ${displaySlot.processName} process found")
            }

            val preserveAnchor = owner != null
            logger?.i(
                if (preserveAnchor) {
                    "[*] Removing all monitor runtime artifacts while preserving the running-container bind anchor..."
                } else {
                    "[*] Removing complete monitor runtime directory..."
                }
            )
            clearSlotRuntime(displaySlot, preserveAnchor)
            delay(75)

            val remainingPids = getLiveServerPids(displaySlot)
            val socketAfter = hasSocketFile(displaySlot)
            logger?.i("[CTX] Live PIDs after stop: ${formatPids(remainingPids)}")
            logger?.i("[CTX] Socket after stop: ${if (socketAfter) "present" else "absent"}")
            logger?.i("[CTX] Runtime policy: ${if (preserveAnchor) "empty bind anchor retained" else "runtime removed"}")
            logger?.i("[CTX] Stop duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")

            if (remainingPids.isNotEmpty() || socketAfter) {
                logger?.e("[-] Could not fully stop ${displaySlot.describe()}")
                false
            } else {
                logger?.i("[+] ${displaySlot.describe()} inactive")
                logger?.i("[+] X11 runtime cleanup verified")
                true
            }
        } catch (e: Exception) {
            logger?.e("[-] Could not stop ${displaySlot.describe()}: ${e.message}")
            false
        }
    }

    private suspend fun waitForContainerRuntime(
        containerName: String,
        timeoutMillis: Long = 5_000L,
        pollIntervalMillis: Long = 500L
    ): Pair<ContainerStatus, Int?> {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var latest = ContainerManager.getContainerRuntimeStatePublic(containerName)
        if (latest.first == ContainerStatus.RUNNING) return latest

        while (System.nanoTime() < deadline) {
            delay(pollIntervalMillis)
            latest = ContainerManager.getContainerRuntimeStatePublic(containerName)
            if (latest.first == ContainerStatus.RUNNING) return latest
        }
        return latest
    }

    private suspend fun waitForContainerCommandReady(
        containerName: String,
        timeoutMillis: Long = 15_000L,
        pollIntervalMillis: Long = 1_000L
    ): Boolean {
        val marker = "__SAAS_X11_READY__"
        val command =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
                shellQuote("echo $marker") + " 2>/dev/null"
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L

        while (true) {
            val ready = try {
                val result = Shell.cmd(command).exec()
                result.isSuccess && result.out.any { it.contains(marker) }
            } catch (_: Exception) {
                false
            }
            if (ready) return true
            if (System.nanoTime() >= deadline) return false
            delay(pollIntervalMillis)
        }
    }

    suspend fun startX11SessionDetailed(
        containerName: String,
        logger: ContainerLogger? = null,
        beforeGraphicSession: (suspend () -> Unit)? = null
    ): X11SessionStartResult? = withContext(Dispatchers.IO) {
        var serverLease: ServerLease? = null
        var containerStartAccepted = false
        val operationStartedAt = System.nanoTime()
        setPendingLease(containerName, true)

        try {
            logger?.i("--- Starting Integrated X11 Session ---")
            logger?.i("")

            var containers = ContainerManager.listContainers()
            reconcileRuntime(containers, logger)
            containers = ContainerManager.listContainers()

            val before = containers.firstOrNull { it.name == containerName }
                ?: ContainerManager.getContainerInfo(containerName)
                ?: run {
                    logger?.e("[-] Container $containerName was not found")
                    return@withContext null
                }
            val wasRunning = before.isRunning
            logger?.i("[CTX] Container: $containerName")
            logger?.i("[CTX] Initial container status: ${before.status}")
            logger?.i("[CTX] Initial container PID: ${before.pid ?: "none"}")
            logger?.i("[CTX] Configured init: ${before.initSystem}")

            val displaySlot = selectDisplaySlotForContainer(containerName, containers)
            logger?.i("[CTX] Selected monitor: ${displaySlot.monitorNumber}")
            logger?.i("[CTX] Selected display: ${displaySlot.displayName}")
            logger?.i("[CTX] Selected process: ${displaySlot.processName}")
            logger?.i("[CTX] Selected runtime: ${displaySlot.runtimeDir}")
            logger?.i("[CTX] Selected socket: ${displaySlot.socketFile}")

            if (wasRunning) {
                logger?.i("[+] Keeping ${displaySlot.describe()} for running container $containerName")
            } else {
                val activeReusable = getLiveServerPids(displaySlot).isNotEmpty() && hasLiveSocket(displaySlot)
                if (activeReusable) {
                    logger?.i("[+] Adopting already-active ${displaySlot.describe()} for $containerName")
                } else {
                    logger?.i("[+] Assigned lowest available ${displaySlot.describe()} to $containerName")
                }

                for (other in containers) {
                    if (other.name == containerName || other.isRunning || other.name in pendingLeasesSnapshot()) continue
                    val oldSlot = ContainerConfigManager.displaySlotFromBindMounts(other.bindMounts)
                    if (oldSlot?.number == displaySlot.number) {
                        ContainerConfigManager.clearManualX11Config(other.name, logger)
                    }
                }

                logger?.i("[*] Preparing container X11 config...")
                val configReady = ContainerConfigManager.ensureManualX11Config(
                    containerName = containerName,
                    logger = logger,
                    displaySlot = displaySlot
                )
                if (!configReady) {
                    logger?.e("[-] Container X11 config is not ready")
                    return@withContext null
                }
                logger?.i("[+] Container X11 configuration confirmed")
            }

            logger?.i("")
            val serverResult = startIntegratedServerTracked(displaySlot, containerName, logger)
            if (serverResult.isFailure) {
                if (!wasRunning) ContainerConfigManager.clearManualX11Config(containerName, logger)
                logger?.e("[-] Integrated X11 failed: ${serverResult.exceptionOrNull()?.message}")
                return@withContext null
            }
            val activeServer = serverResult.getOrThrow()
            serverLease = activeServer
            logger?.i("[CTX] X11 server PID: ${activeServer.pid}")
            logger?.i("[CTX] X11 server lease: ${if (activeServer.reused) "adopted/reused" else "new"}")

            logger?.i("")
            if (wasRunning) {
                containerStartAccepted = true
                logger?.i("[+] Container already running on ${displaySlot.describe()}")
            } else {
                logger?.i("[*] Starting container...")
                val started = ContainerManager.startContainer(containerName, logger)
                if (started) {
                    containerStartAccepted = true
                } else {
                    val (statusAfterFailure, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
                    if (statusAfterFailure == ContainerStatus.RUNNING) {
                        containerStartAccepted = true
                        logger?.w("[!] Start command reported failure, but container is running")
                    } else if (statusAfterFailure == ContainerStatus.STOPPED) {
                        logger?.e("[-] Container start failed and runtime is stopped")
                        rollbackServer(activeServer, logger)
                        ContainerConfigManager.clearManualX11Config(containerName, logger)
                        return@withContext null
                    }
                }
            }

            logger?.i("[*] Confirming container runtime (timeout 5s)...")
            val (runtimeStatus, pid) = waitForContainerRuntime(containerName)
            when (runtimeStatus) {
                ContainerStatus.RUNNING -> logger?.i("[+] Container runtime active${if (pid != null) " (PID=$pid)" else ""}")
                ContainerStatus.STOPPED -> logger?.w("[!] Container runtime is currently stopped")
                ContainerStatus.UNKNOWN -> logger?.w("[!] Container runtime status is still unknown")
            }

            if (runtimeStatus == ContainerStatus.STOPPED) {
                if (!activeServer.reused) rollbackServer(activeServer, logger)
                ContainerConfigManager.clearManualX11Config(containerName, logger)
                return@withContext null
            }

            logger?.i("[*] Waiting for container command readiness (15s)...")
            val commandReady = waitForContainerCommandReady(containerName)
            if (commandReady) logger?.i("[+] Container command channel ready")
            else logger?.w("[!] Container command channel is still becoming ready")

            val graphicSessionReady = if (commandReady) {
                beforeGraphicSession?.invoke()
                logger?.i("[*] Synchronizing configured graphic session with ${displaySlot.displayName}...")
                ensureContainerGraphicSession(containerName, displaySlot, logger)
            } else {
                false
            }

            logger?.i("")
            logger?.i("--- Integrated X11 Session Result ---")
            logger?.i("[CTX] Container runtime: $runtimeStatus")
            logger?.i("[CTX] Container PID: ${pid ?: "none"}")
            logger?.i("[CTX] Command channel ready: ${if (commandReady) "yes" else "no"}")
            logger?.i("[CTX] Graphic session confirmed: ${if (graphicSessionReady) "yes" else "no"}")
            logger?.i("[CTX] Monitor: ${displaySlot.monitorNumber}")
            logger?.i("[CTX] Display: ${displaySlot.displayName}")
            logger?.i("[CTX] X11 PID: ${activeServer.pid}")
            logger?.i("[CTX] Total session start duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")

            if (runtimeStatus == ContainerStatus.RUNNING && commandReady && graphicSessionReady) {
                logger?.i("[+] Integrated X11 session started on ${displaySlot.describe()}")
            } else if (runtimeStatus == ContainerStatus.RUNNING && commandReady) {
                logger?.w("[!] ${displaySlot.describe()} is ready, but the configured graphic session is not active")
            } else {
                logger?.w("[!] ${displaySlot.describe()} is ready while container startup is still settling")
            }
            logger?.i("[+] Monitor: ${displaySlot.monitorNumber}")
            logger?.i("[+] X11 display: ${displaySlot.displayName}")
            X11SessionStartResult(
                slot = displaySlot,
                runtimeStatus = runtimeStatus,
                containerPid = pid,
                commandReady = commandReady,
                graphicSessionReady = graphicSessionReady
            )
        } catch (e: Exception) {
            if (!containerStartAccepted) {
                serverLease?.let { rollbackServer(it, logger) }
                ContainerConfigManager.clearManualX11Config(containerName, logger)
            }
            logger?.e("[-] Error: ${e.message}")
            null
        } finally {
            setPendingLease(containerName, false)
        }
    }

    suspend fun startX11Session(
        containerName: String,
        logger: ContainerLogger? = null,
        beforeGraphicSession: (suspend () -> Unit)? = null
    ): X11DisplaySlot? = startX11SessionDetailed(
        containerName = containerName,
        logger = logger,
        beforeGraphicSession = beforeGraphicSession
    )?.slot

    suspend fun stopX11Session(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Stopping Container X11 Session ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Policy: stop container, remove its config lease, then release its monitor if unused")

        val before = ContainerManager.getContainerInfo(containerName)
        logger?.i("[CTX] Container status before stop: ${before?.status ?: ContainerStatus.UNKNOWN}")
        logger?.i("[CTX] Container PID before stop: ${before?.pid ?: "none"}")
        val displaySlot = before?.let {
            ContainerConfigManager.displaySlotFromBindMounts(it.bindMounts)
        }
        logger?.i("[CTX] Assigned display before stop: ${displaySlot?.displayName ?: "none"}")

        val stopped = ContainerManager.stopContainer(containerName, logger)
        if (!stopped) {
            logger?.e("[-] Container stop was not confirmed; X11 ownership will not be recycled")
            return@withContext false
        }
        logger?.i("[+] Container stop confirmed")

        if (displaySlot != null) {
            val stillUsed = ContainerManager.listContainers()
                .asSequence()
                .filter { it.isRunning && it.name != containerName }
                .mapNotNull { ContainerConfigManager.displaySlotFromBindMounts(it.bindMounts) }
                .any { it.number == displaySlot.number }

            logger?.i("[CTX] Display still owned by another running container: ${if (stillUsed) "yes" else "no"}")
            if (!stillUsed) {
                val serverStopped = stopIntegratedServer(displaySlot, logger)
                if (serverStopped) logger?.i("[+] Released ${displaySlot.describe()}")
                else logger?.w("[!] Container stopped, but ${displaySlot.describe()} cleanup was not fully confirmed")
            }
        }

        val configReleased = ContainerConfigManager.clearManualX11Config(containerName, logger)
        if (!configReleased) {
            logger?.w("[!] Container stopped, but its persistent X11 bind could not be cleared")
        }

        reconcileRuntime(ContainerManager.listContainers(), logger)
        true
    }

    suspend fun stopAll(logger: ContainerLogger? = null) = withContext(Dispatchers.IO) {
        try {
            logger?.i("--- Stopping All ---")
            val containers = ContainerManager.listContainers()
            val runningContainers = containers.filter { it.isRunning }
            logger?.i("[CTX] Running containers discovered: ${runningContainers.size}")

            val slots = buildSet {
                addAll(discoverRuntimeSlots().map { it.number })
                containers.forEach { container ->
                    ContainerConfigManager.displaySlotFromBindMounts(container.bindMounts)
                        ?.let { add(it.number) }
                }
            }

            for (container in runningContainers) {
                logger?.i("[*] Stopping container: ${container.name}")
                ContainerManager.stopContainer(container.name, logger)
            }
            for (number in slots.sorted()) {
                stopIntegratedServer(X11DisplaySlot(number), logger)
            }
            for (container in containers) {
                if (ContainerConfigManager.hasManagerX11Bind(container.bindMounts)) {
                    ContainerConfigManager.clearManualX11Config(container.name, logger)
                }
            }

            reconcileRuntime(ContainerManager.listContainers(), logger)
            logger?.i("[+] All containers and X11 monitors stopped")
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
        }
    }
}
