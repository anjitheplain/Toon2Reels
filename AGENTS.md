# Agent guide

Read README.md first. This is a native Android app, not a desktop CLI or hosted service. Do not add accounts, network permission, telemetry, generated artwork, speech-bubble sequencing, natural motion, or an exposed automation service merely for discoverability.

## Map

- `app/src/main/java/kr/toon2reels/Project.kt`: Project/Panel models, image decode, border detection, reading order, timeline.
- `FrameComposer.kt`: identical composition rules for preview and export.
- `VideoExporter.kt`: MediaCodec/EGL H.264, audio decode/AAC mux, cancellation/cleanup.
- `GallerySaver.kt`: pending MediaStore insert, cropped PNG and MP4.
- `MainActivity.kt`: Compose flow and system picker.
- `app/src/test`: JVM logic; `app/src/androidTest`: actual Android codec/export tests.

## Invariants

- Keep pixels of the original comic intact except user-chosen crop and scaling to fit. Preserve aspect ratio.
- One-sheet and multi-image imports both produce `Panel` objects. Detection can fail to one full-image panel, and the user can recover.
- Selected crop/order/duration must drive both preview and MP4; cropped PNG uses the same normalized crop.
- Video is 1080×1920 at 30fps; audio is optional. No network or hidden data collection.
- Stop and clean up if export is cancelled or fails. Present Korean user-facing errors.
- Keep versionName, CHANGELOG, CITATION.cff, and .zenodo.json aligned. Use `vX.Y.Z` tags. Keep Anji as copyright/citation author and N as AI collaborator.

## Commands

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest # device/emulator required; codec test can be slow
```

JDK 17 and Android SDK Platform 35/Build Tools 34+ are required. Run tests relevant to changes. Do not commit build outputs, local.properties, signed keys, real comics, music, or device data. The bundled `tone.mp3` is a synthesized test fixture. Check manifest and artifacts for permission or secret changes before release.

## Contributions

Start with a focused change and explain user-visible behavior, tests, and known limitations. Update README, CHANGELOG, tests, and dependency notices as needed. Do not invent GitHub repository or DOI URLs. The MIT LICENSE notice must travel with substantial copies.
