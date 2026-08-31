package com.persiandub.mobile

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the Gemini Live API in live-translation mode.
 * Audio in (16 kHz PCM16) -> translated audio out (24 kHz PCM16).
 *
 * ⚠️ Live translation is a PREVIEW API. If the connection is rejected
 * (e.g. close code 1007), the two things most likely to need updating are the
 * [model] id and the `setup` message below. See:
 *   https://ai.google.dev/gemini-api/docs/live-api/live-translate
 */
class GeminiClient(
    private val apiKey: String,
    private val targetLang: String,
    private val model: String = "models/gemini-3.5-live-translate-preview",
    private val onAudio: (ShortArray) -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var ready = false

    fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=" + apiKey
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun isReady() = ready

    fun sendAudio(pcm: ShortArray, len: Int) {
        val w = ws ?: return
        if (!ready) return
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", AudioUtils.shortsToBase64(pcm, len))
                })
            })
        }
        w.send(payload.toString())
    }

    fun close() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        ready = false
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val setup = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", model)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        // translationConfig MUST be inside generationConfig.
                        put("translationConfig", JSONObject().apply {
                            put("targetLanguageCode", targetLang)
                            put("echoTargetLanguage", true)
                        })
                    })
                })
            }
            webSocket.send(setup.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (code != 1000) onError("اتصال بسته شد (code $code) ${reason}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ready = false
            onClosed()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ready = false
            onError(t.message ?: "خطای اتصال به Gemini")
        }
    }

    private fun handle(text: String) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }

        if (msg.has("setupComplete")) {
            ready = true
            onReady()
            return
        }
        val parts = msg.optJSONObject("serverContent")
            ?.optJSONObject("modelTurn")
            ?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val data = inline.optString("data", "")
            if (data.isNotEmpty()) onAudio(AudioUtils.base64ToShorts(data))
        }
    }
}
