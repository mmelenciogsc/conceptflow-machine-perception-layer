// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.conceptflow.mpl.transport.DeterministicI420Fixture

/** Shell-only debug exporter for the synthetic hardware-AVC fidelity fixture. */
class CodecFidelityDebugProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val status = if (Binder.getCallingUid() in setOf(Process.SHELL_UID, Process.ROOT_UID)) {
            when {
                uri.pathSegments == listOf(EXPORT_PATH) -> exportFixture(DEFAULT_BIT_RATE)
                uri.pathSegments.size == 2 && uri.pathSegments.first() == EXPORT_PATH ->
                    exportFixture(parseBitRate(uri.pathSegments.last()))
                uri.pathSegments == listOf(CLEAR_PATH) -> clearFixture()
                else -> "codec_fidelity_unknown_path"
            }
        } else {
            "codec_fidelity_unauthorized"
        }
        return MatrixCursor(arrayOf(STATUS_COLUMN), 1).apply { addRow(arrayOf(status)) }
    }

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Debug codec fidelity provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Debug codec fidelity provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Debug codec fidelity provider is read-only")

    private fun exportFixture(bitRate: Int?): String {
        if (bitRate == null) return "codec_fidelity_invalid_bitrate"
        if (!running.compareAndSet(false, true)) return "codec_fidelity_export_busy"
        val directory = requireNotNull(context).noBackupFilesDir.resolve(DIRECTORY_NAME)
        val target = directory.resolve(OUTPUT_FILE_NAME)
        val temporary = directory.resolve("$OUTPUT_FILE_NAME.tmp")
        val referenceInput = directory.resolve(REFERENCE_FILE_NAME)
        var fixture: ByteArray? = null
        return try {
            check(directory.isDirectory || directory.mkdirs())
            temporary.delete()
            fixture = if (referenceInput.isFile) {
                check(referenceInput.length() == EXPECTED_I420_BYTES.toLong())
                referenceInput.readBytes()
            } else {
                DeterministicI420Fixture.create()
            }
            val captureNs = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
            val encodedResult = HardwareAvcIntraFrameEncoder(
                DeterministicI420Fixture.WIDTH,
                DeterministicI420Fixture.HEIGHT,
                FRAME_RATE,
                bitRate,
            ).use { encoder ->
                encoder.codecName to encoder.encode(requireNotNull(fixture), captureNs)
            }
            val codecName = encodedResult.first
            val encoded = encodedResult.second
            temporary.outputStream().buffered().use { it.write(encoded) }
            check(temporary.length() == encoded.size.toLong())
            if (target.exists()) check(target.delete())
            check(temporary.renameTo(target))
            "codec_fidelity_export_ready" +
                " bytes_${encoded.size}" +
                " bitrate_$bitRate" +
                " codec_${sanitize(codecName)}" +
                " reference_sha256_${sha256(requireNotNull(fixture))}" +
                " encoded_sha256_${sha256(encoded)}"
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            "codec_fidelity_export_failed_${error.javaClass.simpleName}"
        } finally {
            fixture?.fill(0)
            referenceInput.delete()
            running.set(false)
        }
    }

    private fun clearFixture(): String {
        if (running.get()) return "codec_fidelity_clear_busy"
        val directory = requireNotNull(context).noBackupFilesDir.resolve(DIRECTORY_NAME)
        val deleted = listOf(OUTPUT_FILE_NAME, "$OUTPUT_FILE_NAME.tmp", REFERENCE_FILE_NAME)
            .map { directory.resolve(it) }
            .all { !it.exists() || it.delete() }
        if (directory.isDirectory && directory.list()?.isEmpty() == true) directory.delete()
        return if (deleted) "codec_fidelity_cleared" else "codec_fidelity_clear_failed"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun parseBitRate(value: String): Int? = value.toIntOrNull()?.takeIf(ALLOWED_BIT_RATES::contains)

    private companion object {
        val running = AtomicBoolean(false)
        const val DIRECTORY_NAME = "codec-fidelity"
        const val OUTPUT_FILE_NAME = "fixture.avc"
        const val REFERENCE_FILE_NAME = "reference.i420"
        const val EXPORT_PATH = "export"
        const val CLEAR_PATH = "clear"
        const val STATUS_COLUMN = "status"
        const val MIME_TYPE = "vnd.android.cursor.item/vnd.conceptflow.codec-fidelity"
        const val FRAME_RATE = 5
        const val DEFAULT_BIT_RATE = 1_500_000
        const val EXPECTED_I420_BYTES = 640 * 640 * 3 / 2
        val ALLOWED_BIT_RATES = setOf(1_500_000, 3_000_000, 6_000_000, 9_000_000, 12_000_000)
    }
}
