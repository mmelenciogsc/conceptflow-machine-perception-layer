// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.protobuf.ByteString
import org.conceptflow.mpl.host.core.AndroidCapabilityDetector
import org.conceptflow.mpl.host.core.CancellationHandle
import org.conceptflow.mpl.host.core.CorrelationResult
import org.conceptflow.mpl.host.core.CueModalityPolicy
import org.conceptflow.mpl.host.core.CueScheduler
import org.conceptflow.mpl.host.core.CueSchedulingPolicy
import org.conceptflow.mpl.host.core.ElapsedHostClock
import org.conceptflow.mpl.host.core.InProcessCueDispatchTransport
import org.conceptflow.mpl.host.core.InProcessHostTransport
import org.conceptflow.mpl.host.core.ResultCorrelator
import org.conceptflow.mpl.host.core.RuntimeCapabilities
import org.conceptflow.mpl.host.core.SessionEvent
import org.conceptflow.mpl.host.core.SessionState
import org.conceptflow.mpl.host.core.SessionStateMachine
import org.conceptflow.mpl.host.core.TransportCallback
import org.conceptflow.mpl.host.feedback.AccessibilityAwareSpeechFeedback
import org.conceptflow.mpl.host.feedback.HostCueDispatcher
import org.conceptflow.mpl.host.feedback.PlatformHostAudioFeedback
import org.conceptflow.mpl.host.feedback.PlatformHostHapticFeedback
import org.conceptflow.mpl.protocol.SyntheticImageFixtures
import org.conceptflow.mpl.v1.CapabilitySet
import org.conceptflow.mpl.v1.CueCategory
import org.conceptflow.mpl.v1.CueModality
import org.conceptflow.mpl.v1.Direction
import org.conceptflow.mpl.v1.Earcon
import org.conceptflow.mpl.v1.EphemeralIdentity
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.Haptic
import org.conceptflow.mpl.v1.HapticPattern
import org.conceptflow.mpl.v1.ImageDescriptor
import org.conceptflow.mpl.v1.ImageEncoding
import org.conceptflow.mpl.v1.NegotiateRequest
import org.conceptflow.mpl.v1.NegotiateResponse
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.PerceptionResult
import org.conceptflow.mpl.v1.ProtocolVersion
import org.conceptflow.mpl.v1.Speech
import org.conceptflow.mpl.v1.Urgency
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var capabilityView: TextView
    private lateinit var cueStatusView: TextView
    private val clock = ElapsedHostClock
    private val session = SessionStateMachine(clock)
    private val correlator = ResultCorrelator(clock)
    private lateinit var scheduler: CueScheduler
    private val frameIds = AtomicLong(0L)
    private var activeRequestId: String? = null
    private var activeOperation: CancellationHandle? = null
    private lateinit var audio: PlatformHostAudioFeedback
    private lateinit var haptics: PlatformHostHapticFeedback
    private lateinit var speech: AccessibilityAwareSpeechFeedback
    private lateinit var cueDispatch: InProcessCueDispatchTransport
    private lateinit var negotiatedCapabilities: CapabilitySet

    private val transport = InProcessHostTransport(
        negotiation = { request ->
            NegotiateResponse.newBuilder()
                .setSelectedVersion(request.supportedVersionsList.first())
                .setIdentity(EphemeralIdentity.newBuilder().setSessionId("synthetic-host-session"))
                .build()
        },
        processor = { frame -> syntheticResult(frame) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.session_status)
        capabilityView = findViewById(R.id.capability_status)
        cueStatusView = findViewById(R.id.cue_status)
        audio = PlatformHostAudioFeedback()
        haptics = PlatformHostHapticFeedback(this)
        speech = AccessibilityAwareSpeechFeedback(this)
        val capabilities = AndroidCapabilityDetector(this).detect()
        val modalityPolicy = CueModalityPolicy(
            allowEarcon = capabilities.audioOutput,
            allowSpeech = capabilities.audioOutput,
            allowHaptic = capabilities.haptics,
        )
        scheduler = CueScheduler(CueSchedulingPolicy(modalities = modalityPolicy))
        negotiatedCapabilities = CapabilitySet.newBuilder()
            .addImageEncodings(ImageEncoding.IMAGE_ENCODING_RGB8)
            .addImageEncodings(ImageEncoding.IMAGE_ENCODING_GRAY8)
            .addImageEncodings(ImageEncoding.IMAGE_ENCODING_JPEG)
            .addImageEncodings(ImageEncoding.IMAGE_ENCODING_PNG)
            .setMaxWidth(1_920)
            .setMaxHeight(1_080)
            .setMaxFrameBytes(1_048_576)
            .setSupportsCancellation(true)
            .setSupportsSupersession(true)
            .apply {
                if (capabilities.audioOutput) {
                    addCueModalities(CueModality.CUE_MODALITY_EARCON)
                    addCueModalities(CueModality.CUE_MODALITY_SPEECH)
                }
                if (capabilities.haptics) addCueModalities(CueModality.CUE_MODALITY_HAPTIC)
            }
            .build()
        val dispatcher = HostCueDispatcher(audio, haptics, speech) { cueText ->
            runOnUiThread {
                cueStatusView.text = getString(R.string.cue_accessible_status, cueText)
            }
        }
        cueDispatch = InProcessCueDispatchTransport(dispatcher::dispatch)

        findViewById<Button>(R.id.connect).setOnClickListener { connect() }
        findViewById<Button>(R.id.process_frame).setOnClickListener { processSyntheticFrame() }
        findViewById<Button>(R.id.cancel).setOnClickListener { cancelCurrent() }
        findViewById<Button>(R.id.disconnect).setOnClickListener { disconnect() }
        showCapabilities(capabilities)
        announceState(getString(R.string.idle_status), speak = false)
    }

    override fun onDestroy() {
        activeOperation?.cancel()
        cueDispatch.close()
        transport.close()
        audio.close()
        speech.close()
        super.onDestroy()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_C -> true.also { connect() }
        KeyEvent.KEYCODE_P -> true.also { processSyntheticFrame() }
        KeyEvent.KEYCODE_X -> true.also { cancelCurrent() }
        KeyEvent.KEYCODE_D -> true.also { disconnect() }
        else -> super.onKeyUp(keyCode, event)
    }

    private fun showCapabilities(capabilities: RuntimeCapabilities) {
        capabilityView.text = getString(
            R.string.capability_summary,
            yesNo(capabilities.camera),
            yesNo(capabilities.rotationVector),
            yesNo(capabilities.audioOutput),
            yesNo(capabilities.haptics),
            yesNo(capabilities.validatedNetwork),
        )
    }

    private fun connect() {
        if (session.dispatch(SessionEvent.Connect) !is SessionState.Connecting) return
        announceState(getString(R.string.connecting_status))
        session.dispatch(SessionEvent.TransportConnected)
        val request = NegotiateRequest.newBuilder()
            .setClientInstanceId("android-host-runtime")
            .addSupportedVersions(ProtocolVersion.newBuilder().setMajor(1).setMinor(0).setPatch(0))
            .setCapabilities(negotiatedCapabilities)
            .build()
        val operation = transport.negotiate(request, object : TransportCallback<NegotiateResponse> {
            override fun onSuccess(value: NegotiateResponse) {
                val state = session.dispatch(SessionEvent.Negotiated(value.identity.sessionId))
                announceState(
                    if (state is SessionState.Active) getString(R.string.connected_status) else getString(R.string.connect_error_status),
                )
            }

            override fun onFailure(error: Throwable) {
                session.dispatch(SessionEvent.TransportFailed(error.message ?: "Connection failed", retryable = true))
                announceState(getString(R.string.connect_error_status))
            }
        })
        activeOperation = operation.takeUnless { session.state is SessionState.Active }
    }

    private fun processSyntheticFrame() {
        if (session.state !is SessionState.Active) {
            announceState(getString(R.string.connect_first_status))
            return
        }
        val frame = syntheticFrame(frameIds.incrementAndGet())
        activeRequestId = frame.requestId
        correlator.register(frame)
        announceState(getString(R.string.processing_status, frame.frameId), speak = false)
        val operation = transport.process(frame, object : TransportCallback<PerceptionResult> {
            override fun onSuccess(value: PerceptionResult) {
                when (correlator.correlate(value)) {
                    is CorrelationResult.Accepted -> deliverCues(value)
                    is CorrelationResult.Rejected -> announceState(getString(R.string.stale_result_status))
                }
            }

            override fun onFailure(error: Throwable) {
                announceState(getString(R.string.processing_error_status))
            }
        })
        activeOperation = operation.takeIf { activeRequestId != null }
    }

    private fun deliverCues(result: PerceptionResult) {
        result.cuesList.forEach { scheduler.submit(it, clock.nowNanos()) }
        val next = scheduler.next(clock.nowNanos())
        if (next != null) {
            val delivered = cueDispatch.send(next)
            scheduler.complete(next.cueId)
            if (delivered) {
                announceState(
                    getString(R.string.cue_delivered_status, next.direction.name.removePrefix("DIRECTION_").lowercase()),
                    speak = false,
                )
            } else {
                announceState(getString(R.string.no_cue_status), speak = false)
            }
        } else {
            announceState(getString(R.string.no_cue_status), speak = false)
        }
        activeRequestId = null
        activeOperation = null
    }

    private fun cancelCurrent() {
        activeOperation?.cancel()
        activeRequestId?.let(correlator::cancel)
        activeOperation = null
        activeRequestId = null
        session.dispatch(SessionEvent.Cancel)
        session.dispatch(SessionEvent.TransportClosed)
        announceState(getString(R.string.cancelled_status))
    }

    private fun disconnect() {
        activeOperation?.cancel()
        activeOperation = null
        activeRequestId = null
        session.dispatch(SessionEvent.Disconnect)
        announceState(getString(R.string.disconnected_status))
    }

    private fun announceState(message: String, speak: Boolean = true) {
        statusView.text = message
        if (speak) speech.speak(message)
    }

    private fun yesNo(value: Boolean): String = getString(if (value) R.string.available else R.string.unavailable)

    private fun syntheticFrame(frameId: Long): FramePayload {
        val bytes = SyntheticImageFixtures.onePixelJpeg()
        val descriptor = ImageDescriptor.newBuilder()
            .setWidth(1)
            .setHeight(1)
            .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
            .setMediaType("image/jpeg")
            .setPayloadBytes(bytes.size.toLong())
            .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .build()
        return FramePayload.newBuilder()
            .setRequestId("host-request-$frameId")
            .setSessionId("synthetic-host-session")
            .setStreamId("host-synthetic-camera")
            .setFrameId(frameId)
            .setCaptureMonotonicTimestampNs(clock.nowNanos())
            .setImage(descriptor)
            .setFrameData(ByteString.copyFrom(bytes))
            .setSynthetic(true)
            .build()
    }

    private fun syntheticResult(frame: FramePayload): PerceptionResult {
        val now = clock.nowNanos()
        val cue = PerceptionCue.newBuilder()
            .setCueId("host-cue-${frame.frameId}")
            .setFrameId(frame.frameId)
            .setCreatedMonotonicTimestampNs(now)
            .setTtlMs(1_200)
            .setCategory(CueCategory.CUE_CATEGORY_OBSTACLE)
            .setDescription("Synthetic obstacle cue")
            .setConfidence(0.9)
            .setPriority(80)
            .setDirection(if (frame.frameId % 2L == 0L) Direction.DIRECTION_RIGHT else Direction.DIRECTION_LEFT)
            .setUrgency(Urgency.URGENCY_HIGH)
            .setEarcon(Earcon.newBuilder().setEarconId("obstacle").setGain(0.5f).setPitch(1f))
            .setSpeech(Speech.newBuilder().setText("Synthetic obstacle ahead").setInterrupt(true))
            .setHaptic(
                Haptic.newBuilder()
                    .setPattern(HapticPattern.HAPTIC_PATTERN_PULSE)
                    .setIntensity(0.35f)
                    .setDurationMs(70),
            )
            .build()
        return PerceptionResult.newBuilder()
            .setResultId("host-result-${frame.frameId}")
            .setRequestId(frame.requestId)
            .setSessionId(frame.sessionId)
            .setStreamId(frame.streamId)
            .setFrameId(frame.frameId)
            .setCaptureMonotonicTimestampNs(frame.captureMonotonicTimestampNs)
            .setCompletedMonotonicTimestampNs(now)
            .addCues(cue)
            .build()
    }
}
