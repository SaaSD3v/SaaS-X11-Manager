# Embedded standalone VNC viewer (TEST branches)

The VNC tab is intentionally a generic, standalone viewer. It does not call the Manager's DroidSpaces container APIs, `VncServerManager`, `X11SessionManager`, PulseAudio routing, or Integrated X11 lifecycle. A VNC connection is treated only as a remote `host:port` endpoint.

## Engine baseline

The protocol/framebuffer engine is pinned to **AVNC v3.3.1**, commit `f30406514ef8880097e960da7dc1d8dc4c713d7f`, under `third_party/avnc`. The local `:embedded-avnc` wrapper consumes only the engine-oriented sources needed by the Manager and builds AVNC's `native-vnc` JNI library through the upstream CMake project.

The embedded native build therefore uses AVNC's pinned LibVNCClient, libjpeg-turbo and wolfSSL submodules. Their exact revisions come from the AVNC v3.3.1 tree rather than floating branches.

## Ownership boundary

AVNC supplies the RFB/VNC protocol client, security negotiation, Tight/native decoding, framebuffer storage, cursor data and OpenGL texture bridge. The X11 Manager supplies its own Compose navigation, profile editor, Android lifecycle adapter, screen-on/screen-off policy, renderer host, input bridge and saved connection model. AVNC's Home/Prefs/VNC activities, Room database and application navigation are not embedded.

The viewer starts with no connection and no VNC framebuffer worker. Connecting is an explicit user action. Turning the VNC screen off pauses framebuffer updates while preserving the RFB connection; Disconnect closes the client lifecycle. Neither action starts or stops a DroidSpaces/TigerVNC server.

## Licensing

AVNC source files are licensed **GPL-3.0-or-later** and retain their upstream notices in `third_party/avnc`. AVNC itself includes LibVNCClient and other third-party components with their own notices. Any distribution containing this embedded engine must comply with the corresponding GPL/source-distribution obligations. The upstream `COPYING.txt` and dependency license material remain available through the pinned submodule.

This integration is experimental on `X11TEST` and `X11-X0TEST` until it is physically validated. Stable branches are not part of this integration unless explicitly promoted later.
