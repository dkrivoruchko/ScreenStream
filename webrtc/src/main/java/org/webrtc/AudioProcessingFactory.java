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
   * Dynamically allocates a webrtc::AudioProcessing instance and returns a pointer to it.
   * The caller takes ownership of the object.
   */
  // TODO: bugs.webrtc.org/369904700 - Remove when implementations switch to
  // another variant.
  @Deprecated
  public default long createNative() {
    return 0;
  }

  public default long createNative(long webrtcEnvRef) {
    return createNative();
  }
}
