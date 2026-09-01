package io.screenstream.capture.internal.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer

internal class NativeJpegRegistrationReflectionTest {
    // Verification: ENC-04
    @Test
    fun privateRegistrationPacketMatchesMaintainedPreShrinkAbiWithoutInitializationOrInvocation() {
        val loader = checkNotNull(javaClass.classLoader)
        val runtimeClass = Class.forName(RUNTIME_BINARY_NAME, false, loader)
        val sinkClass = Class.forName(SINK_BINARY_NAME, false, loader)
        assertEquals(RUNTIME_BINARY_NAME, runtimeClass.name)
        assertEquals(SINK_BINARY_NAME, sinkClass.name)

        val expectedRuntimePackets = setOf(
            "nativeAllocateCarrier(J)Ljava/nio/ByteBuffer;",
            "nativeFreeCarrier(Ljava/nio/ByteBuffer;)V",
            "nativeHasWeakCompressor()Z",
            "nativeCompress(Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/capture/internal/encoding/NativeSegmentSink;Ljava/nio/ByteBuffer;)V",
        )
        val runtimeMethods = runtimeClass.declaredMethods.filter { method ->
            expectedRuntimePackets.any { packet -> packet.startsWith(method.name + "(") }
        }
        assertEquals(expectedRuntimePackets, runtimeMethods.map(::packet).toSet())
        runtimeMethods.forEach { method ->
            assertEquals(PRIVATE_FINAL_NATIVE, method.modifiers)
        }

        val sinkMethod = sinkClass.getDeclaredMethod(
            "adoptNativeSegment",
            ByteBuffer::class.java,
            Integer.TYPE,
        )
        assertEquals(
            "adoptNativeSegment(Ljava/nio/ByteBuffer;I)V",
            packet(sinkMethod),
        )
        assertEquals(PRIVATE_FINAL, sinkMethod.modifiers)
        assertFalse(Modifier.isNative(sinkMethod.modifiers))
    }

    private fun packet(method: Method): String =
        method.name + MethodType.methodType(method.returnType, method.parameterTypes.toList()).toMethodDescriptorString()

    private companion object {
        private const val RUNTIME_BINARY_NAME: String =
            "io.screenstream.capture.internal.encoding.NativeJpegProcess"
        private const val SINK_BINARY_NAME: String =
            "io.screenstream.capture.internal.encoding.NativeSegmentSink"
        private val PRIVATE_FINAL: Int = Modifier.PRIVATE or Modifier.FINAL
        private val PRIVATE_FINAL_NATIVE: Int = PRIVATE_FINAL or Modifier.NATIVE
    }
}
