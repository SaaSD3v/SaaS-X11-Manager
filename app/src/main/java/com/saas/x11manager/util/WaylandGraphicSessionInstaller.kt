package com.saas.x11manager.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs Wayland compositors that render through the Manager's integrated X11
 * host transport. Package/repository behavior is capability-driven; no distro
 * release number is pinned here.
 */
object WaylandGraphicSessionInstaller {

    suspend fun install(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        initSystem: InitSystem,
        cacheDir: File,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (session.protocol != GraphicProtocol.WAYLAND) return@withContext false
        val spec = WaylandGraphicSessionSupport.specFor(session) ?: return@withContext false

        logger?.i("--- Installing Wayland Session: ${session.label} ---")
        logger?.i("")
        val lease = AdditionalGraphicSessionRuntime.ensureReady(
            containerName,
            logger,
            "Wayland installation"
        ) ?: return@withContext false

        try {
            val resolvedPlatform = AdditionalGraphicSessionRuntime.resolvePlatform(
                containerName,
                platform,
                logger
            ) ?: return@withContext false
            val plan = GraphicSessionInstallPlans.forSelection(resolvedPlatform, session)
                ?: return@withContext fail(logger, "${session.label} has no install plan for ${resolvedPlatform.label}")

            if (!runStep(containerName, architectureStep(), logger)) return@withContext false

            for (step in packageSteps(plan)) {
                if (!runStep(containerName, step, logger)) {
                    logger?.e("[-] ${session.label} package installation aborted")
                    return@withContext false
                }
            }

            for (command in spec.postInstallCommands) {
                if (!runStep(
                        containerName,
                        GraphicSessionInstallStep(command.title, command.command),
                        logger
                    )
                ) {
                    logger?.e("[-] ${session.label} launcher provisioning aborted")
                    return@withContext false
                }
            }

            for (command in spec.verificationCommands) {
                if (!runStep(
                        containerName,
                        GraphicSessionInstallStep(command.title, command.command),
                        logger
                    )
                ) {
                    logger?.e("[-] ${session.label} verification aborted")
                    return@withContext false
                }
            }

            logger?.i("[+] Configuring ${initSystem.name.lowercase()} startup")
            logger?.i("[CTX] Protocol: Wayland")
            logger?.i("[CTX] Host transport: Integrated X11")
            logger?.i("")
            for (step in GraphicSessionInstaller.startupStepsFor(resolvedPlatform, initSystem, session)) {
                if (!runStep(containerName, step, logger)) {
                    logger?.e("[-] ${session.label} startup configuration aborted")
                    return@withContext false
                }
            }

            if (!ContainerSettingsManager.setProfile(
                    containerName = containerName,
                    platform = resolvedPlatform,
                    initSystem = initSystem,
                    graphicSession = session,
                    cacheDir = cacheDir
                )
            ) {
                return@withContext fail(logger, "Could not persist Wayland session selection")
            }

            logger?.i("[+] Saved package platform, init system and Wayland session atomically")
            logger?.i("[+] ${session.label} Wayland setup completed")
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
        if (session.protocol != GraphicProtocol.WAYLAND) return@withContext false
        val spec = WaylandGraphicSessionSupport.specFor(session) ?: return@withContext false

        logger?.i("--- Verifying Wayland Session: ${session.label} ---")
        logger?.i("")
        val lease = AdditionalGraphicSessionRuntime.ensureReady(
            containerName,
            logger,
            "Wayland verification"
        ) ?: return@withContext false

        try {
            val resolvedPlatform = AdditionalGraphicSessionRuntime.resolvePlatform(
                containerName,
                platform,
                logger
            ) ?: return@withContext false
            val plan = GraphicSessionInstallPlans.forSelection(resolvedPlatform, session)
                ?: return@withContext false

            val checks = buildList {
                add(architectureStep())
                add(packageVerificationStep(plan))
                spec.verificationCommands.forEach {
                    add(GraphicSessionInstallStep(it.title, it.command))
                }
                add(startupVerificationStep(initSystem))
            }

            for (step in checks) {
                if (!runStep(containerName, step, logger)) {
                    logger?.e("[-] ${session.label} Wayland verification failed")
                    return@withContext false
                }
            }
            logger?.i("[+] ${session.label} Wayland verification completed")
            true
        } finally {
            AdditionalGraphicSessionRuntime.release(containerName, lease, logger)
        }
    }

    internal fun packageSteps(plan: GraphicSessionInstallPlan): List<GraphicSessionInstallStep> =
        when (plan.platform) {
            ContainerPlatform.ALPINE -> buildList {
                add(
                    GraphicSessionInstallStep(
                        "Validating Alpine package manager",
                        "command -v apk >/dev/null"
                    )
                )
                alpineRepositoryPreparationStep(plan)?.let(::add)
                add(GraphicSessionInstallStep("Refreshing package index", "apk update"))
                ApkTransactionSafety.stepFor(plan)?.let(::add)
                add(
                    GraphicSessionInstallStep(
                        "Checking ${plan.session.label} package availability",
                        plan.installPackageArguments().joinToString(" && ") { pkg ->
                            val base = pkg.substringBefore('@')
                            "apk search -e '$base' >/dev/null"
                        }
                    )
                )
                add(
                    GraphicSessionInstallStep(
                        "Installing ${plan.session.label} packages",
                        "apk add ${plan.installPackageArguments().joinToString(" ")}"
                    )
                )
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
                add(aptAvailabilityStep(plan))
                AptTransactionSafety.stepFor(plan)?.let(::add)
                val recommendsFlag = if (plan.installRecommendedPackages) {
                    "--install-recommends"
                } else {
                    "--no-install-recommends"
                }
                add(
                    GraphicSessionInstallStep(
                        "Installing ${plan.session.label} packages",
                        "DEBIAN_FRONTEND=noninteractive apt-get install -y $recommendsFlag " +
                            plan.packages.joinToString(" ")
                    )
                )
            }
        }

    private fun architectureStep() = GraphicSessionInstallStep(
        "Checking Wayland architecture support",
        "case \"\$(uname -m 2>/dev/null)\" in aarch64|arm64) exit 0 ;; " +
            "*) echo 'This Wayland catalog is currently validated for arm64/aarch64 only.' >&2; exit 1 ;; esac"
    )

    private fun packageVerificationStep(plan: GraphicSessionInstallPlan): GraphicSessionInstallStep =
        when (plan.platform) {
            ContainerPlatform.ALPINE -> GraphicSessionInstallStep(
                "Checking installed ${plan.session.label} packages",
                plan.packages.joinToString(" && ") { pkg ->
                    "apk info -e '${pkg.substringBefore('@')}' >/dev/null"
                }
            )

            ContainerPlatform.UBUNTU -> GraphicSessionInstallStep(
                "Checking installed ${plan.session.label} packages",
                plan.packages.joinToString(" && ") { pkg -> "dpkg -s '$pkg' >/dev/null 2>&1" }
            )
        }

    private fun startupVerificationStep(initSystem: InitSystem): GraphicSessionInstallStep =
        when (initSystem) {
            InitSystem.OPENRC -> GraphicSessionInstallStep(
                "Checking OpenRC Wayland startup",
                "test -x /usr/local/bin/x11-session.sh && " +
                    "test -x /etc/init.d/x11-setup && test -x /etc/init.d/x11-session && " +
                    "test -L /etc/runlevels/default/x11-setup && test -L /etc/runlevels/default/x11-session && " +
                    "grep -Fq 'XDG_SESSION_TYPE=wayland' /usr/local/bin/x11-session.sh"
            )

            InitSystem.SYSTEMD -> GraphicSessionInstallStep(
                "Checking systemd Wayland startup",
                "test -x /usr/local/bin/x11-session.sh && " +
                    "test -f /etc/systemd/system/setup-x11-socket.service && " +
                    "test -f /etc/systemd/system/x11-session.service && " +
                    "grep -Fq 'XDG_SESSION_TYPE=wayland' /usr/local/bin/x11-session.sh"
            )
        }

    private fun aptAvailabilityStep(plan: GraphicSessionInstallPlan): GraphicSessionInstallStep {
        val availability = AptPackageAvailability.shellFunctionDefinition()
        val packages = plan.packages.joinToString(" ")
        val command = availability + " " +
            "all_available() { for pkg in $packages; do apt_package_available \"\$pkg\" || return 1; done; }; " +
            "if all_available; then exit 0; fi; " +
            "if grep -Eq '^ID=ubuntu$' /etc/os-release 2>/dev/null; then " +
            "if ! command -v add-apt-repository >/dev/null 2>&1; then " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends software-properties-common; fi; " +
            "if command -v add-apt-repository >/dev/null 2>&1; then " +
            "if add-apt-repository --help 2>&1 | grep -q -- '--component'; then " +
            "add-apt-repository -y -c universe; else add-apt-repository -y universe; fi; " +
            "DEBIAN_FRONTEND=noninteractive apt-get update; fi; fi; " +
            "all_available || { echo 'Required Wayland packages are unavailable for this distro/architecture.' >&2; exit 1; }"
        return GraphicSessionInstallStep(
            "Checking ${plan.session.label} package availability",
            command
        )
    }

    private fun alpineRepositoryPreparationStep(
        plan: GraphicSessionInstallPlan
    ): GraphicSessionInstallStep? = when (plan.repositoryRequirement) {
        RepositoryRequirement.APK_COMMUNITY -> GraphicSessionInstallStep(
            "Preparing Alpine community repository",
            "if grep -Eq '^[[:space:]]*[^#[:space:]].*/community([[:space:]]|$)' /etc/apk/repositories 2>/dev/null; then :; " +
                "elif command -v setup-apkrepos >/dev/null 2>&1; then setup-apkrepos -c; " +
                "elif grep -Eq '^[[:space:]]*#[[:space:]]*[^#[:space:]].*/community([[:space:]]|$)' /etc/apk/repositories 2>/dev/null; then " +
                "sed -i -E 's|^[[:space:]]*#[[:space:]]*([^[:space:]]*/community)[[:space:]]*$|\\1|' /etc/apk/repositories; " +
                "else main_repo=\$(awk '/^[[:space:]]*#/ {next} /\\/main([[:space:]]*)$/ {print; exit}' /etc/apk/repositories 2>/dev/null); " +
                "[ -n \"\$main_repo\" ] || { echo 'Could not derive Alpine community repository.' >&2; exit 1; }; " +
                "printf '%s\\n' \"\${main_repo%/main}/community\" >> /etc/apk/repositories; fi"
        )

        RepositoryRequirement.APK_EDGE_TESTING -> GraphicSessionInstallStep(
            "Preparing Alpine edge/testing repository",
            "repo_file=/etc/apk/repositories; " +
                "if grep -Eq '^[[:space:]]*@$APK_EDGE_TESTING_TAG[[:space:]]+[^#[:space:]]+/edge/testing/?[[:space:]]*$' \"\$repo_file\" 2>/dev/null; then :; else " +
                "remote_repo=\$(grep -E '^[[:space:]]*(@[^[:space:]]+[[:space:]]+)?https?://[^[:space:]]+/(main|community)/?[[:space:]]*$' \"\$repo_file\" 2>/dev/null | head -n 1 | sed -E 's/^[[:space:]]*(@[^[:space:]]+[[:space:]]+)?//'); " +
                "[ -n \"\$remote_repo\" ] || { echo 'Could not derive Alpine edge/testing mirror.' >&2; exit 1; }; " +
                "base_repo=\$(printf '%s\\n' \"\$remote_repo\" | sed -E 's#/[^/]+/(main|community)/?$##'); " +
                "[ -n \"\$base_repo\" ] || { echo 'Could not derive Alpine repository base.' >&2; exit 1; }; " +
                "printf '%s\\n' '@$APK_EDGE_TESTING_TAG '\"\${base_repo%/}/edge/testing\" >> \"\$repo_file\"; fi"
        )

        RepositoryRequirement.APT_UNIVERSE,
        RepositoryRequirement.APT_MULTIVERSE -> null
    }

    private suspend fun runStep(
        containerName: String,
        step: GraphicSessionInstallStep,
        logger: ContainerLogger?
    ): Boolean {
        // Unlike the legacy X11 installer, Wayland deliberately keeps package
        // availability checks: distro/architecture package coverage is part of
        // the feature contract and should fail before a misleading install result.
        return AdditionalGraphicSessionRuntime.runStep(containerName, step, logger)
    }

    private fun fail(logger: ContainerLogger?, message: String): Boolean {
        logger?.e("[-] FAIL")
        logger?.e("[-] $message")
        return false
    }
}
