# Third-party components

The project sources and custom vector icon are MIT licensed by Anji. The test
MP3 is a generated sine tone (not licensed commercial music). The app does not
bundle the user's artwork, music, downloaded fonts, FFmpeg, MoviePy, OpenCV, or
Pillow.

| Dependency / category | Use | License / upstream |
| --- | --- | --- |
| Android SDK platform APIs (MediaCodec, MediaStore, etc.) | Device APIs | [Android SDK terms](https://developer.android.com/studio/terms); AOSP framework code includes Apache-2.0 components |
| AndroidX Activity, Core, Lifecycle, Compose UI/Material3 and AndroidX Test | App UI, sharing, tests | [Apache-2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) |
| Kotlin / Compose compiler plugin | Language and compiler | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Android Gradle Plugin / Gradle and bundled Gradle Wrapper | Build only | [Apache-2.0](https://docs.gradle.org/current/userguide/licenses.html) |
| JUnit 4 | JVM tests only | [EPL-1.0](https://junit.org/junit4/license.html) |
| Mockito Core | JVM tests only | [MIT](https://github.com/mockito/mockito/blob/main/LICENSE) |

Versions are pinned in `build.gradle.kts` and `app/build.gradle.kts`; the Compose BOM controls Compose artifact versions. Transitive dependency notices remain governed by their own upstream licenses. On a connected development machine, `./gradlew :app:dependencies --configuration debugRuntimeClasspath` lists the resolved runtime tree. Do not assume the table is a complete SBOM.
