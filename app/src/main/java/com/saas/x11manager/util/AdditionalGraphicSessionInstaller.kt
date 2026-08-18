package com.saas.x11manager.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AdditionalGraphicSessionInstaller {

    internal fun stepsFor(plan: GraphicSessionInstallPlan): List<GraphicSessionInstallStep> {
        val spec = GraphicSessionSupport.specFor(plan.session) ?: return emptyList()
        if (plan.session in setOf(GraphicSession.OPENBOX, GraphicSession.ICEWM, GraphicSession.JWM)) {
            return emptyList()
        }

        val packageSteps = when (plan.platform) {
            ContainerPlatform.ALPINE -> buildList {
                add(GraphicSessionInstallStep("Validating Alpine package manager", "command -v apk >/dev/null"))
                alpineRepositoryPreparationStep(plan)?.let(::add)
                add(GraphicSessionInstallStep("Refreshing package index", "apk update"))
                plan.packages.forEach { packageName ->
                    add(
                        GraphicSessionInstallStep(
                            "Checking $packageName availability",
                            "apk search -e $packageName >/dev/null"
                        )
                    )
                }
                plan.packages.forEach { packageName ->
                    add(GraphicSessionInstallStep("Installing $packageName", "apk add $packageName"))
                }
            }

            ContainerPlatform.UBUNTU -> buildList {
                add(
                    GraphicSessionInstallStep(
                        "Validating Debian package manager",
                        "command -v apt-get >/dev/null && command -v dpkg >/dev/null && command -v apt-cache >/dev/null"
                    )
                )
                add(
                    GraphicSessionInstallStep(
                        "Refreshing package index",
                        "DEBIAN_FRONTEND=noninteractive apt-get update"
                    )
                )
                aptRepositoryPreparationStep(plan)?.let(::add)
                plan.packages.forEach { packageName ->
                    add(
                        GraphicSessionInstallStep(
                            "Checking $packageName availability",
                            "apt-cache show $packageName >/dev/null 2>&1"
                        )
                    )
                }
                val recommendsFlag = if (plan.installRecommendedPackages) "" else " --no-install-recommends"
                plan.packages.forEach { packageName ->
                    add(
                        GraphicSessionInstallStep(
                            "Installing $packageName",
                            "DEBIAN_FRONTEND=noninteractive apt-get install -y$recommendsFlag $packageName"
                        )
                    )
                }
            }
        }

        return packageSteps +
            spec.postInstallCommands.map { GraphicSessionInstallStep(it.title, it.command) } +
            GraphicSessionInstallStep(
                "Validating ${plan.session.label} session command",
                "command -v ${plan.verificationCommand}"
            )
    }

    private fun alpineRepositoryPreparationStep(
        plan: GraphicSessionInstallPlan
    ): GraphicSessionInstallStep? {
        if (plan.repositoryRequirement != RepositoryRequirement.APK_COMMUNITY) return null

        val command =
            "if grep -Eq '^[[:space:]]*[^#[:space:]].*/community([[:space:]]|$)' " +
                "/etc/apk/repositories 2>/dev/null; then :; " +
                "elif command -v setup-apkrepos >/dev/null 2>&1; then " +
                "setup-apkrepos -c; " +
                "elif grep -Eq '^[[:space:]]*#[[:space:]]*[^#[:space:]].*/community([[:space:]]|$)' " +
                "/etc/apk/repositories 2>/dev/null; then " +
                "sed -i -E 's|^[[:space:]]*#[[:space:]]*([^[:space:]]*/community)[[:space:]]*$|\\1|' " +
                "/etc/apk/repositories; " +
                "else main_repo=\$(awk '/^[[:space:]]*#/ {next} /\\/main([[:space:]]*)$/ {print; exit}' " +
                "/etc/apk/repositories 2>/dev/null); " +
                "if [ -z \"\$main_repo\" ]; then " +
                "echo 'Could not derive Alpine community repository from the configured main repository.' >&2; " +
                "exit 1; fi; " +
                "printf '%s\\n' \"\${main_repo%/main}/community\" >> /etc/apk/repositories; fi"

        return GraphicSessionInstallStep("Preparing Alpine community repository", command)
    }

    private fun aptRepositoryPreparationStep(
        plan: GraphicSessionInstallPlan
    ): GraphicSessionInstallStep? {
        val probePackage = plan.packages.firstOrNull() ?: return null
        val component = when (plan.repositoryRequirement) {
            RepositoryRequirement.APT_UNIVERSE -> "universe"
            RepositoryRequirement.APT_MULTIVERSE -> "multiverse"
            RepositoryRequirement.APK_COMMUNITY -> return null
        }
        val fallbackDescription = when (plan.repositoryRequirement) {
            RepositoryRequirement.APT_MULTIVERSE -> "Multiverse/non-free"
            else -> "Universe or the distro repository containing the package"
        }
        val command =
            "if apt-cache show $probePackage >/dev/null 2>&1; then :; " +
                "elif grep -Eq '^ID=ubuntu$' /etc/os-release 2>/dev/null && " +
                "command -v add-apt-repository >/dev/null 2>&1; then " +
                "add-apt-repository -y $component && " +
                "DEBIAN_FRONTEND=noninteractive apt-get update && " +
                "apt-cache show $probePackage >/dev/null 2>&1; " +
                "else echo 'Required apt repository is unavailable for $probePackage. " +
                "Enable $fallbackDescription for this image.' >&2; exit 1; fi"
        return GraphicSessionInstallStep("Checking required apt repository", command)
    }

    internal fun verificationStepsFor(
        platform: ContainerPlatform,
        session: GraphicSession,
        initSystem: InitSystem
    ): List<GraphicSessionInstallStep> {
        val plan = GraphicSessionInstallPlans.forSelection(platform, session) ?: return emptyList()
        val spec = GraphicSessionSupport.specFor(session) ?: return emptyList()
        if (session in setOf(GraphicSession.OPENBOX, GraphicSession.ICEWM, GraphicSession.JWM)) {
            return emptyList()
        }

        val packageChecks = when (platform) {
            ContainerPlatform.ALPINE -> listOf(
                GraphicSessionInstallStep("Checking Alpine package manager", "command -v apk >/dev/null"),
                GraphicSessionInstallStep(
                    "Checking ${session.label} packages",
                    plan.packages.joinToString(" && ") { "apk info -e $it >/dev/null" }
                )
            )

            ContainerPlatform.UBUNTU -> listOf(
                GraphicSessionInstallStep(
                    "Checking Debian package manager",
                    "command -v apt-get >/dev/null && command -v dpkg >/dev/null"
                ),
                GraphicSessionInstallStep(
                    "Checking ${session.label} packages",
                    plan.packages.joinToString(" && ") { "dpkg -s $it >/dev/null 2>&1" }
                )
            )
        }

        val commonChecks = buildList {
            add(
                GraphicSessionInstallStep(
                    "Checking ${session.label} session command",
                    "command -v ${session.startCommand}"
                )
            )
            spec.verificationCommands.forEach {
                add(GraphicSessionInstallStep(it.title, it.command))
            }
            add(
                GraphicSessionInstallStep(
                    "Checking X11 session launcher",
                    "test -x /usr/local/bin/x11-session.sh && " +
                        "grep -Fqx 'exec ${session.startCommand}' /usr/local/bin/x11-session.sh"
                )
            )
        }

        val initChecks = when (initSystem) {
            InitSystem.OPENRC -> listOf(
                GraphicSessionInstallStep(
                    "Checking OpenRC X11 startup",
                    "test -x /sbin/openrc-run && " +
                        "test -x /etc/init.d/x11-setup && " +
                        "test -x /etc/init.d/x11-session && " +
                        "test -L /etc/runlevels/default/x11-setup && " +
                        "test -L /etc/runlevels/default/x11-session"
                )
            )

            InitSystem.SYSTEMD -> listOf(
                GraphicSessionInstallStep(
                    "Checking systemd X11 startup",
                    "command -v systemctl >/dev/null && command -v bash >/dev/null && " +
                        "test -f /etc/systemd/system/setup-x11-socket.service && " +
                        "test -f /etc/systemd/system/x11-session.service && " +
                        "test -L /etc/systemd/system/multi-user.target.wants/setup-x11-socket.service && " +
                        "test -L /etc/systemd/system/graphical.target.wants/x11-session.service"
                )
            )
        }

        return packageChecks + commonChecks + initChecks
    }

    suspend fun install(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        initSystem: InitSystem,
        cacheDir: File,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Installing Graphic Session: ${session.label} ---")
        logger?.i("")

        val lease = AdditionalGraphicSessionRuntime.ensureReady(containerName, logger, "installation")
            ?: return@withContext false

        try {
            val resolvedPlatform = AdditionalGraphicSessionRuntime.resolvePlatform(
                containerName,
                platform,
                logger
            ) ?: return@withContext false
            val plan = GraphicSessionInstallPlans.forSelection(resolvedPlatform, session)
            if (plan == null || GraphicSessionSupport.specFor(session) == null) {
                logger?.e("[-] FAIL")
                logger?.e("[-] ${session.label} installer is not enabled for ${resolvedPlatform.label}")
                return@withContext false
            }

            val installSteps = stepsFor(plan)
            if (installSteps.isEmpty()) {
                logger?.e("[-] FAIL")
                logger?.e("[-] Installer workflow is not implemented for this selection")
                return@withContext false
            }

            for (step in installSteps) {
                if (!AdditionalGraphicSessionRuntime.runStep(containerName, step, logger)) {
                    logger?.e("[-] ${session.label} installation aborted")
                    return@withContext false
                }
            }

            logger?.i("[+] Configuring ${initSystem.name.lowercase()} startup")
            logger?.i("")
            for (step in GraphicSessionInstaller.startupStepsFor(resolvedPlatform, initSystem, session)) {
                if (!AdditionalGraphicSessionRuntime.runStep(containerName, step, logger)) {
                    logger?.e("[-] ${session.label} startup configuration aborted")
                    return@withContext false
                }
            }

            if (!persistSelection(containerName, resolvedPlatform, initSystem, session, cacheDir, logger)) {
                return@withContext false
            }

            logger?.i("[+] ${session.label} setup completed")
            true
        } finally {
            AdditionalGraphicSessionRuntime.release(containerName, lease, logger)
        }
    }

    suspend fun verify(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        initSystem: InitSystem,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Verifying Graphic Session: ${session.label} ---")
        logger?.i("")

        val lease = AdditionalGraphicSessionRuntime.ensureReady(containerName, logger, "verification")
            ?: return@withContext false

        try {
            val resolvedPlatform = AdditionalGraphicSessionRuntime.resolvePlatform(
                containerName,
                platform,
                logger
            ) ?: return@withContext false
            val checks = verificationStepsFor(resolvedPlatform, session, initSystem)
            if (checks.isEmpty()) {
                logger?.e("[-] FAIL")
                logger?.e("[-] Verification is not enabled for ${session.label} on ${resolvedPlatform.label}")
                return@withContext false
            }

            for (step in checks) {
                if (!AdditionalGraphicSessionRuntime.runStep(containerName, step, logger)) {
                    logger?.e("[-] ${session.label} verification failed")
                    return@withContext false
                }
            }

            logger?.i("[+] ${session.label} verification completed")
            true
        } finally {
            AdditionalGraphicSessionRuntime.release(containerName, lease, logger)
        }
    }

    private suspend fun persistSelection(
        containerName: String,
        platform: ContainerPlatform,
        initSystem: InitSystem,
        session: GraphicSession,
        cacheDir: File,
        logger: ContainerLogger?
    ): Boolean {
        val writes = listOf(
            "Package Platform" to ContainerSettingsManager.setPlatform(containerName, platform, cacheDir),
            "Init System" to ContainerSettingsManager.setInitSystem(containerName, initSystem, cacheDir),
            "Graphic Session" to ContainerSettingsManager.setGraphicSession(containerName, session, cacheDir)
        )
        for ((label, success) in writes) {
            if (!success) {
                logger?.e("[-] FAIL")
                logger?.e("[-] Could not persist $label")
                return false
            }
        }
        logger?.i("[+] Saved package platform, init system and graphic session")
        logger?.i("")
        return true
    }
}
