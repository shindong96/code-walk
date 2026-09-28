# Code Walk

온보딩용으로 직접 필요해서 만든 플러그인이에요. 새 코드베이스나 낯선 흐름을 익힐 때, 예전에
cmd+B 로 호출 체인을 따라가며 이해하던 방식을 Claude Code 와 IDE 가 대신 이끌어 주게 한 거예요.

Claude Code writes a step-by-step walkthrough of a code flow; a Rider/IntelliJ plugin shows it as
a tool window where each step moves the editor to the code and highlights it. You browse the
steps in the IDE and ask questions in Claude Code — it knows which step you are looking at.

```
 Claude Code                          ~/.code-walk/                        Rider
 ───────────                          ─────────────                        ─────
 /walk 로그인 과정  ──writes──▶  walks/login.walk.json  ──polled 1s──▶  Code Walk tool window
                                                                            │ click step / ctrl+alt+↓
 "여기 왜 이래?"   ◀──hook────  state.line / state.json  ◀──writes──      editor moves + highlight
```

Nothing is written inside the repository — walks and state live in `~/.code-walk/`.

## Parts

| Part | Where | What |
|---|---|---|
| IDE plugin (Kotlin) | `src/` | Tool window, step list, editor navigation + highlight, state file |
| Claude Code plugin | `claude-plugin/` | `/walk` command, `code-walk` skill, `UserPromptSubmit` hook |

## Install the IDE plugin (users)

Add this repo as a plugin repository once — Rider then offers updates like any Marketplace plugin:

**Settings | Plugins | ⚙ | Manage Plugin Repositories | +**
```
https://github.com/shindong96/code-walk/releases/latest/download/updatePlugins.xml
```
Then search "Code Walk" in the Marketplace tab and install. A new version is published whenever a
`vX.Y.Z` tag is pushed (`.github/workflows/release.yml`).

## Build the IDE plugin (developers)

Needs no system JDK — Gradle runs on the JBR that ships with Rider:

```bash
source gradlew.env && ./gradlew installToRider
```

`installToRider` builds the plugin and copies it straight into Rider's user plugins directory
(`~/Library/Application Support/JetBrains/Rider2026.2/plugins/code-walk`); restart Rider to load
it. `buildPlugin` alone leaves the zip in `build/distributions/` for **Install Plugin from Disk…**.
Builds against the Rider at `/Applications/Rider.app` (`riderPath` in `gradle.properties`); when
that path is missing (CI) it downloads Rider `platformVersion` instead.

To run a sandbox IDE with the plugin: `./gradlew runIde`.

Release: `git tag v0.2.0 && git push origin v0.2.0` — CI builds, creates the GitHub Release and
refreshes `updatePlugins.xml`.

## Install the Claude Code plugin

```bash
claude --plugin-dir /Users/shin/Desktop/code-walk/claude-plugin
```

## Walk file format

`~/.code-walk/walks/<id>.walk.json` — see `claude-plugin/skills/code-walk/SKILL.md` for the
schema. `~/.code-walk/state.json` (and the one-line `state.line`) hold the current step; the IDE
plugin writes them, the hook reads them.
