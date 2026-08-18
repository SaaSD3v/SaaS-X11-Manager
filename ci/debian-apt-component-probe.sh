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
    LC_ALL=C apt-cache policy "$1" |
        awk '$1 == "Candidate:" && $2 != "(none)" { found=1 } END { exit found ? 0 : 1 }'
}

emit_deb822_component_sources() {
    file=$1
    component=$2
    awk -v component="$component" '
        BEGIN { RS=""; ORS=""; emitted=0 }
        {
            n=split($0, line, "\n")
            enabled=1
            has_deb=0
            has_main=0
            has_component=0
            trusted=0

            for (i=1; i<=n; i++) {
                text=line[i]
                if (text ~ /^Enabled:[[:space:]]*no([[:space:]]|$)/) enabled=0
                if (text ~ /^Types:[[:space:]]/ &&
                    (" " text " ") ~ /[[:space:]]deb([[:space:]]|$)/) has_deb=1
                if (text ~ /^Components:[[:space:]]/) {
                    fields=text
                    sub(/^Components:[[:space:]]*/, "", fields)
                    if ((" " fields " ") ~ /[[:space:]]main([[:space:]]|$)/) has_main=1
                    if ((" " fields " ") ~ ("[[:space:]]" component "([[:space:]]|$)")) {
                        has_component=1
                    }
                }
                if (text ~ /^Signed-By:.*debian-archive-keyring\.gpg([[:space:]]|$)/) trusted=1
                if (text ~ /^URIs:[[:space:]].*(deb\.debian\.org\/debian|security\.debian\.org\/debian-security|deb\.debian\.org\/debian-security)([[:space:]]|\/|$)/) {
                    trusted=1
                }
            }

            if (enabled && has_deb && has_main && trusted && !has_component) {
                for (i=1; i<=n; i++) {
                    if (line[i] ~ /^Types:[[:space:]]/) {
                        printf "Types: deb\n"
                    } else if (line[i] ~ /^Components:[[:space:]]/) {
                        printf "Components: %s\n", component
                    } else {
                        printf "%s\n", line[i]
                    }
                }
                printf "\n"
                emitted=1
            }
        }
        END { if (!emitted) exit 2 }
    ' "$file"
}

emit_list_component_sources() {
    file=$1
    component=$2
    awk -v component="$component" '
        BEGIN { emitted=0 }
        /^[[:space:]]*deb[[:space:]]/ {
            uri_index=0
            for (i=2; i<=NF; i++) {
                if ($i ~ /^https?:\/\//) {
                    uri_index=i
                    break
                }
            }
            if (!uri_index || uri_index + 1 > NF) next

            uri=$uri_index
            trusted=(uri ~ /^https?:\/\/(deb\.debian\.org\/debian|security\.debian\.org\/debian-security|deb\.debian\.org\/debian-security)(\/|$)/)
            if (!trusted && $0 ~ /debian-archive-keyring\.gpg/) trusted=1
            if (!trusted) next

            has_main=0
            has_component=0
            for (i=uri_index+2; i<=NF; i++) {
                if ($i == "main") has_main=1
                if ($i == component) has_component=1
            }
            if (!has_main || has_component) next

            for (i=1; i<=uri_index+1; i++) {
                printf "%s%s", (i == 1 ? "" : " "), $i
            }
            printf " %s\n", component
            emitted=1
        }
        END { if (!emitted) exit 2 }
    ' "$file"
}

create_debian_component_source() {
    component=$1
    target_base="/etc/apt/sources.list.d/saas-x11-manager-$component"
    tmp=$(mktemp)
    generated=0
    : > "$tmp"

    for source_file in /etc/apt/sources.list.d/*.sources; do
        [ -f "$source_file" ] || continue
        [ "$source_file" = "$target_base.sources" ] && continue
        piece=$(mktemp)
        if emit_deb822_component_sources "$source_file" "$component" > "$piece"; then
            cat "$piece" >> "$tmp"
            generated=1
        fi
        rm -f "$piece"
    done

    if [ "$generated" -eq 1 ]; then
        cat "$tmp" > "$target_base.sources"
        rm -f "$target_base.list" "$tmp"
        return 0
    fi

    : > "$tmp"
    for source_file in /etc/apt/sources.list /etc/apt/sources.list.d/*.list; do
        [ -f "$source_file" ] || continue
        [ "$source_file" = "$target_base.list" ] && continue
        piece=$(mktemp)
        if emit_list_component_sources "$source_file" "$component" > "$piece"; then
            cat "$piece" >> "$tmp"
            generated=1
        fi
        rm -f "$piece"
    done

    [ "$generated" -eq 1 ] || {
        rm -f "$tmp"
        return 1
    }

    cat "$tmp" > "$target_base.list"
    rm -f "$target_base.sources" "$tmp"
}

validate_format_fixtures() {
    fixture_dir=$(mktemp -d)
    trap 'rm -rf "$fixture_dir"' EXIT HUP INT TERM

    cat > "$fixture_dir/debian.sources" <<'EOF'
Types: deb deb-src
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

    emit_deb822_component_sources "$fixture_dir/debian.sources" non-free > "$fixture_dir/generated.sources" ||
        fail "Could not generate supplemental deb822 fixture"
    grep -Fq 'Types: deb' "$fixture_dir/generated.sources" ||
        fail "Supplemental deb822 fixture did not restrict Types to deb"
    grep -Fq 'Components: non-free' "$fixture_dir/generated.sources" ||
        fail "Supplemental deb822 fixture did not receive non-free"
    grep -Fq 'URIs: http://deb.debian.org/debian' "$fixture_dir/generated.sources" ||
        fail "Supplemental deb822 fixture lost Debian URI"
    if grep -Fq 'example.invalid' "$fixture_dir/generated.sources"; then
        fail "Third-party deb822 source leaked into supplemental source"
    fi

    cat > "$fixture_dir/sources.list" <<'EOF'
deb http://deb.debian.org/debian stable main non-free-firmware
deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://mirror.example.invalid/debian stable main
deb https://third-party.example.invalid/repo stable main
EOF

    emit_list_component_sources "$fixture_dir/sources.list" non-free > "$fixture_dir/generated.list" ||
        fail "Could not generate supplemental one-line fixture"
    grep -Fq 'deb http://deb.debian.org/debian stable non-free' "$fixture_dir/generated.list" ||
        fail "Official one-line fixture did not generate non-free source"
    grep -Fq 'deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://mirror.example.invalid/debian stable non-free' "$fixture_dir/generated.list" ||
        fail "Archive-keyring one-line fixture did not generate non-free source"
    if grep -Fq 'third-party.example.invalid' "$fixture_dir/generated.list"; then
        fail "Third-party one-line source leaked into supplemental source"
    fi

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
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is unavailable"

DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null

snapshot=$(mktemp)
for source_file in /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list; do
    [ -f "$source_file" ] || continue
    case "$source_file" in
        /etc/apt/sources.list.d/saas-x11-manager-*) continue ;;
    esac
    sha256sum "$source_file" >> "$snapshot"
done

if apt_package_available amiwm; then
    info "amiwm candidate already available; no supplemental repository is required"
else
    create_debian_component_source non-free ||
        fail "Could not derive a trusted Debian source for non-free"
    DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null
    apt_package_available amiwm ||
        fail "amiwm still has no installable candidate after enabling Debian non-free"
    info "Supplemental Debian non-free source resolved amiwm candidate"
fi

while read -r expected_hash source_file; do
    [ -f "$source_file" ] || fail "Original Debian source disappeared: $source_file"
    actual_hash=$(sha256sum "$source_file" | awk '{ print $1 }')
    [ "$actual_hash" = "$expected_hash" ] ||
        fail "Original Debian source was modified: $source_file"
done < "$snapshot"
rm -f "$snapshot"
info "Original Debian source files remained unchanged"

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
