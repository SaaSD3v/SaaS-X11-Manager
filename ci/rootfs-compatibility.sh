#!/bin/sh
set -eu

fail() {
    echo "[-] $*" >&2
    exit 1
}

info() {
    echo "[+] $*"
}

if [ -r /etc/os-release ]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    info "Rootfs: ${ID:-unknown} ${VERSION_ID:-rolling/current}"
else
    info "Rootfs: unknown"
fi

APT_BLOCKED='xorg|xserver-xorg.*|gdm3|lightdm|sddm|lxdm|xdm|slim|nodm|pulseaudio|pipewire-pulse|pipewire-audio'
APK_BLOCKED='(^|[[:space:](])(xorg-server|lightdm|sddm|gdm|lxdm|xdm|slim|nodm|pulseaudio|pipewire-pulse)(-[0-9][^[:space:]]*)?([[:space:])]|$)'

check_apt_packages() {
    for package_name in "$@"; do
        apt-cache show "$package_name" >/dev/null 2>&1 || fail "APT package unavailable: $package_name"
    done
}

simulate_apt() {
    session_name=$1
    shift
    simulation=$(mktemp)
    trap 'rm -f "$simulation"' EXIT HUP INT TERM

    LC_ALL=C DEBIAN_FRONTEND=noninteractive \
        apt-get -s --no-install-recommends install "$@" >"$simulation" 2>&1 || {
            cat "$simulation" >&2
            fail "APT simulation failed for $session_name"
        }

    removed=$(awk '$1 == "Remv" { print $2 }' "$simulation")
    [ -z "$removed" ] || {
        cat "$simulation" >&2
        fail "$session_name would remove existing packages: $removed"
    }

    blocked=$(awk '$1 == "Inst" { print $2 }' "$simulation" | grep -Ex "$APT_BLOCKED" || true)
    [ -z "$blocked" ] || {
        cat "$simulation" >&2
        fail "$session_name would install host-owned infrastructure: $blocked"
    }

    rm -f "$simulation"
    trap - EXIT HUP INT TERM
    info "$session_name APT transaction is available and safe"
}

check_apk_packages() {
    for package_name in "$@"; do
        apk search -e "$package_name" >/dev/null 2>&1 || fail "apk package unavailable: $package_name"
    done
}

simulate_apk() {
    session_name=$1
    shift
    simulation=$(mktemp)
    trap 'rm -f "$simulation"' EXIT HUP INT TERM

    LC_ALL=C apk --simulate add "$@" >"$simulation" 2>&1 || {
        cat "$simulation" >&2
        fail "apk simulation failed for $session_name"
    }

    blocked=$(grep -E "$APK_BLOCKED" "$simulation" || true)
    [ -z "$blocked" ] || {
        cat "$simulation" >&2
        fail "$session_name would install host-owned infrastructure: $blocked"
    }

    rm -f "$simulation"
    trap - EXIT HUP INT TERM
    info "$session_name apk transaction is available and safe"
}

if command -v apk >/dev/null 2>&1; then
    info "Detected apk package family"
    apk update >/dev/null

    check_apk_packages \
        openbox xterm font-terminus \
        dbus dbus-x11 xfce4 xfce4-terminal xfce4-notifyd \
        lxqt-desktop mate-desktop-environment

    simulate_apk Openbox openbox xterm font-terminus
    simulate_apk XFCE dbus dbus-x11 xfce4 xfce4-terminal xfce4-notifyd
    simulate_apk LXQt dbus dbus-x11 lxqt-desktop
    simulate_apk MATE mate-desktop-environment dbus
elif command -v apt-get >/dev/null 2>&1 && command -v apt-cache >/dev/null 2>&1 && command -v dpkg >/dev/null 2>&1; then
    info "Detected apt/dpkg package family"
    DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null

    check_apt_packages \
        openbox xterm fonts-terminus dbus-x11 \
        libxfce4ui-utils thunar thunar-volman xfce4-appfinder xfce4-panel \
        xfce4-session xfce4-settings xfconf xfdesktop4 xfwm4 xfce4-terminal \
        xfce4-notifyd xfce4-power-manager \
        lxqt-core mate-desktop-environment

    simulate_apt Openbox openbox xterm fonts-terminus
    simulate_apt XFCE \
        dbus-x11 libxfce4ui-utils thunar thunar-volman xfce4-appfinder \
        xfce4-panel xfce4-session xfce4-settings xfconf xfdesktop4 xfwm4 \
        xfce4-terminal xfce4-notifyd xfce4-power-manager
    simulate_apt LXQt dbus-x11 lxqt-core openbox
    simulate_apt MATE mate-desktop-environment dbus-x11
else
    fail "No supported package family found"
fi

info "Rootfs compatibility probe passed"
