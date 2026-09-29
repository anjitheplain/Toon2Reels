# Release checklist

This file is for the repository owner. Do not publish a tag before build and
license/privacy checks have passed.

1. Create a real public GitHub repository and upload this folder's contents
   (the folder itself is the repository root). Do not commit local.properties,
   caches, keys, APKs, test reports, or real comic/music files.
   For a new empty repository, extract the project ZIP and run these commands
   inside the `Toon2Reels` folder after replacing the remote placeholder:

   ```sh
   git init
   git add .
   git commit -m "Prepare Toon2Reels v1.0.0"
   git branch -M main
   git remote add origin "REPLACE_WITH_REAL_GIT_REMOTE_URL"
   git push -u origin main
   ```

   GitHub Desktop can do the same. Verify that `gradlew` keeps its executable
   bit when uploading by a method other than Git.
2. Add the real repository URL to README, CITATION.cff (`repository-code`),
   codemeta.json (`codeRepository`), and .zenodo.json if appropriate. Do not
   add a guessed DOI. Add real sibling repository URLs only after publication.
3. Set GitHub About description to `Offline Android comic-to-reels converter · Created by Anji with N (ChatGPT)` and Website to `https://anjimouse.com`.
   Suggested topics: `anjimouse`, `ai-friendly`, `android`, `kotlin`,
   `comics`, `short-form-video`, `offline-first`.
4. Enable Issues, private vulnerability reporting, dependency graph and
   Dependabot alerts. Discussions are optional. Confirm the private security
   report form before encouraging reports.
5. Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
   On a device/emulator, run `./gradlew connectedDebugAndroidTest` and manually
   check image picker, drag reorder, Korean title, cropped PNGs, gallery save,
   and Android share sheet. Scan for secrets, local paths, and unintended assets.
6. Keep `versionName`, `versionCode`, CHANGELOG, CITATION.cff, codemeta.json,
   and .zenodo.json in sync; increment Android versionCode monotonically.
   Commit the release changes, tag `v1.0.0`, and create a GitHub Release.
7. Copy `app/build/outputs/apk/debug/app-debug.apk` as
   `Toon2Reels-debug.apk` and attach it to the Release with
   `Toon2Reels-debug.apk.sha256`. Regenerate the checksum after each rebuild.
   Explain that debug builds use development signing. GitHub automatically
   provides source ZIP/tarball for the tag.
8. After the first Release, connect this repository to Zenodo, archive the
   release, then add the actual DOI to citation/README metadata in the next
   version. Do not backfill a guessed DOI.

The app sends no telemetry. GitHub Release downloads, stars/forks, later
citations, and Zenodo DOI records are public signals, not per-user analytics.
