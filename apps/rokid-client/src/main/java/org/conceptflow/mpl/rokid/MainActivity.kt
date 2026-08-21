// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.conceptflow.mpl.rokid.core.ElapsedRealtimeClock
import org.conceptflow.mpl.rokid.core.CueEnvelope
import org.conceptflow.mpl.rokid.core.FrameSource
import org.conceptflow.mpl.rokid.core.FrameSourceStateController
import org.conceptflow.mpl.rokid.core.InProcessCueTransport
import org.conceptflow.mpl.rokid.core.InspectableCueRenderer
import org.conceptflow.mpl.rokid.core.RenderDisposition
import org.conceptflow.mpl.rokid.hardware.Camera2FrameSource
import org.conceptflow.mpl.rokid.hardware.PlatformHapticOutput
import org.conceptflow.mpl.rokid.hardware.PlatformStereoAudioOutput
import org.conceptflow.mpl.rokid.hardware.SensorManagerPoseSource
import org.conceptflow.mpl.v1.Direction
import org.conceptflow.mpl.v1.Earcon
import org.conceptflow.mpl.v1.Haptic
import org.conceptflow.mpl.v1.HapticPattern
import org.conceptflow.mpl.v1.PerceptionCue
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var captureButton: Button
    private lateinit var audioOutput: PlatformStereoAudioOutput
    private lateinit var renderer: InspectableCueRenderer
    private lateinit var transport: InProcessCueTransport
    private val frameSources = FrameSourceStateController()
    private var poseSource: SensorManagerPoseSource? = null
    private val testCueIds = AtomicLong(0L)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCapture() else updateStatus(getString(R.string.camera_permission_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)
        captureButton = findViewById(R.id.capture_toggle)
        audioOutput = PlatformStereoAudioOutput()
        renderer = InspectableCueRenderer(
            clock = ElapsedRealtimeClock,
            audio = audioOutput,
            haptics = PlatformHapticOutput(this),
        )
        transport = InProcessCueTransport().also { it.connect(renderer::render) }

        captureButton.setOnClickListener { toggleCapture() }
        findViewById<Button>(R.id.cue_left).setOnClickListener { deliverTestCue(Direction.DIRECTION_LEFT) }
        findViewById<Button>(R.id.cue_right).setOnClickListener { deliverTestCue(Direction.DIRECTION_RIGHT) }
        updateStatus(getString(R.string.ready_status))
    }

    override fun onResume() {
        super.onResume()
        val source = SensorManagerPoseSource(this)
        poseSource = source
        runCatching { source.start { } }
            .onFailure { updateStatus(getString(R.string.pose_unavailable_status)) }
    }

    override fun onPause() {
        poseSource?.close()
        poseSource = null
        stopCapture()
        super.onPause()
    }

    override fun onDestroy() {
        transport.close()
        audioOutput.close()
        super.onDestroy()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_C -> true.also { toggleCapture() }
        KeyEvent.KEYCODE_L -> true.also { deliverTestCue(Direction.DIRECTION_LEFT) }
        KeyEvent.KEYCODE_R -> true.also { deliverTestCue(Direction.DIRECTION_RIGHT) }
        else -> super.onKeyUp(keyCode, event)
    }

    private fun toggleCapture() {
        if (frameSources.hasActiveSource) {
            stopCapture()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            startCapture()
        }
    }

    private fun startCapture() {
        val source = Camera2FrameSource(this)
        if (!frameSources.attach(source)) {
            source.close()
            return
        }
        source.start(object : FrameSource.Listener {
            override fun onFrame(frame: org.conceptflow.mpl.v1.FramePayload) {
                runOnUiThread {
                    if (frameSources.isCurrent(source)) {
                        updateStatus(getString(R.string.frame_captured_status, frame.frameId))
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    if (frameSources.stopIfCurrent(source)) {
                        captureButton.setText(R.string.start_capture)
                        updateStatus(getString(R.string.capture_error_status, message))
                    }
                }
            }
        })
        if (source.isRunning && frameSources.isCurrent(source)) {
            captureButton.setText(R.string.stop_capture)
            updateStatus(getString(R.string.capture_started_status))
        } else {
            frameSources.stopIfCurrent(source)
            captureButton.setText(R.string.start_capture)
        }
    }

    private fun stopCapture() {
        frameSources.stopCurrent()
        if (::captureButton.isInitialized) captureButton.setText(R.string.start_capture)
    }

    private fun deliverTestCue(direction: Direction) {
        val id = testCueIds.incrementAndGet()
        val cue = PerceptionCue.newBuilder()
            .setCueId("local-cue-$id")
            .setFrameId(id)
            .setCreatedMonotonicTimestampNs(ElapsedRealtimeClock.nowNanos())
            .setTtlMs(1_000)
            .setDescription(if (direction == Direction.DIRECTION_LEFT) "Left cue" else "Right cue")
            .setConfidence(1.0)
            .setPriority(1)
            .setDirection(direction)
            .setEarcon(Earcon.newBuilder().setEarconId("orientation").setGain(0.55f).setPitch(1f))
            .setHaptic(
                Haptic.newBuilder()
                    .setPattern(HapticPattern.HAPTIC_PATTERN_PULSE)
                    .setIntensity(0.35f)
                    .setDurationMs(70),
            )
            .build()
        val event = transport.deliver(CueEnvelope("local-session", "manual-cues", cue))
        if (event?.disposition == RenderDisposition.RENDERED) {
            updateStatus(
                getString(
                    if (direction == Direction.DIRECTION_LEFT) R.string.left_cue_status else R.string.right_cue_status,
                ),
            )
        } else {
            updateStatus(getString(R.string.cue_unavailable_status))
        }
    }

    private fun updateStatus(message: String) {
        statusView.text = message
    }
}
