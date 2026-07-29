package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ContainerStatus { RUNNING, STOPPED, UNKNOWN }

data class ContainerInfo(
    val name: String,
    val rootfsPath: String,
    val configPath: String,
    val hostname: String = "",
    val enableTermuxX11: Boolean = false,
    val enableLegacyTermuxX11: Boolean = false,
    val enableHwAccess: Boolean = false,
    val netMode: String = "nat",
    val status: ContainerStatus = ContainerStatus.UNKNOWN,
    val pid: Int? = null
) {
    val isRunning: Boolean get() = status == ContainerStatus.RUNNING
}

object ContainerManager {

    suspend fun listContainers(log: ((String) -> Unit)? = null): List<ContainerInfo> = withContext(Dispatchers.IO) {
        val containers = mutableListOf<ContainerInfo>()
        log?.invoke("Scanning containers...")

        try {
            val result = Shell.cmd("ls -d '${Constants.CONTAINERS_DIR}'/*/ 2>/dev/null").exec()
            if (!result.isSuccess || result.out.isEmpty()) {
                log?.invoke("No containers found")
                return@withContext containers
            }

            for (line in result.out) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith(Constants.CONTAINERS_DIR)) continue

                val sanitizedName = trimmed.removeSuffix("/").substringAfterLast("/")
                if (sanitizedName.isEmpty()) continue

                val configPath = "${Constants.CONTAINERS_DIR}/$sanitizedName/${Constants.CONFIG_FILE}"
                if (!Shell.cmd("test -f '$configPath'").exec().isSuccess) continue

                val config = loadContainerConfig(configPath, sanitizedName) ?: continue

                val (isRunning, pid) = checkContainerStatus(config.name)
                val status = if (isRunning) ContainerStatus.RUNNING else ContainerStatus.STOPPED

                log?.invoke("Found: ${config.name} (${if (isRunning) "running" else "stopped"})")
                containers.add(config.copy(status = status, pid = pid))
            }
        } catch (e: Exception) {
            log?.invoke("Error: ${e.message}")
        }
        log?.invoke("${containers.size} container(s) found")
        containers
    }

    private fun loadContainerConfig(configPath: String, defaultName: String): ContainerInfo? {
        try {
            val readResult = Shell.cmd("cat '$configPath' 2>/dev/null").exec()
            if (!readResult.isSuccess || readResult.out.isEmpty()) return null

            val configMap = mutableMapOf<String, String>()
            readResult.out.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    configMap[parts[0].trim()] = parts[1].trim()
                }
            }

            val containerName = configMap["name"] ?: defaultName
            val useSparseImage = configMap["use_sparse_image"] == "1"

            val rootfsPath = configMap["rootfs_path"] ?: if (useSparseImage) {
                "${Constants.CONTAINERS_DIR}/$defaultName/rootfs.img"
            } else {
                "${Constants.CONTAINERS_DIR}/$defaultName/rootfs"
            }

            if (rootfsPath.isBlank() || !Shell.cmd("test -f '$rootfsPath'").exec().isSuccess) return null

            return ContainerInfo(
                name = containerName,
                rootfsPath = rootfsPath,
                configPath = configPath,
                hostname = configMap["hostname"] ?: "",
                enableTermuxX11 = configMap["enable_termux_x11"] == "1",
                enableLegacyTermuxX11 = configMap["enable_legacy_termux_x11"] == "1",
                enableHwAccess = configMap["enable_hw_access"] == "1",
                netMode = configMap["net_mode"] ?: "nat"
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun checkContainerStatus(name: String): Pair<Boolean, Int?> {
        return try {
            val quoted = "'$name'"
            val result = Shell.cmd(
                "${Constants.DS_BINARY_PATH} --name=$quoted pid 2>/dev/null"
            ).exec()
            if (result.isSuccess && result.out.isNotEmpty()) {
                val pidStr = result.out.first().trim()
                val pid = pidStr.toIntOrNull()
                if (pid != null && pid > 0) {
                    val alive = Shell.cmd("kill -0 $pid 2>/dev/null").exec()
                    if (alive.isSuccess) Pair(true, pid) else Pair(false, null)
                } else {
                    Pair(false, null)
                }
            } else {
                Pair(false, null)
            }
        } catch (_: Exception) {
            Pair(false, null)
        }
    }

    suspend fun checkContainerStatusPublic(name: String): Pair<Boolean, Int?> = withContext(Dispatchers.IO) {
        checkContainerStatus(name)
    }

    suspend fun startContainer(name: String, logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val configPath = "${Constants.CONTAINERS_DIR}/$name/${Constants.CONFIG_FILE}"
            val result = Shell.cmd(
                "${Constants.DS_BINARY_PATH} --config='$configPath' start 2>&1"
            ).exec()
            val output = result.out.joinToString("\n")
            if (output.isNotBlank()) logger?.i(output)
            if (result.err.isNotEmpty()) {
                result.err.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) logger?.w(trimmed)
                }
            }
            result.isSuccess
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
            false
        }
    }

    suspend fun stopContainer(name: String, logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("${Constants.DS_BINARY_PATH} --name='$name' stop 2>&1").exec()
            val output = result.out.joinToString("\n")
            if (output.isNotBlank()) logger?.i(output)
            result.isSuccess
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
            false
        }
    }

    suspend fun restartContainer(name: String, logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("${Constants.DS_BINARY_PATH} --name='$name' restart 2>&1").exec()
            val output = result.out.joinToString("\n")
            if (output.isNotBlank()) logger?.i(output)
            result.isSuccess
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
            false
        }
    }
}
