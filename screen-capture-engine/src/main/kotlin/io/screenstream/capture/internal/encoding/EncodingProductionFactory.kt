package io.screenstream.capture.internal.encoding

internal interface EncodingProductionFactory {
    fun createNativeTransaction(): NativeEncodedTransaction

    fun createFrameworkTransaction(): FrameworkEncodedTransaction

    fun createNativeProduction(
        runtime: EncoderRuntime,
        expectedInput: EncodingInput,
        transaction: NativeEncodedTransaction,
        jpegQuality: Int,
        healthCell: NativeHealthCell,
        nativeJpeg: NativeJpegFacade,
    ): NativeJpegProduction?

    fun createFrameworkProduction(
        runtime: EncoderRuntime,
        expectedInput: EncodingInput,
        transaction: FrameworkEncodedTransaction,
        jpegQuality: Int,
    ): FrameworkJpegProduction?
}

internal object DefaultEncodingProductionFactory : EncodingProductionFactory {
    override fun createNativeTransaction(): NativeEncodedTransaction = NativeEncodedTransaction()

    override fun createFrameworkTransaction(): FrameworkEncodedTransaction = FrameworkEncodedTransaction()

    override fun createNativeProduction(
        runtime: EncoderRuntime,
        expectedInput: EncodingInput,
        transaction: NativeEncodedTransaction,
        jpegQuality: Int,
        healthCell: NativeHealthCell,
        nativeJpeg: NativeJpegFacade,
    ): NativeJpegProduction? = runtime.newNativeProduction(
        expectedInput = expectedInput,
        transaction = transaction,
        jpegQuality = jpegQuality,
        healthCell = healthCell,
        nativeJpeg = nativeJpeg,
    )

    override fun createFrameworkProduction(
        runtime: EncoderRuntime,
        expectedInput: EncodingInput,
        transaction: FrameworkEncodedTransaction,
        jpegQuality: Int,
    ): FrameworkJpegProduction? = runtime.newFrameworkProduction(
        expectedInput = expectedInput,
        transaction = transaction,
        jpegQuality = jpegQuality,
    )
}
