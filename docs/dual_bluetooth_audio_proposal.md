# Dual Bluetooth Audio Output (Proposal)

This document proposes adding support for dual Bluetooth audio output
using Android's Combined Audio Device Routing APIs (Android 12+).

## Summary
Enable simultaneous media playback to multiple Bluetooth devices.

## Intended Changes
- AudioPolicyManager support for multiple preferred output devices
- SystemUI media output picker multi-device selection
- Graceful fallback for devices without multi-stream Audio HAL support

## References
https://source.android.com/docs/core/audio/combined-audio-routing
https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio

## Status
Proposal only. No functional changes included.
