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
      "title": "LoginWithRegionCheck — 요청 진입",
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

### Body (markdown, 4–8 sentences, in the user's language)

**Explain the flow, not the lines.** The highlighted code is already on their screen, so the body
must answer "what happens here and why", never "line N does X". No code blocks, no restating what
a statement literally does. Think of it as what a senior colleague would say while pointing at the
screen: what comes in, what is decided, what goes out, what it protects.

**Line references go at the end of the sentence, in parentheses, and become clickable links in
the tool window** (the editor jumps there when clicked):

- `(line 1417)` or `(lines 1441-1460)` — a spot in the step's own file
- `(Services/Foo.cs:120)` — a spot in another file, relative to the project root

Never put a line number inside the sentence ("1417줄에서 …"). Use a reference only where the
reader would otherwise have to hunt for the spot — a branch, an early return, a call handed off —
and at most one per sentence. Most sentences need none.

Shape:

1. **Why we are here** — one sentence linking to the previous step ("LoginAsync 가 기존 로그인을
   먼저 시도하는 자리예요").
2. **What happens** — the flow in 2–4 sentences: inputs → decisions → outcome. Branches as a short
   bullet list of *situations and their outcomes* (`- 잠겨 있으면: 비밀번호를 보지 않고 바로 거절`).
   Name identifiers only when they are the thing being explained (`RegionHint`, `Enabled`).
3. **What to notice** — the one thing a smart reader would get wrong, or the invariant this code
   protects (ordering, fail-open, a contract another caller depends on). Skip if there is none.
4. **Where it goes next** — which call the next step follows, and what you skipped and why, in one line.

Good: "잠금 중이면 비밀번호를 대조하지 않고 바로 거절해요 (line 89). 잠금 상태는 Redis 에 있고, 규칙은 10분 안에 5번 실패면 10분 잠금이에요."
Bad: "85줄에서 lockoutKey 를 만들고 86줄에서 캐시를 읽은 뒤 89줄에서 IsLockedOut 을 호출해요."

Plain language. Identifiers stay in backticks.

## 3. Reply in chat

After writing the file, reply with:

1. One line: which entry point you picked (if it was a described flow).
2. The **map**: a nested bullet list, one bullet per step, `` `File.cs:line` — `Class.Method` 가 <what it does> ``,
   indented by depth. No prose beyond that.
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
