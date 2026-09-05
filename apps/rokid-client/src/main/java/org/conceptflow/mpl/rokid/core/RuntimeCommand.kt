// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

enum class RuntimeCommand(val action: String) {
    START_CAPTURE("org.conceptflow.mpl.rokid.action.START_CAPTURE"),
    START_STREAM_TEST("org.conceptflow.mpl.rokid.action.START_STREAM_TEST"),
    INITIALIZE_LIVE_IDENTITY("org.conceptflow.mpl.rokid.action.INITIALIZE_LIVE_IDENTITY"),
    START_LIVE_LINK_TEST("org.conceptflow.mpl.rokid.action.START_LIVE_LINK_TEST"),
    START_LIVE_LINK_SOAK_TEST("org.conceptflow.mpl.rokid.action.START_LIVE_LINK_SOAK_TEST"),
    STOP_LIVE_LINK_TEST("org.conceptflow.mpl.rokid.action.STOP_LIVE_LINK_TEST"),
    ENABLE_IDLE_CONTROL("org.conceptflow.mpl.rokid.action.ENABLE_IDLE_CONTROL"),
    RECOVER_SAME_BOOT("org.conceptflow.mpl.rokid.internal.RECOVER_SAME_BOOT"),
    RECOVER_PERSISTED_BOOT("org.conceptflow.mpl.rokid.internal.RECOVER_PERSISTED_BOOT"),
    DISABLE_IDLE_CONTROL("org.conceptflow.mpl.rokid.action.DISABLE_IDLE_CONTROL"),
    START_PHYSICAL_TRACE("org.conceptflow.mpl.rokid.action.START_PHYSICAL_TRACE"),
    BENCHMARK_VIDEO_CODECS("org.conceptflow.mpl.rokid.action.BENCHMARK_VIDEO_CODECS"),
    PLAY_LEFT_CUE("org.conceptflow.mpl.rokid.action.PLAY_LEFT_CUE"),
    PLAY_RIGHT_CUE("org.conceptflow.mpl.rokid.action.PLAY_RIGHT_CUE"),
    PLAY_FULL_BRAND_TEST("org.conceptflow.mpl.rokid.action.PLAY_FULL_BRAND_TEST"),
    ENABLE_VALIDATED_GESTURE_COMMANDS(
        "org.conceptflow.mpl.rokid.action.ENABLE_VALIDATED_GESTURE_COMMANDS",
    ),
    DISABLE_GESTURE_COMMANDS("org.conceptflow.mpl.rokid.action.DISABLE_GESTURE_COMMANDS"),
    ENABLE_LEGACY_SENSOR_SPOOL("org.conceptflow.mpl.rokid.action.ENABLE_LEGACY_SENSOR_SPOOL"),
    DISABLE_LEGACY_SENSOR_SPOOL("org.conceptflow.mpl.rokid.action.DISABLE_LEGACY_SENSOR_SPOOL"),
    STOP("org.conceptflow.mpl.rokid.action.STOP"),
    ;

    companion object {
        fun fromAction(action: String?): RuntimeCommand? = entries.firstOrNull { it.action == action }
    }
}

/** Exported ADB controls that provision or operate the private live link are debug-build only. */
object RuntimeCommandAuthorization {
    private val debugOnly = setOf(
        RuntimeCommand.START_CAPTURE,
        RuntimeCommand.START_STREAM_TEST,
        RuntimeCommand.INITIALIZE_LIVE_IDENTITY,
        RuntimeCommand.START_LIVE_LINK_TEST,
        RuntimeCommand.START_LIVE_LINK_SOAK_TEST,
        RuntimeCommand.STOP_LIVE_LINK_TEST,
        RuntimeCommand.ENABLE_IDLE_CONTROL,
        RuntimeCommand.DISABLE_IDLE_CONTROL,
        RuntimeCommand.START_PHYSICAL_TRACE,
        RuntimeCommand.BENCHMARK_VIDEO_CODECS,
        RuntimeCommand.PLAY_FULL_BRAND_TEST,
        RuntimeCommand.ENABLE_VALIDATED_GESTURE_COMMANDS,
        RuntimeCommand.DISABLE_GESTURE_COMMANDS,
        RuntimeCommand.ENABLE_LEGACY_SENSOR_SPOOL,
        RuntimeCommand.DISABLE_LEGACY_SENSOR_SPOOL,
    )

    fun isAllowed(command: RuntimeCommand, debuggable: Boolean): Boolean =
        debuggable || command !in debugOnly
}
