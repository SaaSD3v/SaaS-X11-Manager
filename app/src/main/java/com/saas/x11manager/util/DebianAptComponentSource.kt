package com.saas.x11manager.util

/**
 * Builds a shell command that adds one Debian archive component through a
 * SaaS-X11-Manager-owned supplemental source file. Existing user/system APT
 * source files are never edited in place.
 *
 * The command discovers the configured Debian archive shape at runtime. It
 * supports deb822 .sources files and traditional one-line .list files without
 * relying on VERSION_ID or a codename.
 */
internal object DebianAptComponentSource {

    private val safeComponent = Regex("[a-z0-9][a-z0-9-]*")

    fun commandFor(component: String): String {
        require(safeComponent.matches(component)) { "Unsafe Debian component name" }
        return TEMPLATE
            .replace("__COMPONENT__", component)
            .replace("__DOLLAR__", "$")
            .trim()
    }

    private val TEMPLATE = """
        component='__COMPONENT__'
        target_base="/etc/apt/sources.list.d/saas-x11-manager-__DOLLAR__component"

        emit_deb822_component_sources() {
            file=__DOLLAR__1
            component=__DOLLAR__2
            awk -v component="__DOLLAR__component" '
                BEGIN { RS=""; ORS=""; emitted=0 }
                {
                    n=split(__DOLLAR__0, line, "\n")
                    enabled=1
                    has_deb=0
                    has_main=0
                    has_component=0
                    trusted=0
                    for (i=1; i<=n; i++) {
                        text=line[i]
                        if (text ~ /^Enabled:[[:space:]]*no([[:space:]]|__DOLLAR__)/) enabled=0
                        if (text ~ /^Types:[[:space:]]/ && (" " text " ") ~ /[[:space:]]deb([[:space:]]|__DOLLAR__)/) has_deb=1
                        if (text ~ /^Components:[[:space:]]/) {
                            fields=text
                            sub(/^Components:[[:space:]]*/, "", fields)
                            if ((" " fields " ") ~ /[[:space:]]main([[:space:]]|__DOLLAR__)/) has_main=1
                            if ((" " fields " ") ~ ("[[:space:]]" component "([[:space:]]|__DOLLAR__)")) has_component=1
                        }
                        if (text ~ /^Signed-By:.*debian-archive-keyring\.gpg([[:space:]]|__DOLLAR__)/) trusted=1
                        if (text ~ /^URIs:[[:space:]].*(deb\.debian\.org\/debian|security\.debian\.org\/debian-security|deb\.debian\.org\/debian-security)([[:space:]]|\/|__DOLLAR__)/) trusted=1
                    }
                    if (enabled && has_deb && has_main && trusted && !has_component) {
                        for (i=1; i<=n; i++) {
                            if (line[i] ~ /^Types:[[:space:]]/) printf "Types: deb\n"
                            else if (line[i] ~ /^Components:[[:space:]]/) printf "Components: %s\n", component
                            else printf "%s\n", line[i]
                        }
                        printf "\n"
                        emitted=1
                    }
                }
                END { if (!emitted) exit 2 }
            ' "__DOLLAR__file"
        }

        emit_list_component_sources() {
            file=__DOLLAR__1
            component=__DOLLAR__2
            awk -v component="__DOLLAR__component" '
                BEGIN { emitted=0 }
                /^[[:space:]]*deb[[:space:]]/ {
                    uri_index=0
                    for (i=2; i<=NF; i++) {
                        if (__DOLLAR__i ~ /^https?:\/\//) { uri_index=i; break }
                    }
                    if (!uri_index || uri_index + 1 > NF) next
                    uri=__DOLLAR__uri_index
                    trusted=(uri ~ /^https?:\/\/(deb\.debian\.org\/debian|security\.debian\.org\/debian-security|deb\.debian\.org\/debian-security)(\/|__DOLLAR__)/)
                    if (!trusted && __DOLLAR__0 ~ /debian-archive-keyring\.gpg/) trusted=1
                    if (!trusted) next
                    has_main=0
                    has_component=0
                    for (i=uri_index+2; i<=NF; i++) {
                        if (__DOLLAR__i == "main") has_main=1
                        if (__DOLLAR__i == component) has_component=1
                    }
                    if (!has_main || has_component) next
                    for (i=1; i<=uri_index+1; i++) printf "%s%s", (i == 1 ? "" : " "), __DOLLAR__i
                    printf " %s\n", component
                    emitted=1
                }
                END { if (!emitted) exit 2 }
            ' "__DOLLAR__file"
        }

        tmp=__DOLLAR__(mktemp) || exit 1
        generated=0
        : > "__DOLLAR__tmp"
        for source_file in /etc/apt/sources.list.d/*.sources; do
            [ -f "__DOLLAR__source_file" ] || continue
            [ "__DOLLAR__source_file" = "__DOLLAR__target_base.sources" ] && continue
            piece=__DOLLAR__(mktemp) || exit 1
            if emit_deb822_component_sources "__DOLLAR__source_file" "__DOLLAR__component" > "__DOLLAR__piece"; then
                cat "__DOLLAR__piece" >> "__DOLLAR__tmp"
                generated=1
            fi
            rm -f "__DOLLAR__piece"
        done
        if [ "__DOLLAR__generated" -eq 1 ]; then
            cat "__DOLLAR__tmp" > "__DOLLAR__target_base.sources"
            rm -f "__DOLLAR__target_base.list" "__DOLLAR__tmp"
            exit 0
        fi

        : > "__DOLLAR__tmp"
        for source_file in /etc/apt/sources.list /etc/apt/sources.list.d/*.list; do
            [ -f "__DOLLAR__source_file" ] || continue
            [ "__DOLLAR__source_file" = "__DOLLAR__target_base.list" ] && continue
            piece=__DOLLAR__(mktemp) || exit 1
            if emit_list_component_sources "__DOLLAR__source_file" "__DOLLAR__component" > "__DOLLAR__piece"; then
                cat "__DOLLAR__piece" >> "__DOLLAR__tmp"
                generated=1
            fi
            rm -f "__DOLLAR__piece"
        done
        if [ "__DOLLAR__generated" -eq 1 ]; then
            cat "__DOLLAR__tmp" > "__DOLLAR__target_base.list"
            rm -f "__DOLLAR__target_base.sources" "__DOLLAR__tmp"
            exit 0
        fi

        rm -f "__DOLLAR__tmp"
        echo 'Could not derive a trusted Debian archive source for __COMPONENT__.' >&2
        exit 1
    """.trimIndent()
}
