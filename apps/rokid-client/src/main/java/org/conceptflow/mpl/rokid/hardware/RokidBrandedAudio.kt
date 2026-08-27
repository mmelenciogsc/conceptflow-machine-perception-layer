// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.conceptflow.mpl.rokid.core.BrandedAudioScript
import org.conceptflow.mpl.rokid.core.BrandedAudioStep
import org.conceptflow.mpl.rokid.core.BrandedAmbientBedGenerator
import org.conceptflow.mpl.rokid.core.BrandedToneGenerator
import org.conceptflow.mpl.rokid.core.BrandedToneKind
import org.conceptflow.mpl.rokid.core.SpeechVoiceCapability
import org.conceptflow.mpl.rokid.core.SpeechVoicePolicy
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class RokidBrandedAudio(
    context: Context,
    private val handler: Handler,
) : AutoCloseable {
    private val callbackToken = Any()
    private val pendingSteps = ArrayDeque<QueueItem>()
    private val toneOutput = ProceduralDeepToneOutput(handler)
    private val ambientBedOutput = ProceduralAmbientBedOutput()
    private val speechOutput = BrandedSpeechOutput(context, handler)
    private var sequenceGeneration = 0L
    private var playing = false
    private var closed = false

    fun onAuthenticatedConnection() {
        enqueue(listOf(BrandedAudioStep.Tone(BrandedToneKind.AUTHENTICATED_CONNECTION)))
    }

    fun onActivationReady(includeBootBrandLine: Boolean, includeProductLine: Boolean) {
        enqueue(BrandedAudioScript.activationReady(includeBootBrandLine, includeProductLine))
    }

    fun playDebugFullBrandTest(onTerminal: () -> Unit) {
        enqueue(BrandedAudioScript.fullBrandTest(), onTerminal)
    }

    private fun enqueue(steps: List<BrandedAudioStep>, onTerminal: (() -> Unit)? = null) {
        post {
            if (closed) {
                onTerminal?.invoke()
                return@post
            }
            val additionalItems = steps.size + if (onTerminal == null) 0 else 1
            if (pendingSteps.size + additionalItems > MAX_PENDING_STEPS) {
                Log.w(TAG, "branded_audio_event_dropped reason=queue_capacity")
                onTerminal?.invoke()
                return@post
            }
            steps.forEach { pendingSteps.addLast(QueueItem.Audio(it)) }
            if (onTerminal != null) pendingSteps.addLast(QueueItem.Completion(onTerminal))
            if (!playing) {
                Log.i(TAG, "ambient_bed started=${ambientBedOutput.start()}")
                playNext(sequenceGeneration)
            }
        }
    }

    private fun playNext(generation: Long) {
        if (closed || generation != sequenceGeneration) return
        val item = pendingSteps.pollFirst()
        if (item == null) {
            playing = false
            ambientBedOutput.stop()
            return
        }
        playing = true
        if (item is QueueItem.Completion) {
            item.callback()
            playNext(generation)
            return
        }
        val step = (item as QueueItem.Audio).step
        when (step) {
            is BrandedAudioStep.Tone -> {
                val started = toneOutput.play(step.kind)
                Log.i(
                    TAG,
                    "tone_playback kind=${step.kind.name.lowercase()} started=$started",
                )
                postDelayed(BrandedToneGenerator.DURATION_MILLIS + TONE_SETTLE_MILLIS) {
                    playNext(generation)
                }
            }
            is BrandedAudioStep.Speech -> {
                speechOutput.speakExact(step.text) {
                    post { playNext(generation) }
                }
            }
            is BrandedAudioStep.Pause -> postDelayed(step.durationMillis) {
                playNext(generation)
            }
        }
    }

    private fun post(action: () -> Unit) {
        handler.postAtTime(action, callbackToken, SystemClock.uptimeMillis())
    }

    private fun postDelayed(delayMillis: Long, action: () -> Unit) {
        handler.postAtTime(action, callbackToken, SystemClock.uptimeMillis() + delayMillis)
    }

    override fun close() {
        if (closed) return
        closed = true
        sequenceGeneration += 1L
        pendingSteps.clear()
        handler.removeCallbacksAndMessages(callbackToken)
        runCatching { speechOutput.close() }
        runCatching { toneOutput.close() }
        runCatching { ambientBedOutput.close() }
    }

    private sealed interface QueueItem {
        data class Audio(val step: BrandedAudioStep) : QueueItem
        data class Completion(val callback: () -> Unit) : QueueItem
    }

    private companion object {
        const val TAG = "ConceptFlowRokidAudio"
        const val MAX_PENDING_STEPS = 32
        const val TONE_SETTLE_MILLIS = 45L
    }
}

private class BrandedSpeechOutput(
    context: Context,
    handler: Handler,
) : AutoCloseable {
    private val privateVoice = PrivateClonedVoiceOutput(context.noBackupFilesDir, handler)
    private val ttsFallback = AndroidTextToSpeechOutput(context, handler)

    fun speakExact(text: String, onTerminal: () -> Unit) {
        if (privateVoice.hasCompleteVoiceSet) {
            privateVoice.speakExact(text) { succeeded ->
                if (succeeded) onTerminal() else ttsFallback.speakExact(text, onTerminal)
            }
        } else {
            ttsFallback.speakExact(text, onTerminal)
        }
    }

    override fun close() {
        runCatching { privateVoice.close() }
        runCatching { ttsFallback.close() }
    }
}

private class PrivateClonedVoiceOutput(
    noBackupFilesDirectory: File,
    private val handler: Handler,
) : AutoCloseable {
    private val callbackToken = Any()
    private val voiceDirectory = File(noBackupFilesDirectory, PRIVATE_VOICE_DIRECTORY)
    private val fileByText = mapOf(
        BrandedAudioScript.CONCEPTFLOW_SPOKEN to File(voiceDirectory, "concept_flow.wav"),
        BrandedAudioScript.MACHINE_INTELLIGENCE_SPOKEN to
            File(voiceDirectory, "machine_intelligence.wav"),
        BrandedAudioScript.HUMAN_ARCHITECTURE_SPOKEN to
            File(voiceDirectory, "human_architecture.wav"),
        BrandedAudioScript.MACHINE_PERCEPTION_LAYER_SPOKEN to
            File(voiceDirectory, "machine_perception_layer.wav"),
        BrandedAudioScript.MAP_SPOKEN to File(voiceDirectory, "map.wav"),
        BrandedAudioScript.MORPH_SPOKEN to File(voiceDirectory, "morph.wav"),
        BrandedAudioScript.MOVE_SPOKEN to File(voiceDirectory, "move.wav"),
        BrandedAudioScript.SUPPLEMENTAL_AWARENESS to File(
            voiceDirectory,
            "supplemental_awareness.wav",
        ),
    )
    val hasCompleteVoiceSet: Boolean = validateVoiceSet()
    private var activePlayer: MediaPlayer? = null
    private var activeEnhancer: LoudnessEnhancer? = null
    private var activeCompletion: ((Boolean) -> Unit)? = null
    private var closed = false

    init {
        Log.i(
            TAG,
            if (hasCompleteVoiceSet) {
                "speech_source=private_consent_gated_clone watermark=perth"
            } else {
                "speech_source=android_tts_fallback private_voice_assets=unavailable"
            },
        )
    }

    fun speakExact(text: String, onTerminal: (Boolean) -> Unit) {
        if (closed) {
            onTerminal(false)
            return
        }
        val file = fileByText[text]
        if (file == null) {
            onTerminal(false)
            return
        }
        val player = runCatching { MediaPlayer() }.getOrElse {
            onTerminal(false)
            return
        }
        var enhancer: LoudnessEnhancer? = null
        val prepared = runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.setVolume(PRIVATE_SPEECH_VOLUME, PRIVATE_SPEECH_VOLUME)
            player.prepare()
            runCatching {
                player.playbackParams = PlaybackParams()
                    .setSpeed(PRIVATE_SPEECH_SPEED)
                    .setPitch(1.0f)
            }.onFailure {
                Log.w(TAG, "private_voice_speed_fallback")
            }
            enhancer = runCatching {
                LoudnessEnhancer(player.audioSessionId).apply {
                    setTargetGain(PRIVATE_SPEECH_BOOST_MILLIBELS)
                    enabled = true
                }
            }.getOrNull()
        }.isSuccess
        if (!prepared) {
            runCatching { enhancer?.release() }
            player.release()
            Log.w(TAG, "private_voice_file_failed name=${file.name}")
            onTerminal(false)
            return
        }
        activeEnhancer?.let { runCatching { it.release() } }
        activeEnhancer = null
        activePlayer?.let { runCatching { it.release() } }
        activeCompletion = onTerminal
        activePlayer = player
        activeEnhancer = enhancer
        player.setOnCompletionListener { finish(it, succeeded = true) }
        player.setOnErrorListener { failedPlayer, _, _ ->
            finish(failedPlayer, succeeded = false)
            true
        }
        if (runCatching { player.start() }.isFailure) {
            finish(player, succeeded = false)
        } else {
            Log.i(
                TAG,
                "private_voice_started name=${file.name} " +
                    "boost_millibels=${if (enhancer == null) 0 else PRIVATE_SPEECH_BOOST_MILLIBELS}",
            )
            handler.postAtTime(
                { finish(player, succeeded = false) },
                callbackToken,
                SystemClock.uptimeMillis() + MAX_PRIVATE_UTTERANCE_MILLIS,
            )
        }
    }

    private fun validateVoiceSet(): Boolean = runCatching {
        val canonicalDirectory = voiceDirectory.canonicalFile
        require(canonicalDirectory.isDirectory)
        val manifestFile = File(canonicalDirectory, "manifest.json").canonicalFile
        require(manifestFile.parentFile == canonicalDirectory)
        require(manifestFile.isFile && manifestFile.length() in 1L..MAX_MANIFEST_BYTES)
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(manifest.keys().asSequence().toSet() == EXPECTED_MANIFEST_KEYS)
        require(manifest.getInt("schemaVersion") == 1)
        require(manifest.getString("sourceRepository") == QUICKPUB_REPOSITORY)
        require(manifest.getString("sourceRevision") == AUDITED_QUICKPUB_REVISION)
        require(manifest.getString("workerSha256") == AUDITED_WORKER_SHA256)
        require(manifest.getString("engine") == PRIVATE_ENGINE)
        require(manifest.getString("modelRevision") == MODEL_REVISION)
        require(manifest.getString("runtimeManifestSha256") == AUDITED_RUNTIME_MANIFEST_SHA256)
        require(manifest.getBoolean("voicePermissionAffirmed"))
        require(manifest.getBoolean("voiceSampleKeptExternal"))
        require(manifest.getString("watermark") == PERTH_WATERMARK)

        val outputs = manifest.getJSONArray("outputs")
        require(outputs.length() == EXPECTED_FILE_NAMES.size)
        val declaredHashes = buildMap {
            for (index in 0 until outputs.length()) {
                val output = outputs.getJSONObject(index)
                require(output.keys().asSequence().toSet() == EXPECTED_OUTPUT_KEYS)
                val fileName = output.getString("file")
                val declaredHash = output.getString("sha256")
                require(fileName in EXPECTED_FILE_NAMES)
                require(declaredHash.matches(SHA256_PATTERN))
                require(put(fileName, declaredHash) == null)
            }
        }
        require(declaredHashes.keys == EXPECTED_FILE_NAMES)
        fileByText.values.forEach { file ->
            val canonicalFile = file.canonicalFile
            require(canonicalFile.parentFile == canonicalDirectory)
            require(canonicalFile.isFile && canonicalFile.length() in 1L..MAX_VOICE_FILE_BYTES)
            require(sha256(canonicalFile) == declaredHashes.getValue(canonicalFile.name))
        }
        true
    }.getOrElse {
        Log.w(TAG, "private_voice_set_rejected reason=validation_failed")
        false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun finish(player: MediaPlayer, succeeded: Boolean) {
        if (activePlayer !== player) return
        activePlayer = null
        activeEnhancer?.let { runCatching { it.release() } }
        activeEnhancer = null
        handler.removeCallbacksAndMessages(callbackToken)
        runCatching { player.release() }
        Log.i(TAG, "private_voice_completed succeeded=$succeeded")
        val completion = activeCompletion
        activeCompletion = null
        completion?.invoke(succeeded)
    }

    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(callbackToken)
        activeEnhancer?.let { runCatching { it.release() } }
        activeEnhancer = null
        activePlayer?.let { runCatching { it.release() } }
        activePlayer = null
        activeCompletion = null
    }

    private companion object {
        const val TAG = "ConceptFlowRokidAudio"
        const val PRIVATE_VOICE_DIRECTORY = "private/rokid_brand_voice"
        const val QUICKPUB_REPOSITORY = "https://github.com/mmelenciogsc/QUICKPub"
        const val AUDITED_QUICKPUB_REVISION = "27808e8f9d0ec073af6091a6b9a49f1d021779a9"
        const val AUDITED_WORKER_SHA256 =
            "7fa492d8d684bf7c01daef8d65e67a1307e7fe3df16268e796abe85610b9801b"
        const val AUDITED_RUNTIME_MANIFEST_SHA256 =
            "dda48c703de5fc22b997f28921658a6e414a4795ff5e2f4ea3e88b2048389d35"
        const val PRIVATE_ENGINE = "chatterbox-turbo-0.1.7"
        const val MODEL_REVISION = "749d1c1a46eb10492095d68fbcf55691ccf137cd"
        const val PERTH_WATERMARK = "PerTh disclosure watermark retained by Chatterbox"
        const val MAX_PRIVATE_UTTERANCE_MILLIS = 20_000L
        const val MAX_MANIFEST_BYTES = 64L * 1_024L
        const val MAX_VOICE_FILE_BYTES = 20L * 1_024L * 1_024L
        const val HASH_BUFFER_BYTES = 64 * 1_024
        const val PRIVATE_SPEECH_VOLUME = 1.0f
        // +14 dB is five times the previous requested amplitude. Android's
        // LoudnessEnhancer compresses signals that would exceed sample range.
        const val PRIVATE_SPEECH_BOOST_MILLIBELS = 2_400
        const val PRIVATE_SPEECH_SPEED = 1.08f
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val EXPECTED_FILE_NAMES = setOf(
            "concept_flow.wav",
            "machine_intelligence.wav",
            "human_architecture.wav",
            "machine_perception_layer.wav",
            "map.wav",
            "morph.wav",
            "move.wav",
            "supplemental_awareness.wav",
        )
        val EXPECTED_MANIFEST_KEYS = setOf(
            "schemaVersion",
            "sourceRepository",
            "sourceRevision",
            "workerSha256",
            "engine",
            "modelRevision",
            "runtimeManifestSha256",
            "voicePermissionAffirmed",
            "voiceSampleKeptExternal",
            "watermark",
            "outputs",
        )
        val EXPECTED_OUTPUT_KEYS = setOf("file", "sha256")
    }
}

private class ProceduralAmbientBedOutput : AutoCloseable {
    private var activeTrack: AudioTrack? = null

    @Synchronized
    fun start(): Boolean {
        if (activeTrack != null) return true
        val bed = BrandedAmbientBedGenerator.generate()
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(bed.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bed.stereoPcm16.size * Short.SIZE_BYTES)
                .build()
        }.getOrElse { return false }
        val started = runCatching {
            check(audioTrackStateCanAcceptStaticData(track.state))
            check(
                track.write(bed.stereoPcm16, 0, bed.stereoPcm16.size, AudioTrack.WRITE_BLOCKING) ==
                    bed.stereoPcm16.size,
            )
            check(track.setLoopPoints(0, bed.frameCount, -1) == AudioTrack.SUCCESS)
            track.play()
        }.isSuccess
        if (!started) {
            runCatching { track.release() }
            return false
        }
        activeTrack = track
        return true
    }

    @Synchronized
    fun stop() {
        val track = activeTrack ?: return
        activeTrack = null
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    override fun close() = stop()
}

private class ProceduralDeepToneOutput(
    private val handler: Handler,
) : AutoCloseable {
    private val callbackToken = Any()
    private val activeTracks = linkedSetOf<AudioTrack>()
    private var closed = false

    @Synchronized
    fun play(kind: BrandedToneKind): Boolean {
        if (closed) return false
        val tone = BrandedToneGenerator.generate(kind)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(tone.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(tone.stereoPcm16.size * Short.SIZE_BYTES)
                .build()
        }.getOrElse { return false }
        if (!audioTrackStateCanAcceptStaticData(track.state)) {
            track.release()
            return false
        }
        if (track.write(tone.stereoPcm16, 0, tone.stereoPcm16.size, AudioTrack.WRITE_BLOCKING) !=
            tone.stereoPcm16.size
        ) {
            track.release()
            return false
        }
        if (runCatching { track.play() }.isFailure) {
            track.release()
            return false
        }
        activeTracks += track
        handler.postAtTime(
            { release(track) },
            callbackToken,
            SystemClock.uptimeMillis() + BrandedToneGenerator.DURATION_MILLIS + RELEASE_GRACE_MILLIS,
        )
        return true
    }

    @Synchronized
    private fun release(track: AudioTrack) {
        if (activeTracks.remove(track)) track.release()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(callbackToken)
        activeTracks.forEach { runCatching { it.release() } }
        activeTracks.clear()
    }

    private companion object {
        const val RELEASE_GRACE_MILLIS = 200L
    }
}

private class AndroidTextToSpeechOutput(
    context: Context,
    private val handler: Handler,
) : AutoCloseable {
    private val callbackToken = Any()
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private val pending = ArrayDeque<PendingSpeech>()
    private val utteranceIds = AtomicLong(0L)
    private var engine: TextToSpeech? = null
    private var state = State.INITIALIZING
    private val initializationTimeout = Runnable {
        if (state != State.INITIALIZING) return@Runnable
        state = State.FAILED
        runCatching { engine?.shutdown() }
        engine = null
        drainWithoutSpeech()
    }

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            handler.post { onInitialized(status) }
        }
        handler.postDelayed(initializationTimeout, INITIALIZATION_TIMEOUT_MILLIS)
    }

    fun speakExact(text: String, onTerminal: () -> Unit) {
        handler.post {
            when (state) {
                State.INITIALIZING -> pending.addLast(PendingSpeech(text, onTerminal))
                State.READY -> speakNow(text, onTerminal)
                State.FAILED, State.CLOSED -> onTerminal()
            }
        }
    }

    private fun onInitialized(status: Int) {
        if (state != State.INITIALIZING) return
        handler.removeCallbacks(initializationTimeout)
        val tts = engine
        if (status != TextToSpeech.SUCCESS || tts == null) {
            state = State.FAILED
            runCatching { tts?.shutdown() }
            engine = null
            drainWithoutSpeech()
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                complete(utteranceId)
            }

            @Deprecated("Deprecated by Android, retained for API compatibility")
            override fun onError(utteranceId: String?) {
                complete(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                complete(utteranceId)
            }
        })
        configureVoice(tts)
        state = State.READY
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            speakNow(item.text, item.onTerminal)
        }
    }

    private fun configureVoice(tts: TextToSpeech) {
        val availableVoices = tts.voices.orEmpty()
        val selectedCapability = SpeechVoicePolicy.selectPreferredEnglish(
            availableVoices.map { voice ->
                SpeechVoiceCapability(
                    name = voice.name,
                    languageTag = voice.locale.toLanguageTag(),
                    requiresNetwork = voice.isNetworkConnectionRequired,
                    quality = voice.quality,
                    latency = voice.latency,
                )
            },
        )
        val selectedVoice = selectedCapability?.let { capability ->
            availableVoices.firstOrNull { it.name == capability.name }
        }
        if (selectedVoice != null) {
            tts.voice = selectedVoice
            val source = if (selectedVoice.isNetworkConnectionRequired) {
                "network_english_fallback"
            } else {
                "installed_local"
            }
            Log.i(TAG, "tts_voice=${selectedVoice.name} source=$source gender=unspecified")
        } else {
            val languageResult = tts.setLanguage(Locale.US)
            Log.i(TAG, "tts_voice=engine_fallback locale=en-US result=$languageResult gender=unspecified")
        }
        tts.setSpeechRate(SPEECH_RATE)
        tts.setPitch(SPEECH_PITCH)
    }

    private fun speakNow(text: String, onTerminal: () -> Unit) {
        val tts = engine
        if (state != State.READY || tts == null) {
            onTerminal()
            return
        }
        val id = "rokid-brand-${utteranceIds.incrementAndGet()}"
        callbacks[id] = onTerminal
        val parameters = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, SPEECH_VOLUME)
        }
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, parameters, id) == TextToSpeech.ERROR) {
            callbacks.remove(id)?.invoke()
        } else {
            handler.postAtTime(
                {
                    val callback = callbacks.remove(id) ?: return@postAtTime
                    runCatching { tts.stop() }
                    callback()
                },
                callbackToken,
                SystemClock.uptimeMillis() + MAX_UTTERANCE_MILLIS,
            )
        }
    }

    private fun complete(utteranceId: String?) {
        if (utteranceId == null) return
        handler.post { callbacks.remove(utteranceId)?.invoke() }
    }

    private fun drainWithoutSpeech() {
        while (pending.isNotEmpty()) pending.removeFirst().onTerminal()
    }

    override fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        handler.removeCallbacks(initializationTimeout)
        handler.removeCallbacksAndMessages(callbackToken)
        pending.clear()
        callbacks.clear()
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
    }

    private data class PendingSpeech(val text: String, val onTerminal: () -> Unit)

    private enum class State {
        INITIALIZING,
        READY,
        FAILED,
        CLOSED,
    }

    private companion object {
        const val TAG = "ConceptFlowRokidAudio"
        const val INITIALIZATION_TIMEOUT_MILLIS = 5_000L
        const val MAX_UTTERANCE_MILLIS = 20_000L
        const val SPEECH_RATE = 0.92f
        const val SPEECH_PITCH = 0.96f
        const val SPEECH_VOLUME = 0.72f
    }
}
