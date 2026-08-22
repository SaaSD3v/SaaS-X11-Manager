# App-level R8 rules are intentionally minimal.
#
# The embedded Termux:X11/Lorie module exports consumer rules that preserve its
# app_process entry point and JNI-referenced members. libsu 5.2.1 likewise
# exports the rules required for Shell.Initializer and RootService subclasses.
# Avoid blanket -keep rules here so release minification and resource shrinking
# can remove genuinely unused Manager/library code.
