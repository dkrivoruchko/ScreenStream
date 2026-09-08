# Image pipeline contract

This contract connects authoritative source geometry, Capture rendering, and Encoding payload commit. Public parameters and effective-output semantics belong in the [usage guide](../../docs/usage.md#choose-and-update-capture-parameters) and [architecture guide](../../docs/architecture.md#requested-and-applied-output); physical ownership belongs in [Capture](../components/capture.md) and [Encoding](../components/encoding.md).

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

## Geometry and coordinates

All geometry is integer pixel geometry in unrotated capture coordinates until rotation. Rectangles use an inclusive left/top and exclusive right/bottom edge. For authoritative source `(W,H)`, Full is `(0,0,W,H)`; a half selects `(0,0,W/2,H)` or `(W/2,0,W,H)`, with the right half owning the final odd column. Crop offsets that selected rectangle, and the resulting rectangle `(sx0, sy0, Sw, Sh)` must remain nonempty.

The oriented dimensions are `(Sw,Sh)` at 0°/180° and `(Sh,Sw)` at 90°/270°. For output pixel center `(i,j)`, let

```text
u = (i + 0.5) * Rw / Ow
v = (j + 0.5) * Rh / Oh
```

where `(Rw,Rh)` is the oriented source size and `(Ow,Oh)` is the final output size. Inverse mirror is applied before the inverse clockwise rotation:

```text
0°:   xs = u       ys = v
90°:  xs = v       ys = Sh - u
180°: xs = Sw - u  ys = Sh - v
270°: xs = Sw - v  ys = u
```

The source coordinate is `p = ((sx0 + xs) / W, (sy0 + ys) / H)`. Capture evaluates this mapping in binary64 without intermediate integer rounding and uploads one affine transform. For the applied half-open rectangle `(L,T,R,B)`, the renderer derives logical bounds `xmin = (L + 0.5) / W` when `L > 0`, otherwise `0`; `xmax = (R - 0.5) / W` when `R < W`, otherwise `1`; and the corresponding `ymin`/`ymax` from `T`, `B`, and `H`. Each fragment clamps `p` to these bounds, inverts the framebuffer row once, applies the copied `SurfaceTexture` matrix once, and performs one linear OES sample. Cut sides therefore repeat the retained logical edge pixel; unchanged outer sides keep their ordinary whole-image `0` or `1` mapping. The [capture component](../components/capture.md#egl-gles-and-the-pixel-boundary) owns the renderer mechanics.

For the Full, producer-equals-logical identity-grid fixture, the independent CPU oracle converts normalized target coordinates `(a,b)` to texel-center coordinates `qx = a * Tw - 0.5` and `qy = b * Th - 0.5`, then sets `x0 = floor(qx)`, `x1 = x0 + 1`, `y0 = floor(qy)`, `y1 = y0 + 1`, `wx = qx - x0`, and `wy = qy - y0`. It independently clamps each neighbour index to the retained ranges `[L,R - 1]` and `[T,B - 1]` while preserving the original weights `(1-wx)(1-wy)`, `wx(1-wy)`, `(1-wx)wy`, and `wxwy`. For a Downscaled fixture, the retained ranges are the whole Target, `[0,Tw - 1]` and `[0,Th - 1]`. This oracle describes the known identity-grid fixtures, not an arbitrary acquired-buffer transform. Selection and scaling operate on the image Android supplies; they cannot undo resampling or other preprocessing already mixed into that image.

## Output sizing and requested versus applied

Scale-factor sizing rounds each oriented axis independently as `floor(binary64(axis) * factor + 0.5)` and then applies the one-pixel minimum. Aspect-fit target sizing uses oriented source `(Rw,Rh)` and requested positive bounds `(Aw,Ah)`: with checked positive arithmetic, compare `Aw*Rh` with `Ah*Rw`; if `Aw*Rh <= Ah*Rw`, fix `Ow = Aw` and set `Oh = clamp((Aw*Rh + Rw/2) / Rw, 1, Ah)`, otherwise fix `Oh = Ah` and set `Ow = clamp((Ah*Rw + Rh/2) / Rh, 1, Aw)`. The products and rounding sums use checked arithmetic, and the added half-denominator gives integer half-up rounding. Thus a retained `5 x 3` source with `8 x 8` bounds derives `8 x 5`. Stretch uses the requested positive bounds exactly. The resulting `(Ow,Oh)` is checked before constructing the RGBA carrier byte count `4 * Ow * Oh`; an unaddressable or deterministically denied carrier is resource exhaustion, not invalid geometry. The public reference owns the full input domain and error mapping; this page records only the pipeline consequence.

Capture may use an API 32+ early downscaled Target only for authoritative dimensions, a full source, zero crop, a sub-1.0 scale factor, and no exact target-size request. A newly created Target preserves the exact source aspect ratio and uses the smallest integer aspect multiple sufficient for the rotation-aware output; if that multiple equals the full source, the plan remains Full. A reused Downscaled Target may remain larger than this minimum, but must remain large enough on both rotation-aware axes and preserve the source aspect and source-to-Target coordinate mapping. For source `(W,H)` and Target `(Tw,Th)`, aspect equality requires checked `Tw*H == Th*W`; the whole-Target mapping cannot assume that a size-compatible, different-aspect Surface contains no bars. Android's [captured-content resize contract](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback#onCapturedContentResize(int,%20int)) explicitly warns of letterboxing when the Surface aspect differs. Reuse must preserve the mapping, not merely update effective metadata.

This changes the physical input size, not the logical output dimensions or transform order. API 24–31 always use the full Target. API 34+ uses a full Target for [provisional capture setup](../architecture/runtime.md#start-and-first-active); requested-output resolution and effective-output publication require authoritative dimensions. The source dimensions and selected metrics are therefore part of the effective snapshot, not merely the latest request.

`requestedParameters` expresses the latest accepted desire. `appliedParameters`, `appliedSourceRect`, `captureGeometry`, and `finalImageSize` express the plan that produced one JPEG. Raw parameter identity remains part of effective metadata and cached-image compatibility even when two requests resolve to the same physical Capture configuration. A live update, authoritative resize, or backend-health transition can leave those snapshots different while reconciliation is in progress. No consumer may reconstruct an applied snapshot from the current request; it must use the frame's effective parameters.

## Color and readback

The shader's observable color steps are fixed after sampling: clamp RGB to `[0,1]`, round each channel to an 8-bit value with `floor(255*c + 0.5)`, optionally compute integer grayscale `Y = (77*R8 + 150*G8 + 29*B8 + 128) >> 8`, and set alpha to 255. Color preserves the quantized channels. Channels are interpreted as gamma-coded sRGB; that interpretation is an assumption about the sampled input, not proof of its color encoding. The result is opaque, top-down RGBA8888 in the exact tight layout, encoded under the same nominal-sRGB interpretation by either JPEG backend.

[OES external-texture sampling](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external.txt) preserves the source colorspace and transfer encoding. Clamping, quantization, grayscale arithmetic, and selecting sRGB for the encoder do not establish conversion from another gamut or transfer function. Color fidelity is therefore best effort for unknown or unconverted inputs; the engine does not promise general color conversion, HDR tone mapping or output, linear-light grayscale, or cross-GPU bit identity. API 24–32 do not query `SurfaceTexture.getDataSpace()`; that query begins at API 33. Exact Display-P3 dataspace on API 33+ fails before draw/readback as `UnsupportedColorSpace`, while unknown, exact sRGB, and other values retain the best-effort path.

The mandatory Framework backend and optional Native backend must consume the same RGBA semantics. Native and early downscale are transparent accelerations: they may differ in ordinary platform filtering or JPEG byte size while preserving dimensions, transforms, color mode, ownership, and public failure meanings. There is no same-frame fallback.

## Related contracts

- [Public image and geometry guidance](../../docs/usage.md#how-image-settings-combine)
