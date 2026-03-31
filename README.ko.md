[English](README.md) | **한국어**

# GitHub Actions Tool for IntelliJ

GitHub / GitHub Enterprise 환경을 위한 IntelliJ IDEA 플러그인.
GitHub Actions 워크플로우의 모니터링, 로그 조회, 수동 실행(Dispatch)을 IDE 안에서 처리할 수 있습니다.

---

## 목차

1. [주요 기능](#주요-기능)
2. [요구 사항](#요구-사항)
3. [설치](#설치)
4. [인증 설정](#인증-설정)
5. [플러그인 설정](#플러그인-설정)
6. [사용법](#사용법)
7. [단축키 및 조작법](#단축키-및-조작법)
8. [기술 스택](#기술-스택)

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| **워크플로우 모니터링** | 전체 워크플로우 목록 및 실행(Run) 상태를 실시간 자동 갱신 |
| **워크플로우 수동 실행** | `workflow_dispatch` 이벤트를 지원하는 워크플로우를 IDE에서 직접 Dispatch |
| **Jobs/Steps 상세 조회** | Run별 Job 트리와 Step 단위 로그를 표시 |
| **로그 검색** | Step 로그 내 키워드 검색, 매칭 라인 자동 하이라이트 및 펼침 |
| **상태/브랜치 필터** | 실행 결과(success, failure 등)와 브랜치별 필터링 |
| **Quick Search** | Run 목록에서 타이핑 즉시 이름 검색 (Speed Search) |
| **VCS 자동 감지** | Git remote에서 GitHub 서버/Owner/Repository를 자동 감지 |
| **IntelliJ GitHub 계정 연동** | IntelliJ에 등록된 GitHub 계정 토큰을 그대로 활용 (별도 PAT 입력 불필요) |
| **네트워크 자동 복구** | 네트워크 끊김 시 오프라인 모드 전환, 자동 health-check로 복구 시 데이터 리로드 |

---

## 요구 사항

- **IntelliJ IDEA** 2024.1 ~ 2026.1 (Community / Ultimate)
- **Git** 플러그인 활성화 (기본 내장)
- **GitHub** 플러그인 활성화 (기본 내장)
- 프로젝트에 **Git remote**가 설정되어 있어야 함 (GitHub 또는 GitHub Enterprise)

---

## 설치

### 1. 플러그인 zip 다운로드

아래 링크에서 최신 플러그인 zip 파일을 다운로드합니다:

> **[intellij-github-actions-tool-1.1.0.zip](dist/intellij-github-actions-tool-1.1.0.zip)**

또는 저장소의 `dist/` 폴더에서 직접 다운로드할 수 있습니다.

### 2. IntelliJ에 플러그인 설치

1. IntelliJ IDEA를 엽니다.
2. **Settings** 를 엽니다 (Mac: `⌘,` / Windows: `Ctrl+Alt+S`).
3. 좌측 메뉴에서 **Plugins** 를 선택합니다.
4. 상단의 **톱니바퀴(⚙) 아이콘** 을 클릭합니다.
5. **Install Plugin from Disk...** 를 선택합니다.
6. 다운로드한 `intellij-github-actions-tool-1.1.0.zip` 파일을 선택합니다.
7. **OK** 를 클릭하면 설치가 완료됩니다.
8. **Restart IDE** 버튼을 클릭하여 IntelliJ를 재시작합니다.

### 3. 설치 확인

재시작 후 하단 탭 바에 **GitHub Actions** 탭이 나타나면 설치가 완료된 것입니다.

> 탭이 보이지 않으면: **View** > **Tool Windows** > **GitHub Actions**

---

## 인증 설정

플러그인은 두 가지 인증 방식을 지원합니다. **IntelliJ GitHub 계정 연동**이 기본 활성화되어 있어, 대부분의 경우 별도 설정 없이 바로 사용할 수 있습니다.

### 방식 1: IntelliJ GitHub 계정 사용 (기본값, 권장)

IntelliJ에 이미 GitHub 계정이 등록되어 있다면 추가 설정이 필요 없습니다.

**GitHub 계정이 아직 없는 경우:**

1. **Settings (⌘,)** > **Version Control** > **GitHub**
2. **+** 버튼 클릭 > **Log In via GitHub...** 또는 **Log In with Token...**
3. GitHub Enterprise 서버라면 **Server** 주소를 입력합니다.
4. 로그인 후 계정이 등록되면, 플러그인이 해당 토큰을 자동으로 사용합니다.

> 토큰에 **repo**, **workflow** 권한(scope)이 포함되어 있어야 합니다.

### 방식 2: Personal Access Token 직접 입력

IntelliJ GitHub 계정을 사용하지 않거나, 별도의 토큰을 지정하고 싶은 경우:

1. **Settings (⌘,)** > **Tools** > **GitHub Actions Tool**
2. **"IntelliJ GitHub 계정 설정 사용"** 체크박스를 **해제**합니다.
3. **Personal Access Token** 필드에 토큰을 입력합니다.
4. **Apply** 또는 **OK**를 클릭합니다.

**토큰 발급 방법:**

1. GitHub 웹 > 우측 상단 프로필 > **Settings**
2. **Developer settings** > **Personal access tokens** > **Tokens (classic)**
3. **Generate new token** 클릭
4. 다음 권한(scope)을 선택합니다:
   - `repo` (전체)
   - `workflow`
5. **Generate token** 후 토큰 값을 복사하여 플러그인에 입력합니다.

---

## 플러그인 설정

**Settings (⌘,)** > **Tools** > **GitHub Actions Tool**

### VCS 저장소 정보

Git remote에서 자동 감지된 GitHub 서버 URL, Owner, Repository 정보가 표시됩니다.
별도 설정이 필요 없으며, `origin` remote 기준으로 감지됩니다.

### 인증 설정 (글로벌)

| 항목 | 설명 | 기본값 |
|------|------|--------|
| IntelliJ GitHub 계정 설정 사용 | 체크 시 IntelliJ에 등록된 GitHub 계정 토큰 사용 | 활성화 |
| Personal Access Token | 체크 해제 시 직접 토큰 입력 (모든 프로젝트에서 공유) | - |

### 모니터링 설정

| 항목 | 설명 | 기본값 |
|------|------|--------|
| 자동 새로고침 활성화 | 워크플로우 실행 목록 자동 갱신 | 활성화 |
| 새로고침 주기 (초) | 자동 갱신 간격 (최소 10초) | 30초 |

---

## 사용법

### Tool Window 열기

하단 탭 바에서 **GitHub Actions** 탭을 클릭합니다.

> 탭이 보이지 않으면: **View** > **Tool Windows** > **GitHub Actions**

### 화면 구성

Tool Window는 최대 4개 영역으로 구성됩니다:

```
┌──────────────┬──────────────────────┬──────────────────┐
│              │ [상태▾][브랜치▾]      │                  │
│  ① 워크플로우 │  [Dispatch][Web][↻]  │                  │
│     트리      ├──────────────────────┤  ④ Step 로그     │
│              │  ② 실행(Run) 목록     │     패널         │
│              ├──────────────────────┤                  │
│              │  ③ Jobs 상세 트리     │                  │
└──────────────┴──────────────────────┴──────────────────┘
```

| 영역 | 설명 |
|------|------|
| **① 워크플로우 트리** | 전체 워크플로우 목록. "전체 히스토리" 또는 특정 워크플로우 선택 |
| **② 실행(Run) 목록** | 선택한 워크플로우의 최근 실행 기록. 상태/브랜치 필터 적용 가능 |
| **③ Jobs 상세 트리** | 선택한 Run의 Job 목록을 그룹 트리로 표시 (그룹은 Job 이름의 ` / ` 기준) |
| **④ Step 로그 패널** | 선택한 Job의 Step별 collapsible 로그 뷰 (Job 선택 시 표시) |

### 워크플로우 모니터링

1. **① 워크플로우 트리**에서 모니터링할 워크플로우를 선택합니다.
2. **② 실행 목록**에서 Run을 클릭하면 **③ Jobs 상세 트리**에 Job 목록이 로드됩니다.
3. **③ Jobs 트리**에서 Job을 클릭하면 **④ Step 로그 패널**이 우측에 나타납니다.

### 워크플로우 수동 실행 (Dispatch)

`workflow_dispatch` 이벤트가 정의된 워크플로우만 실행 가능합니다.

1. **① 워크플로우 트리**에서 워크플로우를 선택합니다.
2. **Dispatch** 버튼 클릭 또는 우클릭 > **"Dispatch 실행"**을 선택합니다.
3. 브랜치와 입력 파라미터를 설정한 뒤 **실행**합니다.

### 로그 검색

**④ Step 로그 패널** 상단의 **"로그 검색..."** 필드에 키워드를 입력합니다:
- 매칭되는 라인이 **노란색 하이라이트**로 표시됩니다.
- 매칭 라인이 포함된 Step과 Group이 **자동으로 펼쳐집니다**.

---

## 단축키 및 조작법

| 조작 | 동작 |
|------|------|
| Run 목록에서 **타이핑** | Quick Search (Speed Search) |
| Run **클릭** | Jobs 상세 트리 로드 |
| Run **더블클릭** | 브라우저에서 열기 |
| Run **우클릭** | 컨텍스트 메뉴 (웹으로 이동) |
| 워크플로우 **우클릭** | 컨텍스트 메뉴 (Dispatch 실행, 웹으로 이동) |
| Job **클릭** | Step 로그 패널 표시 |
| Step 헤더 **클릭** | 로그 접기/펼치기 토글 |

---

## 기술 스택

- **Kotlin** + IntelliJ Platform SDK 2024.1+
- **OkHttp** — HTTP 클라이언트
- **Gson** — JSON 파싱
- **SnakeYAML** — 워크플로우 YAML 파싱
- **IntelliJ GitHub Plugin API** — 계정 토큰 연동

---

## 라이선스

MIT License
