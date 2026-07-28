package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ContainerStatus { RUNNING, STOPPED, UNKNOWN }

data class ContainerInfo(
    val name: String,
    val rootfsPath: String,
    val configPath: String,
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
            val result = Shell.cmd("ls -1 '${Constants.CONTAINERS_DIR}' 2>/dev/null").exec()
            if (!result.isSuccess || result.out.isEmpty()) {
                log?.invoke("No containers found")
                return@withContext containers
            }

            for (name in result.out) {
                if (name.isBlank()) continue
                val dir = "${Constants.CONTAINERS_DIR}/$name"
                val configPath = "$dir/${Constants.CONFIG_FILE}"

                if (!Shell.cmd("test -f '$configPath'").exec().isSuccess) continue

                val rootfsResult = Shell.cmd("grep '^rootfs_path=' '$configPath' 2>/dev/null | cut -d= -f2").exec()
                val rootfsPath = rootfsResult.out.firstOrNull()?.trim() ?: continue
                if (rootfsPath.isBlank() || !Shell.cmd("test -f '$rootfsPath'").exec().isSuccess) continue

                log?.invoke("Found: $name")

                val (isRunning, pid) = checkContainerStatus(name)

                containers.add(
                    ContainerInfo(
                        name = name,
                        rootfsPath = rootfsPath,
                        configPath = configPath,
                        status = if (isRunning) ContainerStatus.RUNNING else ContainerStatus.STOPPED,
                        pid = pid
                    )
                )
            }
        } catch (e: Exception) {
            log?.invoke("Error: ${e.message}")
        }
        log?.invoke("${containers.size} container(s) found")
        containers
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
        logger?.i("Starting container $name...")
        try {
            val result = Shell.cmd("${Constants.DS_BINARY_PATH} --name='$name' start 2>&1").exec()
            val output = result.out.joinToString("\n")
            if (output.isNotBlank()) logger?.i(output)
            result.isSuccess
        } catch (e: Exception) {
            logger?.e("Error starting: ${e.message}")
            false
        }
    }

    suspend fun stopContainer(name: String, logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        logger?.i("Stopping container $name...")
        try {
            val result = Shell.cmd("${Constants.DS_BINARY_PATH} --name='$name' stop 2>&1").exec()
            val output = result.out.joinToString("\n")
            if (output.isNotBlank()) logger?.i(output)
            result.isSuccess
        } catch (e: Exception) {
            logger?.e("Error stopping: ${e.message}")
            false
        }
    }

    suspend fun restartContainer(name: String, logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        logger?.i("Restarting container $name...")
        try {
            val result = Shell.cmd("${Constants.DS_BINARY_PATH} --name='$name' restart 2>&1").exec()
            val output = result.out.joinToString("\n")
            if (output.isNotBlank()) logger?.i(output)
            result.isSuccess
        } catch (e: Exception) {
            logger?.e("Error restarting: ${e.message}")
            false
        }
    }
}
