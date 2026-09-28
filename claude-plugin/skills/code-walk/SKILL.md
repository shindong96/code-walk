---
name: code-walk
description: Write a step-by-step code walk for a function, class, file:line or a described flow ("로그인 과정 알려줘", "walk me through X", "이 흐름 따라가면서 설명해줘") and hand it to the Code Walk IDE tool window, then answer questions about it. Use whenever the user asks for an ordered, follow-the-flow explanation of code, runs /walk, or asks a question while a walk is open in the IDE (the hook injects a "[code-walk] The user is viewing …" line).
---

# Code Walk

You write the walk; the IDE shows it. The user browses steps in the **Code Walk** tool window
(Rider/IntelliJ plugin) — clicking a step moves the editor to the code and highlights it — and
asks questions here. There is no checkpoint loop: write the whole walk in one go, then wait.

Read-only: never edit, format or run project code while producing a walk.

## 1. Explore

Resolve the entry point first. If the request is a symbol or `path:line`, find its declaration.
If it is a described flow ("로그인 과정"), pick the most likely entry point (controller action,
handler, `main`, exported function) and say which one you picked and why.

Follow the call graph from there, in **execution order**, to depth 3 by default (`--depth N`
overrides). Prefer IDE call-graph tools when present (JetBrains MCP `analyze_calls` with
`OUTGOING_CALLS`, `search_symbol`); otherwise read each function body and list the calls it makes.

Skip silently: framework/library/stdlib code, generated code, tests, trivial getters/setters,
logging, DTO constructors, pure config. If a function has more than ~6 interesting calls, group
them into one step. Do not revisit a function already covered.

**Every `file` and `line` you write must come from a file you read in this session.** Re-read
before writing if unsure — a step pointing at the wrong line is worse than no step.

## 2. Write the walk file

Path: `~/.code-walk/walks/<id>.walk.json` — **outside the repo**, so nothing lands in git.
`<id>` is a short kebab-case slug of the entry point (`login-with-region-check`,
`order-service-place-order`). Overwrite if it exists. Create the directory if needed.

```json
{
  "version": 1,
  "id": "login-with-region-check",
  "title": "앱 로그인 과정",
  "project": "/abs/path/to/project/root",
  "createdAt": "2026-09-28T15:40:00+09:00",
  "steps": [
    {
      "id": "s1",
      "title": "한눈에 보기 — 앱 로그인은 어떻게 흘러가나",
      "depth": 0,
      "body": "…이 흐름의 목적, 시작·끝, 핵심 규칙 2–4개 (파일 없음)…"
    },
    {
      "id": "s2",
      "title": "비밀번호를 제출하면 먼저 이 리전 계정으로 로그인을 시도해요 (LoginWithRegionCheck)",
      "file": "FitPlusAPI/Controllers/v1_1/V1_1_OnboardingController.cs",
      "line": 201,
      "endLine": 206,
      "depth": 0,
      "body": "…markdown…"
    }
  ]
}
```

- `project`: absolute path of the directory the step `file` paths are relative to (normally the
  git root / current working directory). The IDE plugin only lists walks whose `project` and its
  own project root contain each other.
- `file`: relative to `project`. `line`/`endLine`: 1-based, inclusive. Point `line` at the
  declaration or the first line that matters; `endLine` closes the range that will be
  highlighted (a whole short function, or the few lines the body talks about). Keep ranges ≤ 40 lines.
- `depth`: call depth from the entry point (0, 1, 2…). The tool window indents by it.
- Order = execution order, depth-first, exactly as you would explain it out loud.
- Step count: 6–15 for a typical flow. Fold what is below max depth into the parent's body.
- **Step 1 is an overview with no `file`** (text-only step): what this flow is for in product
  terms, when it starts, how it ends, and the 2–4 rules that shape it. A planner should be able to
  read only this step and know what the feature does.

### Audience: the planner sits next to the developer

The walk exists to explain **what the product does and why**, with the code as evidence — not to
narrate the code. Write every title and body so that a planner (기획자) with no code background
follows the flow, while a developer still gets the pointer they need.

That means **business vocabulary first, code vocabulary only as a pointer**:

| Say | Not |
|---|---|
| 사용자, 계정, 회원 | user object, entity, row |
| 약관에 동의하지 않았으면 | flag 가 false 면, `IsAgreed == false` |
| 값이 없으면 / 아직 등록하지 않았으면 | null 이면, empty 면 |
| 로그인 정보(토큰)를 확보해요 | 토큰을 fetch 해서 DTO 에 담아요 |
| 오류가 나면 / 서버가 응답하지 않으면 | exception 이 throw 되면, 5xx 면 |
| 이미 저장해 둔 값을 다시 써요 | 캐시에서 읽어요 |
| DB 에서 조회해요 | DAO 를 호출해요, repository 를 통해 |
| 상태가 "진행 중"이면 (`CONTINUE`) | status == CONTINUE 이면 |
| 매월 1일에 한 번 | cron 이 돌 때 |

Rules of thumb:

- Name the **actor and the situation**, then the **outcome**: "사용자가 약관에 동의하지 않았으면
  동기화를 시작하지 않아요." Not "CheckSyncEnabled 가 false 를 반환해요".
- Translate enum values and status codes into their product meaning; keep the original in
  backticks after it once, if a developer would need it: `종료(`ENDED`)`.
- Say **why** the rule exists when the code or its comments/ADRs tell you (privacy, billing,
  a policy decision, an app-version constraint). Planners care about the why more than the how.
- Method, class and variable names appear only (a) in a trailing parenthesis as a pointer, or
  (b) when the name *is* the concept being discussed. Never build a sentence around them.
- No implementation mechanics unless they change product behaviour: threads, DI, async, DTO
  mapping, logging, serialization, HTTP status codes are invisible to the reader.

### Titles

`<비즈니스 의미> (<MethodName>)` — the business phrase first, the code pointer in parentheses:

- Good: `약관 동의와 로그인 계정이 있어야 동기화를 시작해요 (CheckSyncEnabled)`
- Good: `비밀번호가 틀리면 다른 리전에도 확인해요 (LoginAsync 2단)`
- Bad: `CheckSyncEnabled — 약관·로그인ID 가드 후 토큰 확보`
- Bad: `GetPathfinderStatus — LTG 상태로 라우팅 결정`

The walk `title` is the feature name a planner would use ("앱 로그인", "본사 측정 데이터 동기화").

### Body (markdown, 4–8 sentences, in the user's language)

**Explain the flow, not the lines.** The highlighted code is already on their screen, so the body
must answer "what happens here and why", never "line N does X". No code blocks, no restating what
a statement literally does. Think of it as what a senior colleague would say to a planner while
pointing at the screen: what comes in, what is decided, what goes out, what rule it protects.

**Line references go at the end of the sentence, in parentheses, and become clickable links in
the tool window** (the editor jumps there when clicked):

- `(line 1417)` or `(lines 1441-1460)` — a spot in the step's own file
- `(Services/Foo.cs:120)` — a spot in another file, relative to the project root

Never put a line number inside the sentence ("1417줄에서 …"). Use a reference only where the
reader would otherwise have to hunt for the spot — a branch, an early return, a call handed off —
and at most one per sentence. Most sentences need none.

Shape:

1. **Why we are here** — one sentence linking to the previous step, in product terms ("사용자가
   비밀번호를 제출하면 여기서 먼저 이 리전 계정으로 로그인을 시도해요").
2. **What happens** — the flow in 2–4 sentences: what comes in → what is checked/decided → what the
   user or system gets. Branches as a short bullet list of *situations and their outcomes*
   (`- 잠겨 있으면: 비밀번호를 보지 않고 바로 거절`). Every branch is a product rule — state it as one.
3. **Why / what to notice** — the policy behind the rule, or the one thing a reader would get wrong
   (an ordering that matters, a case that deliberately does nothing, a rule another feature depends
   on). Skip if there is none.
4. **Where it goes next** — what happens after this, and what you skipped and why, in one line.

Good: "5번 연속으로 비밀번호를 틀리면 10분 동안 로그인이 막혀요. 막힌 동안에는 비밀번호가 맞아도 확인하지 않고 바로 거절해요 (line 89). 자동 대입 공격을 늦추기 위한 규칙이에요."
Bad: "85줄에서 lockoutKey 를 만들고 86줄에서 캐시를 읽은 뒤 89줄에서 IsLockedOut 을 호출해요."
Bad: "`_loginLockoutCache.Get` 이 null 이 아니고 `IsLockedOut` 이 true 면 `LOGIN_LOCKED` 코드로 early return 해요."

Plain language. Identifiers stay in backticks and stay rare.

## 3. Reply in chat

After writing the file, reply with:

1. One line: which entry point you picked (if it was a described flow).
2. The **map**: a nested bullet list, one bullet per step, `<비즈니스 의미> (`Class.Method`)`,
   indented by depth — the same phrasing as the step titles. No prose beyond that.
3. One line telling them the walk is in the Code Walk tool window (`View | Tool Windows | Code Walk`,
   or the tab at the bottom), and that `ctrl+alt+↓ / ↑` move between steps.

Then stop and wait for questions.

## 4. Answering questions while they walk

The `UserPromptSubmit` hook injects a line like
`[code-walk] The user is viewing walk 'login-with-region-check' step 4/13 "LoginAsync — …" at Services/LoginService.cs:220-248 in the IDE.`
when the IDE has a walk open. Use it:

- "여기", "이거", "this", "here", "왜 이렇게 했어?" → that step's code. Re-read the file range
  before answering; don't answer from the walk body alone.
- "다음은?", "그 다음에 뭐 해?" → the step after the current one — explain, and tell them it is
  step N+1 in the panel.
- "지도", "전체 흐름" → reprint the map with `← 지금 여기` after the current step.
- Questions that go deeper than the walk ("그 안의 `ApplyFailure` 는?") → answer from the code,
  and offer to append steps: if they say yes, add the steps at the right place in the walk file
  (keep ids stable for existing steps) and rewrite it; the IDE reloads within a second.

If no hook line is present, they are not in the IDE — answer normally, referring to steps by number.
