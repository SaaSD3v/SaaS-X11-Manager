package com.saas.x11manager.util

/**
 * Simulates an APT install before changing the container and rejects package
 * transactions that would provision infrastructure owned by the host-side X11
 * model or remove existing packages unexpectedly.
 */
internal object AptTransactionSafety {

    private const val BLOCKED_PACKAGE_PATTERN =
        "^(xorg|xserver-xorg($|-.*)|gdm3|lightdm|sddm|lxdm|xdm|slim|nodm|pulseaudio|pipewire-pulse|pipewire-audio)$"

    fun stepFor(plan: GraphicSessionInstallPlan): GraphicSessionInstallStep? {
        if (plan.platform != ContainerPlatform.UBUNTU || plan.packages.isEmpty()) return null

        val recommendsFlag = if (plan.installRecommendedPackages) {
            "--install-recommends"
        } else {
            "--no-install-recommends"
        }
        val packageList = plan.packages.joinToString(" ")
        val command =
            "simulation=\$(mktemp) || exit 1; " +
                "cleanup() { rm -f \"\$simulation\"; }; " +
                "trap cleanup EXIT HUP INT TERM; " +
                "LC_ALL=C DEBIAN_FRONTEND=noninteractive apt-get -s $recommendsFlag install $packageList " +
                ">\"\$simulation\" 2>&1 || { cat \"\$simulation\" >&2; " +
                "echo 'APT transaction simulation failed.' >&2; exit 1; }; " +
                "removed=\$(awk '\$1 == \"Remv\" { print \$2 }' \"\$simulation\"); " +
                "[ -z \"\$removed\" ] || { echo 'Refusing APT transaction because it would remove existing packages:' >&2; " +
                "printf '%s\\n' \"\$removed\" >&2; exit 1; }; " +
                "blocked=\$(awk '\$1 == \"Inst\" { print \$2 }' \"\$simulation\" | " +
                "grep -E '$BLOCKED_PACKAGE_PATTERN' || true); " +
                "[ -z \"\$blocked\" ] || { " +
                "echo 'Refusing APT transaction because it would install host-owned X11/display/audio infrastructure:' >&2; " +
                "printf '%s\\n' \"\$blocked\" >&2; exit 1; }"

        return GraphicSessionInstallStep(
            title = "Checking APT transaction safety",
            command = command
        )
    }
}
