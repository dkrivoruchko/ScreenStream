-keepnames class io.screenstream.capture.internal.encoding.NativeJpegProcess
-keepclassmembers,allowoptimization,includedescriptorclasses class io.screenstream.capture.internal.encoding.NativeJpegProcess {
    private java.nio.ByteBuffer nativeAllocateCarrier(long);
    private void nativeFreeCarrier(java.nio.ByteBuffer);
    private boolean nativeHasWeakCompressor();
    private void nativeCompress(java.nio.ByteBuffer, long, int, int, int, int, long, int, int, int, io.screenstream.capture.internal.encoding.NativeSegmentSink, java.nio.ByteBuffer);
}

-keepnames class io.screenstream.capture.internal.encoding.NativeSegmentSink
-keepclassmembers,allowoptimization,includedescriptorclasses class io.screenstream.capture.internal.encoding.NativeSegmentSink {
    private void adoptNativeSegment(java.nio.ByteBuffer, int);
}
