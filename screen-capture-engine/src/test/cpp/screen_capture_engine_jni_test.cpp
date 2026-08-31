#include <android/bitmap.h>
#include <android/data_space.h>
#include <jni.h>

#include <array>
#include <cstdarg>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved);

struct _jmethodID final {
    int value = 0;
};

namespace {

    constexpr const char *kFacadeClassName =
            "io/screenstream/capture/internal/encoding/NativeJpegProcess";
    constexpr const char *kSinkMethodName = "adoptNativeSegment";
    constexpr const char *kSinkMethodDescriptor = "(Ljava/nio/ByteBuffer;I)V";
    constexpr std::size_t kResultBlockByteCount = 16;
    constexpr std::size_t kProducedByteCountOffset = 0;
    constexpr std::size_t kWireStatusOffset = 8;
    constexpr std::int64_t kPendingWord = -1;
    constexpr std::int64_t kCompleteStatus = 0;
    constexpr std::int64_t kInternalFailureStatus = 3;
    constexpr std::int64_t kJavaThrowableStatus = 4;

    enum class ReferenceKind {
        Generic,
        Class,
        Buffer,
        Sink,
        Throwable,
    };

    struct FakeReference final : _jclass {
        ReferenceKind kind = ReferenceKind::Generic;
        void *address = nullptr;
        jlong capacity = -1;
        bool local = false;
        bool active = true;
        std::string className;
    };

    struct RegisteredMethod final {
        std::string name;
        std::string descriptor;
        void *function = nullptr;
    };

    struct FakeJniState final {
        std::vector<std::unique_ptr<FakeReference>> references;
        std::vector<RegisteredMethod> registeredMethods;
        std::vector<std::uint8_t> adoptedBytes;

        std::string lastFoundClassName;
        std::string lastMethodName;
        std::string lastMethodDescriptor;
        std::string thrownClassName;
        jint requestedJniVersion = 0;

        jint getEnvResult = JNI_OK;
        bool getEnvReturnsNull = false;
        bool failFacadeLookup = false;
        jint registerResult = JNI_OK;
        jthrowable failureThrowable = nullptr;
        jthrowable pendingThrowable = nullptr;
        jthrowable failNextDirectViewWith = nullptr;
        jthrowable sinkThrowable = nullptr;
        jthrowable compressorThrowable = nullptr;
        bool sinkThrows = false;
        bool compressorLeavesThrowable = false;
        bool forbiddenCallWhilePending = false;

        std::vector<std::uint8_t> compressorBytes = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77};
        std::int32_t compressorResult = ANDROID_BITMAP_RESULT_SUCCESS;
        bool compressorDescriptorMatched = false;
        bool compressorWriteSucceeded = false;

        const void *expectedPixels = nullptr;
        std::uint32_t expectedWidth = 2;
        std::uint32_t expectedHeight = 2;
        std::uint32_t expectedStride = 8;
        std::uint32_t expectedFlags = ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
        std::int32_t expectedFormat = ANDROID_BITMAP_FORMAT_RGBA_8888;
        std::int32_t expectedDataspace = ADATASPACE_SRGB;
        std::int32_t expectedCompressFormat = ANDROID_BITMAP_COMPRESS_FORMAT_JPEG;
        std::int32_t expectedQuality = 80;

        FakeReference *makeReference(
                ReferenceKind kind,
                bool local = false,
                void *address = nullptr,
                jlong capacity = -1,
                std::string className = {}
        ) {
            auto reference = std::make_unique<FakeReference>();
            reference->kind = kind;
            reference->address = address;
            reference->capacity = capacity;
            reference->local = local;
            reference->className = std::move(className);
            FakeReference *result = reference.get();
            references.push_back(std::move(reference));
            return result;
        }

        jthrowable makeThrowable(const std::string &className) {
            return reinterpret_cast<jthrowable>(
                    makeReference(ReferenceKind::Throwable, false, nullptr, -1, className)
            );
        }

        FakeReference *findReference(jobject object) {
            for (const auto &reference: references) {
                if (static_cast<jobject>(reference.get()) == object) return reference.get();
            }
            return nullptr;
        }

        [[nodiscard]] std::size_t liveLocalReferenceCount() const {
            std::size_t result = 0;
            for (const auto &reference: references) {
                if (reference->local && reference->active) ++result;
            }
            return result;
        }
    };

    struct FakeEnvironment final {
        JNIEnv interface{};
        FakeJniState *state = nullptr;
    };

    struct FakeVirtualMachine final {
        JavaVM interface{};
        FakeJniState *state = nullptr;
        FakeEnvironment *environment = nullptr;
    };

    FakeJniState &stateOf(JNIEnv *environment) {
        return *reinterpret_cast<FakeEnvironment *>(environment)->state;
    }

    FakeVirtualMachine &vmOf(JavaVM *vm) {
        return *reinterpret_cast<FakeVirtualMachine *>(vm);
    }

    bool rejectForbiddenPendingCall(FakeJniState &state) {
        if (state.pendingThrowable == nullptr) return false;
        state.forbiddenCallWhilePending = true;
        return true;
    }

    jclass fakeFindClass(JNIEnv *environment, const char *name) {
        FakeJniState &state = stateOf(environment);
        if (rejectForbiddenPendingCall(state)) return nullptr;
        state.lastFoundClassName = name == nullptr ? "" : name;
        if (state.failFacadeLookup && state.lastFoundClassName == kFacadeClassName) {
            state.pendingThrowable = state.failureThrowable;
            return nullptr;
        }
        return state.makeReference(
                ReferenceKind::Class,
                true,
                nullptr,
                -1,
                state.lastFoundClassName
        );
    }

    jint fakeThrowNew(JNIEnv *environment, jclass clazz, const char *) {
        FakeJniState &state = stateOf(environment);
        if (rejectForbiddenPendingCall(state)) return JNI_ERR;
        FakeReference *reference = state.findReference(clazz);
        if (reference == nullptr || reference->kind != ReferenceKind::Class) return JNI_ERR;
        state.thrownClassName = reference->className;
        state.pendingThrowable = state.makeThrowable(state.thrownClassName);
        return JNI_OK;
    }

    void fakeDeleteLocalRef(JNIEnv *environment, jobject object) {
        FakeJniState &state = stateOf(environment);
        FakeReference *reference = state.findReference(object);
        if (reference == nullptr || !reference->local || !reference->active) {
            state.forbiddenCallWhilePending = true;
            return;
        }
        reference->active = false;
    }

    jclass fakeGetObjectClass(JNIEnv *environment, jobject object) {
        FakeJniState &state = stateOf(environment);
        if (rejectForbiddenPendingCall(state) || state.findReference(object) == nullptr) return nullptr;
        return state.makeReference(ReferenceKind::Class, true, nullptr, -1, "NativeSegmentSink");
    }

    _jmethodID adoptMethodId{};

    jmethodID fakeGetMethodID(JNIEnv *environment, jclass clazz, const char *name, const char *descriptor) {
        FakeJniState &state = stateOf(environment);
        if (rejectForbiddenPendingCall(state) || state.findReference(clazz) == nullptr) return nullptr;
        state.lastMethodName = name == nullptr ? "" : name;
        state.lastMethodDescriptor = descriptor == nullptr ? "" : descriptor;
        if (state.lastMethodName != kSinkMethodName || state.lastMethodDescriptor != kSinkMethodDescriptor) {
            return nullptr;
        }
        return &adoptMethodId;
    }

    void fakeCallVoidMethodV(JNIEnv *environment, jobject sink, jmethodID method, va_list arguments) {
        FakeJniState &state = stateOf(environment);
        if (rejectForbiddenPendingCall(state)) return;
        jobject buffer = va_arg(arguments, jobject);
        const jint byteCount = va_arg(arguments, jint);
        FakeReference *sinkReference = state.findReference(sink);
        FakeReference *bufferReference = state.findReference(buffer);
        if (sinkReference == nullptr || sinkReference->kind != ReferenceKind::Sink ||
            method != &adoptMethodId || bufferReference == nullptr ||
            bufferReference->kind != ReferenceKind::Buffer || !bufferReference->active ||
            byteCount <= 0 || bufferReference->address == nullptr || bufferReference->capacity < byteCount) {
            state.forbiddenCallWhilePending = true;
            return;
        }
        if (state.sinkThrows) {
            state.pendingThrowable = state.sinkThrowable;
            return;
        }
        const auto *first = static_cast<const std::uint8_t *>(bufferReference->address);
        state.adoptedBytes.insert(state.adoptedBytes.end(), first, first + byteCount);
    }

    jint fakeRegisterNatives(
            JNIEnv *environment,
            jclass clazz,
            const JNINativeMethod *methods,
            jint methodCount
    ) {
        FakeJniState &state = stateOf(environment);
        if (rejectForbiddenPendingCall(state) || state.findReference(clazz) == nullptr ||
            methods == nullptr || methodCount <= 0) {
            return JNI_ERR;
        }
        state.registeredMethods.clear();
        for (jint index = 0; index < methodCount; ++index) {
            state.registeredMethods.push_back({
                                                      methods[index].name == nullptr ? "" : methods[index].name,
                                                      methods[index].signature == nullptr ? "" : methods[index].signature,
                                                      methods[index].fnPtr,
                                              });
        }
        if (state.registerResult != JNI_OK) state.pendingThrowable = state.failureThrowable;
        return state.registerResult;
    }

    jboolean fakeExceptionCheck(JNIEnv *environment) {
        return stateOf(environment).pendingThrowable == nullptr ? JNI_FALSE : JNI_TRUE;
    }

    jobject fakeNewDirectByteBuffer(JNIEnv *environment, void *address, jlong capacity) {
        FakeJniState &state = stateOf(environment);
        if (rejectForbiddenPendingCall(state)) return nullptr;
        if (state.failNextDirectViewWith != nullptr) {
            state.pendingThrowable = state.failNextDirectViewWith;
            state.failNextDirectViewWith = nullptr;
            return nullptr;
        }
        if (address == nullptr || capacity <= 0) return nullptr;
        return state.makeReference(ReferenceKind::Buffer, true, address, capacity);
    }

    void *fakeGetDirectBufferAddress(JNIEnv *environment, jobject buffer) {
        FakeJniState &state = stateOf(environment);
        if (rejectForbiddenPendingCall(state)) return nullptr;
        FakeReference *reference = state.findReference(buffer);
        if (reference == nullptr || reference->kind != ReferenceKind::Buffer || !reference->active) return nullptr;
        return reference->address;
    }

    jlong fakeGetDirectBufferCapacity(JNIEnv *environment, jobject buffer) {
        FakeJniState &state = stateOf(environment);
        if (rejectForbiddenPendingCall(state)) return -1;
        FakeReference *reference = state.findReference(buffer);
        if (reference == nullptr || reference->kind != ReferenceKind::Buffer || !reference->active) return -1;
        return reference->capacity;
    }

    jint fakeGetEnv(JavaVM *vm, void **environment, jint version) {
        FakeVirtualMachine &virtualMachine = vmOf(vm);
        FakeJniState &state = *virtualMachine.state;
        state.requestedJniVersion = version;
        if (state.getEnvResult != JNI_OK) {
            *environment = nullptr;
            return state.getEnvResult;
        }
        *environment = state.getEnvReturnsNull ? nullptr : &virtualMachine.environment->interface;
        return JNI_OK;
    }

    const JNINativeInterface jniFunctions = {
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            &fakeFindClass,
            &fakeThrowNew,
            &fakeDeleteLocalRef,
            &fakeGetObjectClass,
            &fakeGetMethodID,
            &fakeCallVoidMethodV,
            &fakeRegisterNatives,
            &fakeExceptionCheck,
            &fakeNewDirectByteBuffer,
            &fakeGetDirectBufferAddress,
            &fakeGetDirectBufferCapacity,
    };

    const JNIInvokeInterface invocationFunctions = {
            nullptr,
            nullptr,
            nullptr,
            &fakeGetEnv,
    };

    struct Harness final {
        FakeJniState state;
        FakeEnvironment environment;
        FakeVirtualMachine vm;

        Harness() {
            environment.interface.functions = &jniFunctions;
            environment.state = &state;
            vm.interface.functions = &invocationFunctions;
            vm.state = &state;
            vm.environment = &environment;
        }

        jobject makeBuffer(void *address, jlong capacity) {
            return state.makeReference(ReferenceKind::Buffer, false, address, capacity);
        }

        jobject makeSink() {
            return state.makeReference(ReferenceKind::Sink);
        }
    };

    FakeJniState *activeCompressorState = nullptr;

    class ActiveCompressorState final {
    public:
        explicit ActiveCompressorState(FakeJniState &state) {
            if (activeCompressorState != nullptr) throw std::runtime_error("nested compressor state");
            activeCompressorState = &state;
        }

        ~ActiveCompressorState() {
            activeCompressorState = nullptr;
        }

        ActiveCompressorState(const ActiveCompressorState &) = delete;

        ActiveCompressorState &operator=(const ActiveCompressorState &) = delete;
    };

    void require(bool condition, const std::string &message) {
        if (!condition) throw std::runtime_error(message);
    }

    bool sameReference(jthrowable first, jthrowable second) {
        return reinterpret_cast<void *>(first) == reinterpret_cast<void *>(second);
    }

    std::int64_t readWord(const std::array<std::uint8_t, kResultBlockByteCount> &block, std::size_t offset) {
        std::int64_t result = 0;
        std::memcpy(&result, block.data() + offset, sizeof(result));
        return result;
    }

    using AllocateFunction = jobject (*)(JNIEnv *, jobject, jlong);
    using FreeFunction = void (*)(JNIEnv *, jobject, jobject);
    using CapabilityFunction = jboolean (*)(JNIEnv *, jobject);
    using CompressFunction = void (*)(
            JNIEnv *,
            jobject,
            jobject,
            jlong,
            jint,
            jint,
            jint,
            jint,
            jlong,
            jint,
            jint,
            jint,
            jobject,
            jobject
    );

    struct NativePacket final {
        AllocateFunction allocate = nullptr;
        FreeFunction free = nullptr;
        CapabilityFunction hasCompressor = nullptr;
        CompressFunction compress = nullptr;
    };

    NativePacket loadPacket(Harness &harness) {
        require(JNI_OnLoad(&harness.vm.interface, nullptr) == JNI_VERSION_1_6, "JNI_OnLoad failed");
        require(harness.state.registeredMethods.size() == 4, "registered packet size mismatch");
        NativePacket result{};
        result.allocate = reinterpret_cast<AllocateFunction>(harness.state.registeredMethods[0].function);
        result.free = reinterpret_cast<FreeFunction>(harness.state.registeredMethods[1].function);
        result.hasCompressor = reinterpret_cast<CapabilityFunction>(harness.state.registeredMethods[2].function);
        result.compress = reinterpret_cast<CompressFunction>(harness.state.registeredMethods[3].function);
        require(result.allocate != nullptr && result.free != nullptr &&
                result.hasCompressor != nullptr && result.compress != nullptr,
                "registered packet contained a null entry");
        return result;
    }

    struct CompressionFixture final {
        Harness harness;
        NativePacket packet;
        std::vector<std::uint8_t> pixels = std::vector<std::uint8_t>(16, 0x7a);
        std::array<std::uint8_t, kResultBlockByteCount> resultBlock{};
        FakeReference *carrierReference = nullptr;
        jobject carrier = nullptr;
        jobject sink = nullptr;
        jobject result = nullptr;

        explicit CompressionFixture(jlong resultCapacity = static_cast<jlong>(kResultBlockByteCount)) :
                packet(loadPacket(harness)) {
            resultBlock.fill(0xff);
            carrierReference = harness.state.makeReference(
                    ReferenceKind::Buffer,
                    false,
                    pixels.data(),
                    static_cast<jlong>(pixels.size())
            );
            carrier = carrierReference;
            sink = harness.makeSink();
            result = harness.makeBuffer(resultBlock.data(), resultCapacity);
            harness.state.expectedPixels = pixels.data();
        }

        void invoke(jint quality = 80) {
            ActiveCompressorState active(harness.state);
            packet.compress(
                    &harness.environment.interface,
                    nullptr,
                    carrier,
                    static_cast<jlong>(pixels.size()),
                    2,
                    2,
                    8,
                    ANDROID_BITMAP_FORMAT_RGBA_8888,
                    ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE,
                    ADATASPACE_SRGB,
                    ANDROID_BITMAP_COMPRESS_FORMAT_JPEG,
                    quality,
                    sink,
                    result
            );
        }

        [[nodiscard]] std::int64_t producedByteCount() const {
            return readWord(resultBlock, kProducedByteCountOffset);
        }

        [[nodiscard]] std::int64_t status() const {
            return readWord(resultBlock, kWireStatusOffset);
        }
    };

    void requireCleanLocalsAndJniUse(const FakeJniState &state, const std::string &context) {
        require(state.liveLocalReferenceCount() == 0, context + ": live call-local reference remained");
        require(!state.forbiddenCallWhilePending, context + ": invalid JNI use was observed");
    }

    // Verification: ENC-04
    void testOnLoadRegistersFrozenPacketAndFailuresReturnJniErr() {
        require(JNI_OnLoad(nullptr, nullptr) == JNI_ERR, "null VM was accepted");
        {
            Harness harness;
            const NativePacket packet = loadPacket(harness);
            (void) packet;
            require(harness.state.requestedJniVersion == JNI_VERSION_1_6, "JNI version mismatch");
            require(harness.state.lastFoundClassName == kFacadeClassName, "facade class mismatch");
            const std::array<std::pair<const char *, const char *>, 4> expected = {{
                                                                                           {"nativeAllocateCarrier", "(J)Ljava/nio/ByteBuffer;"},
                                                                                           {"nativeFreeCarrier", "(Ljava/nio/ByteBuffer;)V"},
                                                                                           {"nativeHasWeakCompressor", "()Z"},
                                                                                           {
                                                                                                   "nativeCompress",
                                                                                                   "(Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/capture/internal/"
                                                                                                   "encoding/NativeSegmentSink;Ljava/nio/ByteBuffer;)V"
                                                                                           },
                                                                                   }};
            for (std::size_t index = 0; index < expected.size(); ++index) {
                require(harness.state.registeredMethods[index].name == expected[index].first,
                        "registered method name/order mismatch");
                require(harness.state.registeredMethods[index].descriptor == expected[index].second,
                        "registered method descriptor/order mismatch");
                require(harness.state.registeredMethods[index].function != nullptr,
                        "registered method function was null");
            }
            requireCleanLocalsAndJniUse(harness.state, "successful registration");
        }
        {
            Harness harness;
            harness.state.getEnvResult = JNI_EVERSION;
            require(JNI_OnLoad(&harness.vm.interface, nullptr) == JNI_ERR, "GetEnv failure was accepted");
            require(harness.state.requestedJniVersion == JNI_VERSION_1_6, "failed GetEnv version mismatch");
            requireCleanLocalsAndJniUse(harness.state, "GetEnv failure");
        }
        {
            Harness harness;
            harness.state.getEnvReturnsNull = true;
            require(JNI_OnLoad(&harness.vm.interface, nullptr) == JNI_ERR, "null environment was accepted");
            requireCleanLocalsAndJniUse(harness.state, "null environment");
        }
        {
            Harness harness;
            const jthrowable original = harness.state.makeThrowable("java/lang/NoClassDefFoundError");
            harness.state.failureThrowable = original;
            harness.state.failFacadeLookup = true;
            require(JNI_OnLoad(&harness.vm.interface, nullptr) == JNI_ERR, "class lookup failure was accepted");
            require(sameReference(harness.state.pendingThrowable, original),
                    "class lookup Throwable was replaced or cleared");
            requireCleanLocalsAndJniUse(harness.state, "class lookup failure");
        }
        {
            Harness harness;
            const jthrowable original = harness.state.makeThrowable("java/lang/NoSuchMethodError");
            harness.state.failureThrowable = original;
            harness.state.registerResult = JNI_ERR;
            require(JNI_OnLoad(&harness.vm.interface, nullptr) == JNI_ERR, "registration failure was accepted");
            require(sameReference(harness.state.pendingThrowable, original),
                    "registration Throwable was replaced or cleared");
            requireCleanLocalsAndJniUse(harness.state, "registration failure");
        }
    }

    // Verification: ENC-04
    void testCarrierCapabilityAndPreexistingThrowableEntries() {
        Harness harness;
        const NativePacket packet = loadPacket(harness);
        JNIEnv *environment = &harness.environment.interface;

        require(packet.hasCompressor(environment, nullptr) == JNI_TRUE,
                "linked host compressor capability was not visible");
        jobject carrier = packet.allocate(environment, nullptr, 64);
        require(carrier != nullptr && harness.state.pendingThrowable == nullptr,
                "positive native carrier allocation failed");
        FakeReference *carrierReference = harness.state.findReference(carrier);
        require(carrierReference != nullptr && carrierReference->kind == ReferenceKind::Buffer &&
                carrierReference->address != nullptr && carrierReference->capacity == 64,
                "native carrier was not the exact positive direct range");
        packet.free(environment, nullptr, carrier);
        environment->DeleteLocalRef(carrier);
        requireCleanLocalsAndJniUse(harness.state, "carrier lifecycle");

        require(packet.allocate(environment, nullptr, 0) == nullptr, "invalid carrier size was accepted");
        require(harness.state.pendingThrowable != nullptr &&
                harness.state.thrownClassName == "java/lang/IllegalArgumentException",
                "invalid carrier size did not use the declared exception");
        harness.state.pendingThrowable = nullptr;
        requireCleanLocalsAndJniUse(harness.state, "invalid carrier size");

        const jthrowable directViewThrowable = harness.state.makeThrowable("java/lang/OutOfMemoryError");
        harness.state.failNextDirectViewWith = directViewThrowable;
        require(packet.allocate(environment, nullptr, 48) == nullptr,
                "failed carrier direct view returned a buffer");
        require(sameReference(harness.state.pendingThrowable, directViewThrowable),
                "carrier direct-view Throwable was replaced or cleared");
        harness.state.pendingThrowable = nullptr;
        requireCleanLocalsAndJniUse(harness.state, "carrier direct-view failure");

        jobject pendingCarrier = packet.allocate(environment, nullptr, 32);
        require(pendingCarrier != nullptr, "pending-entry cleanup carrier allocation failed");
        std::vector<std::uint8_t> pixels(16, 0x31);
        std::array<std::uint8_t, kResultBlockByteCount> resultBlock{};
        resultBlock.fill(0xff);
        jobject input = harness.makeBuffer(pixels.data(), static_cast<jlong>(pixels.size()));
        jobject sink = harness.makeSink();
        jobject result = harness.makeBuffer(resultBlock.data(), static_cast<jlong>(resultBlock.size()));
        const jthrowable original = harness.state.makeThrowable("java/lang/RuntimeException");
        harness.state.pendingThrowable = original;

        require(packet.allocate(environment, nullptr, 16) == nullptr,
                "allocation replaced a preexisting Throwable");
        require(packet.hasCompressor(environment, nullptr) == JNI_FALSE,
                "capability ignored a preexisting Throwable");
        {
            ActiveCompressorState active(harness.state);
            packet.compress(
                    environment,
                    nullptr,
                    input,
                    16,
                    2,
                    2,
                    8,
                    ANDROID_BITMAP_FORMAT_RGBA_8888,
                    ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE,
                    ADATASPACE_SRGB,
                    ANDROID_BITMAP_COMPRESS_FORMAT_JPEG,
                    80,
                    sink,
                    result
            );
        }
        packet.free(environment, nullptr, pendingCarrier);
        require(sameReference(harness.state.pendingThrowable, original),
                "preexisting Throwable was replaced or cleared");
        require(readWord(resultBlock, kProducedByteCountOffset) == kPendingWord &&
                readWord(resultBlock, kWireStatusOffset) == kPendingWord,
                "preexisting Throwable was converted to a wire result");
        require(harness.state.adoptedBytes.empty(), "preexisting Throwable published sink bytes");
        require(!harness.state.forbiddenCallWhilePending, "entry used forbidden JNI while Throwable was pending");

        harness.state.pendingThrowable = nullptr;
        packet.free(environment, nullptr, pendingCarrier);
        environment->DeleteLocalRef(pendingCarrier);
        requireCleanLocalsAndJniUse(harness.state, "preexisting Throwable entries");
    }

    // Verification: ENC-04
    void testCompressMalformedInputsDoNotPublish() {
        {
            CompressionFixture fixture(15);
            fixture.invoke();
            require(fixture.producedByteCount() == kPendingWord && fixture.status() == kPendingWord,
                    "wrong-sized result block was mutated");
            require(fixture.harness.state.adoptedBytes.empty(), "wrong-sized result block published bytes");
            requireCleanLocalsAndJniUse(fixture.harness.state, "wrong-sized result block");
        }
        {
            CompressionFixture fixture;
            fixture.invoke(101);
            require(fixture.producedByteCount() == 0 && fixture.status() == kInternalFailureStatus,
                    "invalid descriptor did not return internal wire failure");
            require(fixture.harness.state.adoptedBytes.empty(), "invalid descriptor published bytes");
            requireCleanLocalsAndJniUse(fixture.harness.state, "invalid descriptor");
        }
        {
            CompressionFixture fixture;
            fixture.carrierReference->capacity -= 1;
            fixture.invoke();
            require(fixture.producedByteCount() == 0 && fixture.status() == kInternalFailureStatus,
                    "carrier capacity mismatch did not return internal wire failure");
            require(fixture.harness.state.adoptedBytes.empty(), "carrier capacity mismatch published bytes");
            requireCleanLocalsAndJniUse(fixture.harness.state, "carrier capacity mismatch");
        }
    }

    // Verification: ENC-04
    void testCompressTransfersExactBytesAndCompletesWire() {
        CompressionFixture fixture;
        const std::vector<std::uint8_t> expected = fixture.harness.state.compressorBytes;
        fixture.invoke();

        require(fixture.harness.state.compressorDescriptorMatched,
                "owner entry changed the maintained compressor descriptor");
        require(fixture.harness.state.compressorWriteSucceeded, "fake compressor output was rejected");
        require(fixture.harness.state.adoptedBytes == expected, "sink bytes were not exact FIFO output");
        require(fixture.producedByteCount() == static_cast<std::int64_t>(expected.size()),
                "produced byte count mismatch");
        require(fixture.status() == kCompleteStatus, "successful transfer did not complete the wire");
        require(fixture.harness.state.pendingThrowable == nullptr, "successful transfer left a Throwable");
        require(fixture.harness.state.lastMethodName == kSinkMethodName &&
                fixture.harness.state.lastMethodDescriptor == kSinkMethodDescriptor,
                "sink boundary descriptor mismatch");
        requireCleanLocalsAndJniUse(fixture.harness.state, "successful compression");
    }

    enum class ThrowableOrigin {
        Compressor,
        DirectView,
        Sink,
    };

    // Verification: ENC-04
    void testCompressPreservesPendingThrowableAndCleansOwnedState() {
        const std::array<std::pair<ThrowableOrigin, const char *>, 3> cases = {{
                                                                                       {ThrowableOrigin::Compressor, "compressor"},
                                                                                       {ThrowableOrigin::DirectView, "direct view"},
                                                                                       {ThrowableOrigin::Sink, "sink"},
                                                                               }};
        for (const auto &testCase: cases) {
            CompressionFixture fixture;
            const jthrowable original = fixture.harness.state.makeThrowable("java/lang/RuntimeException");
            switch (testCase.first) {
                case ThrowableOrigin::Compressor:
                    fixture.harness.state.compressorThrowable = original;
                    fixture.harness.state.compressorLeavesThrowable = true;
                    fixture.harness.state.compressorResult = ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
                    break;
                case ThrowableOrigin::DirectView:
                    fixture.harness.state.failNextDirectViewWith = original;
                    break;
                case ThrowableOrigin::Sink:
                    fixture.harness.state.sinkThrowable = original;
                    fixture.harness.state.sinkThrows = true;
                    break;
            }

            fixture.invoke();

            const std::string context = testCase.second;
            require(sameReference(fixture.harness.state.pendingThrowable, original),
                    context + ": pending Throwable was replaced or cleared");
            require(fixture.status() == kJavaThrowableStatus,
                    context + ": pending Throwable did not use JavaThrowable wire status");
            require(fixture.producedByteCount() ==
                    static_cast<std::int64_t>(fixture.harness.state.compressorBytes.size()),
                    context + ": produced byte evidence changed during cleanup");
            require(fixture.harness.state.adoptedBytes.empty(), context + ": failing call published bytes");
            requireCleanLocalsAndJniUse(fixture.harness.state, context);
        }
    }

    using TestFunction = void (*)();

    const std::array<std::pair<const char *, TestFunction>, 5> tests = {{
                                                                                {"JNI_OnLoad packet/failures",
                                                                                 &testOnLoadRegistersFrozenPacketAndFailuresReturnJniErr},
                                                                                {"carrier/capability/pending entries",
                                                                                 &testCarrierCapabilityAndPreexistingThrowableEntries},
                                                                                {"compress malformed inputs", &testCompressMalformedInputsDoNotPublish},
                                                                                {"compress exact transfer", &testCompressTransfersExactBytesAndCompletesWire},
                                                                                {"compress pending Throwable cleanup",
                                                                                 &testCompressPreservesPendingThrowableAndCleansOwnedState},
                                                                        }};

}

extern "C" int AndroidBitmap_compress(
        const AndroidBitmapInfo *info,
        int32_t dataspace,
        const void *pixels,
        int32_t format,
        int32_t quality,
        void *userContext,
        AndroidBitmap_CompressWriteFunc function
) {
    if (activeCompressorState == nullptr) return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    FakeJniState &state = *activeCompressorState;
    state.compressorDescriptorMatched =
            info != nullptr &&
            info->width == state.expectedWidth &&
            info->height == state.expectedHeight &&
            info->stride == state.expectedStride &&
            info->format == state.expectedFormat &&
            info->flags == state.expectedFlags &&
            dataspace == state.expectedDataspace &&
            pixels == state.expectedPixels &&
            format == state.expectedCompressFormat &&
            quality == state.expectedQuality &&
            userContext != nullptr &&
            function != nullptr;
    if (!state.compressorDescriptorMatched) return ANDROID_BITMAP_RESULT_BAD_PARAMETER;

    const std::size_t midpoint = state.compressorBytes.size() / 2;
    const bool firstAccepted = function(userContext, state.compressorBytes.data(), midpoint);
    const bool secondAccepted = function(
            userContext,
            state.compressorBytes.data() + midpoint,
            state.compressorBytes.size() - midpoint
    );
    state.compressorWriteSucceeded = firstAccepted && secondAccepted;
    if (state.compressorLeavesThrowable) state.pendingThrowable = state.compressorThrowable;
    return state.compressorResult;
}

int main() {
    std::size_t failures = 0;
    for (const auto &test: tests) {
        try {
            test.second();
            std::cout << "PASS: " << test.first << '\n';
        } catch (const std::exception &failure) {
            ++failures;
            std::cerr << "FAIL: " << test.first << ": " << failure.what() << '\n';
        }
    }
    return failures == 0 ? 0 : 1;
}
