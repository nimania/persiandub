package com.persiandub.mobile

import android.util.Base64

/** PCM16 little-endian <-> base64 helpers used to talk to the Gemini Live API. */
object AudioUtils {

    fun shortsToBase64(pcm: ShortArray, len: Int): String {
        val bytes = ByteArray(len * 2)
        var j = 0
        for (i in 0 until len) {
            val s = pcm[i].toInt()
            bytes[j++] = (s and 0xFF).toByte()
            bytes[j++] = ((s shr 8) and 0xFF).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun base64ToShorts(b64: String): ShortArray {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val n = bytes.size / 2
        val out = ShortArray(n)
        var j = 0
        for (i in 0 until n) {
            val lo = bytes[j++].toInt() and 0xFF
            val hi = bytes[j++].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }
}
