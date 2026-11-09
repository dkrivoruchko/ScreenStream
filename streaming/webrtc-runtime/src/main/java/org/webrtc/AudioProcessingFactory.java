/*
 *  Copyright 2017 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

/** Factory for creating webrtc::AudioProcessing instances. */
public interface AudioProcessingFactory {
  /**
   * Dynamically allocates a {@code webrtc::AudioProcessing} instance.
   *
   * @param webrtcEnvRef non-null non-owning pointer to {@code webrtc::Environment}
   * @return non-null owning scoped_refptr pointer to {@code webrtc::AudioProcessing}
   */
  long createNative(long webrtcEnvRef);
}
