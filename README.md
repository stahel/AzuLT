# AzuLT
An Android app which transcribes Swedish and English using MS Azure Speech Services.
The user interface is inspired by Google Live Transcribe, which has a very nice UI for Deaf people. However, for Swedish, MSFT Azure Speech Services offers a lower word error rate (especially when the audio is bad, like somebody sitting faraway), so I made this for myself to get better Swedish transcription.
An Azure Speech Resource has to be created by the user, and specified in the app Settings.

There are some minor bugs (e.g. text vanishing when rotating the phone) and things remaining to do (making the primary/secondary languages selectable instead of hardcoded Swedish+English) - PRs are very welcome, and feel free to build upon this for your own needs.

License: MIT.
