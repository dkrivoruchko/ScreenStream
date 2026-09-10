# Image test oracles

These procedures verify the image path described by public [Architecture](../../docs/architecture.md#android-capture-and-jpeg-pipeline) and [Usage](../../docs/usage.md#change-capture-parameters). The internal [image pipeline](../02-capture/image-pipeline.md) owns the production geometry and color rules. This page owns reproducible test inputs, independent expected pixels, numeric bounds, and the limits of that evidence.

Use [Testing](testing.md#instrumentation) for execution and result inspection, and [Verification contracts](verification-contracts.md) for the obligations, including `IMG-01`, `ENC-01`, `ENC-02`, and `ENC-04`. Procedures and test source are not records of a passing device run.

## Raw RGBA fixture

Generate this top-down, opaque `5 x 3` RGBA fixture in test code; do not add it to production assets:

```text
#FF0000  #B34D26  #000000  #00FFFF  #00FF00
#FF00FF  #404040  #808080  #C0C0C0  #FFFFFF
#0000FF  #7030B0  #26994D  #008080  #FFFF00
```

The source row and total byte ranges are exactly 20 and 60 bytes. [RawPixelOracle](../../src/androidTest/kotlin/io/screenstream/capture/internal/capture/RawPixelOracle.kt) stores literal top-down ARGB colors and independently calculates expected top-down RGBA bytes. Its `expandedTenBySixTarget` repeats every source pixel into a `2 x 2` block to form a `10 x 6` image; it introduces no interpolation.

[GLRendererRawPixelTest](../../src/androidTest/kotlin/io/screenstream/capture/internal/capture/GLRendererRawPixelTest.kt) posts the fixture through a real Canvas whose dimensions already equal the Target. The producer uses a density-free Bitmap and disables antialiasing, dithering, and bitmap filtering. The procedure must then drive the real listener, Target, OES texture, GLES renderer, and readback path. Comparing CPU plan values alone does not satisfy `IMG-01`.

### Required render cases

The 29 raw-render cases consist of 27 Full cases, one producer-already-target Downscaled case, and one provisional-Full case.

The base Full cases preserve LeftHalf columns 0–1, RightHalf columns 2–4, and crop `(1,0,1,1)` to `3 x 2`. They cover all four rotations with each mirror mode, including non-square 90-degree cases; `ScaleFactor(2.0)` to `10 x 6`; Stretch to `8 x 8`; AspectFit within `8 x 8` to `8 x 5`; and grayscale with fractional Stretch sampling.

Four Full regressions cover retained-neighbour boundaries: LeftHalf at scale 2, crop `(1,0,1,1)` at scale 2, that crop rotated 90 degrees and horizontally mirrored, and single retained pixel crop `(2,1,2,1)` at scale 2. Independent literal edge and interior anchors check both the CPU expectation and actual output. They distinguish excluded-neighbour bleed from vertex-endpoint compression; the single-pixel case exercises equal minimum and maximum bounds.

Four further Full cases use the expanded `10 x 6` fixture and exercise the conditions that keep resolution in Full mode:

- LeftHalf at scale 0.5 resolves to `3 x 3`.
- Crop `(1,0,1,1)` at scale 0.5 resolves to `4 x 3`.
- Stretch to a target size of `5 x 3` resolves to `5 x 3`.
- Scale 0.9 resolves to `9 x 5`, exercising the required aspect-multiple condition.

The Downscaled case models authoritative logical dimensions `10 x 6`, a producer already at the `5 x 3` Target, scale 0.5, rotation 90 degrees, and horizontal mirror, with output `3 x 5`. The provisional case uses a Full `10 x 6` Target and a separate neutral `1 x 1` output. Its resolver input models API 34 with dimensions not yet authoritative; it does not test Session admission or make requested parameters effective.

### Independent CPU oracle

The oracle implements selection, crop, inverse rotation/mirroring, output-size resolution, bilinear weights, retained-neighbour clamping, quantization, and grayscale independently of production helpers. It checks its resolved dimensions against literal case expectations. For each output pixel center it maps back to normalized Target coordinates `(a,b)` using the selected source rectangle and orientation.

For the Full, producer-equals-logical identity-grid fixture, convert those coordinates to texel-center coordinates:

```text
qx = a * Tw - 0.5       qy = b * Th - 0.5
x0 = floor(qx)         y0 = floor(qy)
x1 = x0 + 1            y1 = y0 + 1
wx = qx - x0           wy = qy - y0
```

Independently clamp each neighbour index to the retained ranges `[L,R - 1]` and `[T,B - 1]`. Preserve the original weights `(1-wx)(1-wy)`, `wx(1-wy)`, `(1-wx)wy`, and `wxwy`; clamping indices does not recompute those weights. For the Downscaled fixture, the retained ranges are the whole Target, `[0,Tw - 1]` and `[0,Th - 1]`.

Quantize each interpolated channel with `floor(clamp(value, 0, 255) + 0.5)`. For grayscale, calculate integer `Y = (77R + 150G + 29B + 128) >> 8` from those quantized channels and write that value to R, G, and B. Expected alpha is exactly 255.

This oracle describes the known identity-grid fixtures, not an arbitrary acquired-buffer transform. Selection and scaling operate on the image Android supplies; they cannot undo resampling or other preprocessing already mixed into that image.

### Raw bounds and precision evidence

The real Android procedure verifies resolved dimensions and mapping, top-down order, a direct writable carrier with position zero and exact limit/capacity, every actual RGB value, alpha 255, and the literal anchors. Apply the tolerance corresponding to the naturally selected fragment precision:

| Raw oracle | High precision | Medium precision |
| --- | --- | --- |
| Nominal-sRGB, every RGB channel | Maximum absolute error `2` | Maximum absolute error `6` |
| Grayscale, every channel against integer Y | Maximum absolute error `2` | Maximum absolute error `6` |
| Producer-already-target Downscaled, every RGB channel | Maximum absolute error `2` | Maximum absolute error `6` |

Alpha must be exactly 255 for every pixel at either precision. Grayscale must also have exact `R == G == B`.

Each device execution must report the named case and the naturally selected fragment precision passed from `EglOwner` to `GLRenderer`. The test's `GLRendererRawPixel` log tag records `case`, `naturalFragmentPrecision`, and the status of each branch. Renderer setup is `selected/attempted`; only a completed real read is `exercised`; the other branch remains `unexercised`. A completed-read log is not itself proof that the subsequent pixel assertions passed, so retain the test result with the precision record.

If the device reports no high-precision fragment capability and naturally selects medium precision, record that path and mark the high-precision case `Not applicable` for that device. Forcing the mediump shader on high-precision hardware does not prove behavior at the GLSL ES mediump minimum. AndroidTest assembly supplies neither a selected-branch nor an exercised-branch result.

## JPEG fixture and oracle

The Framework/Native JPEG fixture is top-down opaque RGBA, `64 x 48`, quality 80, with `16 x 16` tiles:

```text
#E02020  #B34D26  #20B0C0  #20C040
#C020C0  #404040  #808080  #D0D0D0
#2040E0  #7030B0  #26994D  #E0C020
```

[DeviceJpegFixture](../../src/androidTest/kotlin/io/screenstream/capture/internal/encoding/DeviceJpegFixture.kt) fills the exact direct carrier using absolute RGBA writes, leaving its position at zero. Instrumentation must encode and decode these pixels through Framework JPEG and, on eligible API 30+ devices, the registered-JNI Native path. A successful decode must report `BitmapFactory.Options.outMimeType` exactly `image/jpeg` before the pixel oracle runs.

The decoder must report `64 x 48`, preserve the tile orientation, and return alpha 255 for every pixel. For each tile at column `c`, row `r`, sample its half-open interior `[16c+4,16c+12) x [16r+4,16r+12)`:

- Each channel's interior mean absolute error (MAE) is at most `24`.
- Each channel's per-row interior MAE is at most `36`.
- For each of the three gray tiles, the spread between channel means is at most `8`.
- Gray-tile means strictly increase, with adjacent separation at least `32`.

Local row and tile bounds prevent one region's error from being diluted into a whole-image average. Do not use JPEG byte equality, decoded backend-to-backend equality, encoded size, quality monotonicity, an aggregate score, or performance as a correctness oracle.

### Runtime procedures and API eligibility

[EncodingOwnerFrameworkJpegTest](../../src/androidTest/kotlin/io/screenstream/capture/internal/encoding/EncodingOwnerFrameworkJpegTest.kt) drives `FrameworkOnly` through the real owner, validates decoded MIME/pixels, checks exact carrier reuse, and retires it. [NativeJpegProductionRuntimeTest](../../src/androidTest/kotlin/io/screenstream/capture/internal/encoding/NativeJpegProductionRuntimeTest.kt) contains three distinct procedures:

- Below API 30, load the DSO, establish that the weak compressor is unavailable, and verify Auto's Framework output.
- On API 30+, drive Auto through the real Native owner path, validate the JPEG, and check exact carrier reuse/free.
- On API 30+, call the registered-JNI facade and verify transfer and transaction settlement directly. This contributes direct JNI evidence, not EncodingOwner backend-selection evidence.

Use their fully qualified class names with the [instrumentation command](testing.md#instrumentation). Record the actual API-gated execution and skip outcomes separately. Success of an eligible procedure does not imply the other API branch ran or that every packaged ABI was loaded.

JVM/Robolectric checks can assert exact visible Bitmap pixels after padded-row transfer; real Framework JPEG fidelity requires instrumentation. The raw-renderer procedure above verifies the producer-already-target path separately. AndroidTest assembly supplies no JPEG MIME or pixel result.

## Additional physical and inspection evidence

The bounds above apply to these fixtures. They do not establish color conversion for unknown or non-sRGB inputs, or geometry fidelity at arbitrary capture dimensions. In particular, [GLSL ES 1.00 precision minima](https://registry.khronos.org/OpenGL/specs/es/2.0/GLSL_ES_Specification_1.00.pdf) do not make binary64 CPU mapping proof of medium-precision GPU sampling at phone-sized dimensions. Validate that compatibility separately with a representative large-image landmark procedure.

The Canvas producer and `SurfaceTexture` path do not inject an arbitrary acquired-buffer transform. They also cannot establish real API 32+ `MediaProjection` scaling into a smaller Surface. Validate that separately on a physical device with asymmetric orientation landmarks, distinguishing new Target creation from reuse. Include an aspect-changing source shrink that still fits within the old Target: Android can introduce letterboxing in that case, so updated geometry metadata alone does not prove the [source-to-Target mapping](../02-capture/image-pipeline.md).

Source inspection verifies that the unified renderer parameter refresh resets all four bounds for Full before the same-output-size Apply early return, including Full to restricted to Full. That check is configuration evidence, not executed same-target GPU pixel evidence. It must remain separate from the raw fixture procedure, which creates resources for each case.
