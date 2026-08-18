#!/bin/sh
set -eu
set -f

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
ROOTFS_ID=${ID:-unknown}

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

AUDIT_SAFE=0
AUDIT_UNAVAILABLE=0
AUDIT_BLOCKED=0
AUDIT_UNRESOLVABLE=0
AUDIT_PROCESSED=0
AUDIT_REPORT=''

record_audit() {
    platform=$1
    session_name=$2
    status=$3
    detail=$4
    printf '%s\t%s\t%s\t%s\t%s\n' \
        "$ROOTFS_ID" "$platform" "$session_name" "$status" "$detail" >> "$AUDIT_REPORT"
}

audit_apt_plan() {
    session_name=$1
    shift
    missing=''
    for package_name in "$@"; do
        if ! apt-cache show "$package_name" >/dev/null 2>&1; then
            missing="${missing}${missing:+,}$package_name"
        fi
    done
    if [ -n "$missing" ]; then
        AUDIT_UNAVAILABLE=$((AUDIT_UNAVAILABLE + 1))
        record_audit apt "$session_name" UNAVAILABLE "$missing"
        return 0
    fi

    simulation=$(mktemp)
    if ! LC_ALL=C DEBIAN_FRONTEND=noninteractive \
        apt-get -s --no-install-recommends install "$@" >"$simulation" 2>&1; then
        rm -f "$simulation"
        AUDIT_UNRESOLVABLE=$((AUDIT_UNRESOLVABLE + 1))
        record_audit apt "$session_name" UNRESOLVABLE dependency-simulation
        return 0
    fi

    removed=$(awk '$1 == "Remv" { print $2 }' "$simulation" | tr '\n' ',' | sed 's/,$//')
    if [ -n "$removed" ]; then
        rm -f "$simulation"
        AUDIT_BLOCKED=$((AUDIT_BLOCKED + 1))
        record_audit apt "$session_name" BLOCKED "removes:$removed"
        return 0
    fi

    blocked=$(awk '$1 == "Inst" { print $2 }' "$simulation" | \
        grep -Ex "$APT_BLOCKED" | tr '\n' ',' | sed 's/,$//' || true)
    rm -f "$simulation"
    if [ -n "$blocked" ]; then
        AUDIT_BLOCKED=$((AUDIT_BLOCKED + 1))
        record_audit apt "$session_name" BLOCKED "host-infra:$blocked"
        return 0
    fi

    AUDIT_SAFE=$((AUDIT_SAFE + 1))
    record_audit apt "$session_name" SAFE packages-and-transaction
}

audit_apk_plan() {
    session_name=$1
    shift
    missing=''
    for package_name in "$@"; do
        if ! apk search -e "$package_name" >/dev/null 2>&1; then
            missing="${missing}${missing:+,}$package_name"
        fi
    done
    if [ -n "$missing" ]; then
        AUDIT_UNAVAILABLE=$((AUDIT_UNAVAILABLE + 1))
        record_audit apk "$session_name" UNAVAILABLE "$missing"
        return 0
    fi

    simulation=$(mktemp)
    if ! LC_ALL=C apk --simulate add "$@" >"$simulation" 2>&1; then
        rm -f "$simulation"
        AUDIT_UNRESOLVABLE=$((AUDIT_UNRESOLVABLE + 1))
        record_audit apk "$session_name" UNRESOLVABLE dependency-simulation
        return 0
    fi

    blocked=$(grep -E "$APK_BLOCKED" "$simulation" | tr '\n' ';' | sed 's/;$//' || true)
    rm -f "$simulation"
    if [ -n "$blocked" ]; then
        AUDIT_BLOCKED=$((AUDIT_BLOCKED + 1))
        record_audit apk "$session_name" BLOCKED "host-infra:$blocked"
        return 0
    fi

    AUDIT_SAFE=$((AUDIT_SAFE + 1))
    record_audit apk "$session_name" SAFE packages-and-transaction
}

audit_catalog() {
    family=$1
    catalog=${GRAPHIC_SESSION_PLAN_CATALOG:-}
    [ -n "$catalog" ] || return 0
    [ -r "$catalog" ] || fail "Graphic session plan catalog is unreadable: $catalog"

    AUDIT_REPORT=${ROOTFS_AUDIT_REPORT:-/tmp/rootfs-session-catalog.tsv}
    : > "$AUDIT_REPORT"
    printf 'rootfs\tplatform\tsession\tstatus\tdetail\n' >> "$AUDIT_REPORT"

    tab=$(printf '\t')
    while IFS="$tab" read -r plan_platform session_name package_list; do
        [ -n "$plan_platform" ] || continue
        [ "$plan_platform" = "$family" ] || continue
        AUDIT_PROCESSED=$((AUDIT_PROCESSED + 1))
        set -- $package_list
        if [ "$family" = apt ]; then
            audit_apt_plan "$session_name" "$@"
        else
            audit_apk_plan "$session_name" "$@"
        fi
    done < "$catalog"

    [ "$AUDIT_PROCESSED" -gt 0 ] || fail "No $family plans were found in the exported catalog"
    info "Catalog audit: $AUDIT_SAFE safe, $AUDIT_UNAVAILABLE unavailable, $AUDIT_BLOCKED blocked, $AUDIT_UNRESOLVABLE unresolvable"
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
    audit_catalog apk
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
    simulate_apt LXDE-direct-candidate \
        lxsession lxde-common openbox lxpanel pcmanfm lxterminal dbus-x11
    audit_catalog apt
else
    fail "No supported package family found"
fi

info "Rootfs compatibility probe passed"
