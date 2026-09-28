# Code Walk

**한국어** · [English](README.en.md)

온보딩용으로 직접 필요해서 만든 플러그인이에요.
새 코드베이스나 낯선 흐름을 익힐 때, 예전에 cmd+B 로 호출 체인을 따라가며 이해하던 방식을
Claude Code 와 IDE 가 대신 이끌어 주게 한 거예요.

<br>

## 어떻게 동작하나

Claude Code 가 코드 흐름을 **단계별 walk** 로 써 주고, Rider/IntelliJ 플러그인이 그걸 툴 윈도우로 보여줘요.
단계를 클릭하면 에디터가 그 코드로 이동하며 하이라이트돼요.

질문은 Claude Code 에서 해요. 지금 어느 단계를 보고 있는지 Claude 가 알고 있어서,
"여기 왜 이렇게 했어?" 라고만 물어도 그 코드 기준으로 답해요.

```
 Claude Code                         ~/.code-walk/                       Rider
 ───────────                         ─────────────                       ─────
 /walk 로그인 과정  ──── 쓰기 ────▶  walks/login.walk.json  ── 1초 폴링 ──▶  Code Walk 툴 윈도우
                                                                            │ 단계 클릭 / ctrl+alt+↓
 "여기 왜 이래?"   ◀─── 훅 ────    state.json / state.line  ◀── 쓰기 ──   에디터 이동 + 하이라이트
```

레포 안에는 아무것도 생기지 않아요. walk 와 상태 파일은 전부 `~/.code-walk/` 에 있어요.

<br>

## 구성

| 부분 | 위치 | 역할 |
|------|------|------|
| IDE 플러그인 (Kotlin) | `src/` | 툴 윈도우, 단계 목록, 에디터 이동·하이라이트, 상태 파일 기록 |
| Claude Code 플러그인 | `claude-plugin/` | `/walk` 커맨드, `code-walk` 스킬, 현재 단계를 주입하는 훅 |

<br>

## 설치

### 1. IDE 플러그인

Rider 에 플러그인 저장소를 한 번만 등록하면, 이후엔 Marketplace 플러그인처럼 업데이트를 받을 수 있어요.

**Settings → Plugins → ⚙ → Manage Plugin Repositories → +**

```
https://github.com/shindong96/code-walk/releases/latest/download/updatePlugins.xml
```

등록한 뒤 Marketplace 탭에서 **Code Walk** 를 검색해 설치하세요.

### 2. Claude Code 플러그인

```bash
claude plugin marketplace add shindong96/code-walk
```

```bash
claude plugin install code-walk@code-walk
```

<br>

## 사용

Claude Code 에서:

```
/walk 로그인 과정 알려줘
```

함수 이름, `파일:줄`, 또는 흐름 설명을 인자로 줄 수 있어요.
잠시 뒤 Rider 하단 **Code Walk** 탭에 walk 가 나타나요.

| 동작 | 방법 |
|------|------|
| 단계 이동 | 목록 클릭, 또는 `ctrl+alt+↓` / `ctrl+alt+↑` |
| 질문 | Claude Code 에서 그냥 물어보기 — 보고 있는 단계가 자동으로 전달돼요 |
| walk 전환 | 툴 윈도우 상단 드롭다운 |

<br>

## 개발

### IDE 플러그인 빌드

시스템 JDK 가 없어도 돼요. Rider 에 포함된 JBR 로 Gradle 이 돌아가요.

```bash
source gradlew.env && ./gradlew installToRider
```

`installToRider` 는 빌드 후 Rider 플러그인 폴더에 바로 복사해요. Rider 를 재시작하면 반영돼요.
zip 만 필요하면 `./gradlew buildPlugin` → `build/distributions/`.

기본은 `/Applications/Rider.app` 을 대상으로 빌드해요 (`gradle.properties` 의 `riderPath`).
그 경로가 없으면(CI) `platformVersion` 의 Rider 를 내려받아 빌드해요.

샌드박스 IDE 로 실행: `./gradlew runIde`

### 릴리스

```bash
git tag v0.2.0 && git push origin v0.2.0
```

CI 가 빌드하고 GitHub Release 를 만들고 `updatePlugins.xml` 을 갱신해요.
Rider 는 다음 확인 때 업데이트를 제안해요.

<br>

## 파일 형식

`~/.code-walk/walks/<id>.walk.json` — 스키마는 [`claude-plugin/skills/code-walk/SKILL.md`](claude-plugin/skills/code-walk/SKILL.md) 참고.
`~/.code-walk/state.json` (과 한 줄짜리 `state.line`) 이 현재 단계를 담아요. IDE 플러그인이 쓰고, 훅이 읽어요.
