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

apt_package_available() {
    LC_ALL=C apt-cache policy "$1" | \
        awk '$1 == "Candidate:" && $2 != "(none)" { found=1 } END { exit found ? 0 : 1 }'
}

append_component_deb822_file() {
    file=$1
    component=$2
    tmp=$(mktemp)

    if awk -v component="$component" '
        BEGIN { RS=""; ORS="\n\n"; changed=0 }
        {
            stanza=$0
            n=split(stanza, line, "\n")
            enabled=1
            has_deb=0
            has_main=0
            has_component=0
            trusted=0
            components_line=0

            for (i=1; i<=n; i++) {
                text=line[i]
                if (text ~ /^Enabled:[[:space:]]*no([[:space:]]|$)/) enabled=0
                if (text ~ /^Types:[[:space:]]/ &&
                    (" " text " ") ~ /[[:space:]]deb([[:space:]]|$)/) has_deb=1
                if (text ~ /^Components:[[:space:]]/) {
                    components_line=i
                    fields=text
                    sub(/^Components:[[:space:]]*/, "", fields)
                    if ((" " fields " ") ~ /[[:space:]]main([[:space:]]|$)/) has_main=1
                    needle=" " component " "
                    if ((" " fields " ") ~ needle) has_component=1
                }
                if (text ~ /^Signed-By:.*debian-archive-keyring\.gpg([[:space:]]|$)/) trusted=1
                if (text ~ /^URIs:[[:space:]].*(deb\.debian\.org\/debian|security\.debian\.org\/debian-security|deb\.debian\.org\/debian-security)([[:space:]]|\/|$)/) trusted=1
            }

            if (enabled && has_deb && has_main && trusted && !has_component && components_line > 0) {
                line[components_line]=line[components_line] " " component
                changed=1
            }

            for (i=1; i<=n; i++) print line[i]
            printf "\n"
        }
        END { if (!changed) exit 2 }
    ' "$file" > "$tmp"; then
        cat "$tmp" > "$file"
        rm -f "$tmp"
        return 0
    fi

    rc=$?
    rm -f "$tmp"
    [ "$rc" -eq 2 ] && return 1
    return "$rc"
}

append_component_list_file() {
    file=$1
    component=$2
    tmp=$(mktemp)

    if awk -v component="$component" '
        BEGIN { changed=0 }
        {
            raw=$0
            if (raw !~ /^[[:space:]]*deb(-src)?[[:space:]]/) {
                print raw
                next
            }

            trusted=0
            has_main=0
            has_component=0
            for (i=1; i<=NF; i++) {
                if ($i ~ /debian-archive-keyring\.gpg/) trusted=1
                if ($i ~ /^https?:\/\/(deb\.debian\.org\/debian|security\.debian\.org\/debian-security|deb\.debian\.org\/debian-security)(\/|$)/) trusted=1
                if ($i == "main") has_main=1
                if ($i == component) has_component=1
            }

            if (trusted && has_main && !has_component) {
                print raw " " component
                changed=1
            } else {
                print raw
            }
        }
        END { if (!changed) exit 2 }
    ' "$file" > "$tmp"; then
        cat "$tmp" > "$file"
        rm -f "$tmp"
        return 0
    fi

    rc=$?
    rm -f "$tmp"
    [ "$rc" -eq 2 ] && return 1
    return "$rc"
}

enable_debian_component() {
    component=$1
    changed=0

    if [ -f /etc/apt/sources.list.d/debian.sources ]; then
        if append_component_deb822_file /etc/apt/sources.list.d/debian.sources "$component"; then
            changed=1
        fi
    fi

    for source_file in /etc/apt/sources.list.d/*.sources; do
        [ -f "$source_file" ] || continue
        [ "$source_file" = /etc/apt/sources.list.d/debian.sources ] && continue
        if append_component_deb822_file "$source_file" "$component"; then
            changed=1
        fi
    done

    if [ -f /etc/apt/sources.list ]; then
        if append_component_list_file /etc/apt/sources.list "$component"; then
            changed=1
        fi
    fi

    for source_file in /etc/apt/sources.list.d/*.list; do
        [ -f "$source_file" ] || continue
        if append_component_list_file "$source_file" "$component"; then
            changed=1
        fi
    done

    [ "$changed" -eq 1 ] || fail "Could not identify a trusted Debian archive source to enable $component"
}

validate_format_fixtures() {
    fixture_dir=$(mktemp -d)
    trap 'rm -rf "$fixture_dir"' EXIT HUP INT TERM

    cat > "$fixture_dir/debian.sources" <<'EOF'
Types: deb
URIs: http://deb.debian.org/debian
Suites: stable stable-updates
Components: main non-free-firmware
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg

Types: deb
URIs: https://example.invalid/repository
Suites: stable
Components: main
Signed-By: /usr/share/keyrings/example.gpg
EOF

    append_component_deb822_file "$fixture_dir/debian.sources" non-free ||
        fail "deb822 fixture was not updated"
    grep -Fq 'Components: main non-free-firmware non-free' "$fixture_dir/debian.sources" ||
        fail "deb822 fixture did not receive non-free"
    grep -A4 -F 'https://example.invalid/repository' "$fixture_dir/debian.sources" |
        grep -Fq 'Components: main' ||
        fail "third-party deb822 fixture was unexpectedly changed"

    cat > "$fixture_dir/sources.list" <<'EOF'
deb http://deb.debian.org/debian stable main non-free-firmware
deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://mirror.example.invalid/debian stable main
deb https://third-party.example.invalid/repo stable main
EOF

    append_component_list_file "$fixture_dir/sources.list" non-free ||
        fail "one-line fixture was not updated"
    grep -Fq 'deb http://deb.debian.org/debian stable main non-free-firmware non-free' "$fixture_dir/sources.list" ||
        fail "official one-line fixture did not receive non-free"
    grep -Fq 'deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://mirror.example.invalid/debian stable main non-free' "$fixture_dir/sources.list" ||
        fail "archive-keyring one-line fixture did not receive non-free"
    grep -Fq 'deb https://third-party.example.invalid/repo stable main' "$fixture_dir/sources.list" ||
        fail "third-party one-line fixture was unexpectedly changed"

    rm -rf "$fixture_dir"
    trap - EXIT HUP INT TERM
    info "Debian source format fixtures passed"
}

validate_format_fixtures

[ -r /etc/os-release ] || fail "/etc/os-release is unavailable"
# shellcheck disable=SC1091
. /etc/os-release
[ "${ID:-}" = debian ] || fail "Repository component probe requires ID=debian"

command -v apt-get >/dev/null 2>&1 || fail "apt-get is unavailable"
command -v apt-cache >/dev/null 2>&1 || fail "apt-cache is unavailable"

DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null

if apt_package_available amiwm; then
    info "amiwm candidate already available; no repository mutation required"
else
    enable_debian_component non-free
    DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null
    apt_package_available amiwm ||
        fail "amiwm still has no installable candidate after enabling Debian non-free"
    info "Debian non-free enabled and amiwm candidate resolved"
fi

simulation=$(mktemp)
trap 'rm -f "$simulation"' EXIT HUP INT TERM
LC_ALL=C DEBIAN_FRONTEND=noninteractive \
    apt-get -s --no-install-recommends install amiwm >"$simulation" 2>&1 || {
        cat "$simulation" >&2
        fail "amiwm dependency simulation failed"
    }

blocked=$(awk '$1 == "Inst" { print $2 }' "$simulation" |
    grep -E '^(xorg|xserver-xorg.*|gdm3|lightdm|sddm|lxdm|xdm|slim|nodm|pulseaudio|pipewire-pulse|pipewire-audio)$' || true)
[ -z "$blocked" ] || {
    cat "$simulation" >&2
    fail "amiwm would install host-owned infrastructure: $blocked"
}

rm -f "$simulation"
trap - EXIT HUP INT TERM
info "Debian non-free capability probe passed"
