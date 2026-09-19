# EmulaNerd Android integration for PPSSPP

This directory publishes the Android/Capacitor glue used by EmulaNerd to launch
the PPSSPP engine bundled in its Android application. It is published under
the same GPL-2.0-or-later terms as this PPSSPP fork. The full license text is
in the repository's `LICENSE.TXT`.

## Exact tested build

- EmulaNerd build ID: `f4505cf08f7c7c97`
- PPSSPP source revision: `46bcf76742d65185339d6d41d6536e71b23701cd`
- PPSSPP source branch: `emulanerd-v1.20.4`
- Tested Android package: `com.emulanerd.app`, version `1.0.4` (version code 5)

The files here are copied byte-for-byte from that EmulaNerd build's source:

- `android/app/src/main/java/com/emulanerd/app/EmulaNerdPpssppActivity.java`
  prepares PPSSPP's writable, app-private memory stick and returns gameplay
  frame metrics to the host.
- `android/app/src/main/java/com/emulanerd/app/PpssppNativePlugin.java`
  streams a verified local PSP image into app-private storage and starts the
  bundled native PPSSPP Activity through Capacitor.
- `web/src/lib/ppssppNative.ts` is the matching TypeScript bridge.

The host's `MainActivity` registers `PpssppNativePlugin`, and the Android
manifest declares `EmulaNerdPpssppActivity` as a non-exported activity. The
host build compiles PPSSPP from this fork's `android/src` and `CMakeLists.txt`.
These host integration points are described here rather than copying unrelated
application screens, credentials, catalog data, game images, or game files.

## Scope and other bundled components

This source drop covers the PPSSPP engine changes and the PPSSPP-specific
Android bridge only. It is **not** a complete source package for the entire
EmulaNerd APK: that APK also bundles LibretroDroid, separate Libretro core
libraries, and Play! WebAssembly assets. Those components have independent
source and license obligations. Do not treat this directory as their source
offer; their exact revisions and corresponding source must be published
separately before redistributing the complete APK as a GPL-compliant source
package.

No game ROMs, BIOS files, private account data, service credentials, or game
catalog/download identifiers are included here.
