package com.saas.x11manager.util

enum class VirGLRuntimeFlag(
    val argument: String,
    val title: String,
    val description: String
) {
    USE_EGL_SURFACELESS(
        "--use-egl-surfaceless",
        "EGL surfaceless",
        "Use EGL without a window surface; useful for headless Android rendering."
    ),
    USE_GLES(
        "--use-gles",
        "OpenGL ES",
        "Request a GLES host context through EGL."
    ),
    USE_GLX(
        "--use-glx",
        "GLX backend",
        "Use GLX instead of EGL; cannot be combined with surfaceless or GLES."
    ),
    NO_FORK(
        "--no-fork",
        "No fork",
        "Keep client handling in the Manager-owned renderer process instead of forking."
    ),
    NO_LOOP_OR_FORK(
        "--no-loop-or-fork",
        "No loop or fork",
        "Run clients in-process and let the renderer exit after the last client disconnects."
    )
}

object VirGLRuntimeFlags {
    val all: List<VirGLRuntimeFlag> = listOf(
        VirGLRuntimeFlag.USE_EGL_SURFACELESS,
        VirGLRuntimeFlag.USE_GLES,
        VirGLRuntimeFlag.USE_GLX,
        VirGLRuntimeFlag.NO_FORK,
        VirGLRuntimeFlag.NO_LOOP_OR_FORK
    )

    fun fromHelp(help: String): Set<VirGLRuntimeFlag> =
        all.filterTo(linkedSetOf()) { flag -> help.contains(flag.argument) }

    fun fromStored(values: Set<String>): Set<VirGLRuntimeFlag> = sanitize(
        values.mapNotNullTo(linkedSetOf()) { value ->
            enumValues<VirGLRuntimeFlag>().firstOrNull { it.name == value }
        }
    )

    fun toStored(flags: Set<VirGLRuntimeFlag>): Set<String> =
        sanitize(flags).mapTo(linkedSetOf()) { it.name }

    fun withToggled(
        current: Set<VirGLRuntimeFlag>,
        flag: VirGLRuntimeFlag,
        enabled: Boolean
    ): Set<VirGLRuntimeFlag> {
        val next = LinkedHashSet(current)
        if (enabled) next.add(flag) else next.remove(flag)
        if (enabled) {
            when (flag) {
                VirGLRuntimeFlag.USE_GLX -> {
                    next.remove(VirGLRuntimeFlag.USE_EGL_SURFACELESS)
                    next.remove(VirGLRuntimeFlag.USE_GLES)
                }
                VirGLRuntimeFlag.USE_EGL_SURFACELESS,
                VirGLRuntimeFlag.USE_GLES -> next.remove(VirGLRuntimeFlag.USE_GLX)
                VirGLRuntimeFlag.NO_FORK -> next.remove(VirGLRuntimeFlag.NO_LOOP_OR_FORK)
                VirGLRuntimeFlag.NO_LOOP_OR_FORK -> next.remove(VirGLRuntimeFlag.NO_FORK)
            }
        }
        return sanitize(next)
    }

    fun sanitize(flags: Set<VirGLRuntimeFlag>): Set<VirGLRuntimeFlag> {
        val normalized = LinkedHashSet(flags)
        if (VirGLRuntimeFlag.USE_GLX in normalized) {
            normalized.remove(VirGLRuntimeFlag.USE_EGL_SURFACELESS)
            normalized.remove(VirGLRuntimeFlag.USE_GLES)
        }
        if (VirGLRuntimeFlag.NO_LOOP_OR_FORK in normalized) {
            normalized.remove(VirGLRuntimeFlag.NO_FORK)
        }
        return all.filterTo(linkedSetOf()) { it in normalized }
    }

    fun arguments(flags: Set<VirGLRuntimeFlag>): List<String> =
        sanitize(flags).map { it.argument }

    fun describe(flags: Set<VirGLRuntimeFlag>): String =
        arguments(flags).takeIf { it.isNotEmpty() }?.joinToString(" ") ?: "default"
}
