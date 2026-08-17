package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContainerDistributionParserTest {

    @Test
    fun parsesAlpine() {
        val distro = ContainerDistributionParser.parse(
            listOf(
                "NAME=\"Alpine Linux\"",
                "ID=alpine"
            )
        )

        assertEquals(ContainerDistribution.ALPINE, distro)
        assertEquals(ContainerPlatform.ALPINE, distro.suggestedPlatform)
    }

    @Test
    fun parsesUbuntu() {
        val distro = ContainerDistributionParser.parse(
            listOf(
                "ID=ubuntu",
                "ID_LIKE=debian"
            )
        )

        assertEquals(ContainerDistribution.UBUNTU, distro)
        assertEquals(ContainerPlatform.UBUNTU, distro.suggestedPlatform)
    }

    @Test
    fun parsesDebianIntoUbuntuAptProfile() {
        val distro = ContainerDistributionParser.parse(
            listOf("ID=debian")
        )

        assertEquals(ContainerDistribution.DEBIAN, distro)
        assertEquals(ContainerPlatform.UBUNTU, distro.suggestedPlatform)
    }

    @Test
    fun exactIdWinsOverIdLike() {
        val distro = ContainerDistributionParser.parse(
            listOf(
                "ID=ubuntu",
                "ID_LIKE=\"alpine debian\""
            )
        )

        assertEquals(ContainerDistribution.UBUNTU, distro)
    }

    @Test
    fun fallsBackToQuotedIdLikeForDerivative() {
        val distro = ContainerDistributionParser.parse(
            listOf(
                "ID=custom-linux",
                "ID_LIKE='debian'"
            )
        )

        assertEquals(ContainerDistribution.DEBIAN, distro)
        assertEquals(ContainerPlatform.UBUNTU, distro.suggestedPlatform)
    }

    @Test
    fun alpineLikeDerivativeSuggestsAlpineProfile() {
        val distro = ContainerDistributionParser.parse(
            listOf(
                "ID=custom",
                "ID_LIKE=\"alpine\""
            )
        )

        assertEquals(ContainerDistribution.ALPINE, distro)
        assertEquals(ContainerPlatform.ALPINE, distro.suggestedPlatform)
    }

    @Test
    fun unknownDistributionStaysUnknown() {
        val distro = ContainerDistributionParser.parse(
            listOf(
                "ID=arch",
                "ID_LIKE=archlinux"
            )
        )

        assertEquals(ContainerDistribution.UNKNOWN, distro)
        assertNull(distro.suggestedPlatform)
    }

    @Test
    fun malformedAndCommentLinesAreIgnored() {
        val distro = ContainerDistributionParser.parse(
            listOf(
                "# ID=alpine",
                "garbage",
                "ID=debian"
            )
        )

        assertEquals(ContainerDistribution.DEBIAN, distro)
    }
}
