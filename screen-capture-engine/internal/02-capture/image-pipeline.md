# Image pipeline

This contract connects authoritative source geometry, Capture rendering, and Encoding payload commit. Public parameters and effective-output semantics belong in the [usage guide](../../docs/usage.md#change-capture-parameters) and [architecture guide](../../docs/architecture.md#requested-and-applied-output); physical ownership belongs in [Capture](capture.md) and [Encoding](../03-output/encoding.md).

This page is the canonical implementation reference for image coordinates, sizing, Target selection, and color/readback semantics. It complements the public guides rather than redefining their input domain or lifecycle behavior.

## Contents

- [Operation order](#operation-order)
- [Geometry and inverse coordinates](#geometry-and-inverse-coordinates)
- [Retained-edge sampling](#retained-edge-sampling)
- [Output sizing and addressability](#output-sizing-and-addressability)
- [Target selection and reuse](#target-selection-and-reuse)
- [Applied output identity](#applied-output-identity)
- [Color and readback](#color-and-readback)
- [Implementation and verification](#implementation-and-verification)

## Operation order

One fresh frame follows this logical order:

```text
authoritative source geometry
→ source-region selection
→ unrotated crop
→ clockwise rotation
→ mirror in the rotated image
→ output sizing
→ source-dataspace check and nominal-sRGB interpretation
→ color mode
→ opaque top-down RGBA readback
→ one JPEG encode
→ immutable payload commit
```

Capture performs the prefix in one read: consume the latest [`SurfaceTexture`](https://developer.android.com/reference/android/graphics/SurfaceTexture) image with [`updateTexImage()`](https://developer.android.com/reference/android/graphics/SurfaceTexture#updateTexImage()), copy and validate its transform, apply the [source-color policy](#color-and-readback), draw one OES pass, and read directly into the exact RGBA carrier. Encoding transfers the carrier once to the selected backend, closes the producer, and commits only a complete JPEG. Failed, partial, or tentative bytes cannot commit. A mechanically complete stale payload may commit, but Session must not publish it as an output frame.

## Geometry and inverse coordinates

All geometry is integer pixel geometry in unrotated capture coordinates until rotation. Rectangles use an inclusive left/top and exclusive right/bottom edge. For authoritative source `(W,H)`, Full is `(0,0,W,H)`; a half selects `(0,0,W/2,H)` or `(W/2,0,W,H)`, with integer division and the right half owning the final odd column. A half requires source width at least two. Crop offsets that selected rectangle, and the resulting rectangle `(sx0, sy0, Sw, Sh)` must remain nonempty.

The oriented dimensions are `(Sw,Sh)` at 0°/180° and `(Sh,Sw)` at 90°/270°. For output pixel center `(i,j)`, let

```text
u = (i + 0.5) * Rw / Ow
v = (j + 0.5) * Rh / Oh
```

where `(Rw,Rh)` is the oriented source size and `(Ow,Oh)` is the final output size. First undo mirror in the oriented image: Horizontal replaces `u` with `Rw - u`, Vertical replaces `v` with `Rh - v`, and None leaves both unchanged. Then undo the clockwise rotation:

```text
0°:   xs = u       ys = v
90°:  xs = v       ys = Sh - u
180°: xs = Sw - u  ys = Sh - v
270°: xs = Sw - v  ys = u
```

The source coordinate is `p = ((sx0 + xs) / W, (sy0 + ys) / H)`. Capture evaluates this mapping in binary64 without intermediate integer rounding and uploads one finite binary32 affine transform; it rejects unrepresentable matrix values.

## Retained-edge sampling

For the applied half-open rectangle `(L,T,R,B)`, the renderer derives logical bounds `xmin = (L + 0.5) / W` when `L > 0`, otherwise `0`; `xmax = (R - 0.5) / W` when `R < W`, otherwise `1`; and the corresponding `ymin`/`ymax` from `T`, `B`, and `H`. Each fragment clamps `p` to these bounds, inverts the framebuffer row once, applies the copied `SurfaceTexture` matrix once, and performs one linear OES sample. When a retained dimension contains one pixel, its two cut-side bounds coincide. Cut sides therefore repeat the retained logical edge pixel; unchanged outer sides keep their ordinary whole-image `0` or `1` mapping. The [capture component](capture.md#rendering-and-readback) owns the renderer mechanics.

The fixture-specific identity-grid CPU oracle and its upstream-resampling limit belong to the [image test oracles](../04-testing/image-test-oracles.md#raw-rgba-fixture).

## Output sizing and addressability

Let `(Rw,Rh)` be the oriented retained source size and `(Ow,Oh)` the final output size. Sizing uses the logical source dimensions, even when Capture uses a smaller producer Target.

For `ScaleFactor`, calculate each axis in binary64:

```text
scaled = axis * factor
rounded = floor(scaled + 0.5)
outputAxis = max(1, rounded)
```

Both `scaled` and `rounded` must be finite, and `rounded` must lie in `0..Int.MAX_VALUE`, before conversion and the one-pixel clamp. A positive factor that rounds a small dimension to zero therefore produces one pixel; an overflowing result is rejected rather than clamped into a representable size.

For `AspectFit` with requested positive bounds `(Aw,Ah)`, compare checked `Aw*Rh` and `Ah*Rw`:

```text
if Aw*Rh <= Ah*Rw:
    Ow = Aw
    Oh = clamp((Aw*Rh + Rw/2) / Rw, 1, Ah)
else:
    Oh = Ah
    Ow = clamp((Ah*Rw + Rh/2) / Rh, 1, Aw)
```

The products and rounding sums use checked `Long` arithmetic; all divisions in this formula are integer division. The added half-denominator gives half-up rounding. A retained `5 x 3` source with `8 x 8` bounds therefore derives `8 x 5`. `Stretch` uses the requested positive bounds exactly.

Representable image geometry and an addressable RGBA carrier are separate checks. Unrepresentable output dimensions are `InvalidRequest`. After sizing, `Rgba8888Layout` checks `rowBytes = 4 * Ow` and `byteCount = rowBytes * Oh` with exact multiplication; both must fit `Int.MAX_VALUE`. An unaddressable or deterministically denied carrier is `ResourceExhausted`. Capture then applies its own texture/viewport capacity checks. The public [error guide](../../docs/usage.md#handle-errors-and-recovery) owns caller handling.

## Target selection and reuse

Capture may use an API 32+ early downscaled Target only for authoritative dimensions, a full source, zero crop, a sub-1.0 scale factor, and no exact target-size request. For source `(W,H)`, choose that minimum as follows:

```text
g = gcd(W, H)
rw = W / g
rh = H / g
requiredWidth, requiredHeight = (Ow, Oh) at 0°/180°, (Oh, Ow) at 90°/270°
k = min(g, max(1, ceil(requiredWidth / rw), ceil(requiredHeight / rh)))
```

The ceiling is computed by integer quotient plus one only for a nonzero remainder, with checked arithmetic. If `k < g`, create a Downscaled Target `(rw*k, rh*k)`; otherwise keep the Full Target `(W,H)`. Products must remain positive representable dimensions. This is the smallest integer aspect multiple sufficient for both rotation-aware output axes.

A reused Downscaled Target may remain larger than this minimum, but must remain large enough on both rotation-aware axes and preserve the source aspect and source-to-Target coordinate mapping. For source `(W,H)` and Target `(Tw,Th)`, aspect equality requires checked `Tw*H == Th*W`; the whole-Target mapping cannot assume that a size-compatible, different-aspect Surface contains no bars. Android's [captured-content resize contract](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback#onCapturedContentResize(int,%20int)) explicitly warns of letterboxing when the Surface aspect differs. Reuse must preserve the mapping, not merely update effective metadata. The current Capture implementation replaces a Target for every source-dimension change; sufficient capacity permits no-shrink reuse only while the source dimensions remain unchanged.

This changes the physical input size, not the logical output dimensions or transform order. API 24–31 always use the full Target. API 34+ uses a full Target for [provisional capture setup](../01-session/session.md#first-active-and-start-settlement); requested-output resolution and effective-output publication require authoritative dimensions. The source dimensions and selected metrics are therefore part of the effective snapshot, not merely the latest request.

## Applied output identity

The public [requested and applied output model](../../docs/architecture.md#requested-and-applied-output) defines `requestedParameters`, `Active.outputInfo`, and each frame's applied metadata. Internally, a plan commits source geometry, selected metrics, resolved rectangle, and final image size together. A live update or geometry/backend-health transition can leave the latest request and effective output different during reconciliation. Rendering must use the installed plan; a consumer must use the frame's `outputInfo`, never reconstruct its meaning from a current request.

[Production](../01-session/production.md#output-identity-and-cache-compatibility) owns exact image-cache comparisons and metadata identity. Cache compatibility compares the specified image-affecting parameter values as well as resolved geometry; equivalent physical rectangles alone are insufficient. Capture refreshes its inverse transform, color mode, and all four retained-edge bounds before the same-output-size Apply early return, so reusing an FBO cannot preserve stale image parameters.

## Color and readback

The shader's observable color steps are fixed after sampling: clamp RGB to `[0,1]`, round each channel to an 8-bit value with `floor(255*c + 0.5)`, optionally compute integer grayscale `Y = (77*R8 + 150*G8 + 29*B8 + 128) >> 8`, and set alpha to 255. Color preserves the quantized channels. The medium-precision shader expresses the same color policy using its supported arithmetic; fixture tolerances, rather than cross-GPU bit equality, validate that path. Channels are interpreted as gamma-coded sRGB; that interpretation is an assumption about the sampled input, not proof of its color encoding. The result is opaque, top-down RGBA8888 in the exact tight layout, encoded under the same nominal-sRGB interpretation by either JPEG backend.

[OES external-texture sampling](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external.txt) preserves the source colorspace and transfer encoding. Clamping, quantization, grayscale arithmetic, and selecting sRGB for the encoder do not establish conversion from another gamut or transfer function. Color fidelity is therefore best effort for unknown or unconverted inputs; the engine does not promise general color conversion, HDR tone mapping or output, linear-light grayscale, or cross-GPU bit identity. API 24–32 do not query `SurfaceTexture.getDataSpace()`; that query begins at API 33. Exact Display-P3 dataspace on API 33+ fails before draw/readback as `UnsupportedColorSpace`, while unknown, exact sRGB, and other values retain the best-effort path.

The mandatory Framework backend and optional Native backend must consume the same RGBA semantics. Native and early downscale are transparent accelerations: they may differ in ordinary platform filtering or JPEG byte size while preserving dimensions, transforms, color mode, ownership, and public failure meanings. [Encoding](../03-output/encoding.md) owns backend selection and the prohibition on same-frame fallback.

## Implementation and verification

[SessionPlanResolution](../../src/main/kotlin/io/screenstream/capture/internal/session/topology/SessionPlanResolution.kt) resolves authoritative geometry, output sizes, and the Target minimum. [Rgba8888Layout](../../src/main/kotlin/io/screenstream/capture/internal/Rgba8888Layout.kt) checks byte addressability. [GLRenderer](../../src/main/kotlin/io/screenstream/capture/internal/capture/GLRenderer.kt) implements inverse coordinates, retained-edge bounds, shaders, and readback.

`SessionPlanResolutionGeometryTest` and `Rgba8888LayoutValidationTest` exercise integer geometry and carrier limits; `GLRendererRawPixelTest` exercises actual pixels on a device. The [image test oracles](../04-testing/image-test-oracles.md) own the exact CPU fixture interpretation, filtering limits, color tolerances, and decoded-JPEG comparisons; the [verification catalog](../04-testing/verification-contracts.md) owns the stable image obligations.
