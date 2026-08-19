package com.saas.x11manager.util

object Constants {
    const val DS_BINARY_PATH = "/data/local/Droidspaces/bin/droidspaces"
    const val DS_BASE_DIR = "/data/local/Droidspaces"
    const val CONTAINERS_DIR = "/data/local/Droidspaces/Containers"
    const val CONFIG_FILE = "container.config"
    const val DAEMON_MODE_FILE = "/data/local/Droidspaces/.daemon_mode"

    const val INTEGRATED_X11_RUNTIME_DIR = "/data/local/tmp/saas-x11"
    const val X11_SERVER_PROCESS = "saas-x11"
    const val X11_DISPLAY = ":0"
    const val X11_SOCK_DIR = "$INTEGRATED_X11_RUNTIME_DIR/.X11-unix"
    const val X11_SOCK_FILE = "$X11_SOCK_DIR/X0"
    const val X11_LOCK_FILE = "$INTEGRATED_X11_RUNTIME_DIR/.X0-lock"
    const val X11_LOG_FILE = "$INTEGRATED_X11_RUNTIME_DIR/server.log"
}
