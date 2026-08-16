package com.saas.x11manager.util

object Constants {
    const val DS_BINARY_PATH = "/data/local/Droidspaces/bin/droidspaces"
    const val DS_BASE_DIR = "/data/local/Droidspaces"
    const val CONTAINERS_DIR = "/data/local/Droidspaces/Containers"
    const val CONFIG_FILE = "container.config"
    const val DAEMON_MODE_FILE = "/data/local/Droidspaces/.daemon_mode"

    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_X11_PACKAGE = "com.termux.x11"

    const val TERMUX_DATA_DIR = "/data/data/com.termux"
    const val TERMUX_DATA_ALT = "/data/user/0/com.termux"
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    const val LOADER_APK = "$TERMUX_PREFIX/libexec/termux-x11/loader.apk"
    const val X11_SOCK_DIR = "$TERMUX_PREFIX/tmp/.X11-unix"
    const val X11_SOCK_FILE = "$TERMUX_PREFIX/tmp/.X11-unix/X0"
}
