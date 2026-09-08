package com.saas.x11manager.util

/**
 * Internal-only terminal layout tokens.
 *
 * Raw empty shell lines are intentionally ignored by ConciseLogReducer. Callers
 * that want a visual separation between semantic lifecycle blocks emit SPACER;
 * the reducer converts it to one empty rendered row and never exposes the token.
 */
internal object LogLayout {
    const val SPACER = "[[SAAS_LOG_SPACER]]"
}
