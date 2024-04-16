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
package com.example.bluetoothlechat.bluetooth

import android.bluetooth.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.bluetoothlechat.chat.DeviceConnectionState

private const val TAG = "ChatServer"

object ChatServer {

//     hold reference to app context to run the chat server
//    private var app: Application? = null

    // LiveData for reporting the messages sent to the device
    private val _messages = MutableLiveData<Message>()
    val messages = _messages as LiveData<Message>

    // Properties for current chat device connection
    private var currentDevice: BluetoothDevice? = null
    private val _deviceConnection = MutableLiveData<DeviceConnectionState>()
    val deviceConnection = _deviceConnection as LiveData<DeviceConnectionState>

    fun setCurrentChatConnection(device: BluetoothDevice) {
        currentDevice = device
        // Set gatt so BluetoothChatFragment can display the device data
        _deviceConnection.value = DeviceConnectionState.Connected(device)
    }
}