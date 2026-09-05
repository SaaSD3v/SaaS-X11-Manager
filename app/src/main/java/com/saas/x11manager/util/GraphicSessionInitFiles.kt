package com.saas.x11manager.util

/** Pure templates for init-owned graphical session files on the fixed :0 X11 transport. */
internal object GraphicSessionInitFiles {

    private fun fixedDisplayEnvironment(): String =
        "export DISPLAY=:0\n" +
            "export SAAS_HOST_DISPLAY=:0\n"

    /**
     * Compatibility launcher for the traditional root-owned desktop path.
     * OpenRC still receives /bin/sh and systemd still receives /bin/bash from
     * the caller; their init services remain completely separate.
     */
    fun rootSessionScript(session: GraphicSession, shell: String): String {
        val sessionType = if (session.protocol == GraphicProtocol.WAYLAND) "wayland" else "x11"
        val protocolEnvironment = if (session.protocol == GraphicProtocol.WAYLAND) {
            "export XDG_SESSION_TYPE=wayland\n" +
                "export SAAS_WAYLAND_SOCKET=wayland-0\n" +
                "unset WAYLAND_DISPLAY\n"
        } else {
            "export XDG_SESSION_TYPE=x11\n"
        }
        val launch = if (session == GraphicSession.NONE) {
            "exit 0\n"
        } else {
            "exec ${session.startCommand}\n"
        }

        return "#!$shell\n" +
            fixedDisplayEnvironment() +
            "export HOME=/root\n" +
            "export USER=root\n" +
            "export LOGNAME=root\n" +
            "export SHELL=$shell\n" +
            protocolEnvironment +
            "export SAAS_GRAPHIC_PROTOCOL=$sessionType\n" +
            "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
            "mkdir -p \"\$XDG_RUNTIME_DIR\" && chmod 700 \"\$XDG_RUNTIME_DIR\"\n" +
            launch
    }

    private fun selectedUserEnvironment(defaultShell: String): String =
        "SESSION_USER=root\n" +
            "SESSION_CREATE=0\n" +
            "SESSION_USER_FILE=/etc/saas-x11-manager/session-user\n" +
            "if [ -r \"\$SESSION_USER_FILE\" ]; then\n" +
            "    requested_user=\$(sed -n 's/^user=//p' \"\$SESSION_USER_FILE\" | head -n 1)\n" +
            "    requested_create=\$(sed -n 's/^create=//p' \"\$SESSION_USER_FILE\" | head -n 1)\n" +
            "    case \"\$requested_user\" in\n" +
            "        ''|[!A-Za-z_]*|*[!A-Za-z0-9_-]*) echo \"Invalid Manager graphical user selection\" >&2; exit 1 ;;\n" +
            "        *) SESSION_USER=\$requested_user ;;\n" +
            "    esac\n" +
            "    [ \"\$requested_create\" = 1 ] && SESSION_CREATE=1\n" +
            "fi\n" +
            "SESSION_ENTRY=\$(awk -F: -v user=\"\$SESSION_USER\" '\$1 == user { print \$3 \":\" \$4 \":\" \$6 \":\" \$7; exit }' /etc/passwd)\n" +
            "SESSION_CREATED=0\n" +
            "if [ -z \"\$SESSION_ENTRY\" ] && [ \"\$SESSION_CREATE\" = 1 ]; then\n" +
            "    if command -v apk >/dev/null 2>&1; then\n" +
            "        adduser -D \"\$SESSION_USER\" || exit 1\n" +
            "    elif command -v adduser >/dev/null 2>&1; then\n" +
            "        ADDUSER_NAME_OPT=\n" +
            "        if adduser --help 2>&1 | grep -q -- '--allow-bad-names'; then\n" +
            "            ADDUSER_NAME_OPT=--allow-bad-names\n" +
            "        elif adduser --help 2>&1 | grep -q -- '--force-badname'; then\n" +
            "            ADDUSER_NAME_OPT=--force-badname\n" +
            "        fi\n" +
            "        if adduser --help 2>&1 | grep -q -- '--comment'; then\n" +
            "            adduser \$ADDUSER_NAME_OPT --disabled-password --comment '' \"\$SESSION_USER\" || exit 1\n" +
            "        else\n" +
            "            adduser \$ADDUSER_NAME_OPT --disabled-password --gecos '' \"\$SESSION_USER\" || exit 1\n" +
            "        fi\n" +
            "    elif command -v useradd >/dev/null 2>&1; then\n" +
            "        useradd -m \"\$SESSION_USER\" || exit 1\n" +
            "    else\n" +
            "        echo \"No supported user creation command is available\" >&2\n" +
            "        exit 1\n" +
            "    fi\n" +
            "    SESSION_CREATED=1\n" +
            "    SESSION_ENTRY=\$(awk -F: -v user=\"\$SESSION_USER\" '\$1 == user { print \$3 \":\" \$4 \":\" \$6 \":\" \$7; exit }' /etc/passwd)\n" +
            "fi\n" +
            "if [ -z \"\$SESSION_ENTRY\" ]; then\n" +
            "    echo \"Selected graphical user does not exist: \$SESSION_USER\" >&2\n" +
            "    exit 1\n" +
            "fi\n" +
            "SESSION_UID=\${SESSION_ENTRY%%:*}\n" +
            "SESSION_REST=\${SESSION_ENTRY#*:}\n" +
            "SESSION_GID=\${SESSION_REST%%:*}\n" +
            "SESSION_REST=\${SESSION_REST#*:}\n" +
            "SESSION_HOME=\${SESSION_REST%%:*}\n" +
            "SESSION_SHELL=\${SESSION_REST#*:}\n" +
            "case \"\$SESSION_UID:\$SESSION_GID\" in\n" +
            "    *[!0-9:]*|'':*|*:'') echo \"Invalid UID/GID for graphical user: \$SESSION_USER\" >&2; exit 1 ;;\n" +
            "esac\n" +
            "case \"\$SESSION_HOME\" in /*) ;; *) echo \"Invalid home for graphical user: \$SESSION_USER\" >&2; exit 1 ;; esac\n" +
            "[ -x \"\$SESSION_SHELL\" ] || SESSION_SHELL=$defaultShell\n" +
            "if [ \"\$SESSION_CREATED\" = 1 ]; then\n" +
            "    mkdir -p \"\$SESSION_HOME/.config\" \"\$SESSION_HOME/.local\" \"\$SESSION_HOME/.cache\" || exit 1\n" +
            "    chown \"\$SESSION_UID:\$SESSION_GID\" \"\$SESSION_HOME\" \"\$SESSION_HOME/.config\" \"\$SESSION_HOME/.local\" \"\$SESSION_HOME/.cache\" || exit 1\n" +
            "    sed -i 's/^create=1$/create=0/' \"\$SESSION_USER_FILE\" 2>/dev/null || true\n" +
            "fi\n" +
            "export HOME=\$SESSION_HOME\n" +
            "export USER=\$SESSION_USER\n" +
            "export LOGNAME=\$SESSION_USER\n" +
            "export SHELL=\$SESSION_SHELL\n" +
            "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
            "mkdir -p \"\$XDG_RUNTIME_DIR\" || exit 1\n" +
            "chown \"\$SESSION_UID:\$SESSION_GID\" \"\$XDG_RUNTIME_DIR\" || exit 1\n" +
            "chmod 700 \"\$XDG_RUNTIME_DIR\" || exit 1\n"

    fun sessionScript(session: GraphicSession, shell: String): String {
        val sessionType = if (session.protocol == GraphicProtocol.WAYLAND) "wayland" else "x11"
        val protocolEnvironment = if (session.protocol == GraphicProtocol.WAYLAND) {
            "export XDG_SESSION_TYPE=wayland\n" +
                "export SAAS_WAYLAND_SOCKET=wayland-0\n" +
                "unset WAYLAND_DISPLAY\n"
        } else {
            "export XDG_SESSION_TYPE=x11\n"
        }
        val launch = if (session == GraphicSession.NONE) {
            "exit 0\n"
        } else {
            "if [ \"\$SESSION_UID\" = 0 ]; then\n" +
                "    exec ${session.startCommand}\n" +
                "fi\n" +
                "exec su -p -s \"\$SESSION_SHELL\" \"\$SESSION_USER\" -c 'exec ${session.startCommand}'\n"
        }

        return "#!$shell\n" +
            fixedDisplayEnvironment() +
            selectedUserEnvironment(shell) +
            protocolEnvironment +
            "export SAAS_GRAPHIC_PROTOCOL=$sessionType\n" +
            launch
    }

    fun openRcSetupService(): String =
        "#!/sbin/openrc-run\n\n" +
            "description=\"Setup Manager X11 transport socket directory and bind mount\"\n\n" +
            "depend() {\n" +
            "    before x11-session\n" +
            "}\n\n" +
            "start() {\n" +
            "    ebegin \"Setting up Manager X11 transport socket\"\n" +
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
            "    rc=\$?\n" +
            "    eend \$rc\n" +
            "    return \$rc\n" +
            "}\n\n" +
            "stop() {\n" +
            "    ebegin \"Unmounting Manager X11 transport socket\"\n" +
            "    if mountpoint -q /tmp/.X11-unix 2>/dev/null; then\n" +
            "        umount /tmp/.X11-unix\n" +
            "        rc=\$?\n" +
            "        eend \$rc\n" +
            "        return \$rc\n" +
            "    fi\n" +
            "    eend 0\n" +
            "}\n"

    fun openRcSessionService(session: GraphicSession): String {
        val description = if (session.protocol == GraphicProtocol.WAYLAND) {
            "Wayland ${session.label} Session on SaaS X11 transport"
        } else {
            "X11 ${session.label} Session - SaaS X11"
        }
        return "#!/sbin/openrc-run\n\n" +
            "description=\"$description\"\n" +
            "command=\"/usr/local/bin/x11-session.sh\"\n" +
            "command_background=\"yes\"\n" +
            "pidfile=\"/run/x11-session.pid\"\n" +
            "stopgroup=\"yes\"\n\n" +
            "depend() {\n" +
            "    need x11-setup\n" +
            "}\n"
    }

    fun systemdSocketService(): String =
        "[Unit]\n" +
            "Description=Setup Manager X11 transport socket directory\n" +
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

    fun systemdSessionService(session: GraphicSession): String {
        val sessionType = if (session.protocol == GraphicProtocol.WAYLAND) "wayland" else "x11"
        val description = if (session.protocol == GraphicProtocol.WAYLAND) {
            "Wayland ${session.label} Session on Manager X11 transport"
        } else {
            "X11 ${session.label} Session"
        }
        return "[Unit]\n" +
            "Description=$description\n" +
            "After=network.target setup-x11-socket.service\n" +
            "Requires=setup-x11-socket.service\n\n" +
            "[Service]\n" +
            "Type=simple\n" +
            "Environment=HOME=/root\n" +
            "Environment=USER=root\n" +
            "Environment=SHELL=/bin/bash\n" +
            "Environment=XDG_SESSION_TYPE=$sessionType\n" +
            "Environment=XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
            "ExecStart=/usr/local/bin/x11-session.sh\n" +
            "Restart=on-failure\n" +
            "RestartSec=3\n\n" +
            "[Install]\n" +
            "WantedBy=multi-user.target\n"
    }
}
