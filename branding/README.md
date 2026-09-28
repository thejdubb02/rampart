# Rampart brand assets

Red is `#DB2D54`, taken from Bulwark's logo so the two sit together.

The mark is a shield with a crenellated band above it and an envelope fold cut
through both, the same two ideas Bulwark uses, drawn flat in one colour with gaps
instead of outlines.

| File | Use |
|---|---|
| `Rampart_Logo_Color` | the mark, red, on a light background |
| `Rampart_Logo_Dark` | the mark in near black, where red is not available |
| `Rampart_Logo_White` | the mark reversed out of a dark background |
| `Rampart_Icon_App` | red tile with the mark knocked out, for taskbars and launchers |
| `Rampart_Favicon` | the mark alone on a square |
| `Rampart_Logo_with_Lettering_Color` | full lockup, all red |
| `Rampart_Logo_with_Lettering_Dark_and_Color` | red mark, near black lettering |
| `Rampart_Logo_with_Lettering_White_and_Color` | red mark, white lettering, for dark backgrounds |

Lettering is Archivo ExtraBold, converted to outlines, so nothing here needs the
font installed. `tools/make_brand.py` regenerates the whole set.

## Rook

Rook is the assistant's character: a small crow in a Rampart-red scarf. The files are
in `rook/`, and the copy in Nextcloud lives in `Rampart/Rook`.

| File | Use |
|---|---|
| `Rook_Avatar_128.png` | round avatar, small, on a dark circle |
| `Rook_Avatar_512.png` | round avatar, large, on a dark circle; source for the app's avatar art |
| `Rook_Full_1024.png` | full body, transparent background |
| `Rook_Original.jpg` | the source image the rest are cut from |
| `Rook_Working.gif` | Rook animated while working: a blink and a head turn, looping seamlessly |
| `Rook_Working_Original.mp4` | the source clip `Rook_Working.gif` and the app's sprite strips are cut from |

The app's own copies, downscaled for use in the UI, are in
`src/main/resources/art/`: `rook-avatar-96.png` and `rook-avatar-256.png` from the
round avatar, `rook-full-512.png` from the full body, and `rook-working-96.png` and
`rook-working-192.png`, each 48 frames at 12 fps laid out left to right in one strip,
for the animated face shown while Rook has a request in flight. The GIF and the mp4
are not loaded by the app; the strips are what it draws from.

Nextcloud's `Rampart/Rook` also holds `Rook_Working.gif` and the mp4 it came from,
alongside the rest of this folder.
