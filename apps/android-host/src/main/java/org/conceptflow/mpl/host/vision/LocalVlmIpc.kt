// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

internal object LocalVlmIpc {
    const val REQUEST_INFER = 1
    const val RESPONSE_CLASSIFIED = 2
    const val RESPONSE_FAILED = 3
    const val REQUEST_PREWARM = 4
    const val RESPONSE_PREWARMED = 5
    const val RESPONSE_PREWARM_FAILED = 6
    const val RESPONSE_DEFERRED = 7
    const val REQUEST_CANCEL_ALL = 8
    const val RESPONSE_BUSY = 9
    const val RESPONSE_VQA_ANSWERED = 10
    const val REQUEST_CANCEL = 11

    const val KEY_REQUEST_ID = "request_id"
    const val KEY_FRAME_ID = "frame_id"
    const val KEY_CAPTURE_NANOS = "capture_nanos"
    const val KEY_IMAGE_PATH = "image_path"
    const val KEY_IMAGE_SHA256 = "image_sha256"
    const val KEY_TASK = "task"
    const val KEY_LABEL = "label"
    const val KEY_COMPLETED_NANOS = "completed_nanos"
    const val KEY_FAILURE = "failure"
    const val KEY_LEASE_WAIT_NANOS = "lease_wait_nanos"
    const val KEY_LEASE_HOLD_NANOS = "lease_hold_nanos"
    const val KEY_FOCUS_REQUEST_ID = "focus_request_id"
    const val KEY_SESSION_GENERATION = "session_generation"
    const val KEY_SNAPSHOT_ID = "snapshot_id"
    const val KEY_FOCUS_GENERATION = "focus_generation"
    const val KEY_TRACK_ID = "track_id"
    const val KEY_REQUESTED_NANOS = "requested_nanos"
    const val KEY_DEADLINE_NANOS = "deadline_nanos"
    const val KEY_QUESTION = "question"
    const val KEY_ANSWER = "answer"
}
