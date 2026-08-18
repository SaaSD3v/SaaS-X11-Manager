package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionInstallerTest {

    private fun openboxSteps(platform: ContainerPlatform): List<GraphicSessionInstallStep> {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            platform,
            GraphicSession.OPENBOX
        ))
        return GraphicSessionInstaller.stepsFor(plan)
    }

    private fun icewmSteps(platform: ContainerPlatform): List<GraphicSessionInstallStep> {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            platform,
            GraphicSession.ICEWM
        ))
        return GraphicSessionInstaller.stepsFor(plan)
    }

    private fun jwmSteps(platform: ContainerPlatform): List<GraphicSessionInstallStep> {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            platform,
            GraphicSession.JWM
        ))
        return GraphicSessionInstaller.stepsFor(plan)
    }

    @Test
    fun alpineOpenboxWorkflowPreflightsCommunityAndInstallsPlanAtomically() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.OPENBOX)
        )
        val commands = openboxSteps(ContainerPlatform.ALPINE).map { it.command }

        assertEquals("command -v apk >/dev/null", commands.first())
        assertTrue(commands.any { it.contains("setup-apkrepos -c") })
        assertTrue("apk update" in commands)
        assertTrue(commands.any {
            it.contains("apk --simulate add ${plan.packages.joinToString(" ")}")
        })
        assertFalse(commands.any { it.startsWith("apk search -e ") })
        assertEquals(1, commands.count { it.startsWith("apk add ") })
        assertTrue("apk add ${plan.packages.joinToString(" ")}" in commands)
        assertFalse(commands.any { it.contains("apt-get") })
    }

    @Test
    fun debOpenboxWorkflowPreflightsUniverseAndSuppressesRecommendationsAtomically() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.OPENBOX)
        )
        val commands = openboxSteps(ContainerPlatform.UBUNTU).map { it.command }

        assertEquals(
            "command -v apt-get >/dev/null && command -v dpkg >/dev/null && command -v apt-cache >/dev/null",
            commands.first()
        )
        assertTrue(commands.contains("DEBIAN_FRONTEND=noninteractive apt-get update"))
        assertTrue(commands.any { it.contains("all_packages_available") && it.contains("add-apt-repository -y universe") })
        assertTrue(commands.any {
            it.contains("apt-get -s --no-install-recommends install ${plan.packages.joinToString(" ")}")
        })
        assertFalse(commands.any { it.startsWith("LC_ALL=C apt-cache policy ") })
        assertEquals(1, commands.count { it.contains("apt-get install -y") })
        assertTrue(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${plan.packages.joinToString(" ")}" in commands
        )
        assertFalse(commands.any { it.contains("--install-recommends") })
        assertFalse(commands.any { it.contains("apk ") })
    }

    @Test
    fun alpineIcewmWorkflowPreflightsCommunityAndInstallsPlanAtomically() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.ICEWM)
        )
        val commands = icewmSteps(ContainerPlatform.ALPINE).map { it.command }

        assertEquals("command -v apk >/dev/null", commands.first())
        assertTrue(commands.any { it.contains("setup-apkrepos -c") })
        assertTrue("apk update" in commands)
        assertTrue(commands.any {
            it.contains("apk --simulate add ${plan.packages.joinToString(" ")}")
        })
        assertFalse(commands.any { it.startsWith("apk search -e ") })
        assertEquals(1, commands.count { it.startsWith("apk add ") })
        assertTrue("apk add ${plan.packages.joinToString(" ")}" in commands)
        assertTrue("command -v icewm-session" in commands)
        assertFalse(commands.any { it.contains("apt-get") })
    }

    @Test
    fun debIcewmWorkflowPreflightsUniverseAndSuppressesRecommendationsAtomically() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.ICEWM)
        )
        val commands = icewmSteps(ContainerPlatform.UBUNTU).map { it.command }

        assertEquals(
            "command -v apt-get >/dev/null && command -v dpkg >/dev/null && command -v apt-cache >/dev/null",
            commands.first()
        )
        assertTrue(commands.contains("DEBIAN_FRONTEND=noninteractive apt-get update"))
        assertTrue(commands.any { it.contains("all_packages_available") && it.contains("add-apt-repository -y universe") })
        assertTrue(commands.any {
            it.contains("apt-get -s --no-install-recommends install ${plan.packages.joinToString(" ")}")
        })
        assertFalse(commands.any { it.startsWith("LC_ALL=C apt-cache policy ") })
        assertEquals(1, commands.count { it.contains("apt-get install -y") })
        assertTrue(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${plan.packages.joinToString(" ")}" in commands
        )
        assertFalse(commands.any { it.contains("--install-recommends") })
        assertTrue("command -v icewm-session" in commands)
        assertFalse(commands.any { it.contains("apk ") })
    }

    @Test
    fun alpineJwmWorkflowPreflightsCommunityAndValidatesConfiguration() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.JWM)
        )
        val commands = jwmSteps(ContainerPlatform.ALPINE).map { it.command }

        assertEquals("command -v apk >/dev/null", commands.first())
        assertTrue(commands.any { it.contains("setup-apkrepos -c") })
        assertTrue("apk update" in commands)
        assertTrue(commands.any {
            it.contains("apk --simulate add ${plan.packages.joinToString(" ")}")
        })
        assertFalse(commands.any { it.startsWith("apk search -e ") })
        assertEquals(1, commands.count { it.startsWith("apk add ") })
        assertTrue("apk add ${plan.packages.joinToString(" ")}" in commands)
        assertTrue("command -v jwm" in commands)
        assertTrue("jwm -p" in commands)
        assertFalse(commands.any { it.contains("apt-get") })
    }

    @Test
    fun debJwmWorkflowPreflightsUniverseAndSuppressesRecommendationsAtomically() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.JWM)
        )
        val commands = jwmSteps(ContainerPlatform.UBUNTU).map { it.command }

        assertEquals(
            "command -v apt-get >/dev/null && command -v dpkg >/dev/null && command -v apt-cache >/dev/null",
            commands.first()
        )
        assertTrue(commands.contains("DEBIAN_FRONTEND=noninteractive apt-get update"))
        assertTrue(commands.any { it.contains("all_packages_available") && it.contains("add-apt-repository -y universe") })
        assertTrue(commands.any {
            it.contains("apt-get -s --no-install-recommends install ${plan.packages.joinToString(" ")}")
        })
        assertFalse(commands.any { it.startsWith("LC_ALL=C apt-cache policy ") })
        assertEquals(1, commands.count { it.contains("apt-get install -y") })
        assertTrue(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${plan.packages.joinToString(" ")}" in commands
        )
        assertFalse(commands.any { it.contains("--install-recommends") })
        assertTrue("command -v jwm" in commands)
        assertTrue("jwm -p" in commands)
        assertFalse(commands.any { it.contains("apk ") })
    }

    @Test
    fun openboxConfigCopyNeverOverwritesExistingUserFiles() {
        ContainerPlatform.entries.forEach { platform ->
            val commands = openboxSteps(platform).map { it.command }

            assertTrue(commands.any {
                it.contains("[ -f /root/.config/openbox/rc.xml ] ||") &&
                    it.contains("cp /etc/xdg/openbox/rc.xml")
            })
            assertTrue(commands.any {
                it.contains("[ -f /root/.config/openbox/menu.xml ] ||") &&
                    it.contains("cp /etc/xdg/openbox/menu.xml")
            })
        }
    }

    @Test
    fun packageInstallersValidateButNeverLaunchOpenbox() {
        ContainerPlatform.entries.forEach { platform ->
            val commands = openboxSteps(platform).map { it.command }

            assertTrue(commands.contains("command -v openbox-session"))
            assertFalse(commands.any { it.trim() == "openbox-session" })
            assertFalse(commands.any { it.contains("exec openbox-session") })
        }
    }

    @Test
    fun icewmInstallerValidatesButNeverLaunchesSession() {
        ContainerPlatform.entries.forEach { platform ->
            val commands = icewmSteps(platform).map { it.command }

            assertTrue(commands.contains("command -v icewm-session"))
            assertFalse(commands.any { it.trim() == "icewm-session" })
            assertFalse(commands.any { it.contains("exec icewm-session") })
            assertFalse(commands.any { it.contains("mkdir ") || it.contains("cp ") })
        }
    }

    @Test
    fun jwmInstallerValidatesButNeverLaunchesSession() {
        ContainerPlatform.entries.forEach { platform ->
            val commands = jwmSteps(platform).map { it.command }

            assertTrue(commands.contains("command -v jwm"))
            assertTrue(commands.contains("jwm -p"))
            assertFalse(commands.any { it.trim() == "jwm" })
            assertFalse(commands.any { it.contains("exec jwm") })
            assertFalse(commands.any { it.contains("mkdir ") || it.contains("cp ") })
        }
    }

    @Test
    fun openrcStartupWritesGenericOpenboxSessionFiles() {
        ContainerPlatform.entries.forEach { platform ->
            val steps = GraphicSessionInstaller.startupStepsFor(
                platform,
                InitSystem.OPENRC,
                GraphicSession.OPENBOX
            )
            val joined = steps.joinToString("\n") { it.command }

            assertEquals("test -x /sbin/openrc-run", steps.first().command)
            assertTrue(joined.contains("/etc/init.d/x11-session"))
            assertTrue(joined.contains("/etc/runlevels/default/x11-session"))
            assertTrue(joined.contains("exec openbox-session"))
            assertTrue(joined.contains("/run/x11-session.pid"))
            assertFalse(steps.any { it.command.trim() == "openbox-session" })
        }
    }

    @Test
    fun existingStartupTemplatesAcceptIcewmWithoutSpecialInitPath() {
        ContainerPlatform.entries.forEach { platform ->
            InitSystem.entries.forEach { initSystem ->
                val steps = GraphicSessionInstaller.startupStepsFor(
                    platform,
                    initSystem,
                    GraphicSession.ICEWM
                )
                val joined = steps.joinToString("\n") { it.command }

                assertTrue(joined.contains("exec icewm-session"))
                assertTrue(joined.contains("/usr/local/bin/x11-session.sh"))
                assertFalse(steps.any { it.command.trim() == "icewm-session" })
            }
        }
    }

    @Test
    fun existingStartupTemplatesAcceptJwmWithoutSpecialInitPath() {
        ContainerPlatform.entries.forEach { platform ->
            InitSystem.entries.forEach { initSystem ->
                val steps = GraphicSessionInstaller.startupStepsFor(
                    platform,
                    initSystem,
                    GraphicSession.JWM
                )
                val joined = steps.joinToString("\n") { it.command }

                assertTrue(joined.contains("exec jwm"))
                assertTrue(joined.contains("/usr/local/bin/x11-session.sh"))
                assertFalse(steps.any { it.command.trim() == "jwm" })
            }
        }
    }

    @Test
    fun alpineSystemdStartupInstallsBashWithApk() {
        val commands = GraphicSessionInstaller.startupStepsFor(
            ContainerPlatform.ALPINE,
            InitSystem.SYSTEMD,
            GraphicSession.OPENBOX
        ).map { it.command }
        val joined = commands.joinToString("\n")

        assertTrue(commands.contains("command -v systemctl >/dev/null"))
        assertTrue(commands.contains("apk add bash"))
        assertTrue(joined.contains("/etc/systemd/system/x11-session.service"))
        assertTrue(joined.contains("exec openbox-session"))
    }

    @Test
    fun debSystemdStartupValidatesBashWithoutApk() {
        val commands = GraphicSessionInstaller.startupStepsFor(
            ContainerPlatform.UBUNTU,
            InitSystem.SYSTEMD,
            GraphicSession.OPENBOX
        ).map { it.command }
        val joined = commands.joinToString("\n")

        assertTrue(commands.contains("command -v systemctl >/dev/null"))
        assertTrue(commands.contains("command -v bash >/dev/null"))
        assertFalse(commands.any { it.contains("apk add bash") })
        assertTrue(joined.contains("/etc/systemd/system/x11-session.service"))
        assertTrue(joined.contains("exec openbox-session"))
    }

    @Test
    fun startupMigrationRemovesLegacyXfceServiceNames() {
        ContainerPlatform.entries.forEach { platform ->
            val openrc = GraphicSessionInstaller.startupStepsFor(
                platform,
                InitSystem.OPENRC,
                GraphicSession.OPENBOX
            ).joinToString("\n") { it.command }
            val systemd = GraphicSessionInstaller.startupStepsFor(
                platform,
                InitSystem.SYSTEMD,
                GraphicSession.OPENBOX
            ).joinToString("\n") { it.command }

            assertTrue(openrc.contains("rm -f") && openrc.contains("x11-xfce"))
            assertTrue(systemd.contains("rm -f") && systemd.contains("x11-xfce"))
        }
    }

    @Test
    fun openboxVerificationIsReadOnlyOnBothPackageFamilies() {
        ContainerPlatform.entries.forEach { platform ->
            InitSystem.entries.forEach { initSystem ->
                val commands = GraphicSessionInstaller.verificationStepsFor(
                    platform,
                    GraphicSession.OPENBOX,
                    initSystem
                ).map { it.command }
                val joined = commands.joinToString("\n")

                assertFalse(joined.contains("apk update"))
                assertFalse(joined.contains("apk add "))
                assertFalse(joined.contains("apt-get update"))
                assertFalse(joined.contains("apt-get install"))
                assertFalse(joined.contains("rm -f"))
                assertFalse(joined.contains("ln -s"))
                assertFalse(joined.contains("chmod "))
                assertFalse(joined.contains("cp "))
                assertFalse(joined.contains("mkdir "))
            }
        }
    }

    @Test
    fun icewmVerificationIsReadOnlyOnBothPackageFamilies() {
        ContainerPlatform.entries.forEach { platform ->
            InitSystem.entries.forEach { initSystem ->
                val commands = GraphicSessionInstaller.verificationStepsFor(
                    platform,
                    GraphicSession.ICEWM,
                    initSystem
                ).map { it.command }
                val joined = commands.joinToString("\n")

                assertFalse(joined.contains("apk update"))
                assertFalse(joined.contains("apk add "))
                assertFalse(joined.contains("apt-get update"))
                assertFalse(joined.contains("apt-get install"))
                assertFalse(joined.contains("rm -f"))
                assertFalse(joined.contains("ln -s"))
                assertFalse(joined.contains("chmod "))
                assertFalse(joined.contains("cp "))
                assertFalse(joined.contains("mkdir "))
            }
        }
    }

    @Test
    fun jwmVerificationIsReadOnlyOnBothPackageFamilies() {
        ContainerPlatform.entries.forEach { platform ->
            InitSystem.entries.forEach { initSystem ->
                val commands = GraphicSessionInstaller.verificationStepsFor(
                    platform,
                    GraphicSession.JWM,
                    initSystem
                ).map { it.command }
                val joined = commands.joinToString("\n")

                assertFalse(joined.contains("apk update"))
                assertFalse(joined.contains("apk add "))
                assertFalse(joined.contains("apt-get update"))
                assertFalse(joined.contains("apt-get install"))
                assertFalse(joined.contains("rm -f"))
                assertFalse(joined.contains("ln -s"))
                assertFalse(joined.contains("chmod "))
                assertFalse(joined.contains("cp "))
                assertFalse(joined.contains("mkdir "))
            }
        }
    }

    @Test
    fun verificationUsesPlatformSpecificPackageQueries() {
        val alpine = GraphicSessionInstaller.verificationStepsFor(
            ContainerPlatform.ALPINE,
            GraphicSession.OPENBOX,
            InitSystem.OPENRC
        ).joinToString("\n") { it.command }
        val deb = GraphicSessionInstaller.verificationStepsFor(
            ContainerPlatform.UBUNTU,
            GraphicSession.OPENBOX,
            InitSystem.SYSTEMD
        ).joinToString("\n") { it.command }

        assertTrue(alpine.contains("apk info -e openbox"))
        assertTrue(alpine.contains("apk info -e font-terminus"))
        assertFalse(alpine.contains("dpkg -s"))

        assertTrue(deb.contains("dpkg -s openbox"))
        assertTrue(deb.contains("dpkg -s xterm"))
        assertTrue(deb.contains("dpkg -s fonts-terminus"))
        assertFalse(deb.contains("apk info"))
        assertTrue(deb.contains("command -v openbox-session"))
        assertTrue(deb.contains("grep -Fqx 'exec openbox-session' /usr/local/bin/x11-session.sh"))
    }

    @Test
    fun icewmVerificationUsesPlatformSpecificPackageQueries() {
        val alpine = GraphicSessionInstaller.verificationStepsFor(
            ContainerPlatform.ALPINE,
            GraphicSession.ICEWM,
            InitSystem.OPENRC
        ).joinToString("\n") { it.command }
        val deb = GraphicSessionInstaller.verificationStepsFor(
            ContainerPlatform.UBUNTU,
            GraphicSession.ICEWM,
            InitSystem.SYSTEMD
        ).joinToString("\n") { it.command }

        assertTrue(alpine.contains("apk info -e icewm"))
        assertTrue(alpine.contains("apk info -e xterm"))
        assertFalse(alpine.contains("dpkg -s"))

        assertTrue(deb.contains("dpkg -s icewm"))
        assertTrue(deb.contains("dpkg -s xterm"))
        assertFalse(deb.contains("apk info"))
        assertTrue(deb.contains("command -v icewm-session"))
        assertTrue(deb.contains("grep -Fqx 'exec icewm-session' /usr/local/bin/x11-session.sh"))
    }

    @Test
    fun jwmVerificationUsesPlatformSpecificPackageQueries() {
        val alpine = GraphicSessionInstaller.verificationStepsFor(
            ContainerPlatform.ALPINE,
            GraphicSession.JWM,
            InitSystem.OPENRC
        ).joinToString("\n") { it.command }
        val deb = GraphicSessionInstaller.verificationStepsFor(
            ContainerPlatform.UBUNTU,
            GraphicSession.JWM,
            InitSystem.SYSTEMD
        ).joinToString("\n") { it.command }

        assertTrue(alpine.contains("apk info -e jwm"))
        assertTrue(alpine.contains("apk info -e xterm"))
        assertFalse(alpine.contains("dpkg -s"))

        assertTrue(deb.contains("dpkg -s jwm"))
        assertTrue(deb.contains("dpkg -s xterm"))
        assertFalse(deb.contains("apk info"))
        assertTrue(deb.contains("command -v jwm"))
        assertTrue(deb.contains("jwm -p"))
        assertTrue(deb.contains("grep -Fqx 'exec jwm' /usr/local/bin/x11-session.sh"))
    }
}
