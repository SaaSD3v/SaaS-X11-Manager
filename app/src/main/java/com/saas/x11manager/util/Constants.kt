package com.saas.x11manager.util

object Constants {
    const val INSTALL_PATH = "/data/local/Droidspaces/bin"
    const val DROIDSPACES_BINARY_NAME = "droidspaces"
    const val BUSYBOX_BINARY_NAME = "busybox"
    const val DS_BINARY_PATH = "$INSTALL_PATH/$DROIDSPACES_BINARY_NAME"
    const val BUSYBOX_BINARY_PATH = "$INSTALL_PATH/$BUSYBOX_BINARY_NAME"
    const val MAGISK_MODULE_PATH = "/data/adb/modules/droidspaces"
    const val MODULE_SYSTEM_BIN_PATH = "$MAGISK_MODULE_PATH/system/bin"
    const val SYSTEM_BIN_SYMLINK_PATH = "$MODULE_SYSTEM_BIN_PATH/$DROIDSPACES_BINARY_NAME"

    const val CONTAINERS_DIR = "/data/local/Droidspaces/Containers"
    const val CONFIG_FILE = "container.config"
    const val DAEMON_MODE_FILE = "/data/local/Droidspaces/.daemon_mode"
    const val DAEMON_PID_FILE = "/data/local/Droidspaces/droidspacesd.pid"

    const val LOADER_APK = "/data/data/com.termux/files/usr/libexec/termux-x11/loader.apk"
    const val X11_SOCK_DIR = "/data/data/com.termux/files/usr/tmp/.X11-unix"
    const val X11_SOCK_FILE = "/data/data/com.termux/files/usr/tmp/.X11-unix/X0"
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_X11_PACKAGE = "com.termux.x11"

    fun getDroidspacesCommand(): String {
        val result = com.topjohnwu.superuser.Shell.cmd("command -v droidspaces 2>&1").exec()
        return if (result.isSuccess && result.out.isNotEmpty() && result.out[0].isNotBlank()) {
            "droidspaces"
        } else {
            DS_BINARY_PATH
        }
    }
}
