package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** NAT audio using the real Termux app identity (UID + supplementary GIDs). */
object PulseAudioNatIdentityTransport {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val INET_GID = 3003
    private const val BASE_PORT = 4713
    private const val MAX_PORT_SHIFT = 12
    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val BEGIN = "# BEGIN $MANAGED"
    private const val END = "# END $MANAGED"

    private const val STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val PULSE_HOME = "$STATE/pulse-home"
    private const val PULSE_CONFIG = "$PULSE_HOME/.config/pulse"
    private const val PULSE_RUNTIME = "$STATE/runtime"
    private const val PULSE_STATE = "$STATE/state"
    private const val COOKIE = "$STATE/transport.cookie"
    private const val CONTROL = "$STATE/control.sock"
    private const val LISTENERS = "$STATE/listeners"
    private const val PID_FILE = "$STATE/pulseaudio.pid"
    private const val LOG_FILE = "$STATE/pulseaudio.log"

    private enum class Mode { SU_GROUPS, SETPRIV, LEGACY }
    private data class Runtime(val uid: Int, val groups: List<Int>, val mode: Mode)
    private data class Transport(val ip: String, val port: Int, val sink: String) {
        val server get() = "tcp:$ip:$port"
    }

    suspend fun prepareBeforeGraphicalStart(containerName: String, logger: ContainerLogger? = null): Boolean =
        withContext(Dispatchers.IO) {
            val context = X11Application.instance
            if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true
            val info = ContainerManager.getContainerInfo(containerName) ?: return@withContext false
            if (info.netMode.trim().lowercase() != "nat") return@withContext true

            val rt = runtime() ?: return@withContext fail(logger, "Termux app identity could not be resolved")
            if (INET_GID !in rt.groups || rt.mode == Mode.LEGACY) {
                return@withContext fail(logger, "Root provider cannot reproduce Termux inet supplementary group")
            }
            logger?.i("[CTX] NAT audio identity: Termux inet GID $INET_GID via ${modeName(rt.mode)}")

            val sink = coreSink(rt) ?: return@withContext fail(logger, "Manager audio core is unavailable")
            if (coreHasInet(rt)) {
                logger?.i("[+] Manager audio core already has Termux network identity")
                return@withContext true
            }

            logger?.i("[*] Migrating Manager audio core to Termux network identity...")
            logger?.i("[CTX] Only Manager PulseAudio is restarted; container and X11/VNC are unchanged")
            if (!stopCore(rt) || !startCore(rt, sink) || !waitCore(rt, sink) || !coreHasInet(rt)) {
                pulseTail(rt, logger)
                return@withContext fail(logger, "Manager audio core identity migration failed")
            }
            logger?.i("[+] Manager audio core now carries Termux inet GID $INET_GID")
            true
        }

    suspend fun finalizeAfterContainerReady(containerName: String, logger: ContainerLogger? = null): Boolean =
        withContext(Dispatchers.IO) {
            val context = X11Application.instance
            if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true
            val info = ContainerManager.getContainerInfo(containerName) ?: return@withContext false
            if (info.netMode.trim().lowercase() != "nat") return@withContext true
            if (!info.isRunning) return@withContext fail(logger, "Container $containerName is not running")

            val rt = runtime() ?: return@withContext fail(logger, "Termux app identity could not be resolved")
            if (INET_GID !in rt.groups || rt.mode == Mode.LEGACY) return@withContext fail(logger, "Termux inet identity is unavailable")
            var sink = coreSink(rt) ?: return@withContext fail(logger, "Manager audio core is unavailable")
            if (!coreHasInet(rt)) {
                logger?.w("[!] Repairing Manager audio core inet identity once")
                if (!stopCore(rt) || !startCore(rt, sink) || !waitCore(rt, sink) || !coreHasInet(rt)) {
                    pulseTail(rt, logger)
                    return@withContext fail(logger, "Manager audio core inet repair failed")
                }
                sink = coreSink(rt) ?: sink
            }

            val ip = natGateway(info, logger) ?: return@withContext fail(logger, "NAT gateway discovery failed")
            logger?.i("[CTX] Audio net_mode: nat")
            logger?.i("[CTX] Audio host endpoint: $ip (port selected automatically)")
            val port = chooseListener(rt, ip, logger) ?: return@withContext fail(logger, "Authenticated NAT listener could not be created")
            val transport = Transport(ip, port, sink)

            if (!verifyClient(containerName, transport.server) && !installClient(rt, containerName, transport.server, logger)) {
                return@withContext fail(logger, "Container NAT audio client configuration failed")
            }
            FixSettings.setPulseAudioApplied(context, containerName, true)
            logger?.i("[+] Audio ready (${transport.sink}, ${transport.server})")
            true
        }

    private suspend fun fail(logger: ContainerLogger?, msg: String): Boolean {
        logger?.w("[!] $msg")
        logger?.w("[!] NAT audio failed fast; graphical startup will continue")
        return false
    }

    private fun modeName(mode: Mode) = when (mode) {
        Mode.SU_GROUPS -> "su -g/-G"
        Mode.SETPRIV -> "BusyBox setpriv"
        Mode.LEGACY -> "legacy su"
    }

    private fun runtime(): Runtime? {
        val bb = "${Constants.DS_BASE_DIR}/bin/busybox"
        val q = """
            home=${quote(TERMUX_HOME)}; pkg=${quote(TERMUX_PACKAGE)}; bb=${quote(bb)}
            test -x ${quote("$TERMUX_PREFIX/bin/sh")} || exit 1
            uid=${'$'}(stat -c '%u' "${'$'}home" 2>/dev/null || toybox stat -c '%u' "${'$'}home" 2>/dev/null)
            case "${'$'}uid" in ''|*[!0-9]*) exit 2;; esac
            gs=''
            if [ -r /data/system/packages.list ]; then
              l=${'$'}(grep -F "${'$'}pkg " /data/system/packages.list 2>/dev/null | sed -n '1p')
              [ "${'$'}(printf '%s\n' "${'$'}l" | awk '{print ${'$'}2}')" = "${'$'}uid" ] && gs=${'$'}(printf '%s\n' "${'$'}l" | awk '{print ${'$'}6}' | tr ',' ' ')
            fi
            case " ${'$'}gs " in *" $INET_GID "*) :;; *)
              if dumpsys package "${'$'}pkg" 2>/dev/null | grep -Eq 'android\.permission\.INTERNET:[[:space:]]+granted=true'; then gs="${'$'}gs $INET_GID"; fi;;
            esac
            gs=${'$'}(printf '%s\n' ${'$'}gs | awk '${'$'}1~/^[0-9]+${'$'}/&&!a[${'$'}1]++{printf "%s ",${'$'}1}' | sed 's/ *${'$'}//')
            csv=${'$'}(printf '%s' "${'$'}gs" | tr ' ' ',')
            if su --help 2>&1 | grep -q -- '--supp-group'; then mode=su
            elif [ -x "${'$'}bb" ] && "${'$'}bb" setpriv --help 2>&1 | grep -q -- '--groups'; then mode=setpriv
            else mode=legacy; fi
            printf '%s|%s|%s\n' "${'$'}uid" "${'$'}mode" "${'$'}csv"
        """.trimIndent()
        return try {
            val r = Shell.cmd(q).exec(); if (!r.isSuccess) return null
            val p = r.out.firstOrNull()?.trim()?.split('|', limit = 3) ?: return null
            if (p.size != 3) return null
            val uid = p[0].toIntOrNull() ?: return null
            val mode = when (p[1]) { "su" -> Mode.SU_GROUPS; "setpriv" -> Mode.SETPRIV; else -> Mode.LEGACY }
            val groups = p[2].split(',').mapNotNull { it.toIntOrNull() }.filter { it > 0 && it != uid }.distinct()
            Runtime(uid, groups, mode)
        } catch (_: Exception) { null }
    }

    private fun wrapped(command: String) = "export HOME=${quote(TERMUX_HOME)}; export PREFIX=${quote(TERMUX_PREFIX)}; export TMPDIR=${quote("$TERMUX_PREFIX/tmp")}; export PATH=${quote("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}; $command"

    private fun identity(rt: Runtime, command: String): String {
        val body = wrapped(command)
        return when (rt.mode) {
            Mode.SU_GROUPS -> buildString {
                append("su -g ${rt.uid}"); rt.groups.forEach { append(" -G $it") }
                append(" -c ${quote(body)} ${rt.uid}")
            }
            Mode.SETPRIV -> {
                val bb = "${Constants.DS_BASE_DIR}/bin/busybox"
                "${quote(bb)} setpriv --reuid ${rt.uid} --regid ${rt.uid} --groups ${quote(rt.groups.joinToString(","))} /system/bin/sh -c ${quote(body)}"
            }
            Mode.LEGACY -> "su ${rt.uid} -c ${quote(body)}"
        }
    }

    private fun run(rt: Runtime, cmd: String) = try { Shell.cmd(identity(rt, cmd)).exec().isSuccess } catch (_: Exception) { false }
    private fun out(rt: Runtime, cmd: String): List<String> = try {
        val r = Shell.cmd(identity(rt, cmd)).exec(); if (r.isSuccess) r.out else emptyList()
    } catch (_: Exception) { emptyList() }

    private fun env() = "PULSE_SERVER=${quote("unix:$CONTROL")} PULSE_COOKIE=${quote(COOKIE)} PULSE_CLIENTCONFIG=${quote("$PULSE_CONFIG/client.conf")}"
    private fun pulseEnv() = "HOME=${quote(PULSE_HOME)} XDG_CONFIG_HOME=${quote("$PULSE_HOME/.config")} PULSE_CONFIG=${quote("$PULSE_CONFIG/daemon.conf")} PULSE_CONFIG_PATH=${quote(PULSE_CONFIG)} PULSE_RUNTIME_PATH=${quote(PULSE_RUNTIME)} PULSE_STATE_PATH=${quote(PULSE_STATE)} PULSE_CLIENTCONFIG=${quote("$PULSE_CONFIG/client.conf")} PULSE_COOKIE=${quote(COOKIE)}"

    private fun coreSink(rt: Runtime): String? {
        val e = env()
        val q = "s=\$($e pactl list short sinks 2>/dev/null)||exit 1; if printf '%s\\n' \"\$s\"|grep -q '[[:space:]]AAudio_sink[[:space:]]';then echo AAudio_sink;elif printf '%s\\n' \"\$s\"|grep -q '[[:space:]]OpenSL_ES_sink[[:space:]]';then echo OpenSL_ES_sink;else exit 2;fi"
        return out(rt, q).firstOrNull { it == "AAudio_sink" || it == "OpenSL_ES_sink" }
    }

    private fun coreHasInet(rt: Runtime): Boolean {
        val q = """
            pf=${quote(PID_FILE)}; [ -f "${'$'}pf" ]||exit 1
            p=${'$'}(sed -n 's/^pid=//p' "${'$'}pf"|sed -n '1p'); o=${'$'}(sed -n 's/^owner=//p' "${'$'}pf"|sed -n '1p')
            [ "${'$'}o" = ${quote(MANAGED)} ]||exit 2; case "${'$'}p" in ''|*[!0-9]*) exit 3;; esac
            [ "${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9]*\).*/\1/p' /proc/${'$'}p/status|sed -n '1p')" = ${rt.uid} ]||exit 4
            g=${'$'}(sed -n 's/^Groups:[[:space:]]*//p' /proc/${'$'}p/status|sed -n '1p'); case " ${'$'}g " in *" $INET_GID "*) exit 0;;*) exit 5;;esac
        """.trimIndent()
        return try { Shell.cmd(q).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun stopCore(rt: Runtime): Boolean {
        val q = """
            pf=${quote(PID_FILE)}; [ -f "${'$'}pf" ]||exit 1
            p=${'$'}(sed -n 's/^pid=//p' "${'$'}pf"|sed -n '1p'); o=${'$'}(sed -n 's/^owner=//p' "${'$'}pf"|sed -n '1p')
            [ "${'$'}o" = ${quote(MANAGED)} ]||exit 2; case "${'$'}p" in ''|*[!0-9]*) exit 3;; esac
            if kill -0 "${'$'}p" 2>/dev/null; then
              [ "${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9]*\).*/\1/p' /proc/${'$'}p/status|sed -n '1p')" = ${rt.uid} ]||exit 4
              c=${'$'}(tr '\000' ' ' </proc/${'$'}p/cmdline 2>/dev/null); case "${'$'}c" in *pulseaudio*${CONTROL}*) :;;*) exit 5;;esac
              kill "${'$'}p" 2>/dev/null||exit 6; i=0; while kill -0 "${'$'}p" 2>/dev/null&&[ "${'$'}i" -lt 20 ];do sleep .05;i=${'$'}((i+1));done
              kill -0 "${'$'}p" 2>/dev/null&&kill -9 "${'$'}p" 2>/dev/null||true
            fi
            rm -f "${'$'}pf" ${quote(CONTROL)} 2>/dev/null||true
        """.trimIndent()
        return try { Shell.cmd(q).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun startCore(rt: Runtime, sink: String): Boolean {
        val module = if (sink == "AAudio_sink") "module-aaudio-sink" else if (sink == "OpenSL_ES_sink") "module-sles-sink" else return false
        val preload = if (module == "module-sles-sink") slesPreload() else null
        val ld = preload?.let { "LD_PRELOAD=${quote(it)} " } ?: ""
        val q = """
            mkdir -p ${quote(STATE)} ${quote(LISTENERS)}||exit 1; :>${quote(LOG_FILE)}||exit 2; rm -f ${quote(CONTROL)}
            ${pulseEnv()} $ld nohup pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=false -L ${quote(module)} -L ${quote("module-native-protocol-unix socket=$CONTROL auth-cookie=$COOKIE")} </dev/null >${quote(LOG_FILE)} 2>&1 &
            p=${'$'}!; { printf 'pid=%s\n' "${'$'}p"; printf 'owner=%s\n' ${quote(MANAGED)}; } >${quote(PID_FILE)}||exit 3; chmod 600 ${quote(PID_FILE)}
        """.trimIndent()
        return run(rt, q)
    }

    private suspend fun waitCore(rt: Runtime, sink: String): Boolean {
        repeat(30) { if (coreSink(rt) == sink) return true; delay(100) }; return false
    }

    private suspend fun natGateway(info: ContainerInfo, logger: ContainerLogger?): String? {
        val pid = info.pid ?: return null; val bb = "${Constants.DS_BASE_DIR}/bin/busybox"
        val q = "gw=\$(${quote(bb)} nsenter -t $pid -n ${quote(bb)} ip -4 route show default 2>/dev/null|sed -n 's/^default via \\([0-9.][0-9.]*\\).*/\\1/p'|sed -n '1p'); printf '%s\\n' \"\$gw\""
        val found = try { Shell.cmd(q).exec().out.firstOrNull()?.trim() } catch (_: Exception) { null }
        if (found != null && ipv4(found) && owns(found)) return found
        if (owns("172.28.0.1")) { logger?.w("[!] Live NAT route unavailable; using verified DroidSpaces gateway 172.28.0.1"); return "172.28.0.1" }
        return null
    }

    private fun ipv4(s: String) = s.split('.').let { it.size == 4 && it.all { p -> p.toIntOrNull() in 0..255 } }
    private fun owns(ip: String): Boolean = try {
        Shell.cmd("/system/bin/ip -4 -o addr show 2>/dev/null|grep -Eq ${quote("[[:space:]]inet[[:space:]]$ip/")}").exec().isSuccess
    } catch (_: Exception) { false }

    private fun forwardOwner(port: Int): String? {
        val q = """
            w=$port; for c in ${quote(Constants.CONTAINERS_DIR)}/*/${quote(Constants.CONFIG_FILE)};do [ -f "${'$'}c" ]||continue
              [ "${'$'}(sed -n 's/^net_mode=//p' "${'$'}c"|tail -n1)" = nat ]||continue; n=${'$'}{c%/${Constants.CONFIG_FILE}};n=${'$'}{n##*/}
              v=${'$'}(sed -n 's/^port_forwards=//p' "${'$'}c"|sed -n '1p'); old=${'$'}IFS;IFS=,;for t in ${'$'}v;do IFS=${'$'}old;t=${'$'}(echo "${'$'}t"|tr -d ' ');case "${'$'}t" in */tcp) b=${'$'}{t%/tcp};;*/*) IFS=,;continue;;*) b=${'$'}t;;esac;h=${'$'}{b%%:*};[ "${'$'}h" = "${'$'}w" ]&&{ echo "${'$'}n";exit 0;};IFS=,;done;IFS=${'$'}old
            done;exit 1
        """.trimIndent()
        return try { val r=Shell.cmd(q).exec(); if(r.isSuccess) r.out.firstOrNull()?.trim() else null } catch (_: Exception) { null }
    }

    private fun probe(rt: Runtime, ip: String, port: Int) = run(rt, "i=\$(PULSE_SERVER=${quote("tcp:$ip:$port")} PULSE_COOKIE=${quote(COOKIE)} pactl info 2>/dev/null)||exit 1; printf '%s\\n' \"\$i\"|grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$'")

    private suspend fun listener(rt: Runtime, ip: String, port: Int, logger: ContainerLogger?): Boolean {
        if (probe(rt, ip, port)) { logger?.i("[+] Reusing authenticated PulseAudio listener on $ip:$port"); return true }
        val e=env(); val args="load-module module-native-protocol-tcp ${quote("listen=$ip")} ${quote("port=$port")} ${quote("auth-cookie=$COOKIE")}"; val r=try{Shell.cmd(identity(rt,"$e pactl $args")).exec()}catch(_:Exception){return false}
        val id=r.out.firstOrNull()?.trim()?.toIntOrNull(); if(!r.isSuccess||id==null){r.err.filter{it.isNotBlank()}.take(3).forEach{logger?.w("[PA] $it")};return false}
        val sf="$LISTENERS/${ip}_${port}.state"; run(rt,"mkdir -p ${quote(LISTENERS)}; printf 'id=%s\\nowner=%s\\n' '$id' ${quote(MANAGED)} >${quote(sf)};chmod 600 ${quote(sf)}")
        repeat(8){if(probe(rt,ip,port)){logger?.i("[+] Authenticated PulseAudio listener ready on $ip:$port");return true};delay(100)}
        run(rt,"$e pactl unload-module '$id' >/dev/null 2>&1||true;rm -f ${quote(sf)}");return false
    }

    private suspend fun chooseListener(rt: Runtime, ip: String, logger: ContainerLogger?): Int? {
        for (p in BASE_PORT..BASE_PORT+MAX_PORT_SHIFT) {
            val owner=forwardOwner(p);if(owner!=null){if(p==BASE_PORT)logger?.w("[!] Port $p is reserved by DroidSpaces port-forward in $owner; selecting another automatically");continue}
            if(listener(rt,ip,p,logger)){if(p!=BASE_PORT)logger?.i("[+] Selected audio port: $p");return p}
            if(p==BASE_PORT)logger?.w("[!] Port $p could not be bound; trying the next port")
        };return null
    }

    private fun escapedCookie(rt: Runtime) = out(rt,"od -An -v -tu1 ${quote(COOKIE)}|awk '{for(i=1;i<=NF;i++)printf \"\\\\0%03o\",${'$'}i}'").joinToString("").trim().takeIf{it.isNotEmpty()}

    private fun verifyClient(name: String, server: String): Boolean {
        val p="s=${quote(server)};c=/root/.config/pulse/saas-audio.cookie;[ -f \"\$c\" ]||exit 1;i=\$(PULSE_SERVER=\"\$s\" PULSE_COOKIE=\"\$c\" pactl info 2>/dev/null)||exit 2;printf '%s\\n' \"\$i\"|grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$'"
        return try { Shell.cmd("${Constants.DS_BINARY_PATH} --name=${quote(name)} run /bin/sh -lc ${quote(p)}").exec().isSuccess } catch (_: Exception) { false }
    }

    private suspend fun installClient(rt: Runtime, name: String, server: String, logger: ContainerLogger?): Boolean {
        val cookie=escapedCookie(rt)?:return false
        val p="""
            S=${quote(server)};C=${quote(cookie)};M=${quote(MANAGED)};mkdir -p /root/.config/pulse /etc/profile.d||exit 10
            command -v pactl >/dev/null 2>&1||{ if command -v apt-get >/dev/null;then echo __APT__;DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1||true;DEBIAN_FRONTEND=noninteractive apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1||true;elif command -v apk >/dev/null;then echo __APK__;apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse >/dev/null 2>&1||true;fi;}
            command -v pactl >/dev/null 2>&1||exit 11;c=/root/.config/pulse/client.conf;[ ! -f "${'$'}c" ]||grep -Fq "${'$'}M" "${'$'}c"||[ -e "${'$'}c.saas-x11-manager.bak" ]||cp -p "${'$'}c" "${'$'}c.saas-x11-manager.bak"
            printf '%b' "${'$'}C" >/root/.config/pulse/saas-audio.cookie;[ "${'$'}(wc -c </root/.config/pulse/saas-audio.cookie|tr -d ' ')" = 256 ]||exit 12;chmod 600 /root/.config/pulse/saas-audio.cookie
            cat >"${'$'}c" <<EOF
            $BEGIN
            default-server = $server
            cookie-file = /root/.config/pulse/saas-audio.cookie
            autospawn = no
            enable-shm = no
            $END
            EOF
            cat >/etc/profile.d/saas-x11-audio.sh <<EOF
            $BEGIN
            export PULSE_SERVER=$server
            export PULSE_COOKIE=/root/.config/pulse/saas-audio.cookie
            $END
            EOF
            a=/etc/asound.conf;if command -v aplay >/dev/null 2>&1&&PULSE_SERVER="${'$'}S" PULSE_COOKIE=/root/.config/pulse/saas-audio.cookie aplay -L 2>/dev/null|grep -q '^pulse';then [ ! -f "${'$'}a" ]||grep -Fq "${'$'}M" "${'$'}a"||[ -e "${'$'}a.saas-x11-manager.bak" ]||cp -p "${'$'}a" "${'$'}a.saas-x11-manager.bak";printf '%s\n' '$BEGIN' 'pcm.!default { type pulse }' 'ctl.!default { type pulse }' '$END' >"${'$'}a";fi
            i=${'$'}(PULSE_SERVER="${'$'}S" PULSE_COOKIE=/root/.config/pulse/saas-audio.cookie pactl info 2>&1)||{ echo "${'$'}i" >&2;exit 13;};echo "${'$'}i"|grep -E '^(Server String|Server Version|Default Sink|Default Source):';echo "${'$'}i"|grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$'||exit 14;echo __READY__
        """.trimIndent()
        return try {
            val r=Shell.cmd("${Constants.DS_BINARY_PATH} --name=${quote(name)} run /bin/sh -lc ${quote(p)}").exec()
            r.out.forEach{when{it=="__APT__"->logger?.i("[*] Installing missing Debian/Ubuntu audio clients...");it=="__APK__"->logger?.i("[*] Installing missing Alpine audio clients...");it.startsWith("Server ")||it.startsWith("Default ")->logger?.i(it)}}
            r.err.filter{it.isNotBlank()}.forEach{logger?.w(it)};r.isSuccess&&r.out.any{it=="__READY__"}
        }catch(_:Exception){false}
    }

    private fun slesPreload(): String? {
        val m=try{Shell.cmd("getprop ro.product.manufacturer").exec().out.firstOrNull()?.lowercase()}catch(_:Exception){null};if(m?.contains("samsung")!=true)return null
        return listOf("/system/lib64/libskcodec.so","/system/lib/libskcodec.so").firstOrNull{try{Shell.cmd("test -r ${quote(it)}").exec().isSuccess}catch(_:Exception){false}}
    }

    private suspend fun pulseTail(rt: Runtime, logger: ContainerLogger?) { out(rt,"tail -n 20 ${quote(LOG_FILE)} 2>/dev/null||true").filter{it.isNotBlank()}.forEach{logger?.w("[PA] $it")} }
    private fun quote(v: String) = "'" + v.replace("'", "'\\''") + "'"
}
