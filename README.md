# 🎵 X-WARE Android

YouTube 음악 스트리밍 앱 — Windows Forms 버전을 Android로 포팅한 버전입니다.

---

## 📱 기능

| 기능 | 설명 |
|------|------|
| 🔍 YouTube 검색 | InnerTube API를 사용한 음악 검색 |
| ▶️ 음악 재생 | YouTube IFrame Player (Chrome UA 사용, CDN 차단 없음) |
| 🎤 실시간 가사 | lrclib.net API를 통한 동기화 가사 |
| ❤️ 즐겨찾기 | 로컬 저장소에 곡 저장 |
| 📋 플레이리스트 | 사용자 정의 플레이리스트 생성·관리 |
| 🔁 셔플 / 반복 | 셔플, 전체 반복, 한 곡 반복 |
| 🔈 에코 효과 | 볼륨 에코 효과 |
| 🌃 오버레이 가사 | 다른 앱 위에 가사 표시 (시스템 오버레이) |
| 🎵 백그라운드 재생 | 앱을 내리거나 화면을 꺼도 음악 계속 재생 |
| 🎨 비주얼 이퀄라이저 | 박자에 반응하는 배경 애니메이션 |

---

## 🚀 빠른 시작 — APK 빌드 방법

### 방법 1: GitHub Actions (가장 쉬움 ⭐)

> **안드로이드 개발을 전혀 몰라도 됩니다!**
> GitHub 계정만 있으면 됩니다.

**1단계 — 저장소 Fork**
1. 이 페이지 오른쪽 상단 **Fork** 버튼 클릭
2. 본인 계정의 저장소로 복사됩니다

**2단계 — 자동 빌드 시작**
1. Fork한 저장소에서 **Actions** 탭 클릭
2. "Build XWare APK" 워크플로 선택
3. **"Run workflow"** 버튼 클릭
4. 약 5~8분 기다립니다

**3단계 — APK 다운로드**
1. 빌드 완료 후 Actions 탭의 빌드 결과 클릭
2. 하단 **Artifacts** 섹션에서 `XWare-Debug-APK` 다운로드
3. zip 파일 압축 해제 → `XWare-1.0.0-debug.apk` 파일

**4단계 — 안드로이드에 설치**
1. APK 파일을 안드로이드 기기로 전송 (USB, 카카오톡, 이메일 등)
2. 파일 열기 → "알 수 없는 소스에서 설치 허용" 팝업에서 **허용**
3. 설치 완료!

---

### 방법 2: Android Studio (로컬 빌드)

**준비물**
- [Android Studio](https://developer.android.com/studio) 최신 버전
- Java 17 이상 (Android Studio에 포함됨)
- 안드로이드 기기 또는 에뮬레이터

**빌드 단계**

```bash
# 1. 저장소 클론
git clone https://github.com/YOUR_USERNAME/XWare-Android.git
cd XWare-Android

# 2. Gradle Wrapper JAR 다운로드 (처음 한 번만)
curl -fsSL "https://raw.githubusercontent.com/gradle/gradle/v8.6.0/gradle/wrapper/gradle-wrapper.jar" \
  -o gradle/wrapper/gradle-wrapper.jar

# 3. Debug APK 빌드
./gradlew assembleDebug

# 빌드된 APK 위치:
# app/build/outputs/apk/debug/XWare-1.0.0-debug.apk
```

또는 Android Studio에서:
1. `File → Open` → 이 폴더 선택
2. Gradle 동기화 완료 대기
3. 상단 메뉴 `Build → Build Bundle(s) / APK(s) → Build APK(s)`
4. `app/build/outputs/apk/debug/` 폴더에서 APK 확인

---

## 📲 설치 후 첫 실행

1. **알림 권한** → 백그라운드 재생 알림에 필요합니다. **허용** 선택

2. **화면이 뜨면** 홈 화면에서 음악 검색창에 원하는 곡 이름 입력

3. **가사 오버레이** 사용 시:
   - NP(재생 중) 화면 하단 "오버레이" 버튼 탭
   - "다른 앱 위에 표시" 권한 요청 팝업 → **허용**
   - 이제 홈 화면이나 다른 앱에서도 가사가 표시됩니다

---

## 🔧 구조 설명

```
XWare-Android/
├── app/src/main/
│   ├── java/com/xware/
│   │   ├── MainActivity.kt         ← 메인 액티비티, WebView 설정
│   │   ├── AndroidBridge.kt        ← JS ↔ Kotlin 통신 브리지
│   │   ├── MusicKeepAliveService.kt ← 백그라운드 재생 서비스
│   │   └── LyricsOverlayService.kt ← 오버레이 가사 서비스
│   ├── assets/
│   │   ├── index.html              ← UI 메인 HTML (수정됨)
│   │   ├── app.js                  ← UI 로직 (원본 유지)
│   │   ├── style.css               ← 기본 스타일 (원본 유지)
│   │   ├── android.css             ← Android 전용 스타일 오버라이드
│   │   └── bridge.js               ← Windows↔Android 브리지 어댑터
│   └── AndroidManifest.xml
├── .github/workflows/build.yml     ← GitHub Actions 자동 빌드
└── README.md
```

### YouTube 재생이 가능한 이유

일반 Android WebView에서 YouTube를 재생하면 차단됩니다.  
X-WARE는 다음 방법으로 이를 해결합니다:

1. **Chrome 모바일 User-Agent 사용** → YouTube가 일반 Chrome으로 인식
2. **`mediaPlaybackRequiresUserGesture = false`** → 자동재생 허용
3. **`MIXED_CONTENT_ALWAYS_ALLOW`** → YouTube CDN 미디어 로드 허용
4. **하드웨어 가속 활성화** → 원활한 재생 성능

---

## 🔐 릴리즈 서명 (선택사항)

배포용 APK는 서명이 필요합니다. GitHub Secrets에 다음을 추가하세요:

| Secret 이름 | 설명 |
|------------|------|
| `KEYSTORE_BASE64` | `base64 -w 0 my.jks` 출력값 |
| `SIGNING_STORE_PASSWORD` | 키스토어 비밀번호 |
| `SIGNING_KEY_ALIAS` | 키 별칭 |
| `SIGNING_KEY_PASSWORD` | 키 비밀번호 |

키스토어 생성:
```bash
keytool -genkey -v -keystore my.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias key0
```

Secrets를 설정하지 않으면 debug 서명으로 빌드됩니다 (개인 사용에는 충분합니다).

---

## 🐛 문제 해결

**Q: 음악이 재생되지 않아요**  
A: 인터넷 연결 확인 후 앱을 재시작하세요. YouTube에서 해당 곡이 한국에서 재생 가능한지 확인해보세요.

**Q: 가사가 나오지 않아요**  
A: lrclib.net 데이터베이스에 없는 곡일 수 있습니다. 영어/한국어 인기곡은 대부분 가사가 있습니다.

**Q: 백그라운드에서 음악이 끊겨요**  
A: 설정 → 배터리 → X-WARE → "배터리 최적화 제외" 설정으로 해결됩니다.

**Q: 오버레이 권한을 허용했는데 가사가 안 보여요**  
A: 앱을 재시작한 후 다시 시도해보세요.

**Q: 빌드 에러 `gradle-wrapper.jar not found`**  
A: `.github/workflows/build.yml`의 "Gradle Wrapper 준비" 단계가 자동으로 다운로드합니다. Actions 탭에서 다시 실행하세요.

---

## 📋 요구사항

- Android 6.0 (API 23) 이상
- 인터넷 연결 필수
- 저장 공간: 약 20MB
