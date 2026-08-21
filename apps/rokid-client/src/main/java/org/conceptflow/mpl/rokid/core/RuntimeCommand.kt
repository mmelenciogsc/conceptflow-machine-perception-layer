// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

enum class RuntimeCommand(val action: String) {
    START_CAPTURE("org.conceptflow.mpl.rokid.action.START_CAPTURE"),
    START_STREAM_TEST("org.conceptflow.mpl.rokid.action.START_STREAM_TEST"),
    PLAY_LEFT_CUE("org.conceptflow.mpl.rokid.action.PLAY_LEFT_CUE"),
    PLAY_RIGHT_CUE("org.conceptflow.mpl.rokid.action.PLAY_RIGHT_CUE"),
    STOP("org.conceptflow.mpl.rokid.action.STOP"),
    ;

    companion object {
        fun fromAction(action: String?): RuntimeCommand? = entries.firstOrNull { it.action == action }
    }
}
