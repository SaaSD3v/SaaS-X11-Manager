package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ContainerStatus { RUNNING, STOPPED, UNKNOWN }

data class ContainerInfo(
    val name: String,
    val rootfsPath: String,
    val configPath: String,
    val hostname: String = "",
    val enableTermuxX11: Boolean = false,
    val enableLegacyTermuxX11: Boolean = false,
    val enableHwAccess: Boolean = false,
    val enablePulseAudio: Boolean = false,
    val netMode: String = "nat",
    val bindMounts: String = "",
    val status: ContainerStatus = ContainerStatus.UNKNOWN,
    val pid: Int? = null
) {
    val isRunning: Boolean get() = status == ContainerStatus.RUNNING
    val hasPulseAudioBindMount: Boolean
        get() = bindMounts.contains("/tmp/.pulse-socket")
}

object ContainerManager {

    private const val PA_BIND = "${Constants.TERMUX_PREFIX}/tmp/.pulse-socket:/tmp/.pulse-socket"

    suspend fun listContainers(): List<ContainerInfo> = withContext(Dispatchers.IO) {
        val containers = mutableListOf<ContainerInfo>()
        try {
            val allConfigs = loadAllConfigs()
            if (allConfigs.isEmpty()) return@withContext containers

            val allNames = allConfigs.map { it.first }
            val psMap = batchGetStatusViaPs(allNames)

            for ((name, config) in allConfigs) {
                val (running, pid) = psMap[name] ?: getStatus(name)
                containers.add(config.copy(
                    status = if (running) ContainerStatus.RUNNING else ContainerStatus.STOPPED,
                    pid = pid
                ))
            }
        } catch (_: Exception) {}
        containers
    }

    private fun loadAllConfigs(): List<Pair<String, ContainerInfo>> {
        val result = mutableListOf<Pair<String, ContainerInfo>>()

        val script = """
            DIR="${Constants.CONTAINERS_DIR}"
            for d in "$DIR"/*/; do
                [ -d "$d" ] || continue
                name=$(basename "$d")
                cfg="$d${Constants.CONFIG_FILE}"
                [ -f "$cfg" ] || continue
                echo "===CONTAINER_START==="
                echo "NAME=$name"
                cat "$cfg"
                echo "===CONTAINER_END==="
            done
        """.trimIndent()

        val r = Shell.cmd(script).exec()
        if (!r.isSuccess || r.out.isEmpty()) return result

        val output = r.out.joinToString("\n")
        val blocks = output.split("===CONTAINER_START===")

        for (block in blocks) {
            val trimmed = block.trim()
            if (!trimmed.startsWith("NAME=")) continue
            val endIdx = trimmed.indexOf("===CONTAINER_END===")
            val content = if (endIdx > 0) trimmed.substring(0, endIdx).trim() else trimmed

            val lines = content.lines()
            if (lines.isEmpty()) continue

            val name = lines.first().removePrefix("NAME=").trim()
            if (name.isEmpty()) continue

            val m = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val t = lines[i].trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val p = t.split("=", limit = 2)
                if (p.size == 2) m[p[0].trim()] = p[1].trim()
            }

            val configName = m["name"] ?: name
            val sparse = m["use_sparse_image"] == "1"
            val rootfs = m["rootfs_path"] ?: if (sparse) {
                "${Constants.CONTAINERS_DIR}/$name/rootfs.img"
            } else {
                "${Constants.CONTAINERS_DIR}/$name/rootfs"
            }

            if (rootfs.isBlank()) continue

            result.add(configName to ContainerInfo(
                name = configName,
                rootfsPath = rootfs,
                configPath = "${Constants.CONTAINERS_DIR}/$name/${Constants.CONFIG_FILE}",
                hostname = m["hostname"] ?: "",
                enableTermuxX11 = m["enable_termux_x11"] == "1",
                enableLegacyTermuxX11 = m["enable_legacy_termux_x11"] == "1",
                enableHwAccess = m["enable_hw_access"] == "1",
                enablePulseAudio = m["enable_pulseaudio"] == "1",
                netMode = m["net_mode"] ?: "nat",
                bindMounts = m["bind_mounts"] ?: ""
            ))
        }

        return result
    }

    private fun batchGetStatusViaPs(names: List<String>): Map<String, Pair<Boolean, Int?>> {
        val result = mutableMapOf<String, Pair<Boolean, Int?>>()
        if (names.isEmpty()) return result

        try {
            val r = Shell.cmd("ps -eo pid,args 2>/dev/null | grep -i droidspaces | grep -v grep").exec()
            if (r.isSuccess && r.out.isNotEmpty()) {
                for (line in r.out) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size < 2) continue
                    val pid = parts[0].toIntOrNull() ?: continue
                    val cmdline = parts.drop(1).joinToString(" ")
                    for (name in names) {
                        if (cmdline.contains("--name=$name") || cmdline.contains(name)) {
                            result[name] = Pair(true, pid)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        for (name in names) {
            if (name !in result) result[name] = Pair(false, null)
        }
        return result
    }

    private fun loadConfig(path: String, defaultName: String): ContainerInfo? {
        try {
            val r = Shell.cmd("cat '$path' 2>/dev/null").exec()
            if (!r.isSuccess || r.out.isEmpty()) return null

            val m = mutableMapOf<String, String>()
            r.out.forEach { line ->
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
                name = name, rootfsPath = rootfs, configPath = path,
                hostname = m["hostname"] ?: "",
                enableTermuxX11 = m["enable_termux_x11"] == "1",
                enableLegacyTermuxX11 = m["enable_legacy_termux_x11"] == "1",
                enableHwAccess = m["enable_hw_access"] == "1",
                enablePulseAudio = m["enable_pulseaudio"] == "1",
                netMode = m["net_mode"] ?: "nat",
                bindMounts = m["bind_mounts"] ?: ""
            )
        } catch (_: Exception) { return null }
    }

    suspend fun getContainerInfo(name: String): ContainerInfo? = withContext(Dispatchers.IO) {
        val path = "${Constants.CONTAINERS_DIR}/$name/${Constants.CONFIG_FILE}"
        if (!Shell.cmd("test -f '$path'").exec().isSuccess) return@withContext null
        val c = loadConfig(path, name) ?: return@withContext null
        val (running, pid) = getStatus(c.name)
        c.copy(status = if (running) ContainerStatus.RUNNING else ContainerStatus.STOPPED, pid = pid)
    }

    suspend fun updatePulseAudioBindMount(
        name: String,
        enable: Boolean,
        cacheDir: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = "${Constants.CONTAINERS_DIR}/$name/${Constants.CONFIG_FILE}"
            val r = Shell.cmd("cat '$path' 2>/dev/null").exec()
            if (!r.isSuccess || r.out.isEmpty()) return@withContext false

            val lines = r.out.toMutableList()
            var bindIdx = -1
            for (i in lines.indices) {
                if (lines[i].trim().startsWith("bind_mounts=")) { bindIdx = i; break }
            }

            if (enable) {
                if (bindIdx >= 0) {
                    val cur = lines[bindIdx].substringAfter("bind_mounts=")
                    if (!cur.contains(PA_BIND)) {
                        lines[bindIdx] = "bind_mounts=${if (cur.isBlank()) PA_BIND else "$cur,$PA_BIND"}"
                    }
                } else {
                    lines.add("bind_mounts=$PA_BIND")
                }
            } else {
                if (bindIdx >= 0) {
                    val cur = lines[bindIdx].substringAfter("bind_mounts=")
                    val new = cur.replace(",$PA_BIND", "").replace("$PA_BIND,", "").replace(PA_BIND, "").trim()
                    if (new.isEmpty()) lines.removeAt(bindIdx) else lines[bindIdx] = "bind_mounts=$new"
                }
            }

            val tmpFile = File(cacheDir, "container_$name.config")
            tmpFile.writeText(lines.joinToString("\n") + "\n")
            val cpResult = Shell.cmd("cp '${tmpFile.absolutePath}' '$path' 2>&1").exec()
            tmpFile.delete()

            if (!cpResult.isSuccess) return@withContext false
            Shell.cmd("chmod 644 '$path' 2>/dev/null").exec()
            true
        } catch (_: Exception) { false }
    }

    private fun getStatus(name: String): Pair<Boolean, Int?> {
        try {
            val r = Shell.cmd("${Constants.DS_BINARY_PATH} --name='$name' pid 2>/dev/null").exec()
            if (r.isSuccess && r.out.isNotEmpty()) {
                val pid = r.out.first().trim().toIntOrNull()
                if (pid != null && pid > 0) {
                    val alive = Shell.cmd("kill -0 $pid 2>/dev/null").exec()
                    if (alive.isSuccess) return Pair(true, pid)
                }
            }
        } catch (_: Exception) {}
        return Pair(false, null)
    }

    suspend fun checkContainerStatusPublic(name: String): Pair<Boolean, Int?> = withContext(Dispatchers.IO) {
        getStatus(name)
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
