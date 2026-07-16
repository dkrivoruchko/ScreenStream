# Screen Capture Engine — Future Evolution

> **NON-NORMATIVE / INFORMATIONAL.** These future directions are not current requirements, implementation
> packets, verification obligations, or V1 promises and cannot override Documents 01–12.

These are possible future directions, not committed scope:

- Add HDR-to-SDR mapping for PQ, HLG, or scRGB behind the internal color-processing seam.
- Add a separately named linear-light grayscale mode that decodes, applies Rec.709 luma, and re-encodes.
- Generalize early target planning for crop, half-regions, `TargetSize`, or a smaller logical VirtualDisplay while
  preserving the Full baseline and explicit content-rectangle authority.
- Add a video path using a sibling transformed GPU-output Surface and codec path, mutually exclusive with the V1
  readback/JPEG path. Such a path would own codec input, timestamps, keyframes, buffering, and failure policy.
- Add an audio path owning its capture and codec resources with an explicit monotonic A/V timebase.
