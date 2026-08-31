# Image pipeline contract

This page fixes the logical path from an authoritative source geometry to one encoded image. It connects Capture's physical rendering to Encoding's byte assembly without reproducing the public parameter reference. Public choices, defaults, validation, operation order, and caller-visible effective-output semantics remain in the public [usage guide](../../docs/usage.md#choose-and-update-capture-parameters) and [architecture guide](../../docs/architecture.md#requested-and-applied-output). Maintained physical boundaries are in the [capture component](../components/capture.md) and [encoding component](../components/encoding.md); encoded bytes continue through [frame ownership and delivery](frame-ownership-and-delivery.md), while Native-specific descriptor rules live in the [native ABI](native-abi.md).

## Operation order

For one fresh frame, the current logical order is:

```text
authoritative source geometry
→ source-region selection
→ unrotated crop
→ clockwise rotation
→ mirror in the rotated image
→ output sizing
→ source-color handling into SDR/sRGB
→ color mode
→ opaque top-down RGBA readback
→ one JPEG encode
→ immutable payload commit
```

Capture performs the physical prefix as one read. It consumes the latest available [`SurfaceTexture`](https://developer.android.com/reference/android/graphics/SurfaceTexture) image with [`updateTexImage()`](https://developer.android.com/reference/android/graphics/SurfaceTexture#updateTexImage()), copies and validates its transform, rejects exact [`DATASPACE_DISPLAY_P3`](https://developer.android.com/reference/android/hardware/DataSpace#DATASPACE_DISPLAY_P3) on API 33+, draws one canonical OES pass, and reads directly into the exact RGBA carrier. Encoding then transfers that carrier once to the selected Framework or Native backend, closes the producer, and commits only a complete JPEG. A failed, partial, stale, or tentative result never crosses the commit seam. The maintained read boundary is described by the [capture component](../components/capture.md#egl-gles-and-the-pixel-boundary), and transaction settlement by the [encoding component](../components/encoding.md#framework-and-native-production).

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

The source coordinate is `((sx0 + xs) / W, (sy0 + ys) / H)`. Capture evaluates this mapping in binary64 without intermediate integer rounding and uploads one affine transform. The copied `SurfaceTexture` matrix and the framebuffer-row inversion are each applied exactly once. Linear clamp sampling uses the target dimensions and clamps the four-neighbour interpolation footprint to the target bounds. The [capture component](../components/capture.md#egl-gles-and-the-pixel-boundary) owns the renderer mechanics.

The independent CPU oracle converts normalized target coordinates `(a,b)` to texel-center coordinates `qx = a * Tw - 0.5` and `qy = b * Th - 0.5`, then sets `x0 = floor(qx)`, `x1 = x0 + 1`, `y0 = floor(qy)`, `y1 = y0 + 1`, `wx = qx - x0`, and `wy = qy - y0`. It samples `(clamp(x0, 0, Tw - 1), clamp(y0, 0, Th - 1))`, `(clamp(x1, 0, Tw - 1), clamp(y0, 0, Th - 1))`, `(clamp(x0, 0, Tw - 1), clamp(y1, 0, Th - 1))`, and `(clamp(x1, 0, Tw - 1), clamp(y1, 0, Th - 1))` with weights `(1-wx)(1-wy)`, `wx(1-wy)`, `(1-wx)wy`, and `wxwy`; clamp means edge-texel replication, while the weights remain those derived from the unclamped coordinates.

## Output sizing and requested versus applied

Scale-factor sizing rounds each oriented axis independently as `floor(binary64(axis) * factor + 0.5)` and then applies the one-pixel minimum. Aspect-fit target sizing uses oriented source `(Rw,Rh)` and requested positive bounds `(Aw,Ah)`: with checked positive arithmetic, compare `Aw*Rh` with `Ah*Rw`; if `Aw*Rh <= Ah*Rw`, fix `Ow = Aw` and set `Oh = clamp((Aw*Rh + Rw/2) / Rw, 1, Ah)`, otherwise fix `Oh = Ah` and set `Ow = clamp((Ah*Rw + Rh/2) / Rh, 1, Aw)`. The products and rounding sums use checked arithmetic, and the added half-denominator gives integer half-up rounding. Thus a retained `5 x 3` source with `8 x 8` bounds derives `8 x 5`. Stretch uses the requested positive bounds exactly. The resulting `(Ow,Oh)` is checked before constructing the RGBA carrier byte count `4 * Ow * Oh`; an unaddressable or deterministically denied carrier is resource exhaustion, not invalid geometry. The public reference owns the full input domain and error mapping; this page records only the pipeline consequence.

Capture may use an API 32–37 early downscaled Target only for authoritative dimensions, a full source, zero crop, a sub-1.0 scale factor, and no exact target-size request. The target preserves the exact source aspect ratio and is the smallest integer aspect multiple sufficient for the rotation-aware output; if that multiple equals the full source, the plan remains Full. This changes the physical input size, not the logical output dimensions or transform order. API 24–31 always use the full Target. API 34–37 uses a full Target while projection dimensions are provisional and keeps frame admission closed until the first valid [`MediaProjection.Callback.onCapturedContentResize`](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback#onCapturedContentResize(int,%20int)) captured-content resize. The source dimensions and selected metrics are therefore part of the effective snapshot, not merely the latest request.

`requestedParameters` expresses the latest accepted desire. `appliedParameters`, `appliedSourceRect`, `captureGeometry`, and `finalImageSize` express the plan that produced one JPEG. A live update, authoritative resize, or backend-health transition can leave those snapshots different while reconciliation is in progress. No consumer may reconstruct an applied snapshot from the current request; it must use the frame's effective parameters.

## Color and readback

The shader's observable color steps are fixed after sampling: clamp RGB to `[0,1]`, round each gamma-coded channel to an 8-bit value with `floor(255*c + 0.5)`, optionally compute gamma-coded integer grayscale `Y = (77*R8 + 150*G8 + 29*B8 + 128) >> 8`, and set alpha to 255. Color preserves the quantized channels. The result is opaque SDR/sRGB RGBA, top-down, and the JPEG backend receives the exact tight layout. V1 does not promise linear-light grayscale, HDR output, or cross-GPU bit identity. Exact Display-P3 source dataspace on API 33+ fails before draw/readback as `UnsupportedColorSpace`; unknown, exact sRGB, and other values retain the best-effort path.

The mandatory Framework backend and optional Native backend must consume the same RGBA semantics. Native and early downscale are transparent accelerations: they may differ in ordinary platform filtering or JPEG byte size while preserving dimensions, transforms, color mode, ownership, and public failure meanings. There is no same-frame fallback.

## Related contracts

- [Public image and geometry guidance](../../docs/usage.md#how-image-settings-combine)
