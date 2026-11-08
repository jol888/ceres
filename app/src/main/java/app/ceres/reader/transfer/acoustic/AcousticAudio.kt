package app.ceres.reader.transfer.acoustic

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import app.ceres.reader.transfer.TransferException
import app.ceres.reader.transfer.acoustic.AcousticModem.SAMPLE_RATE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Speaker / microphone glue around [AcousticModem]. */
object AcousticAudio {
    data class ListenProgress(val received: Int, val total: Int, val heard: Int, val corrupted: Int, val level: Float)

    /** Seconds one carousel round takes. */
    fun roundSeconds(packets: List<ByteArray>, profile: AcousticModem.Profile): Double =
        packets.sumOf { AcousticModem.packetSamples(it.size + AcousticModem.PAYLOAD_PARITY, profile) } / SAMPLE_RATE.toDouble()

    fun volumeFraction(context: Context): Float {
        val am = context.getSystemService(AudioManager::class.java) ?: return 1f
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
    }

    /** Plays the packets in a loop for up to [rounds] rounds; cancel the coroutine to stop early. */
    suspend fun broadcast(
        packets: List<ByteArray>,
        profile: AcousticModem.Profile,
        rounds: Int,
        onRound: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val track = newTrack()
        var finished = false
        try {
            track.play()
            write(track, FloatArray(SAMPLE_RATE / 5))
            for (round in 1..rounds) {
                onRound(round)
                for (p in packets) {
                    ensureActive()
                    write(track, AcousticModem.modulate(p, profile))
                }
            }
            write(track, FloatArray(SAMPLE_RATE / 2))
            finished = true
        } finally {
            if (finished) track.stop() else runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    /** A short rising arpeggio played by the receiver once a transfer lands. */
    suspend fun playSuccess() = withContext(Dispatchers.IO) {
        val notes = doubleArrayOf(1046.5, 1318.5, 1568.0, 2093.0)
        val noteLen = SAMPLE_RATE * 11 / 100
        val samples = FloatArray(noteLen * (notes.size + 2))
        notes.forEachIndexed { i, f ->
            val len = if (i == notes.lastIndex) noteLen * 3 else noteLen
            for (n in 0 until len) {
                val t = n.toDouble() / SAMPLE_RATE
                val attack = minOf(1.0, n / (SAMPLE_RATE * 0.005))
                val env = attack * exp(-t * if (i == notes.lastIndex) 6.0 else 14.0)
                val at = i * noteLen + n
                if (at < samples.size) samples[at] += (0.5 * env * sin(2 * PI * f * t)).toFloat()
            }
        }
        val track = newTrack()
        try {
            track.play()
            write(track, samples)
            write(track, FloatArray(SAMPLE_RATE / 4))
            track.stop()
        } finally {
            track.release()
        }
    }

    /** Listens until a whole payload is assembled and returns it. Needs RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    suspend fun listen(context: Context, onProgress: (ListenProgress) -> Unit): ByteArray = withContext(Dispatchers.Default) {
        val record = openRecord(context)
        val shorts = ShortArray(2048)
        val floats = FloatArray(shorts.size)
        val demod = AcousticDemodulator()
        val assembler = AcousticFraming.Assembler()
        var peak = 0f
        var lastReport = 0L
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw TransferException("麦克风被占用")
            var payload: ByteArray? = null
            while (payload == null) {
                ensureActive()
                val n = record.read(shorts, 0, shorts.size)
                if (n < 0) throw TransferException("录音失败（$n）")
                for (i in 0 until n) {
                    floats[i] = shorts[i] / 32768f
                    peak = max(peak, abs(floats[i]))
                }
                for (packet in demod.feed(floats, n)) {
                    payload = assembler.accept(packet) ?: continue
                    break
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= REPORT_MS) {
                    onProgress(ListenProgress(assembler.received, assembler.total, demod.heard, demod.corrupted, peak))
                    peak = 0f
                    lastReport = now
                }
            }
            payload
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(context: Context): AudioRecord {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = listOfNotNull(
            MediaRecorder.AudioSource.UNPROCESSED.takeIf { unprocessed },
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw TransferException("本机不支持录音")
        for (source in sources) {
            val record = runCatching {
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE))
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        throw TransferException("无法打开麦克风")
    }

    private fun newTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf, SAMPLE_RATE))
            .build()
    }

    private fun write(track: AudioTrack, data: FloatArray) {
        var off = 0
        while (off < data.size) {
            val n = track.write(data, off, data.size - off, AudioTrack.WRITE_BLOCKING)
            if (n < 0) throw TransferException("音频输出失败（$n）")
            off += n
        }
    }

    private const val REPORT_MS = 500L
}
