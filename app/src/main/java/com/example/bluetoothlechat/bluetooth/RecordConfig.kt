package com.example.bluetoothlechat.bluetooth

import android.media.AudioFormat
import java.io.Serializable
import java.util.Locale

class RecordConfig(
    var format: RecordFormat = RecordFormat.WAV,
    var channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    var encodingConfig: Int = AudioFormat.ENCODING_PCM_16BIT,
    var sampleRate: Int = 16000,
) : Serializable {

    var source: Int = SOURCE_MIC

    val encoding: Int
        get() = when (format) {
            RecordFormat.MP3 -> 16
            else -> when (encodingConfig) {
                AudioFormat.ENCODING_PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
                AudioFormat.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                else -> 0
            }
        }

    val channelCount: Int
        get() = when (channelConfig) {
            AudioFormat.CHANNEL_IN_MONO -> AudioFormat.CHANNEL_IN_MONO
            AudioFormat.CHANNEL_IN_STEREO -> AudioFormat.CHANNEL_IN_STEREO
            else -> 0
        }

    val realEncoding: Int
        get() = when (encodingConfig) {
            AudioFormat.ENCODING_PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
            AudioFormat.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
            else -> 0
        }

    override fun toString(): String {
        return String.format(Locale.getDefault(), "录制格式： %s,采样率：%sHz,位宽：%s bit,声道数：%s", format, sampleRate, encoding, channelCount)
    }

    enum class RecordFormat(val extension: String) {
        MP3(".mp3"),
        WAV(".wav"),
        PCM(".pcm");
    }

    companion object {
        const val SOURCE_MIC = 0
        const val SOURCE_SYSTEM = 1
    }
}
