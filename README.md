# 링크세이버 (LinkSaver)

인스타그램 게시물 / 릴스, X(트위터) 게시물의 **링크만 붙여넣으면** 사진·영상을 폰 갤러리에 저장해 주는 안드로이드 앱입니다.

- 추출 엔진: **yt-dlp**(파이썬 런타임 + ffmpeg 번들) — `youtubedl-android` 라이브러리
- UI: Kotlin + Jetpack Compose (Material 3)
- 최소 안드로이드 7.0 (API 24)

---

## 무엇을 받을 수 있나

| 대상 | 지원 | 비고 |
|---|---|---|
| 인스타그램 게시물 (사진·영상) | ✅ | 여러 장(캐러셀)이면 **받을 것만 골라서** 저장 |
| 인스타그램 릴스 | ✅ | |
| 인스타그램 스토리 / 하이라이트 | ❌ | 로그인 세션이 필수라 이번 버전에서는 제외 |
| X(트위터) 게시물 영상 / GIF | ✅ | 공개 계정 |
| X 게시물 이미지 | ✅ | |
| 비공개 계정 · 삭제된 게시물 | ❌ | 원리상 불가 |

> 인스타그램은 로그인 없이 접근할 때 IP 단위로 요청을 제한합니다. 실패가 잦다면 **설정 → cookies.txt** 에 브라우저에서 내보낸 쿠키 파일을 등록하면 성공률이 크게 올라갑니다. (본인 계정 정보이므로 외부에 공유하지 마세요.)

---

## APK 만들기 (이 PC에 아무것도 설치하지 않고)

### 1. GitHub 저장소에 올리기

```bash
git init
```

```bash
git add -A && git commit -m "링크세이버 초기 버전"
```

GitHub에서 **비공개(Private) 저장소**를 하나 만든 뒤:

```bash
git remote add origin https://github.com/<본인계정>/linksaver.git
```

```bash
git branch -M main && git push -u origin main
```

### 2. 자동 빌드 결과 받기

push 하면 `Build APK` 워크플로가 자동으로 돕니다.
저장소 → **Actions** 탭 → 방금 실행된 작업 → 하단 **Artifacts** 의 `linksaver-apk` 를 내려받으면
`app-arm64-v8a-debug.apk` 등이 들어 있습니다.

- 요즘 폰은 대부분 **arm64-v8a** 입니다. 이걸 폰으로 옮겨 설치하세요.
- 설치 시 "출처를 알 수 없는 앱" 허용이 필요합니다.

### 3. (권장) 고정 서명키 만들기 — 업데이트 설치용

서명키가 매번 바뀌면 새 버전을 **덮어쓰기 설치할 수 없습니다**. 한 번만 해두세요.

1. Actions → `Create signing keystore (한 번만 실행)` → **Run workflow**
2. 끝나면 `signing-keystore` 아티팩트를 내려받아 `release.jks` 를 안전한 곳에 백업
3. 저장소 **Settings → Secrets and variables → Actions** 에 4개 등록

   | 이름 | 값 |
   |---|---|
   | `KEYSTORE_B64` | `release.jks.base64.txt` 파일 내용 전체 |
   | `KEYSTORE_PASSWORD` | 워크플로에 입력한 비밀번호 |
   | `KEY_ALIAS` | 워크플로에 입력한 별칭 |
   | `KEY_PASSWORD` | 위와 동일 |

이후 push 하면 서명된 **release** APK가 나오고, 계속 덮어쓰기 업데이트가 됩니다.

---

## 쓰는 법

1. 인스타/X 앱에서 게시물 → **공유 → 링크세이버**
2. 또는 링크를 복사한 뒤 앱을 열면 자동으로 채워집니다 → **다운로드**

### 여러 장인 게시물에서 골라 받기

**다운로드**를 누르면 먼저 게시물 안에 무엇이 들어 있는지 확인합니다.

- 미디어가 **1개**면 → 그대로 바로 받습니다.
- 미디어가 **여러 개**(예: 사진 10장 캐러셀)면 → 썸네일 격자가 뜹니다.
  칸을 눌러 원하는 것만 체크하고 **[선택한 N개 받기]** 를 누르면 그것만 저장됩니다.
  (기본값은 전체 선택이므로, 다 받고 싶으면 그냥 한 번 더 누르면 됩니다.)

각 칸에는 순번, 사진/동영상 구분, 해상도 또는 재생시간이 표시됩니다.
내부적으로는 yt-dlp의 `--playlist-items 1,3,7` 옵션으로 선택한 항목만 내려받습니다.

화질은 **최고 화질 / 720p / 오디오만(MP3)** 중에서 고를 수 있고, 마지막 선택이 기억됩니다.

저장 위치:

- 사진 → `Pictures/LinkSaver`
- 영상 → `Movies/LinkSaver`
- 오디오 → `Music/LinkSaver`

---

## 문제 해결

**첫 실행이 느려요**
파이썬 런타임과 yt-dlp를 내부 저장소에 푸는 과정이라 5~20초 걸립니다. 한 번만 그렇습니다.

**갑자기 전부 실패해요**
인스타/X가 내부 구조를 바꾼 경우입니다. **설정 → yt-dlp 엔진 → 업데이트** 를 누르면 앱 재설치 없이 엔진만 최신으로 갱신됩니다. 이게 이 구조를 택한 가장 큰 이유입니다.

**"인스타그램이 로그인을 요구했습니다"**
익명 요청 제한에 걸린 상태입니다. 몇 분 뒤 재시도하거나, 설정에서 cookies.txt를 등록하세요.

**APK 설치가 안 돼요**
- 기존에 다른 서명으로 설치한 버전이 있으면 먼저 삭제하세요.
- 폰 CPU에 맞는 ABI인지 확인하세요 (보통 `arm64-v8a`).

---

## 프로젝트 구조

```
app/src/main/java/kr/neptune/linksaver/
├── LinkSaverApp.kt          앱 시작 시 엔진 초기화
├── MainActivity.kt          공유 인텐트 / 링크 열기 수신
├── core/
│   ├── YtDlp.kt             yt-dlp 래퍼 (init / probe / download / update)
│   ├── DownloadService.kt   포그라운드 서비스, 순차 다운로드 + 알림
│   ├── MediaImporter.kt     캐시 → MediaStore(갤러리) 이동
│   ├── UrlUtil.kt           링크 정규화, 단축링크 해제, 플랫폼 판별
│   ├── DownloadRepo.kt      화면-서비스 공유 상태
│   ├── Notifications.kt     알림 채널
│   ├── Prefs.kt             설정 저장
│   └── Models.kt            데이터 모델
└── ui/                      Compose 화면
```

---

## 라이선스 / 주의

- 번들된 yt-dlp는 Unlicense, `youtubedl-android` 는 **GPL-3.0** 입니다. 이 앱을 배포한다면 소스도 함께 공개해야 합니다.
- **개인 소장 용도로만 사용하세요.** 저작권자의 허락 없는 재업로드·배포는 위법일 수 있고, 인스타그램·X의 이용약관에도 어긋납니다. 플레이스토어 정책상 등록도 어려우므로 직접 설치(사이드로드)를 전제로 만들었습니다.
