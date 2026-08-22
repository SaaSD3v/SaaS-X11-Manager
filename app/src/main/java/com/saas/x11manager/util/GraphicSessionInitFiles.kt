package com.saas.x11manager.util

/** Pure templates for init-owned X11 session files. */
internal object GraphicSessionInitFiles {

    fun sessionScript(session: GraphicSession, shell: String): String {
        val launch = if (session == GraphicSession.NONE) {
            "exit 0\n"
        } else {
            "exec ${session.startCommand}\n"
        }
        val leaseGuard = if (session == GraphicSession.NONE) {
            ""
        } else {
            "[ -f ${GraphicSessionRuntimePolicy.SESSION_REQUEST_FILE} ] || exit 0\n"
        }

        return "#!$shell\n" +
            leaseGuard +
            "export DISPLAY=:0\n" +
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
            "description=\"Setup X11 socket directory and bind mount\"\n\n" +
            "depend() {\n" +
            "    before x11-session\n" +
            "}\n\n" +
            "start() {\n" +
            "    ebegin \"Setting up X11 socket\"\n" +
            "    if [ ! -f ${GraphicSessionRuntimePolicy.SOCKET_REQUEST_FILE} ]; then\n" +
            "        eend 0\n" +
            "        return 0\n" +
            "    fi\n" +
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
            "description=\"X11 ${session.label} Session on Termux:X11\"\n" +
            "command=\"/usr/local/bin/x11-session.sh\"\n" +
            "command_background=\"yes\"\n" +
            "pidfile=\"/run/x11-session.pid\"\n" +
            "stopgroup=\"yes\"\n\n" +
            "depend() {\n" +
            "    need x11-setup\n" +
            "}\n"

    fun systemdSocketService(): String =
        "[Unit]\n" +
            "Description=Setup X11 socket directory\n" +
            "Before=x11-session.service\n" +
            "ConditionPathExists=${GraphicSessionRuntimePolicy.SOCKET_REQUEST_FILE}\n\n" +
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
            "Description=X11 ${session.label} Session on Termux:X11\n" +
            "After=network.target setup-x11-socket.service\n" +
            "Requires=setup-x11-socket.service\n" +
            "ConditionPathExists=${GraphicSessionRuntimePolicy.SESSION_REQUEST_FILE}\n\n" +
            "[Service]\n" +
            "Type=simple\n" +
            "Environment=DISPLAY=:0\n" +
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
