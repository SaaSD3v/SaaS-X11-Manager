package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ContainerStatus { RUNNING, STOPPED, UNKNOWN }
enum class InitSystem { SYSTEMD, OPENRC }

data class ContainerInfo(
    val name: String,
    val rootfsPath: String,
    val configPath: String,
    val hostname: String = "",
    val enableTermuxX11: Boolean = false,
    val enableLegacyTermuxX11: Boolean = false,
    val enableHwAccess: Boolean = false,
    val netMode: String = "nat",
    val bindMounts: String = "",
    val status: ContainerStatus = ContainerStatus.UNKNOWN,
    val pid: Int? = null,
    val initSystem: InitSystem = InitSystem.SYSTEMD
) {
    val isRunning: Boolean get() = status == ContainerStatus.RUNNING
}

object ContainerManager {

    private const val CONFIG_MARKER = "@@SAAS_X11_CONTAINER@@"

    suspend fun listContainers(): List<ContainerInfo> = withContext(Dispatchers.IO) {
        try {
            val configs = loadConfigsBatch()
            if (configs.isEmpty()) return@withContext emptyList()

            val states = getContainerRuntimeStates(configs.map { it.name })
            configs.map { container ->
                val state = states[container.name]
                    ?: ContainerRuntimeState(ContainerStatus.UNKNOWN)
                container.copy(status = state.status, pid = state.pid)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun loadConfigsBatch(): List<ContainerInfo> {
        val containersDir = Constants.CONTAINERS_DIR
        val configFile = Constants.CONFIG_FILE
        val marker = CONFIG_MARKER
        val script = "for cfg in '$containersDir'/*/$configFile; do " +
            "[ -f \"\$cfg\" ] || continue; " +
            "dir=\${cfg%/$configFile}; " +
            "fallback=\${dir##*/}; " +
            "printf '%s%s\\n' '$marker' \"\$fallback\"; " +
            "cat \"\$cfg\"; " +
            "done"

        val result = Shell.cmd(script).exec()
        if (!result.isSuccess || result.out.isEmpty()) return emptyList()

        val containers = mutableListOf<ContainerInfo>()
        var fallbackName: String? = null
        val configLines = mutableListOf<String>()

        fun flush() {
            val fallback = fallbackName ?: return
            parseConfigLines(
                lines = configLines,
                defaultName = fallback,
                path = "${Constants.CONTAINERS_DIR}/$fallback/${Constants.CONFIG_FILE}"
            )?.let(containers::add)
            configLines.clear()
        }

        result.out.forEach { line ->
            if (line.startsWith(CONFIG_MARKER)) {
                flush()
                fallbackName = line.removePrefix(CONFIG_MARKER)
            } else if (fallbackName != null) {
                configLines.add(line)
            }
        }
        flush()

        return containers
    }

    private fun getContainerRuntimeStates(
        containerNames: List<String>
    ): Map<String, ContainerRuntimeState> {
        val names = containerNames.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return emptyMap()

        tryMachineReadableShow(names)?.let { return it }
        tryPlainShow(names)?.let { return it }
        return queryPidsIndividually(names)
    }

    private fun tryMachineReadableShow(
        containerNames: List<String>
    ): Map<String, ContainerRuntimeState>? {
        return try {
            val result = Shell.cmd("${Constants.DS_BINARY_PATH} --format show 2>/dev/null").exec()
            if (!result.isSuccess) return null
            ContainerRuntimeParser.parseMachineReadableShow(result.out, containerNames)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryPlainShow(
        containerNames: List<String>
    ): Map<String, ContainerRuntimeState>? {
        return try {
            val result = Shell.cmd("${Constants.DS_BINARY_PATH} show 2>/dev/null").exec()
            if (!result.isSuccess) return null
            ContainerRuntimeParser.parsePlainShow(result.out, containerNames)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryPidsIndividually(
        containerNames: List<String>
    ): Map<String, ContainerRuntimeState> {
        return buildMap {
            containerNames.forEach { name ->
                val state = try {
                    val result = Shell.cmd(
                        "${Constants.DS_BINARY_PATH} --name=${shellQuote(name)} pid 2>/dev/null"
                    ).exec()
                    ContainerRuntimeParser.parsePid(result.out)
                } catch (_: Exception) {
                    ContainerRuntimeState(ContainerStatus.UNKNOWN)
                }
                put(name, state)
            }
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun parseConfigLines(
        lines: List<String>,
        defaultName: String,
        path: String
    ): ContainerInfo? {
        val m = mutableMapOf<String, String>()
        lines.forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val p = t.split("=", limit = 2)
            if (p.size == 2) m[p[0].trim()] = p[1].trim()
        }

        val name = m["name"] ?: defaultName
        val sparse = m["use_sparse_image"] == "1"
        val rootfs = m["rootfs_path"] ?: if (sparse) {
            "${Constants.CONTAINERS_DIR}/$defaultName/rootfs.img"
        } else {
            "${Constants.CONTAINERS_DIR}/$defaultName/rootfs"
        }
        if (rootfs.isBlank()) return null

        return ContainerInfo(
            name = name,
            rootfsPath = rootfs,
            configPath = path,
            hostname = m["hostname"] ?: "",
            enableTermuxX11 = m["enable_termux_x11"] == "1",
            enableLegacyTermuxX11 = m["enable_legacy_termux_x11"] == "1",
            enableHwAccess = m["enable_hw_access"] == "1",
            netMode = m["net_mode"] ?: "nat",
            bindMounts = m["bind_mounts"] ?: ""
        )
    }

    private fun loadConfig(path: String, defaultName: String): ContainerInfo? {
        return try {
            val r = Shell.cmd("cat '$path' 2>/dev/null").exec()
            if (!r.isSuccess || r.out.isEmpty()) return null
            parseConfigLines(r.out, defaultName, path)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getContainerInfo(name: String): ContainerInfo? = withContext(Dispatchers.IO) {
        val path = "${Constants.CONTAINERS_DIR}/$name/${Constants.CONFIG_FILE}"
        if (!Shell.cmd("test -f '$path'").exec().isSuccess) return@withContext null
        val c = loadConfig(path, name) ?: return@withContext null
        val state = getContainerRuntimeStates(listOf(c.name))[c.name]
            ?: ContainerRuntimeState(ContainerStatus.UNKNOWN)
        val initSys = ContainerSettingsManager.getInitSystem(c.name) ?: InitSystem.SYSTEMD
        c.copy(status = state.status, pid = state.pid, initSystem = initSys)
    }

    suspend fun getContainerRuntimeStatePublic(
        name: String
    ): Pair<ContainerStatus, Int?> = withContext(Dispatchers.IO) {
        val state = getContainerRuntimeStates(listOf(name))[name]
            ?: ContainerRuntimeState(ContainerStatus.UNKNOWN)
        Pair(state.status, state.pid)
    }

    suspend fun checkContainerStatusPublic(name: String): Pair<Boolean, Int?> {
        val (status, pid) = getContainerRuntimeStatePublic(name)
        return Pair(status == ContainerStatus.RUNNING, pid)
    }

    suspend fun updateInitSystem(
        name: String,
        target: InitSystem,
        cacheDir: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val config = loadConfig(
                "${Constants.CONTAINERS_DIR}/$name/${Constants.CONFIG_FILE}", name
            ) ?: return@withContext false

            val applied = RootfsAccessor.use(
                rootfsPath = config.rootfsPath,
                tag = "init_${name}_${target.name.lowercase()}"
            ) { root ->
                when (target) {
                    InitSystem.OPENRC -> applyOpenRcInitFiles(root, cacheDir)
                    InitSystem.SYSTEMD -> applySystemdInitFiles(root, cacheDir)
                }
            } ?: false

            if (!applied) return@withContext false
            ContainerSettingsManager.setInitSystem(name, target, cacheDir)
        } catch (_: Exception) {
            false
        }
    }

    private fun applyOpenRcInitFiles(root: String, cacheDir: File): Boolean {
        if (!Shell.cmd("test -x '$root/sbin/openrc-run'").exec().isSuccess) {
            return false
        }

        val sessionFile = File.createTempFile("x11-session-openrc-", ".sh", cacheDir)
        val setupFile = File.createTempFile("x11-setup-openrc-", "", cacheDir)
        val xfceFile = File.createTempFile("x11-xfce-openrc-", "", cacheDir)

        return try {
            sessionFile.writeText(
                "#!/bin/sh\n" +
                    "export DISPLAY=:0\n" +
                    "export HOME=/root\n" +
                    "export USER=root\n" +
                    "export SHELL=/bin/sh\n" +
                    "export XDG_SESSION_TYPE=x11\n" +
                    "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
                    "mkdir -p \"\$XDG_RUNTIME_DIR\"\n" +
                    "exec startxfce4\n"
            )

            setupFile.writeText(
                "#!/sbin/openrc-run\n\n" +
                    "description=\"Setup X11 socket directory and bind mount\"\n\n" +
                    "depend() {\n" +
                    "    before x11-xfce\n" +
                    "}\n\n" +
                    "start() {\n" +
                    "    ebegin \"Setting up X11 socket\"\n" +
                    "    if [ ! -d /usr/.X11-unix ]; then\n" +
                    "        eerror \"X11 source socket directory /usr/.X11-unix is missing\"\n" +
                    "        eend 1\n" +
                    "        return 1\n" +
                    "    fi\n" +
                    "    mkdir -p /tmp/.X11-unix /tmp/runtime-root || { eend 1; return 1; }\n" +
                    "    if mountpoint -q /tmp/.X11-unix 2>/dev/null; then\n" +
                    "        eend 0\n" +
                    "        return 0\n" +
                    "    fi\n" +
                    "    mount --bind /usr/.X11-unix /tmp/.X11-unix\n" +
                    "    rc=$?\n" +
                    "    eend \$rc\n" +
                    "    return \$rc\n" +
                    "}\n\n" +
                    "stop() {\n" +
                    "    ebegin \"Unmounting X11 socket\"\n" +
                    "    if mountpoint -q /tmp/.X11-unix 2>/dev/null; then\n" +
                    "        umount /tmp/.X11-unix\n" +
                    "        rc=$?\n" +
                    "        eend \$rc\n" +
                    "        return \$rc\n" +
                    "    fi\n" +
                    "    eend 0\n" +
                    "}\n"
            )

            xfceFile.writeText(
                "#!/sbin/openrc-run\n\n" +
                    "description=\"X11 XFCE Desktop on Termux:X11\"\n" +
                    "command=\"/usr/local/bin/x11-session.sh\"\n" +
                    "command_background=\"yes\"\n" +
                    "pidfile=\"/run/x11-xfce.pid\"\n" +
                    "stopgroup=\"yes\"\n\n" +
                    "depend() {\n" +
                    "    need x11-setup\n" +
                    "}\n"
            )

            val install = Shell.cmd(
                "rm -f '$root/etc/systemd/system/setup-x11-socket.service' " +
                    "'$root/etc/systemd/system/x11-xfce.service' " +
                    "'$root/etc/systemd/system/multi-user.target.wants/setup-x11-socket.service' " +
                    "'$root/etc/systemd/system/graphical.target.wants/x11-xfce.service' 2>/dev/null; " +
                    "mkdir -p '$root/usr/local/bin' '$root/etc/init.d' '$root/etc/runlevels/default' && " +
                    "cp '${sessionFile.absolutePath}' '$root/usr/local/bin/x11-session.sh' && " +
                    "chmod 755 '$root/usr/local/bin/x11-session.sh' && " +
                    "cp '${setupFile.absolutePath}' '$root/etc/init.d/x11-setup' && " +
                    "chmod 755 '$root/etc/init.d/x11-setup' && " +
                    "cp '${xfceFile.absolutePath}' '$root/etc/init.d/x11-xfce' && " +
                    "chmod 755 '$root/etc/init.d/x11-xfce' && " +
                    "ln -sf /etc/init.d/x11-setup '$root/etc/runlevels/default/x11-setup' && " +
                    "ln -sf /etc/init.d/x11-xfce '$root/etc/runlevels/default/x11-xfce'"
            ).exec()
            if (!install.isSuccess) return false

            Shell.cmd(
                "test -x '$root/usr/local/bin/x11-session.sh' && " +
                    "test -x '$root/etc/init.d/x11-setup' && " +
                    "test -x '$root/etc/init.d/x11-xfce' && " +
                    "test -L '$root/etc/runlevels/default/x11-setup' && " +
                    "test -L '$root/etc/runlevels/default/x11-xfce'"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        } finally {
            sessionFile.delete()
            setupFile.delete()
            xfceFile.delete()
        }
    }

    private fun applySystemdInitFiles(root: String, cacheDir: File): Boolean {
        val sessionFile = File.createTempFile("x11-session-systemd-", ".sh", cacheDir)
        val socketServiceFile = File.createTempFile("setup-x11-socket-", ".service", cacheDir)
        val xfceServiceFile = File.createTempFile("x11-xfce-", ".service", cacheDir)

        return try {
            sessionFile.writeText(
                "#!/bin/bash\n" +
                    "export DISPLAY=:0\n" +
                    "export HOME=/root\n" +
                    "export USER=root\n" +
                    "export SHELL=/bin/bash\n" +
                    "export XDG_SESSION_TYPE=x11\n" +
                    "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
                    "mkdir -p \"\$XDG_RUNTIME_DIR\"\n" +
                    "exec startxfce4\n"
            )

            socketServiceFile.writeText(
                "[Unit]\n" +
                    "Description=Setup X11 socket directory\n" +
                    "Before=x11-xfce.service\n\n" +
                    "[Service]\n" +
                    "Type=oneshot\n" +
                    "ExecStart=/bin/mkdir -p /tmp/.X11-unix\n" +
                    "ExecStart=/bin/mount --bind /usr/.X11-unix /tmp/.X11-unix\n" +
                    "ExecStart=/bin/mkdir -p /tmp/runtime-root\n" +
                    "RemainAfterExit=yes\n\n" +
                    "[Install]\n" +
                    "WantedBy=multi-user.target\n"
            )

            xfceServiceFile.writeText(
                "[Unit]\n" +
                    "Description=X11 XFCE Desktop on Termux:X11\n" +
                    "After=network.target setup-x11-socket.service\n" +
                    "Requires=setup-x11-socket.service\n\n" +
                    "[Service]\n" +
                    "Type=simple\n" +
                    "Environment=DISPLAY=:0\n" +
                    "Environment=HOME=/root\n" +
                    "Environment=USER=root\n" +
                    "Environment=SHELL=/bin/bash\n" +
                    "Environment=XDG_SESSION_TYPE=x11\n" +
                    "Environment=XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
                    "ExecStart=/usr/local/bin/x11-session.sh\n" +
                    "Restart=on-failure\n" +
                    "RestartSec=3\n\n" +
                    "[Install]\n" +
                    "WantedBy=graphical.target\n"
            )

            val install = Shell.cmd(
                "rm -f '$root/etc/init.d/x11-setup' " +
                    "'$root/etc/init.d/x11-xfce' " +
                    "'$root/etc/runlevels/default/x11-setup' " +
                    "'$root/etc/runlevels/default/x11-xfce' 2>/dev/null; " +
                    "mkdir -p '$root/usr/local/bin' " +
                    "'$root/etc/systemd/system/multi-user.target.wants' " +
                    "'$root/etc/systemd/system/graphical.target.wants' && " +
                    "cp '${sessionFile.absolutePath}' '$root/usr/local/bin/x11-session.sh' && " +
                    "chmod 755 '$root/usr/local/bin/x11-session.sh' && " +
                    "cp '${socketServiceFile.absolutePath}' '$root/etc/systemd/system/setup-x11-socket.service' && " +
                    "chmod 644 '$root/etc/systemd/system/setup-x11-socket.service' && " +
                    "cp '${xfceServiceFile.absolutePath}' '$root/etc/systemd/system/x11-xfce.service' && " +
                    "chmod 644 '$root/etc/systemd/system/x11-xfce.service' && " +
                    "ln -sf /etc/systemd/system/setup-x11-socket.service " +
                    "'$root/etc/systemd/system/multi-user.target.wants/setup-x11-socket.service' && " +
                    "ln -sf /etc/systemd/system/x11-xfce.service " +
                    "'$root/etc/systemd/system/graphical.target.wants/x11-xfce.service'"
            ).exec()
            if (!install.isSuccess) return false

            Shell.cmd(
                "test -f '$root/usr/local/bin/x11-session.sh' && " +
                    "test -f '$root/etc/systemd/system/setup-x11-socket.service' && " +
                    "test -f '$root/etc/systemd/system/x11-xfce.service' && " +
                    "test -L '$root/etc/systemd/system/multi-user.target.wants/setup-x11-socket.service' && " +
                    "test -L '$root/etc/systemd/system/graphical.target.wants/x11-xfce.service'"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        } finally {
            sessionFile.delete()
            socketServiceFile.delete()
            xfceServiceFile.delete()
        }
    }

    suspend fun startContainer(name: String, logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val r = Shell.cmd(
                "${Constants.DS_BINARY_PATH} --config='${Constants.CONTAINERS_DIR}/$name/${Constants.CONFIG_FILE}' start 2>&1"
            ).exec()
            val out = r.out.joinToString("\n")
            if (out.isNotBlank()) logger?.i(out)
            r.err.forEach { val t = it.trim(); if (t.isNotEmpty()) logger?.w(t) }
            r.isSuccess
        } catch (e: Exception) { logger?.e("[-] Error: ${e.message}"); false }
    }

    suspend fun stopContainer(name: String, logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val r = Shell.cmd("${Constants.DS_BINARY_PATH} --name='$name' stop 2>&1").exec()
            val out = r.out.joinToString("\n")
            if (out.isNotBlank()) logger?.i(out)
            r.isSuccess
        } catch (e: Exception) { logger?.e("[-] Error: ${e.message}"); false }
    }
}
