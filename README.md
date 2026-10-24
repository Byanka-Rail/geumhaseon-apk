# 사격임무: 금하선 Android

`사격임무_금하선_RTS_v1.86_1.html`을 Android WebView 앱으로 묶는 GitHub Actions 프로젝트입니다.

- 앱 이름: `사격임무: 금하선`
- Android applicationId: `com.byankarail.geumhaseon`
- 내장 게임: `1.86.1`
- APK wrapper: `versionCode 1` / `versionName 1.0.0`
- Gradle: 8.9
- Android Gradle Plugin: 8.7.3
- Java: 17
- compileSdk / targetSdk: 35
- GitHub Actions artifact: `GEUMHASEON_APK`

## GitHub에서 APK 만들기

1. GitHub에서 `Byanka-Rail/geumhaseon-apk` 저장소를 만듭니다.
2. 이 ZIP의 **내용물 전체**를 저장소 최상위에 올립니다. `.github` 폴더도 포함해야 합니다.
3. `main` 브랜치에 Commit 합니다.
4. `Actions` → `Build Android APK` → `Run workflow`를 실행합니다.
5. 빌드가 끝나면 Artifacts의 `GEUMHASEON_APK`에서 APK를 받습니다.

## HTML만 갱신하기

APK는 `app/src/main/assets/game.html`을 내장합니다. 같은 저장소의 `update.json`과 `game.html`을 함께 갱신하면 설치된 앱도 업데이트를 확인할 수 있습니다.

`update.json`의 `build`는 이전보다 큰 정수로 올리고, `sha256`은 새 `game.html`의 SHA-256으로 바꿉니다.

> 저장소 이름을 `geumhaseon-apk`가 아닌 다른 이름으로 만들 경우 `MainActivity.java`의 `RAW_BASE` 한 줄도 같은 저장소 주소로 변경해야 합니다.
