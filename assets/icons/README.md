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

## iOS AppIcon (iPhone)

Source of truth is the same `launcher-mark-mask.png` silhouette and the
stable Navy (`#142234`) / cyan (`#5CD9EC`) palette. The mark is drawn at
68% of the canvas width — larger than Android's 56 dp / 108 dp adaptive
geometry, since a full-bleed iOS icon has no launcher mask shrinking
the visible area; it stays centered inside the squircle safe zone.

Regenerate the Apple-spec asset with Python 3 and Pillow installed:

```sh
python3 scripts/make-ios-icon.py
```

Output: `ios/MyWhisper/Assets.xcassets/AppIcon.appiconset/Icon-1024.png`,
wired as `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`. Per Apple's
Human Interface Guidelines / App Store rules the file is a single
1024 × 1024 PNG, sRGB, opaque RGB, full-bleed edge to edge: no alpha,
no pre-rounded corners, no shadow. iOS applies the squircle mask
itself, so artwork stays centered in the safe zone. Xcode thins the
universal 1024 px icon down to every iPhone/iPad/Spotlight/Settings
size at build time.

## Channel defaults and overrides

Stable/debug uses Navy / cyan; nightly uses Cobalt / white. All styles use
identical 56 dp alpha geometry, including Android themed monochrome icons.
The generator also emits white and ink foregrounds for the alternate palettes.

Appearance → App icon offers Default plus Navy / cyan, Cobalt / white,
Cream / ink, and Charcoal / white. Default resolves through the installed
build’s resources. Explicit palettes use stable activity-alias names and
Android persists their enabled states across process restarts and updates,
including stable/nightly and lite/full transitions. MainActivity itself is
never disabled, preserving explicit activity links and existing shortcuts.
A no-affinity launcher trampoline starts MainActivity in its own task, so
disabling the launch alias does not close an open settings screen.

On Android 13+, alias changes use one PackageManager transaction. Older
versions enable the new entry before disabling the previous entry, using
DONT_KILL_APP throughout. Launchers may briefly cache the previous icon;
Android themed icons may replace palette colors with the system theme.
