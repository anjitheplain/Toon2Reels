# Toon2Reels

완성된 인스타툰을 Android 휴대폰에서 9:16 MP4 릴스로 바꾸는 오프라인 앱.

**Created by Anji with N (ChatGPT)**<br>
**https://anjimouse.com**

## 다운로드

[최신 릴리스에서 APK 다운로드](https://github.com/anjitheplain/Toon2Reels/releases/tag/v1.0.0)

Assets의 `Toon2Reels-debug.apk`를 내려받아 Android 10 이상에서 설치하세요.
첨부 APK는 디버그 서명 빌드입니다. 기존 앱과 서명이 다르면 업데이트 설치가 거절될 수 있습니다.

## 입력과 출력

| 입력 | 처리 | 출력 |
| --- | --- | --- |
| 테두리로 나뉜 만화 이미지 한 장 | 컷 자동 검출 후 순서와 crop 수동 확인 | 1080×1920, 30fps, H.264 MP4 |
| 여러 장의 컷 이미지 | 각 파일을 한 컷으로 취급, 순서 변경 | 같은 MP4 |
| 선택한 휴대폰 오디오 파일 | 길이에 맞게 반복/자르기, 볼륨 조절 | MP4의 AAC 트랙 |
| 편집 중인 컷 | 현재 crop 영역으로 저장 | `Pictures/Toon2Reels`의 개별 PNG |

영상은 완성 화면에서 `Movies/Toon2Reels`에 저장하거나 Android 공유 시트로 전달합니다. 음악을 선택하지 않으면 무음 MP4를 만듭니다.

## 빠른 시작

### 휴대폰 사용자

1. **한 장의 만화 가져오기** 또는 **여러 장의 컷 가져오기**를 누릅니다.
2. 컷 목록의 `≡`를 끌어 순서를 바꿉니다. 컷을 누르면 표시시간과 네 변의 crop을 조절할 수 있습니다. 검출 실패 시 원본을 복제해 나누거나 여러 장으로 다시 가져오세요.
3. 컷 카드의 **이미지 저장**이나 **모든 컷을 각각 갤러리에 저장**으로 현재 crop을 PNG로 저장할 수 있습니다. **다음**에서 전체 시간 **1.5초 / 2.5초 / 4.0초**, 배경, 전환, 제목, 음악을 설정합니다. 선택된 시간 버튼은 색이 바뀝니다.
4. 9:16 미리보기 후 **릴스 만들기**를 누르고 **갤러리에 저장** 또는 **공유하기**를 선택합니다.

### 개발자

- Android Studio에서 저장소 루트를 열어 Gradle Sync 후 `app`을 실행합니다.
- 요구사항: JDK 17, Android SDK Platform 35, Build Tools 34 이상, Android 10(API 29) 이상 기기 또는 에뮬레이터. Gradle Wrapper 8.9 포함.
- macOS/Linux:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Windows: `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
- 기기 테스트: 기기가 연결된 상태에서 `./gradlew connectedDebugAndroidTest`. 소프트웨어 에뮬레이터의 영상 인코딩은 오래 걸릴 수 있습니다.

테스트 APK는 `app/build/outputs/apk/androidTest/debug/`에, 앱 APK는 `app/build/outputs/apk/debug/`에 생성됩니다. 생성물은 Git에 넣지 않습니다.

## 명령과 예시

```sh
./gradlew testDebugUnitTest         # 컷 검출과 타임라인
./gradlew lintDebug                 # Android 정적 검사
./gradlew assembleDebug             # 설치용 debug APK
./gradlew connectedDebugAndroidTest # 연결 기기에서 실제 MP4/PNG 테스트
```

앱의 만화 입력은 Android 시스템 파일 선택기에서, 결과는 MediaStore 갤러리에서 처리합니다. 별도 데스크톱 변환 명령은 없습니다.

## AI/API 연동

AI 에이전트는 [AGENTS.md](AGENTS.md)에 따라 소스와 테스트를 수정하고 Gradle로 검증할 수 있습니다. 현재 외부 호출용 JSON API, CLI, Android exported service는 없습니다. 이미지 선택, 갤러리 저장, 기기의 MediaCodec/MediaStore를 사용하는 사용자 중심 앱이므로 데스크톱 CLI를 흉내 내지 않았습니다. 별도 자동화 제품을 만들 때 패널 검출과 프로젝트 구성을 독립 모듈로 분리하고 입력 스키마, JSON 결과, 오류 코드를 설계할 수 있습니다. 현재 버전을 자동 호출 가능한 라이브러리로 소개하지 마세요.

## 구조

| 경로 | 역할 |
| --- | --- |
| `app/src/main/java/kr/toon2reels/Project.kt` | `Project`/`Panel`, 이미지 캐시, 컷 검출, 읽는 순서, 타임라인 |
| `FrameComposer.kt` | crop, 비율 유지, 배경, 전환, 제목 |
| `VideoExporter.kt` | 1080×1920/30fps H.264, AAC 음악, MP4 mux, 취소 |
| `GallerySaver.kt` | MediaStore의 컷별 PNG와 MP4 저장 |
| `MainActivity.kt` | Compose 화면, 파일 선택, 편집·미리보기·공유 |
| `app/src/test/`, `app/src/androidTest/` | 로직 테스트와 기기 렌더링 테스트 |

미리보기와 내보내기는 같은 `Timeline`과 `FrameComposer`를 사용합니다. 컷 감지는 사용자가 제공한 Colab 코드의 어두운 테두리 분석 아이디어를 Android용 축소 이미지의 선분 마스크·닫힌 영역·행별 정렬로 다시 설계했습니다. 컷 개수는 고정되지 않으며 결과는 수정 가능한 초안입니다. Android 플랫폼 MediaCodec/MediaExtractor/MediaMuxer를 사용하고 FFmpeg wrapper나 Colab의 MoviePy/OpenCV/Pillow 코드를 앱에 포함하지 않습니다. 자연스러운 모션과 Smart Motion은 구현하지 않았습니다.

### 테스트와 제한

- JVM 로직 테스트 4개: 컷 수 4/6/8/9/10, 검출 실패, 정렬, 순서·시간, 타임라인.
- Android 10 소프트웨어 에뮬레이터 기기 테스트 4개: 실제 H.264 무음·AAC 포함 MP4, 첫 프레임 디코딩, crop PNG 크기와 갤러리 저장, 취소·재시도, 입력 모드와 감지 실패 복구. 공개용 빌드의 실행 결과는 [CHANGELOG.md](CHANGELOG.md)에 기록합니다.
- 실제 손가락 드래그, 다양한 파일 제공자 및 SNS 공유 대상 앱은 수동 확인 대상입니다. 기기별 인코더, 저장 공간과 메모리에 따라 긴 렌더링이 느리거나 실패할 수 있습니다. 일부 특수 음악 형식은 열리지 않습니다.
- 미리보기에는 음악 재생이 없고 앱을 강제 종료하면 편집 프로젝트와 렌더링이 복원되지 않습니다. 검출은 어두운 직선 테두리에 적합합니다.
- 렌더링 입력은 긴 변 최대 3000픽셀로 디코딩합니다. PNG 저장은 원본 전체의 긴 변 최대 4096픽셀로 디코딩 후 crop하므로 아주 큰 합본에서는 선명도가 줄어듭니다.

## 개인정보와 보안

로그인, 인터넷 권한, 서버, 광고, 숨은 텔레메트리가 없습니다. 원고와 음악은 Android 시스템 파일 선택기에서 읽고 기기 안에서 처리합니다. 공유는 사용자가 공유 시트를 누를 때만 실행됩니다. [PRIVACY.md](PRIVACY.md), [SECURITY.md](SECURITY.md)를 보세요.

## 인용·제작·관련 프로젝트·라이선스

제작자와 AI 협업은 [CREDITS.md](CREDITS.md), 인용은 [CITATION.cff](CITATION.cff), 출처는 [NOTICE](NOTICE), 기여 방법은 [CONTRIBUTING.md](CONTRIBUTING.md)에 있습니다. 관련 프로젝트는 [Anji 프로젝트 허브](https://anjimouse.com)를 참고하세요. 형제 저장소 URL은 실제 공개된 후에만 추가합니다.

소스는 [MIT License](LICENSE)로 공개합니다. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 주요 의존성의 용도와 라이선스를 정리했습니다. 사용자가 불러온 만화·음악의 권리는 해당 저작권자에게 있습니다.

## 릴리스

앱 버전, `CHANGELOG.md`, `CITATION.cff`, `.zenodo.json`은 `1.0.0`으로 맞췄습니다. GitHub 공개 뒤 `v1.0.0` 태그와 Release를 만들고 `Toon2Reels-debug.apk`와 checksum 파일을 첨부합니다. 소스 ZIP은 GitHub가 태그에서 자동 생성합니다. 스토어 배포 시에는 본인 서명 release APK를 준비하세요. Release 다운로드, 별·fork·인용/DOI는 공개적으로 확인 가능한 재사용 신호이며 이용자 추적 코드는 넣지 않습니다. Zenodo 연동은 첫 Release 뒤 저장소 소유자가 설정합니다.
