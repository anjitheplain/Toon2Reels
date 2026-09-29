# Contributing

Thanks for improving Toon2Reels. Please check open Issues before starting a
large change. For a bug report, include the app version, Android version,
input image layout, expected/actual result, and steps to reproduce; share
artwork only if you have permission. For security issues, use SECURITY.md.

Keep changes focused on the comic-to-reels flow. Preserve offline processing,
aspect ratio, recoverable panel detection, and identical preview/export
composition. Avoid adding generic video editing features or network access
without a clear product decision.

Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`
before a pull request. For changes to MediaCodec, MediaStore, or image crop,
also run `./gradlew connectedDebugAndroidTest` on a device/emulator. Describe
the behavior, validation, and limitations in the pull request. Update README,
CHANGELOG, metadata and notices when relevant.

By submitting a contribution, you agree that your contribution is distributed
under the project's MIT License. Do not add third-party assets or code without
provenance and compatible license terms.
