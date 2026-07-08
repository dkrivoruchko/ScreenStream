package org.webrtc.audio;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;

public interface AudioRecordDataCallback {
  /**
   * Invoked after an audio sample is recorded. Can be used to manipulate the ByteBuffer before
   * it is fed into WebRTC. Currently the audio in the ByteBuffer is always PCM 16-bit and the
   * buffer sample size is about 10 ms.
   *
   * @param audioFormat format in android.media.AudioFormat
   */
  void onAudioDataRecorded(
      int audioFormat, int channelCount, int sampleRate, @NonNull ByteBuffer audioBuffer);
}
