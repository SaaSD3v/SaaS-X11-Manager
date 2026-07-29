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
            val lsResult = Shell.cmd("ls '${Constants.CONTAINERS_DIR}' 2>/dev/null").exec()
            if (!lsResult.isSuccess || lsResult.out.isEmpty()) return@withContext containers

            val names = lsResult.out.flatMap { line ->
                line.trim().split("\\s+".toRegex())
            }.filter { it.isNotEmpty() }

            if (names.isEmpty()) return@withContext containers

            for (name in names) {
                val configPath = "${Constants.CONTAINERS_DIR}/$name/${Constants.CONFIG_FILE}"
                val config = loadConfig(configPath, name) ?: continue
                val running = isContainerRunning(name)
                containers.add(config.copy(
                    status = if (running) ContainerStatus.RUNNING else ContainerStatus.STOPPED
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        containers
    }

    private fun isContainerRunning(name: String): Boolean {
        return try {
            val r = Shell.cmd(
                "${Constants.DS_BINARY_PATH} --name='$name' run 'echo ok' 2>/dev/null"
            ).exec()
            r.isSuccess && r.out.any { it.trim() == "ok" }
        } catch (_: Exception) { false }
    }

    private fun loadConfig(path: String, defaultName: String): ContainerInfo? {
        return try {
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

            ContainerInfo(
                name = name, rootfsPath = rootfs, configPath = path,
                hostname = m["hostname"] ?: "",
                enableTermuxX11 = m["enable_termux_x11"] == "1",
                enableLegacyTermuxX11 = m["enable_legacy_termux_x11"] == "1",
                enableHwAccess = m["enable_hw_access"] == "1",
                enablePulseAudio = m["enable_pulseaudio"] == "1",
                netMode = m["net_mode"] ?: "nat",
                bindMounts = m["bind_mounts"] ?: ""
            )
        } catch (_: Exception) { null }
    }

    suspend fun getContainerInfo(name: String): ContainerInfo? = withContext(Dispatchers.IO) {
        val path = "${Constants.CONTAINERS_DIR}/$name/${Constants.CONFIG_FILE}"
        if (!Shell.cmd("test -f '$path'").exec().isSuccess) return@withContext null
        val c = loadConfig(path, name) ?: return@withContext null
        val running = isContainerRunning(c.name)
        c.copy(status = if (running) ContainerStatus.RUNNING else ContainerStatus.STOPPED)
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

    suspend fun checkContainerStatusPublic(name: String): Pair<Boolean, Int?> = withContext(Dispatchers.IO) {
        val running = isContainerRunning(name)
        Pair(running, null)
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
