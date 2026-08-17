package com.saas.x11manager.util

internal data class GraphicSessionProvisionCommand(
    val title: String,
    val command: String
)

internal data class GraphicSessionSupportSpec(
    val session: GraphicSession,
    val postInstallCommands: List<GraphicSessionProvisionCommand> = emptyList(),
    val verificationCommands: List<GraphicSessionProvisionCommand> = emptyList()
)

/**
 * Sessions that currently have a complete install/verify workflow in the app.
 * The order here is the order shown in Edit Container.
 */
object GraphicSessionSupport {
    private val specs = linkedMapOf(
        GraphicSession.OPENBOX to GraphicSessionSupportSpec(
            session = GraphicSession.OPENBOX,
            postInstallCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Creating Openbox configuration directory",
                    "mkdir -p /root/.config/openbox"
                ),
                GraphicSessionProvisionCommand(
                    "Installing default Openbox rc.xml",
                    "[ -f /root/.config/openbox/rc.xml ] || cp /etc/xdg/openbox/rc.xml /root/.config/openbox/rc.xml"
                ),
                GraphicSessionProvisionCommand(
                    "Installing default Openbox menu.xml",
                    "[ -f /root/.config/openbox/menu.xml ] || cp /etc/xdg/openbox/menu.xml /root/.config/openbox/menu.xml"
                )
            ),
            verificationCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Checking Openbox configuration",
                    "test -f /root/.config/openbox/rc.xml && test -f /root/.config/openbox/menu.xml"
                )
            )
        ),
        GraphicSession.ICEWM to GraphicSessionSupportSpec(GraphicSession.ICEWM),
        GraphicSession.JWM to GraphicSessionSupportSpec(
            session = GraphicSession.JWM,
            postInstallCommands = listOf(
                GraphicSessionProvisionCommand("Validating JWM configuration", "jwm -p")
            ),
            verificationCommands = listOf(
                GraphicSessionProvisionCommand("Checking JWM configuration", "jwm -p")
            )
        ),
        GraphicSession.FLUXBOX to GraphicSessionSupportSpec(GraphicSession.FLUXBOX),
        GraphicSession.CWM to GraphicSessionSupportSpec(
            session = GraphicSession.CWM,
            postInstallCommands = listOf(
                GraphicSessionProvisionCommand("Validating cwm configuration", "cwm -n")
            ),
            verificationCommands = listOf(
                GraphicSessionProvisionCommand("Checking cwm configuration", "cwm -n")
            )
        ),
        GraphicSession.HERBSTLUFTWM to GraphicSessionSupportSpec(GraphicSession.HERBSTLUFTWM),
        GraphicSession.SPECTRWM to GraphicSessionSupportSpec(GraphicSession.SPECTRWM),
        GraphicSession.I3 to GraphicSessionSupportSpec(
            session = GraphicSession.I3,
            postInstallCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Preparing i3 configuration",
                    "mkdir -p /root/.config/i3 && ([ -f /root/.config/i3/config ] || [ -f /root/.i3/config ] || cp /etc/i3/config /root/.config/i3/config)"
                )
            ),
            verificationCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Checking i3 configuration",
                    "if [ -f /root/.config/i3/config ]; then i3 -C -c /root/.config/i3/config; elif [ -f /root/.i3/config ]; then i3 -C -c /root/.i3/config; else i3 -C -c /etc/i3/config; fi"
                )
            )
        ),
        GraphicSession.AWESOME to GraphicSessionSupportSpec(
            session = GraphicSession.AWESOME,
            verificationCommands = listOf(
                GraphicSessionProvisionCommand("Checking AwesomeWM configuration syntax", "awesome --check")
            )
        ),
        GraphicSession.RATPOISON to GraphicSessionSupportSpec(GraphicSession.RATPOISON),
        GraphicSession.TWM to GraphicSessionSupportSpec(GraphicSession.TWM),
        GraphicSession.WINDOW_MAKER to GraphicSessionSupportSpec(GraphicSession.WINDOW_MAKER),
        GraphicSession.FVWM to GraphicSessionSupportSpec(GraphicSession.FVWM),
        GraphicSession.PEKWM to GraphicSessionSupportSpec(GraphicSession.PEKWM),
        GraphicSession.BLACKBOX to GraphicSessionSupportSpec(GraphicSession.BLACKBOX),
        GraphicSession.CTWM to GraphicSessionSupportSpec(GraphicSession.CTWM),
        GraphicSession.EVILWM to GraphicSessionSupportSpec(GraphicSession.EVILWM),
        GraphicSession.MATCHBOX to GraphicSessionSupportSpec(GraphicSession.MATCHBOX),
        GraphicSession.SAWFISH to GraphicSessionSupportSpec(GraphicSession.SAWFISH),
        GraphicSession.XMONAD to GraphicSessionSupportSpec(GraphicSession.XMONAD),
        GraphicSession.NINE_WM to GraphicSessionSupportSpec(GraphicSession.NINE_WM),
        GraphicSession.AEWM_PLUS_PLUS to GraphicSessionSupportSpec(GraphicSession.AEWM_PLUS_PLUS),
        GraphicSession.AFTERSTEP to GraphicSessionSupportSpec(GraphicSession.AFTERSTEP),
        GraphicSession.AMIWM to GraphicSessionSupportSpec(GraphicSession.AMIWM),
        GraphicSession.DWM to GraphicSessionSupportSpec(
            session = GraphicSession.DWM,
            postInstallCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Preparing dwm launcher",
                    "if command -v dwm >/dev/null 2>&1; then true; elif [ -x /usr/bin/dwm.default ]; then ln -sf /usr/bin/dwm.default /usr/local/bin/dwm; else exit 1; fi"
                )
            ),
            verificationCommands = listOf(
                GraphicSessionProvisionCommand("Checking dwm launcher", "command -v dwm >/dev/null")
            )
        ),
        GraphicSession.FLWM to GraphicSessionSupportSpec(GraphicSession.FLWM),
        GraphicSession.LWM to GraphicSessionSupportSpec(GraphicSession.LWM),
        GraphicSession.MIWM to GraphicSessionSupportSpec(GraphicSession.MIWM),
        GraphicSession.VTWM to GraphicSessionSupportSpec(GraphicSession.VTWM),
        GraphicSession.W9WM to GraphicSessionSupportSpec(GraphicSession.W9WM),
        GraphicSession.WINDOWLAB to GraphicSessionSupportSpec(GraphicSession.WINDOWLAB),
        GraphicSession.WM2 to GraphicSessionSupportSpec(GraphicSession.WM2),
        GraphicSession.STUMPWM to GraphicSessionSupportSpec(GraphicSession.STUMPWM),
        GraphicSession.NOTION to GraphicSessionSupportSpec(GraphicSession.NOTION),
        GraphicSession.MWM to GraphicSessionSupportSpec(GraphicSession.MWM),
        GraphicSession.MARCO to GraphicSessionSupportSpec(GraphicSession.MARCO),
        GraphicSession.METACITY to GraphicSessionSupportSpec(GraphicSession.METACITY),
        GraphicSession.XFWM4 to GraphicSessionSupportSpec(GraphicSession.XFWM4),
        GraphicSession.KWIN_X11 to dbusWrappedSpec(
            session = GraphicSession.KWIN_X11,
            executable = "kwin_x11",
            wrapper = "saas-kwin-x11-session"
        ),
        GraphicSession.ENLIGHTENMENT to dbusWrappedSpec(
            session = GraphicSession.ENLIGHTENMENT,
            executable = "enlightenment_start",
            wrapper = "saas-enlightenment-session"
        ),
        GraphicSession.BSPWM to GraphicSessionSupportSpec(
            session = GraphicSession.BSPWM,
            postInstallCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Preparing bspwm configuration",
                    "mkdir -p /root/.config/bspwm /root/.config/sxhkd && " +
                        "([ -f /root/.config/bspwm/bspwmrc ] || cp /usr/share/doc/bspwm/examples/bspwmrc /root/.config/bspwm/bspwmrc) && " +
                        "([ -f /root/.config/sxhkd/sxhkdrc ] || cp /usr/share/doc/bspwm/examples/sxhkdrc /root/.config/sxhkd/sxhkdrc) && " +
                        "chmod 755 /root/.config/bspwm/bspwmrc"
                ),
                GraphicSessionProvisionCommand(
                    "Creating bspwm session launcher",
                    "printf '%s\\n' '#!/bin/sh' 'sxhkd >/tmp/saas-sxhkd.log 2>&1 &' 'exec bspwm' > /usr/local/bin/saas-bspwm-session && chmod 755 /usr/local/bin/saas-bspwm-session"
                )
            ),
            verificationCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Checking bspwm companion setup",
                    "command -v bspwm >/dev/null && command -v sxhkd >/dev/null && " +
                        "test -f /root/.config/bspwm/bspwmrc && test -f /root/.config/sxhkd/sxhkdrc"
                )
            )
        ),
        GraphicSession.CLFSWM to GraphicSessionSupportSpec(GraphicSession.CLFSWM),
        GraphicSession.FVWM_CRYSTAL to GraphicSessionSupportSpec(GraphicSession.FVWM_CRYSTAL),
        GraphicSession.QTILE to GraphicSessionSupportSpec(
            session = GraphicSession.QTILE,
            postInstallCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Creating Qtile session launcher",
                    "printf '%s\\n' '#!/bin/sh' 'exec qtile start' > /usr/local/bin/saas-qtile-session && chmod 755 /usr/local/bin/saas-qtile-session"
                )
            ),
            verificationCommands = listOf(
                GraphicSessionProvisionCommand("Checking Qtile executable", "command -v qtile >/dev/null")
            )
        ),
        GraphicSession.MUFFIN to dbusWrappedSpec(
            session = GraphicSession.MUFFIN,
            executable = "muffin",
            wrapper = "saas-muffin-session"
        ),
        GraphicSession.MUTTER to dbusWrappedSpec(
            session = GraphicSession.MUTTER,
            executable = "mutter",
            wrapper = "saas-mutter-session"
        ),
        GraphicSession.UKWM to dbusWrappedSpec(
            session = GraphicSession.UKWM,
            executable = "ukwm",
            wrapper = "saas-ukwm-session"
        ),
        GraphicSession.CINNAMON_SHELL to dbusWrappedSpec(
            session = GraphicSession.CINNAMON_SHELL,
            executable = "cinnamon",
            wrapper = "saas-cinnamon-shell-session"
        ),
        GraphicSession.COMPIZ to dbusWrappedSpec(
            session = GraphicSession.COMPIZ,
            executable = "compiz",
            wrapper = "saas-compiz-session"
        ),
        GraphicSession.SUBTLE to GraphicSessionSupportSpec(GraphicSession.SUBTLE),
        GraphicSession.MATE to dbusWrappedSpec(
            session = GraphicSession.MATE,
            executable = "mate-session",
            wrapper = "saas-mate-session"
        ),
        GraphicSession.LXDE to dbusWrappedSpec(
            session = GraphicSession.LXDE,
            executable = "startlxde",
            wrapper = "saas-lxde-session"
        ),
        GraphicSession.PLASMA_X11 to dbusWrappedSpec(
            session = GraphicSession.PLASMA_X11,
            executable = "startplasma-x11",
            wrapper = "saas-plasma-x11-session"
        ),
        GraphicSession.CINNAMON_DESKTOP to dbusWrappedSpec(
            session = GraphicSession.CINNAMON_DESKTOP,
            executable = "cinnamon-session",
            wrapper = "saas-cinnamon-session"
        ),
        GraphicSession.SUGAR to dbusWrappedSpec(
            session = GraphicSession.SUGAR,
            executable = "sugar",
            wrapper = "saas-sugar-session"
        ),
        GraphicSession.BUDGIE to dbusWrappedSpec(
            session = GraphicSession.BUDGIE,
            executable = "budgie-session",
            wrapper = "saas-budgie-session"
        ),
        GraphicSession.FVWM3 to GraphicSessionSupportSpec(GraphicSession.FVWM3),
        GraphicSession.TWO_BWM to GraphicSessionSupportSpec(GraphicSession.TWO_BWM),
        GraphicSession.BERRY to GraphicSessionSupportSpec(GraphicSession.BERRY),
        GraphicSession.DK to GraphicSessionSupportSpec(GraphicSession.DK),
        GraphicSession.GNOME_XORG to dbusWrappedSpec(
            session = GraphicSession.GNOME_XORG,
            executable = "gnome-session",
            wrapper = "saas-gnome-xorg-session"
        ),
        GraphicSession.GNOME_FLASHBACK to GraphicSessionSupportSpec(
            session = GraphicSession.GNOME_FLASHBACK,
            postInstallCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Creating GNOME Flashback session launcher",
                    "printf '%s\\n' '#!/bin/sh' " +
                        "'export DESKTOP_SESSION=gnome-flashback-metacity' " +
                        "'export XDG_SESSION_DESKTOP=gnome-flashback-metacity' " +
                        "'export XDG_CURRENT_DESKTOP=GNOME-Flashback:GNOME' " +
                        "'exec dbus-run-session -- gnome-session --session=gnome-flashback-metacity' " +
                        "> /usr/local/bin/saas-gnome-flashback-session && chmod 755 /usr/local/bin/saas-gnome-flashback-session"
                )
            ),
            verificationCommands = listOf(
                GraphicSessionProvisionCommand(
                    "Checking GNOME Flashback session",
                    "command -v gnome-session >/dev/null && " +
                        "test -f /usr/share/gnome-session/sessions/gnome-flashback-metacity.session"
                )
            )
        ),
        GraphicSession.GNOME_CLASSIC_XORG to dbusWrappedSpec(
            session = GraphicSession.GNOME_CLASSIC_XORG,
            executable = "gnome-session-classic",
            wrapper = "saas-gnome-classic-xorg-session"
        ),
        GraphicSession.XFCE to GraphicSessionSupportSpec(GraphicSession.XFCE),
        GraphicSession.LXQT to GraphicSessionSupportSpec(GraphicSession.LXQT)
    )

    val installableSessions: List<GraphicSession>
        get() = specs.keys.toList()

    internal fun specFor(session: GraphicSession): GraphicSessionSupportSpec? = specs[session]

    private fun dbusWrappedSpec(
        session: GraphicSession,
        executable: String,
        wrapper: String
    ): GraphicSessionSupportSpec = GraphicSessionSupportSpec(
        session = session,
        postInstallCommands = listOf(
            GraphicSessionProvisionCommand(
                "Creating ${session.label} session launcher",
                "printf '%s\\n' '#!/bin/sh' 'exec dbus-run-session -- $executable' > /usr/local/bin/$wrapper && chmod 755 /usr/local/bin/$wrapper"
            )
        ),
        verificationCommands = listOf(
            GraphicSessionProvisionCommand(
                "Checking ${session.label} executable",
                "command -v $executable >/dev/null && command -v dbus-run-session >/dev/null"
            )
        )
    )
}
