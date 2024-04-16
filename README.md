
Android Audio Recorder
===================================

This file shows how to record audio data from bluetooth connected audio devices.

Use of the app
------------

- Download the whole program and open as an Android Studio project
- Install the program on your android phones
- Open the app, and enable all the permissions required by the app
  - Location + Bluetooth + Audio Record permissions
- Connect your Bluetooth audio devices in the settings -> Bluetooth page
- Back to the app, enter the user_name and confirm; then click the "Audio Record" button to start record.
- **Stop the record: **once you click the "Audio Record", the text of the button changed to "Audio Stop", just push the button again.



##  Export the recorded files

- Use adb tools and enter the specific path on the phone: /storage/emulated/0/Android/data/com.example.bluetoothlechat/files. 
- You will find the folder with same name as the user name you confirmed before. 
- A pair of PCM and txt files contain the raw audio signals with the corresponding timestamps respectly.



## TODO List

- Add the function to record videos (only when the video recorder support remote data transmission)
- UX design and add the configuration page allowing users to specify the sampling rate or file format etc.
- Find a corresponding version in iPhone and test whether iron produces noise during recording process.
