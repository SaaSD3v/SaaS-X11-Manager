# Gradle namespace bridge

This directory intentionally contains no external shell-loader application.

The embedded Termux:X11 `:lorie` module has a compile-only dependency on the
Gradle path `:shell-loader:stub`. The root build maps that child project to
`third_party/termux-x11/shell-loader/stub`, while this empty parent project keeps
the Gradle project path valid without building the upstream shell-loader APK.
