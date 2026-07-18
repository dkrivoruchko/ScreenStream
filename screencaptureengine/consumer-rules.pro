-keepnames class io.screenstream.engine.internal.jpeg.NativeJpegProcess
-keepclassmembers,allowoptimization,includedescriptorclasses class io.screenstream.engine.internal.jpeg.NativeJpegProcess {
    private native int nativeBootstrap();
    private native java.nio.ByteBuffer nativeAllocateCarrier(long);
    private native void nativeFreeCarrier(java.nio.ByteBuffer);
    private native boolean nativeHasWeakCompressor();
    private native void nativeCompress(java.nio.ByteBuffer, long, int, int, int, int, long, int, int, int, io.screenstream.engine.internal.EncodedStorageOwner$NativeSegmentSink, java.nio.ByteBuffer);
}

-keepnames class io.screenstream.engine.internal.EncodedStorageOwner$NativeSegmentSink
-keepclassmembers,allowoptimization,includedescriptorclasses class io.screenstream.engine.internal.EncodedStorageOwner$NativeSegmentSink {
    private void adoptNativeSegment(java.nio.ByteBuffer, int);
}
