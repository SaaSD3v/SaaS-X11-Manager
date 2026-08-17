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
        GraphicSession.HERBSTLUFTWM to GraphicSessionSupportSpec(GraphicSession.HERBSTLUFTWM)
    )

    val installableSessions: List<GraphicSession>
        get() = specs.keys.toList()

    internal fun specFor(session: GraphicSession): GraphicSessionSupportSpec? = specs[session]
}
