# Android launcher icon

`launcher-mark-mask.png` is the cropped, unmodified alpha channel of the
original 432 × 432 launcher foreground. It preserves the existing five-bar
waveform and speech tail without tracing or redrawing the mark.

Regenerate launcher assets with Python 3 and Pillow installed:

```sh
python3 scripts/make-icon.py
```

The generator uses solid navy (`#142234`) and cyan (`#5CD9EC`), with the mark
centered at 56 dp wide on Android's 108 dp adaptive-icon layer. Backgrounds
are opaque and edge-to-edge; Android supplies the launcher mask. The existing
adaptive-icon XML also uses the foreground alpha for themed monochrome icons.

Notification and IME action icons are independent assets.
