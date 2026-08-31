package com.persiandub.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue

class DubService : Service() {

    companion object {
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_LANG = "lang"
        const val EXTRA_SOURCE = "source" // "internal" | "mic"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.persiandub.mobile.STOP"

        private const val CHANNEL_ID = "persiandub"
        private const val NOTIF_ID = 1001

        private const val IN_RATE = 16000
        private const val OUT_RATE = 24000

        @Volatile var isRunning = false
        var statusListener: ((String) -> Unit)? = null

        private fun postStatus(s: String) {
            Handler(Looper.getMainLooper()).post { statusListener?.invoke(s) }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var gemini: GeminiClient? = null

    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    @Volatile private var running = false
    private val playQueue = LinkedBlockingQueue<ShortArray>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running || intent == null) return START_NOT_STICKY

        val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        val lang = intent.getStringExtra(EXTRA_LANG) ?: "fa"
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "internal"
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

        startAsForeground(source)

        try {
            if (source == "internal") {
                startInternalCapture(resultCode, data)
            } else {
                startMicCapture()
            }
            startPlayback()
            startGemini(apiKey, lang)
            running = true
            isRunning = true
            beginCaptureLoop()
            postStatus("در حال اتصال…")
        } catch (e: Exception) {
            postStatus("خطا: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(source: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PersianDub", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DubService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PersianDub فعال است")
            .setContentText("در حال دوبله‌ی زنده به فارسی")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "توقف", stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (source == "internal")
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun minBuf(): Int {
        val m = AudioRecord.getMinBufferSize(
            IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return maxOf(m, 3200)
    }

    private fun startMicCapture() {
        @Suppress("MissingPermission")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            IN_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf()
        )
    }

    private fun startInternalCapture(resultCode: Int, data: Intent?) {
        if (data == null) throw IllegalStateException("مجوز ضبط صفحه داده نشد.")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection ساخته نشد.")
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(IN_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        @Suppress("MissingPermission")
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf())
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    private fun startGemini(apiKey: String, lang: String) {
        gemini = GeminiClient(
            apiKey = apiKey,
            targetLang = lang,
            onAudio = { shorts -> playQueue.offer(shorts) },
            onReady = { postStatus("دوبله فعال است. صدای فارسی به‌زودی شروع می‌شود.") },
            onError = { err ->
                postStatus(err)
                stopSelf()
            },
            onClosed = { }
        )
        gemini?.connect()
    }

    private fun startPlayback() {
        val minOut = AudioTrack.getMinBufferSize(
            OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minOut, OUT_RATE)) // ~1s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        playThread = Thread {
            val track = audioTrack ?: return@Thread
            while (running || playQueue.isNotEmpty()) {
                val chunk = try { playQueue.take() } catch (_: InterruptedException) { break }
                try { track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    private fun beginCaptureLoop() {
        captureThread = Thread {
            val record = audioRecord ?: return@Thread
            try { record.startRecording() } catch (e: Exception) {
                postStatus("شروع ضبط ناموفق: ${e.message}"); stopSelf(); return@Thread
            }
            val buf = ShortArray(1600) // 100 ms @ 16 kHz
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) gemini?.sendAudio(buf, n)
            }
        }.also { it.start() }
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        try { captureThread?.interrupt() } catch (_: Exception) {}
        try { playThread?.interrupt() } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        try { gemini?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        audioRecord = null; audioTrack = null; gemini = null; mediaProjection = null
        postStatus("متوقف شد.")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
