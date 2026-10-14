---
name: walk-export
description: Export a code walk as a single HTML page (diagram, step list, explanations) for people who have neither Rider nor Claude Code, e.g. planners. Add --with-code for a developer version with code excerpts.
argument-hint: [walk-id | --latest | --current] [--with-code] [--out <file>]
---

Export a walk to HTML. Arguments: $ARGUMENTS

Run (no walk argument = the walk open in the IDE, else the newest):

```bash
node "${CLAUDE_PLUGIN_ROOT}/scripts/export-html.mjs" $ARGUMENTS
```

Default is the **planner view**: overview diagram, step list (click scrolls to the step), and the
explanations — no code, no line references. `--with-code` adds each step's code excerpt with
syntax colouring and clickable `(line N)` references, for developers.

The script prints the output path (default `~/.code-walk/export/<id>.html`). Hand that file to the
user with `SendUserFile` (display `attach`) and say in one line what it contains and that the
diagram loads from a CDN when the page is opened (needs network; the text is self-contained). Do
not paste the HTML into chat.
