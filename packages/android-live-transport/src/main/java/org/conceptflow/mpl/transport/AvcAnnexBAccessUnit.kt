// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

/** Structural validation for the independently decodable AVC access units used on the camera lane. */
object AvcAnnexBAccessUnit {
    const val MEDIA_TYPE = "video/avc"
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_IDR = 5

    data class Inspection(
        val nalUnitTypes: List<Int>,
        val sequenceParameterSet: ByteArray?,
        val pictureParameterSet: ByteArray?,
    ) {
        val independentlyDecodable: Boolean
            get() = sequenceParameterSet != null && pictureParameterSet != null && NAL_IDR in nalUnitTypes
    }

    fun inspect(bytes: ByteArray): Inspection {
        val starts = findStartCodes(bytes)
        if (starts.isEmpty() || starts.first().offset != 0) return Inspection(emptyList(), null, null)
        val types = ArrayList<Int>(starts.size)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        starts.forEachIndexed { index, start ->
            val header = start.offset + start.length
            val end = if (index + 1 < starts.size) starts[index + 1].offset else bytes.size
            if (header >= end || bytes[header].toInt() and 0x80 != 0) {
                return Inspection(emptyList(), null, null)
            }
            val type = bytes[header].toInt() and 0x1f
            types += type
            val unit = bytes.copyOfRange(start.offset, end)
            if (type == NAL_SPS && sps == null) sps = unit
            if (type == NAL_PPS && pps == null) pps = unit
        }
        return Inspection(types, sps, pps)
    }

    fun requireIndependent(bytes: ByteArray): Inspection = inspect(bytes).also {
        require(it.independentlyDecodable) { "AVC access unit must contain SPS, PPS, and IDR" }
    }

    fun join(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf(ByteArray::size)
        return ByteArray(total).also { output ->
            var offset = 0
            parts.forEach { part ->
                part.copyInto(output, offset)
                offset += part.size
            }
        }
    }

    private fun findStartCodes(bytes: ByteArray): List<StartCode> {
        val result = ArrayList<StartCode>()
        var index = 0
        while (index + 3 <= bytes.size) {
            val length = when {
                index + 4 <= bytes.size && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
                bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (length > 0) {
                result += StartCode(index, length)
                index += length
            } else {
                index += 1
            }
        }
        return result
    }

    private data class StartCode(val offset: Int, val length: Int)
}
