package com.saas.x11manager.util

/** Persistent client state shared by the HOST and NAT transports. */
internal object PulseAudioClientConfig {
    private const val STATE = "/etc/saas-x11-manager/audio"
    private const val OWNER = "SaaS X11 Manager Audio Configuration"
    private const val FAILURE = "__SAAS_AUDIO_FAILURE__:"

    fun isFailureMarker(line: String): Boolean = line.trim().startsWith(FAILURE)

    fun failureTrap(): String = """
        saas_audio_step=packages
        trap 'saas_audio_exit=${'$'}?; if [ "${'$'}saas_audio_exit" -ne 0 ]; then printf "$FAILURE%s:%s\n" "${'$'}saas_audio_step" "${'$'}saas_audio_exit" >&2; fi' EXIT
        export LC_ALL=C
    """.trimIndent()

    fun failureSummary(exitCode: Int, output: List<String>): String {
        val failure = output.lastOrNull(::isFailureMarker)
            ?.trim()?.removePrefix(FAILURE)?.split(':', limit = 2)
        val step = when (failure?.firstOrNull()) {
            "packages" -> "audio client dependencies"
            "cookie" -> "authentication cookie transfer"
            "client-config" -> "persistent client configuration"
            "root-client" -> "root audio client configuration"
            "user-client" -> "desktop user audio configuration"
            "client-auth" -> "client authentication"
            "pcm-playback" -> "PCM playback"
            else -> "container command"
        }
        val code = failure?.getOrNull(1)?.toIntOrNull() ?: exitCode
        return "$step (exit $code)"
    }

    // Init services do not read /etc/profile. Run this after HOME/USER have been
    // selected and before dropping privileges or exec'ing the desktop.
    fun sessionEnvironment(): String = """
        if [ -r $STATE/prepare-session.sh ]; then
            . $STATE/prepare-session.sh
        fi
    """.trimIndent() + "\n"

    fun install(server: String): String = """
        saas_audio_step=client-config
        export LC_ALL=C
        mkdir -p $STATE /etc/profile.d || exit 90
        chmod 755 /etc/saas-x11-manager $STATE || exit 90
        cp /root/.config/pulse/saas-audio.cookie $STATE/cookie.tmp || exit 91
        chmod 600 $STATE/cookie.tmp || exit 91
        mv $STATE/cookie.tmp $STATE/cookie || exit 91
        cat > $STATE/client.conf.tmp <<'SAAS_CLIENT'
        # $OWNER
        default-server = $server
        autospawn = no
        enable-shm = no
        SAAS_CLIENT
        chmod 644 $STATE/client.conf.tmp || exit 91
        mv $STATE/client.conf.tmp $STATE/client.conf || exit 91

        # Retired profiles can otherwise overwrite HOST with an old NAT endpoint
        # (or vice versa), depending on the login shell's glob order.
        for old in /etc/profile.d/saas-droidspaces-audio.sh /etc/profile.d/android-audio.sh; do
            if [ -f "${'$'}old" ] && grep -Eq 'SaaS (X11 Manager|DroidSpaces Audio)' "${'$'}old"; then
                rm -f "${'$'}old" || exit 92
            fi
        done
        cat > /etc/profile.d/saas-x11-audio.sh <<'SAAS_PROFILE'
        # $OWNER
        if [ -r $STATE/client.conf ] && [ -r "${'$'}HOME/.config/pulse/saas-audio.cookie" ]; then
            # Read the current endpoint from disk, including after a network-mode
            # change. Never retain DroidSpaces' inherited native socket endpoint.
            unset PULSE_SERVER
            export PULSE_CLIENTCONFIG=$STATE/client.conf
            export PULSE_COOKIE="${'$'}HOME/.config/pulse/saas-audio.cookie"
        fi
        SAAS_PROFILE
        chmod 644 /etc/profile.d/saas-x11-audio.sh || exit 92

        # Write inside a user's home as that user. The root-only cookie is passed
        # on stdin, so neither world-readable credentials nor root writes through
        # a user-controlled .config symlink are needed.
        cat > $STATE/write-client.sh <<'SAAS_WRITE_CLIENT'
        # $OWNER
        set -e
        umask 077
        saas_audio_dir="${'$'}HOME/.config/pulse"
        mkdir -p "${'$'}saas_audio_dir"
        saas_cookie_tmp=${'$'}(mktemp "${'$'}saas_audio_dir/.saas-cookie.XXXXXX")
        saas_client_tmp=''
        trap 'rm -f "${'$'}saas_cookie_tmp" "${'$'}saas_client_tmp"' EXIT HUP INT TERM
        cat > "${'$'}saas_cookie_tmp"
        [ "${'$'}(wc -c < "${'$'}saas_cookie_tmp" | tr -d ' ')" = 256 ]
        chmod 600 "${'$'}saas_cookie_tmp"
        mv "${'$'}saas_cookie_tmp" "${'$'}saas_audio_dir/saas-audio.cookie"
        saas_audio_client="${'$'}saas_audio_dir/client.conf"
        if [ -f "${'$'}saas_audio_client" ] &&
           ! grep -Fq '$OWNER' "${'$'}saas_audio_client" &&
           [ ! -e "${'$'}saas_audio_client.saas-x11-manager.bak" ]; then
            cp -p "${'$'}saas_audio_client" "${'$'}saas_audio_client.saas-x11-manager.bak"
        fi
        saas_client_tmp=${'$'}(mktemp "${'$'}saas_audio_dir/.saas-client.XXXXXX")
        cat $STATE/client.conf > "${'$'}saas_client_tmp"
        printf 'cookie-file = %s\n' "${'$'}saas_audio_dir/saas-audio.cookie" >> "${'$'}saas_client_tmp"
        chmod 600 "${'$'}saas_client_tmp"
        mv "${'$'}saas_client_tmp" "${'$'}saas_audio_client"
        SAAS_WRITE_CLIENT
        chmod 644 $STATE/write-client.sh || exit 93

        cat > $STATE/prepare-session.sh <<'SAAS_SESSION'
        # $OWNER
        if [ "${'$'}(id -u)" = 0 ] && [ -f $STATE/cookie ]; then
            saas_audio_user=${'$'}{USER:-root}
            saas_audio_uid=${'$'}(id -u "${'$'}saas_audio_user") || return 1
            if [ "${'$'}saas_audio_uid" = 0 ]; then
                /bin/sh $STATE/write-client.sh < $STATE/cookie || return 1
            else
                su -s /bin/sh "${'$'}saas_audio_user" -c '/bin/sh $STATE/write-client.sh' < $STATE/cookie || return 1
            fi
        fi
        . /etc/profile.d/saas-x11-audio.sh
        SAAS_SESSION
        chmod 644 $STATE/prepare-session.sh || exit 93

        # Configure root and the actual graphical account. An account created by
        # the next launcher is handled by the same helper after user creation.
        saas_audio_step=root-client
        (
            export HOME=/root USER=root
            . $STATE/prepare-session.sh
        ) || exit 94
        selected=${'$'}(sed -n 's/^user=//p' /etc/saas-x11-manager/session-user 2>/dev/null | head -n 1)
        if [ -n "${'$'}selected" ] && [ "${'$'}selected" != root ] && id "${'$'}selected" >/dev/null 2>&1; then
            saas_audio_step=user-client
            selected_home=${'$'}(awk -F: -v u="${'$'}selected" '${'$'}1 == u { print ${'$'}6; exit }' /etc/passwd)
            case "${'$'}selected_home" in /*) ;; *) exit 94 ;; esac
            (
                export HOME="${'$'}selected_home" USER="${'$'}selected"
                . $STATE/prepare-session.sh
            ) || exit 94
            selected_info=${'$'}(su -s /bin/sh "${'$'}selected" -c '. /etc/profile.d/saas-x11-audio.sh; LC_ALL=C timeout 5 pactl info') || exit 95
            printf '%s\n' "${'$'}selected_info"
            printf '%s\n' "${'$'}selected_info" | grep -Fxq 'Server String: $server' || exit 95
        fi
        saas_audio_step=client-auth
        HOME=/root
        . /etc/profile.d/saas-x11-audio.sh
        LC_ALL=C timeout 5 pactl info || exit 95
        saas_audio_step=pcm-playback
        ${playbackProbe(server, "/root/.config/pulse/saas-audio.cookie", "$STATE/client.conf").prependIndent("        ")} || exit 96
        printf '%s\n' __SAAS_AUDIO_PCM_DRAINED__
    """.trimIndent()

    /** A real finite stream must drain; a responsive control socket is insufficient. */
    fun playbackProbe(server: String, cookie: String, clientConfig: String): String = """
        dd if=/dev/zero bs=9600 count=5 2>/dev/null |
            PULSE_SERVER='$server' PULSE_COOKIE='$cookie' PULSE_CLIENTCONFIG='$clientConfig' \
            timeout 5 pacat --playback --raw --format=s16le --rate=48000 --channels=2 --latency-msec=50 >/dev/null 2>&1
    """.trimIndent()

    fun cleanup(root: String): String = """
        audio_root='${root.replace("'", "'\\''")}'
        while IFS=: read -r name password uid gid comment home shell; do
            case "${'$'}home" in /root|'') continue ;; /*) ;; *) continue ;; esac
            client="${'$'}audio_root${'$'}home/.config/pulse/client.conf"
            if [ -f "${'$'}client" ] && grep -Fq '$OWNER' "${'$'}client"; then
                if [ -f "${'$'}client.saas-x11-manager.bak" ]; then
                    mv "${'$'}client.saas-x11-manager.bak" "${'$'}client" || exit 1
                else
                    rm -f "${'$'}client" || exit 1
                fi
                rm -f "${'$'}audio_root${'$'}home/.config/pulse/saas-audio.cookie" || exit 1
            fi
        done < "${'$'}audio_root/etc/passwd"
        if [ -f "${'$'}audio_root$STATE/client.conf" ] &&
           grep -Fq '$OWNER' "${'$'}audio_root$STATE/client.conf"; then
            rm -f "${'$'}audio_root$STATE/client.conf" "${'$'}audio_root$STATE/cookie" \
                "${'$'}audio_root$STATE/prepare-session.sh" "${'$'}audio_root$STATE/write-client.sh" || exit 1
        fi
    """.trimIndent() + "\n"
}
