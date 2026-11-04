# WebRTC Runtime

This module contains the vendored Android WebRTC runtime used by the Play Store
variant of ScreenStream.

## Upstream Baseline

- WebRTC version: `150.0.7871.63`
- Chromium/WebRTC branch: `refs/branch-heads/7871`
- WebRTC commit: `1f975dfd761af6e5d76d28333191973b258d82a8`

The Java package names are intentionally left as upstream expects:

- `org.webrtc.*`
- `org.jni_zero.*`

Do not rename these packages. The native WebRTC library expects these class
names for JNI bindings.

## Contents

- `src/main/java/org/webrtc/**`: upstream WebRTC Android Java API/runtime
  sources plus the local patches listed below.
- `src/main/java/org/jni_zero/**`: JNI Zero Java runtime sources required by
  WebRTC M150.
- `src/main/jniLibs/**/libjingle_peerconnection_so.so`: patched native WebRTC
  libraries.

## Local Patches

Apply these patches to upstream WebRTC before building native libraries and
copying Java sources into this module:

- `patches/rtp-sender-generate-keyframe.patch`
  - Adds Android Java/JNI access to `RtpSenderInterface::GenerateKeyFrame(...)`.
  - Exposes `RtpSender.generateKeyFrame()` and
    `RtpSender.generateKeyFrame(List<String> rids)`.
- `patches/audio-record-data-callback.patch`
  - Adds `AudioRecordDataCallback`.
  - Lets `JavaAudioDeviceModule.Builder` pass the callback into
    `WebRtcAudioRecord`.
  - Invokes the callback after microphone audio is read and before the buffer is
    passed to native WebRTC.

## Native Library SHA-256

```text
armeabi-v7a  08fc563e06d308687bf207aee609dd3d8c663af67980bdb4bde0491642b47931
arm64-v8a    57784faf0d9fed384673b799f870f44818cec590c557883fd3abe696ab59847c
x86          9fd7ec63b8a1d62a940aaa931536c77cd86d7c9b6d353c70fe3c4c638759b3f9
x86_64       d5120ecc7f314a1573db61caf02a53e0dbae0666ad9cd6585c743d25758432fc
```
