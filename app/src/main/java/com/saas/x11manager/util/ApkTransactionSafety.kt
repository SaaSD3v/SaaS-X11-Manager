package com.saas.x11manager.util

/**
 * Simulates an apk add transaction before changing the container and rejects
 * package resolution that would provision infrastructure owned by the
 * host-side Termux:X11 model.
 *
 * The safety check intentionally treats apk output as opaque text instead of
 * parsing progress counters or version-specific formatting. Alpine documents
 * --simulate as a no-write operation; the installer refreshes indexes before
 * invoking this step.
 */
internal object ApkTransactionSafety {

    private const val BLOCKED_PACKAGE_PATTERN =
        "(^|[[:space:](])(xorg-server|lightdm|sddm|gdm|lxdm|xdm|slim|nodm|pulseaudio|pipewire-pulse)(-[0-9][^[:space:]]*)?([[:space:])]|$)"

    fun stepFor(plan: GraphicSessionInstallPlan): GraphicSessionInstallStep? {
        if (plan.platform != ContainerPlatform.ALPINE || plan.packages.isEmpty()) return null

        val packageList = plan.packages.joinToString(" ")
        val command =
            "simulation=\$(mktemp) || exit 1; " +
                "cleanup() { rm -f \"\$simulation\"; }; " +
                "trap cleanup EXIT HUP INT TERM; " +
                "LC_ALL=C apk --simulate add $packageList >\"\$simulation\" 2>&1 || " +
                "{ cat \"\$simulation\" >&2; echo 'apk transaction simulation failed.' >&2; exit 1; }; " +
                "blocked=\$(grep -E '$BLOCKED_PACKAGE_PATTERN' \"\$simulation\" || true); " +
                "[ -z \"\$blocked\" ] || { " +
                "echo 'Refusing apk transaction because it would install host-owned X11/display/audio infrastructure:' >&2; " +
                "printf '%s\\n' \"\$blocked\" >&2; exit 1; }"

        return GraphicSessionInstallStep(
            title = "Checking apk transaction safety",
            command = command
        )
    }
}
