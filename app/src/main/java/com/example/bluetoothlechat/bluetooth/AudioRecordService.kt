package com.example.bluetoothlechat.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.example.bluetoothlechat.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max


private const val TAG = "AudioRecordService"

class AudioRecordService : Service() {
    private val NOTIFICATION_AUDIORECORD_SERVICE_ID: Int = 8
    private val NOTIFICATION_AUDIOCONNECTION_SERVICE_ID: Int = 11
    private val CHANNEL_ID: String = "AudioRecordService"

    @Volatile
    private var audioFilePath: String? = null
    @Volatile
    private var audioTimestampFilePath: String? = null

    private val _bluetoothConnected = MutableLiveData<Boolean>()
    val bluetoothConnected = _bluetoothConnected as LiveData<Boolean>

    private val bluetoothConnectedObserver = Observer<Boolean> { state ->
        when(state) {
            false -> postNotification("Bluetooth Disconnected")
            true -> {}
        }
    }

    private val _dataRecordingState = MutableLiveData<Boolean>()
    val dataRecordingState = _dataRecordingState as LiveData<Boolean>

    private val dataRecordingStateObserver = Observer<Boolean> { state ->
        when(state) {
            true -> updateNotification("Collect Data ing...")
            false -> updateNotification("!!!Collect Stopped!!!")
        }
    }

    @Volatile
    private var audioRecordThread: AudioRecordThread? = null
    private lateinit var bluetoothScoReceiver: BroadcastReceiver
    private var audioRecordConfig: RecordConfig? = null

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val BUFFER_SIZE_MULTIPLIER: Int = 2
    private val DEFAULT_READ_INTERVAL_MICROS: Int = 10_000
    private val MICROS_PER_SECOND: Long = 1_000_000
    private val NANOS_PER_MICROS: Long = 1_000
    private val NANOS_PER_MILLIS: Long = 1_000_000
    private val NANOS_PER_SECOND: Long = 1_000_000_000
    private val UNINITIALIZED_TIMESTAMP: Long = Long.MIN_VALUE

    private fun createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AudioDataRecordService", NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioDataCollect")
            .setContentText("We are collecting your audio data")
            .setSmallIcon(R.mipmap.microphone) // 确保这个图标存在
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(null)
        // 创建通知
        val notification: Notification = notificationBuilder.build()
        // 设置为前台服务
        startForeground(NOTIFICATION_AUDIORECORD_SERVICE_ID, notification)
    }

    private fun updateNotification(content: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioDataCollect")
            .setContentText(content)
            .setSmallIcon(R.mipmap.microphone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // 调用startForeground来更新现有的前台服务通知
        startForeground(NOTIFICATION_AUDIORECORD_SERVICE_ID, builder.build())
    }

    private fun postNotification(content: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioDataCollect")
            .setContentText(content)
            .setSmallIcon(R.mipmap.microphone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // 调用startForeground来更新现有的前台服务通知
        startForeground(NOTIFICATION_AUDIOCONNECTION_SERVICE_ID, builder.build())
    }

    override fun onCreate() {
        super.onCreate()

        bluetoothScoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    _bluetoothConnected.postValue(true)
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    _bluetoothConnected.postValue(false)
                }
            }
        }

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        registerReceiver(bluetoothScoReceiver, filter)

        // 启动蓝牙SCO音频连接
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.startBluetoothSco()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        audioRecordConfig = intent.getSerializableExtra("AudioRecordConfig") as RecordConfig
        audioFilePath = intent.getStringExtra("AudioFilePath")
        audioTimestampFilePath = intent.getStringExtra("AudioTimestampFilePath")

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange: Int -> }
            .build()
        // Request audio focus
        audioFocusRequest?.let {
            val focusRequestResult = audioManager.requestAudioFocus(it)
            if (focusRequestResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.e(TAG, "Audio focus not granted")
                // 处理未获得音频焦点的情况
            }
        }

        audioRecordThread = AudioRecordThread(audioRecordConfig!!)
        audioRecordThread?.start()

        createNotification()
        bluetoothConnected.observeForever(bluetoothConnectedObserver)
        dataRecordingState.observeForever(dataRecordingStateObserver)
        return START_REDELIVER_INTENT
    }

    private inner class AudioRecordThread(private val config: RecordConfig) : Thread() {
        private val audioRecord: AudioRecord
        private val bytesPerFrame: Int
        private val audioRecordBufferSize: Int
        private val audioRecordPacketSize: Int
        private val minBufferSize: Int
        private var startRecordingTimestampNanos: Long = UNINITIALIZED_TIMESTAMP
        private var startRecordingTimestampSystemMills: Long = UNINITIALIZED_TIMESTAMP

        init {
            bytesPerFrame = when(config.encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_16BIT -> 2
                else -> 4
            } * when(config.channelCount) {
                AudioFormat.CHANNEL_IN_MONO -> 1
                AudioFormat.CHANNEL_IN_STEREO -> 2
                else -> 0
            }
            audioRecordPacketSize = ceil(1.0 * bytesPerFrame * config.sampleRate * DEFAULT_READ_INTERVAL_MICROS.toDouble() / MICROS_PER_SECOND).toInt()
            minBufferSize = AudioRecord.getMinBufferSize(config.sampleRate, config.channelCount, config.encoding)
            audioRecordBufferSize = max(audioRecordPacketSize, minBufferSize) * BUFFER_SIZE_MULTIPLIER
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, config.sampleRate, config.channelCount, config.encoding, audioRecordBufferSize)
        }

        @Throws(IOException::class)
        private fun readAudioPacket(audioPacket: ByteBuffer) {
            var totalNumBytesRead = 0
            while (totalNumBytesRead < audioPacket.capacity()) {
                val bytesRemaining = audioPacket.capacity() - totalNumBytesRead
                val numBytesRead: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioRecord.read(audioPacket, bytesRemaining, AudioRecord.READ_BLOCKING)
                } else {
                    audioRecord.read(audioPacket, bytesRemaining)
                }

                if (numBytesRead <= 0) {
                    val error = when (numBytesRead) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                        else -> "ERROR"
                    }
                    throw IOException("AudioRecord.read(...) failed due to $error")
                }

                totalNumBytesRead += numBytesRead
                audioPacket.position(totalNumBytesRead)
            }
//            audioPacket.position(0)  // Reset the position of the ByteBuffer for consumption.
        }

        private fun getTimestampNanos(framePosition: Long): Long {
            var referenceFrame: Long = 0
            var referenceTimestamp: Long = startRecordingTimestampNanos
            val audioTimestamp: AudioTimestamp? = getAudioRecordTimestamp()
            audioTimestamp?.let {
                referenceFrame = it.framePosition
                referenceTimestamp = it.nanoTime
            }
            // 假设第一帧在 0 纳秒读取，对于 48kHz 的采样率，这个时间戳最多可以是
            // (2**63 - 1) / 48000 纳秒。
            return referenceTimestamp + (framePosition - referenceFrame) * NANOS_PER_SECOND / config.sampleRate
        }

        private fun getAudioRecordTimestamp(): AudioTimestamp? {
            checkNotNull(audioRecord)
            val audioTimestamp = AudioTimestamp()
            val status = audioRecord.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
            if (status == AudioRecord.SUCCESS) {
                return audioTimestamp
            } else {
                Log.e(TAG, "audioRecord.getTimestamp failed with status: $status")
            }
            return null
        }

        override fun run() {
            super.run()
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.d(TAG, "开始录制")

            startRecordingTimestampNanos = System.nanoTime()
            startRecordingTimestampSystemMills = System.currentTimeMillis()

            Log.e(TAG, "SystemNanoTime: $startRecordingTimestampNanos")
            var totalNumFramesRead: Long = 0

            try {
                audioRecord.startRecording()

                FileOutputStream(audioFilePath).use { fos ->
                    val fileChannel = fos.channel
                    val audioData: ByteBuffer = ByteBuffer.allocateDirect(audioRecordPacketSize)
                    File(audioTimestampFilePath).bufferedWriter().use { writer ->
                        while (audioFilePath != null) {
                            try {
                                audioData.clear()
                                readAudioPacket(audioData)
                                val timestampMicros: Long = (getTimestampNanos(totalNumFramesRead)
                                        - startRecordingTimestampNanos) / NANOS_PER_MILLIS + startRecordingTimestampSystemMills

                                totalNumFramesRead += (audioData.limit() / bytesPerFrame).toInt()

                                audioData.flip()

                                while (audioData.hasRemaining()) {
                                    _dataRecordingState.postValue(true)
                                    fileChannel.write(audioData)
                                }

                                writer.appendLine("$timestampMicros")
                            } catch (ioException: IOException) {
                                Log.e(TAG, "Error reading audio packet: ${ioException.message}")
                                continue
                            }
                        }
                    }
                }
                audioRecord.stop()
                audioRecord.release()
            } catch (e: Exception) {
                _dataRecordingState.postValue(false)
                Log.e(TAG, "AudioRecordingMessage: ${e.message}", e)
            }
            _dataRecordingState.postValue(false)
            Log.d(TAG, "录音结束")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(bluetoothScoReceiver)

        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        audioManager.stopBluetoothSco()

        audioFilePath = null
        audioTimestampFilePath = null
        try {
            if (audioRecordThread != null) {
                audioRecordThread?.join()
            }
            audioRecordThread = null
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecordStopError: ${e.message}")
        }
        stopForeground(true)
        bluetoothConnected.removeObserver(bluetoothConnectedObserver)
        dataRecordingState.removeObserver(dataRecordingStateObserver)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}