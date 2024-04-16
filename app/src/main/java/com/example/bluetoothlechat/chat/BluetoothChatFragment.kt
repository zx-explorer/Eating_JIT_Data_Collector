/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.bluetoothlechat.chat

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothlechat.bluetooth.Message
import com.example.bluetoothlechat.R
import com.example.bluetoothlechat.bluetooth.AudioRecordService
import com.example.bluetoothlechat.bluetooth.ChatServer
import com.example.bluetoothlechat.bluetooth.RecordConfig
import com.example.bluetoothlechat.databinding.FragmentBluetoothChatBinding
import com.example.bluetoothlechat.gone
import com.example.bluetoothlechat.visible
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

private const val TAG = "BluetoothChatFragment"

class BluetoothChatFragment : Fragment() {

    private var _binding: FragmentBluetoothChatBinding? = null
    // this property is valid between onCreateView and onDestroyView.
    private val binding: FragmentBluetoothChatBinding
        get() = _binding!!

    private var deviceConnected: Boolean = false
    private var userFolderPath: String? = null
    private var audioFilePath: String? = null
    private var audioTimestampFilePath: String? = null

    private var audioFormat: RecordConfig.RecordFormat? = RecordConfig.RecordFormat.PCM
    private var channelCount: Int? = AudioFormat.CHANNEL_IN_MONO
    private var encoding: Int? = AudioFormat.ENCODING_PCM_16BIT
    private var sampleRate: Int? = 16000

    private val deviceConnectionObserver = Observer<DeviceConnectionState> { state ->
        when(state) {
            is DeviceConnectionState.Connected -> {
                val device = state.device
                Log.d(TAG, "Gatt connection observer: have device $device")
                chatWith(device)
            }
            is DeviceConnectionState.Disconnected -> {
                showDisconnected()
            }
        }
    }

    private val messageObserver = Observer<Message> { message ->
        Log.d(TAG, "Have message ${message.text}")
        adapter.addMessage(message)
    }

    private val adapter = MessageAdapter()

    private val inputMethodManager by lazy {
        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentBluetoothChatBinding.inflate(inflater, container, false)

        Log.d(TAG, "chatWith: set adapter $adapter")
        binding.messages.layoutManager = LinearLayoutManager(context)
        binding.messages.adapter = adapter

        return binding.root
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.chat_title)
        ChatServer.deviceConnection.observe(viewLifecycleOwner, deviceConnectionObserver)
        ChatServer.messages.observe(viewLifecycleOwner, messageObserver)
    }

    override fun onResume() {
        super.onResume()

        val testUserName = getDataFromSharedPreference("ChatServer", "testUserName")
        testUserName?.let {
            binding.testUserName.setText(testUserName)
            binding.testUserName.isEnabled = false
            binding.userNameConfirm.isEnabled = false
            userFolderPath = File(context?.getExternalFilesDir(null), testUserName).absolutePath
        }

        audioFilePath = getDataFromSharedPreference("AudioService", "AudioFilePath")
        audioFilePath?.let {
            binding.recordAudio.setText(R.string.audio_record_stop_button)
            audioTimestampFilePath = getDataFromSharedPreference("AudioService", "AudioTimestampFilePath")
            val formatTemp = getDataFromSharedPreference("AudioService", "AudioFormat")
            audioFormat = RecordConfig.RecordFormat.valueOf(formatTemp ?: RecordConfig.RecordFormat.PCM.name)
            encoding = getDataFromSharedPreferenceInt("AudioService", "Encoding", AudioFormat.ENCODING_PCM_16BIT)
            channelCount = getDataFromSharedPreferenceInt("AudioService", "ChannelCount", AudioFormat.CHANNEL_IN_MONO)
            sampleRate = getDataFromSharedPreferenceInt("AudioService", "SampleRate", 16000)
        }

        if (!deviceConnected) {findNavController().navigate(R.id.action_find_new_device)}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun saveDataInSharedPreference(prefName: String, key: String, value: Any) {
        val sharedPreferences = context?.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val editor = sharedPreferences?.edit()
        when (value) {
            is Int -> editor?.putInt(key, value)
            is String -> editor?.putString(key, value)
        }
        editor?.apply()
    }

    private fun RemoveDataFromSharedPreference(prefName: String, key: String) {
        val sharedPreferences = context?.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val editor = sharedPreferences?.edit()
        editor?.remove(key)
        editor?.apply()
    }

    private fun getDataFromSharedPreference(prefName: String, key: String): String? {
        val sharedPreferences = context?.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return sharedPreferences?.getString(key, null)
    }

    private fun getDataFromSharedPreferenceInt(prefName: String, key: String, defaultValue: Int = Int.MIN_VALUE): Int {
        val sharedPreferences = context?.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return sharedPreferences?.getInt(key, defaultValue) ?: defaultValue
    }

    private fun setupAudioRecord(config: RecordConfig) {
        Log.d(TAG, "Create Audio File and Start Audio Record")
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss")
        val currentDateTime = dateFormat.format(Date())
        audioFilePath = userFolderPath + "/audio_$currentDateTime.pcm"
        audioTimestampFilePath = userFolderPath + "/audioTimestamp_$currentDateTime.txt"
        Log.e(TAG, "AudioRecordFilePath: ${audioFilePath}")
    }

    private fun makeUserDirectory(userName: String): Boolean {
        // 获取应用的内部存储目录
        val folder = File(context?.getExternalFilesDir(null), userName)
        userFolderPath = folder.absolutePath

        // 检查目录是否存在，如果不存在则创建
        if (!folder.exists()) {
            return folder.mkdir()
        }

        // 如果目录已存在，直接返回true
        return true
    }

    fun checkAndRequireAudioRecordPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 权限尚未被授予，请求权限
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.RECORD_AUDIO), 200)
            return false
        }
        // 权限已被授予
        return true
    }

    fun checkUserDirectory(): Boolean {
        if(userFolderPath == null) {
            getActivity()?.let {
                Toast.makeText(it, "Please confirm the user name first!!!", Toast.LENGTH_SHORT).show()
            }
            return false
        }
        return true
    }

    private fun chatWith(device: BluetoothDevice) {
        binding.connectedContainer.visible()
        binding.notConnectedContainer.gone()

        deviceConnected = true

        val chattingWithString = resources.getString(R.string.chatting_with_device, device.name)
        binding.connectedDeviceName.text = chattingWithString

        binding.userNameConfirm.setOnClickListener {

            val local_user_name = binding.testUserName.text.toString()
            if (local_user_name.isNotEmpty()) {
                binding.testUserName.isEnabled = false
                binding.userNameConfirm.isEnabled = false
                makeUserDirectory(local_user_name)
//                ChatServer.makeUserDirectory(context?.getExternalFilesDir(null), local_user_name)
                saveDataInSharedPreference("ChatServer", "testUserName", local_user_name)
            } else {
                getActivity()?.let {
                    Toast.makeText(it, "Please enter the user name first!!!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.recordAudio.setOnClickListener {
            val audioButtonText = binding.recordAudio.text.toString()
            if (audioButtonText == context?.getString(R.string.audio_record_default_button)) {
                if (!checkUserDirectory() || !checkAndRequireAudioRecordPermission()) {
                    return@setOnClickListener
                }
                binding.recordAudio.setText(R.string.audio_record_stop_button)
                val audioRecordConfig = RecordConfig(audioFormat!!,
                    channelCount!!, encoding!!, sampleRate!!)
                setupAudioRecord(audioRecordConfig)
                saveDataInSharedPreference("AudioService", "AudioFilePath", audioFilePath!!)
                saveDataInSharedPreference("AudioService", "AudioTimestampFilePath", audioTimestampFilePath!!)
                saveDataInSharedPreference("AudioService", "AudioFormat", audioFormat!!.name)
                saveDataInSharedPreference("AudioService", "Encoding", encoding!!)
                saveDataInSharedPreference("AudioService", "ChannelCount", channelCount!!)
                saveDataInSharedPreference("AudioService", "SampleRate", sampleRate!!)
                val intent = Intent(activity, AudioRecordService::class.java)
                intent.putExtra("AudioFilePath", audioFilePath)
                intent.putExtra("AudioTimestampFilePath", audioTimestampFilePath)
                intent.putExtra("AudioRecordConfig", audioRecordConfig)
                activity?.startService(intent)
//                ChatServer.startAudioRecord(audioRecordConfig)
            } else {
                binding.recordAudio.setText(R.string.audio_record_default_button)
                binding.testUserName.isEnabled = true
                binding.userNameConfirm.isEnabled = true

                val intent = Intent(activity, AudioRecordService::class.java)
                activity?.stopService(intent)

                RemoveDataFromSharedPreference("AudioService", "AudioFilePath")
                RemoveDataFromSharedPreference("AudioService", "AudioTimestampFilePath")
                RemoveDataFromSharedPreference("AudioService", "AudioFormat")
                RemoveDataFromSharedPreference("AudioService", "Encoding")
                RemoveDataFromSharedPreference("AudioService", "ChannelCount")
                RemoveDataFromSharedPreference("AudioService", "SampleRate")
                RemoveDataFromSharedPreference("ChatServer", "testUserName")
//                ChatServer.stopAudioRecord()
                userFolderPath = null
                audioFilePath = null
                audioTimestampFilePath = null
            }
        }
    }

    private fun showDisconnected() {
        hideKeyboard()
        binding.notConnectedContainer.visible()
        binding.connectedContainer.gone()
    }

    private fun hideKeyboard() {
        inputMethodManager.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}