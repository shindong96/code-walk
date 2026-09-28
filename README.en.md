# Code Walk

[한국어](README.md) · **English**

A plugin I built because I needed it for onboarding.
When learning a new codebase or an unfamiliar flow, I used to follow the call chain with cmd+B.
This lets Claude Code and the IDE lead that walk instead.

<br>

## How it works

Claude Code writes a code flow as a **step-by-step walk**; a Rider/IntelliJ plugin shows it in a tool window.
Click a step and the editor jumps to the code and highlights it.

You ask questions in Claude Code. It knows which step you are looking at,
so "why is it done this way here?" is answered against that code.

```
 Claude Code                         ~/.code-walk/                       Rider
 ───────────                         ─────────────                       ─────
 /walk login flow  ──── write ────▶  walks/login.walk.json  ── poll 1s ──▶  Code Walk tool window
                                                                            │ click step / ctrl+alt+↓
 "why this?"       ◀─── hook ────   state.json / state.line  ◀── write ──   editor moves + highlight
```

Nothing is written inside the repository. Walks and state live in `~/.code-walk/`.

<br>

## Parts

| Part | Where | Role |
|------|-------|------|
| IDE plugin (Kotlin) | `src/` | Tool window, step list, editor navigation + highlight, state file |
| Claude Code plugin | `claude-plugin/` | `/walk` command, `code-walk` skill, hook that injects the current step |

<br>

## Install

### 1. IDE plugin

Register this repo as a plugin repository once; Rider then offers updates like any Marketplace plugin.

**Settings → Plugins → ⚙ → Manage Plugin Repositories → +**

```
https://github.com/shindong96/code-walk/releases/latest/download/updatePlugins.xml
```

Then search **Code Walk** in the Marketplace tab and install it.

### 2. Claude Code plugin

```bash
claude plugin marketplace add shindong96/code-walk
```

```bash
claude plugin install code-walk@code-walk
```

<br>

## Use

In Claude Code:

```
/walk how does login work
```

The argument can be a symbol, a `file:line`, or a description of the flow.
A moment later the walk appears in the **Code Walk** tab at the bottom of Rider.

| Action | How |
|--------|-----|
| Move between steps | Click in the list, or `ctrl+alt+↓` / `ctrl+alt+↑` |
| Ask | Just ask in Claude Code — the step you are viewing is passed along automatically |
| Switch walks | Dropdown at the top of the tool window |

<br>

## Develop

### Build the IDE plugin

No system JDK needed — Gradle runs on the JBR that ships with Rider.

```bash
source gradlew.env && ./gradlew installToRider
```

`installToRider` builds and copies the plugin straight into Rider's plugin directory. Restart Rider to load it.
If you only need the zip: `./gradlew buildPlugin` → `build/distributions/`.

By default it builds against `/Applications/Rider.app` (`riderPath` in `gradle.properties`).
When that path is missing (CI), it downloads Rider `platformVersion` instead.

Run a sandbox IDE: `./gradlew runIde`

### Release

```bash
git tag v0.2.0 && git push origin v0.2.0
```

CI builds the plugin, creates a GitHub Release, and refreshes `updatePlugins.xml`.
Rider offers the update on its next check.

<br>

## File format

`~/.code-walk/walks/<id>.walk.json` — schema in [`claude-plugin/skills/code-walk/SKILL.md`](claude-plugin/skills/code-walk/SKILL.md).
`~/.code-walk/state.json` (and the one-line `state.line`) hold the current step. The IDE plugin writes them; the hook reads them.
