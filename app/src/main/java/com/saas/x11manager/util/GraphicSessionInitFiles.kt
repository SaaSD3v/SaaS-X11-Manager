package com.saas.x11manager.util

/** Pure templates for init-owned X11 session files. */
internal object GraphicSessionInitFiles {

    private fun dynamicDisplayEnvironment(): String =
        "X11_SOCKET=\n" +
            "for candidate in /tmp/.X11-unix/X*; do\n" +
            "    [ -S \"\$candidate\" ] || continue\n" +
            "    if [ -n \"\$X11_SOCKET\" ]; then\n" +
            "        echo \"Multiple X11 sockets are mounted; refusing ambiguous display selection\" >&2\n" +
            "        exit 1\n" +
            "    fi\n" +
            "    X11_SOCKET=\$candidate\n" +
            "done\n" +
            "if [ -z \"\$X11_SOCKET\" ]; then\n" +
            "    echo \"No X11 socket is mounted in /tmp/.X11-unix\" >&2\n" +
            "    exit 1\n" +
            "fi\n" +
            "X11_DISPLAY_NUMBER=\${X11_SOCKET##*/X}\n" +
            "case \"\$X11_DISPLAY_NUMBER\" in\n" +
            "    ''|*[!0-9]*) echo \"Invalid X11 socket name: \$X11_SOCKET\" >&2; exit 1 ;;\n" +
            "esac\n" +
            "export DISPLAY=:\$X11_DISPLAY_NUMBER\n"

    fun sessionScript(session: GraphicSession, shell: String): String {
        val launch = if (session == GraphicSession.NONE) {
            "exit 0\n"
        } else {
            "exec ${session.startCommand}\n"
        }

        return "#!$shell\n" +
            dynamicDisplayEnvironment() +
            "export HOME=/root\n" +
            "export USER=root\n" +
            "export SHELL=$shell\n" +
            "export XDG_SESSION_TYPE=x11\n" +
            "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
            "mkdir -p \"\$XDG_RUNTIME_DIR\" && chmod 700 \"\$XDG_RUNTIME_DIR\"\n" +
            launch
    }

    fun openRcSetupService(): String =
        "#!/sbin/openrc-run\n\n" +
            "description=\"Setup Manager X11 socket directory and bind mount\"\n\n" +
            "depend() {\n" +
            "    before x11-session\n" +
            "}\n\n" +
            "start() {\n" +
            "    ebegin \"Setting up X11 socket\"\n" +
            "    if [ ! -d /usr/.X11-unix ]; then\n" +
            "        eerror \"X11 source socket directory /usr/.X11-unix is missing\"\n" +
            "        eend 1\n" +
            "        return 1\n" +
            "    fi\n" +
            "    mkdir -p /tmp/.X11-unix /tmp/runtime-root || { eend 1; return 1; }\n" +
            "    chmod 700 /tmp/runtime-root || { eend 1; return 1; }\n" +
            "    if mountpoint -q /tmp/.X11-unix 2>/dev/null; then\n" +
            "        eend 0\n" +
            "        return 0\n" +
            "    fi\n" +
            "    mount --bind /usr/.X11-unix /tmp/.X11-unix\n" +
            "    rc=$?\n" +
            "    eend \$rc\n" +
            "    return \$rc\n" +
            "}\n\n" +
            "stop() {\n" +
            "    ebegin \"Unmounting X11 socket\"\n" +
            "    if mountpoint -q /tmp/.X11-unix 2>/dev/null; then\n" +
            "        umount /tmp/.X11-unix\n" +
            "        rc=$?\n" +
            "        eend \$rc\n" +
            "        return \$rc\n" +
            "    fi\n" +
            "    eend 0\n" +
            "}\n"

    fun openRcSessionService(session: GraphicSession): String =
        "#!/sbin/openrc-run\n\n" +
            "description=\"X11 ${session.label} Session on SaaS X11\"\n" +
            "command=\"/usr/local/bin/x11-session.sh\"\n" +
            "command_background=\"yes\"\n" +
            "pidfile=\"/run/x11-session.pid\"\n" +
            "stopgroup=\"yes\"\n\n" +
            "depend() {\n" +
            "    need x11-setup\n" +
            "}\n"

    fun systemdSocketService(): String =
        "[Unit]\n" +
            "Description=Setup Manager X11 socket directory\n" +
            "Before=x11-session.service\n\n" +
            "[Service]\n" +
            "Type=oneshot\n" +
            "ExecStart=/bin/sh -c 'test -d /usr/.X11-unix && " +
            "mkdir -p /tmp/.X11-unix /tmp/runtime-root && " +
            "chmod 700 /tmp/runtime-root && " +
            "{ mountpoint -q /tmp/.X11-unix 2>/dev/null || " +
            "mount --bind /usr/.X11-unix /tmp/.X11-unix; }'\n" +
            "ExecStop=/bin/sh -c 'if mountpoint -q /tmp/.X11-unix 2>/dev/null; then " +
            "umount /tmp/.X11-unix; fi'\n" +
            "RemainAfterExit=yes\n\n" +
            "[Install]\n" +
            "WantedBy=multi-user.target\n"

    fun systemdSessionService(session: GraphicSession): String =
        "[Unit]\n" +
            "Description=X11 ${session.label} Session on SaaS X11\n" +
            "After=network.target setup-x11-socket.service\n" +
            "Requires=setup-x11-socket.service\n\n" +
            "[Service]\n" +
            "Type=simple\n" +
            "Environment=HOME=/root\n" +
            "Environment=USER=root\n" +
            "Environment=SHELL=/bin/bash\n" +
            "Environment=XDG_SESSION_TYPE=x11\n" +
            "Environment=XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
            "ExecStart=/usr/local/bin/x11-session.sh\n" +
            "Restart=on-failure\n" +
            "RestartSec=3\n\n" +
            "[Install]\n" +
            "WantedBy=graphical.target\n"
}
