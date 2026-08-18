package com.saas.x11manager.util

/**
 * Keeps APT's recommended-package behavior enabled while blocking only
 * recommendations that conflict with the Termux:X11 architecture.
 *
 * Packages listed here are never removed if they are already installed; the
 * installer only prevents APT from selecting them as new recommendations.
 */
internal object GraphicSessionAptPolicy {
    fun blockedRecommendedPackages(session: GraphicSession): List<String> = when (session) {
        GraphicSession.XFCE -> listOf("systemd-sysv")
        GraphicSession.LXQT -> listOf("pavucontrol-qt", "pavucontrol")
        GraphicSession.GNOME_XORG -> listOf("systemd-sysv")
        GraphicSession.GNOME_FLASHBACK -> listOf("systemd-sysv", "xserver-xorg")
        GraphicSession.CINNAMON_SHELL,
        GraphicSession.CINNAMON_DESKTOP -> listOf("cinnamon-core")
        GraphicSession.MATE -> listOf("pipewire-audio", "pulseaudio")
        else -> emptyList()
    }
}
