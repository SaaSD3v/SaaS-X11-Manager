#!/bin/sh
set -eu

fail() {
    echo "[-] $*" >&2
    exit 1
}

info() {
    echo "[+] $*"
}

arch=$(uname -m)
case "$arch" in
    aarch64|arm64) ;;
    *) fail "Wayland compatibility audit must run as arm64/aarch64, got $arch" ;;
esac
info "Architecture: $arch"

if [ -r /etc/os-release ]; then
    # shellcheck disable=SC1091
    . /etc/os-release
fi
info "Rootfs: ${ID:-unknown} ${VERSION_ID:-rolling/current}"

if command -v apk >/dev/null 2>&1; then
    apk update >/dev/null

    if ! grep -Eq '^[[:space:]]*[^#[:space:]].*/community([[:space:]]|$)' /etc/apk/repositories; then
        main_repo=$(awk '/^[[:space:]]*#/ {next} /\/main([[:space:]]*)$/ {print; exit}' /etc/apk/repositories)
        [ -n "$main_repo" ] || fail "Could not derive Alpine community repository"
        printf '%s\n' "${main_repo%/main}/community" >> /etc/apk/repositories
        apk update >/dev/null
    fi

    check_apk() {
        session=$1
        shift
        for pkg in "$@"; do
            apk search -e "$pkg" >/dev/null 2>&1 || fail "$session package unavailable on arm64: $pkg"
        done
        apk --simulate add "$@" >/dev/null 2>&1 || fail "$session transaction does not resolve on arm64"
        info "$session arm64 packages resolve"
    }

    check_apk Weston weston-desktop-x11
    check_apk Labwc labwc xwayland foot
    check_apk Sway sway xwayland foot
    check_apk Cage cage xwayland foot

    tag=saas_testing
    if ! grep -Eq "^[[:space:]]*@$tag[[:space:]]+[^#[:space:]]+/edge/testing/?[[:space:]]*$" /etc/apk/repositories; then
        remote_repo=$(grep -E '^[[:space:]]*https?://[^[:space:]]+/(main|community)/?[[:space:]]*$' /etc/apk/repositories | head -n 1)
        [ -n "$remote_repo" ] || fail "Could not derive Alpine testing mirror"
        base_repo=$(printf '%s\n' "$remote_repo" | sed -E 's#/[^/]+/(main|community)/?$##')
        printf '@%s %s\n' "$tag" "${base_repo%/}/edge/testing" >> /etc/apk/repositories
        apk update >/dev/null
    fi
    apk search -e wayfire >/dev/null 2>&1 || fail "Wayfire unavailable from Alpine edge/testing on arm64"
    apk --simulate add "wayfire@$tag" xwayland foot >/dev/null 2>&1 || \
        fail "Wayfire edge/testing transaction does not resolve on arm64"
    info "Wayfire arm64 testing packages resolve"

elif command -v apt-get >/dev/null 2>&1 && command -v apt-cache >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update >/dev/null

    apt_available() {
        LC_ALL=C apt-cache policy "$1" | \
            awk '$1 == "Candidate:" && $2 != "(none)" { found=1 } END { exit found ? 0 : 1 }'
    }

    enable_ubuntu_universe_if_needed() {
        [ "${ID:-}" = ubuntu ] || return 0
        if command -v add-apt-repository >/dev/null 2>&1; then
            add-apt-repository -y universe >/dev/null 2>&1 || true
            apt-get update >/dev/null
            return 0
        fi
        apt-get install -y --no-install-recommends software-properties-common >/dev/null 2>&1 || true
        if command -v add-apt-repository >/dev/null 2>&1; then
            add-apt-repository -y universe >/dev/null 2>&1 || true
            apt-get update >/dev/null
        fi
    }

    all_wayland_packages='weston xwayland labwc foot sway cage wayfire'
    missing=0
    for pkg in $all_wayland_packages; do
        if ! apt_available "$pkg"; then
            missing=1
            break
        fi
    done
    if [ "$missing" -eq 1 ]; then
        enable_ubuntu_universe_if_needed
    fi

    check_apt() {
        session=$1
        shift
        for pkg in "$@"; do
            apt_available "$pkg" || fail "$session package unavailable on arm64: $pkg"
        done
        apt-get -s --no-install-recommends install "$@" >/dev/null 2>&1 || \
            fail "$session transaction does not resolve on arm64"
        info "$session arm64 packages resolve"
    }

    check_apt Weston weston xwayland
    check_apt Labwc labwc xwayland foot
    check_apt Sway sway xwayland foot
    check_apt Cage cage xwayland foot
    check_apt Wayfire wayfire xwayland foot
else
    fail "No supported package family found"
fi

info "ARM64 Wayland package audit passed"
