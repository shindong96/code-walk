#!/usr/bin/env node
// Exports one walk as a single self-contained HTML page: step list (depth-indented, overview = 0),
// per-step explanation, mermaid diagrams, and the code excerpt each step points at. Opens in any
// browser — the way to hand a walk to someone without Rider or Claude Code (a planner, a reviewer).
//
//   node export-html.mjs [<walk-id> | --latest | --current] [--with-code] [--out <file>]
//
//   <walk-id>    ~/.code-walk/walks/<id>.walk.json
//   --latest     newest walk file (default when nothing is given and no state.json)
//   --current    the walk open in the IDE (~/.code-walk/state.json)
//   --with-code  also include each step's code excerpt and (line N) links — for developers.
//                Default is the planner view: diagram, step list, explanations only.
//   --out        output path (default ~/.code-walk/export/<id>.html)
//
// mermaid (and highlight.js with --with-code) load from a CDN when the page is opened, so the file stays small.

import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, resolve, extname, basename } from "node:path";
import { homedir } from "node:os";

const ROOT = join(homedir(), ".code-walk");
const WALKS = join(ROOT, "walks");

// ---------- args ----------
const args = process.argv.slice(2);
const outIdx = args.indexOf("--out");
const outArg = outIdx >= 0 ? args[outIdx + 1] : null;
const withCode = args.includes("--with-code");
const sel = args.filter((a, i) => !(a === "--out" || i === outIdx + 1 || a === "--with-code"));
let walkPath;
if (sel.includes("--current") || (sel.length === 0 && existsSync(join(ROOT, "state.json")))) {
  const st = JSON.parse(readFileSync(join(ROOT, "state.json"), "utf8"));
  walkPath = join(WALKS, `${st.walk}.walk.json`);
} else if (sel.includes("--latest") || sel.length === 0) {
  const files = readdirSync(WALKS).filter((f) => f.endsWith(".walk.json"))
    .map((f) => ({ f, t: statSync(join(WALKS, f)).mtimeMs })).sort((a, b) => b.t - a.t);
  if (!files.length) { console.error("no walks in " + WALKS); process.exit(1); }
  walkPath = join(WALKS, files[0].f);
} else {
  const id = sel[0].replace(/\.walk\.json$/, "");
  walkPath = existsSync(id) ? resolve(id) : join(WALKS, `${id}.walk.json`);
}
if (!existsSync(walkPath)) { console.error("walk not found: " + walkPath); process.exit(1); }

const walk = JSON.parse(readFileSync(walkPath, "utf8"));
const hasOverview = walk.steps.length > 0 && !walk.steps[0].file;
const num = (i) => (hasOverview ? i : i + 1);

// ---------- helpers ----------
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** Mirrors the IDE plugin's Markdown.kt: headings, paragraphs, bullets, bold/italic/code, fences, (line N) links. */
function md(src, stepIdx) {
  const lineRef = /\((?:line|lines|L)\s*(\d+)(?:\s*[-–~]\s*(\d+))?\)/g;
  const fileRef = /\(([\w./\\-]+\.\w+):(\d+)(?:-(\d+))?\)/g;
  const inline = (s) => {
    let t = esc(s);
    t = t.replace(/`([^`]+)`/g, "<code>$1</code>");
    t = t.replace(/\*\*(.+?)\*\*/g, "<b>$1</b>");
    t = t.replace(/(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])/g, "<i>$1</i>");
    if (withCode) {
      t = t.replace(lineRef, (m, a, b) => `(<a class="ref" href="#s${stepIdx}-L${a}">line ${a}${b ? "–" + b : ""}</a>)`);
      t = t.replace(fileRef, (m, f, a, b) => `(<span class="ref">${f}:${a}${b ? "–" + b : ""}</span>)`);
    } else {
      // Planner view: line references point at code the page does not show — drop them.
      t = t.replace(lineRef, "").replace(fileRef, "").replace(/\s+([.,!?。])/g, "$1").replace(/[ \t]{2,}/g, " ");
    }
    return t;
  };
  const out = [];
  const lines = src.split("\n");
  let i = 0, para = [], inList = false;
  const flush = () => { if (para.length) { out.push(`<p>${para.join(" ")}</p>`); para = []; } };
  const closeList = () => { if (inList) { out.push("</ul>"); inList = false; } };
  while (i < lines.length) {
    const line = lines[i];
    if (line.startsWith("```")) {
      flush(); closeList();
      const lang = line.slice(3).trim().toLowerCase();
      const code = [];
      i++;
      while (i < lines.length && !lines[i].startsWith("```")) code.push(lines[i++]);
      if (lang === "mermaid") out.push(`<pre class="mermaid">${esc(code.join("\n"))}</pre>`);
      else out.push(`<pre><code>${esc(code.join("\n"))}</code></pre>`);
    } else if (line.startsWith("#")) {
      flush(); closeList();
      const level = Math.min(4, line.match(/^#+/)[0].length);
      out.push(`<h${level + 1}>${inline(line.slice(level).trim())}</h${level + 1}>`);
    } else if (/^\s*[-*] /.test(line)) {
      flush();
      if (!inList) { out.push("<ul>"); inList = true; }
      out.push(`<li>${inline(line.trim().slice(2))}</li>`);
    } else if (line.trim() === "") {
      flush(); closeList();
    } else {
      closeList();
      para.push(inline(line));
    }
    i++;
  }
  flush(); closeList();
  return out.join("\n");
}

const LANG = { cs: "csharp", kt: "kotlin", kts: "kotlin", ts: "typescript", tsx: "typescript", js: "javascript", mjs: "javascript", jsx: "javascript", py: "python", java: "java", go: "go", rb: "ruby", rs: "rust", sql: "sql", json: "json", xml: "xml", html: "xml", yml: "yaml", yaml: "yaml", sh: "bash", md: "markdown" };

function excerpt(step, idx) {
  if (!withCode || !step.file) return "";
  const abs = step.file.startsWith("/") ? step.file : join(walk.project, step.file);
  let lines;
  try { lines = readFileSync(abs, "utf8").split("\n"); }
  catch { return `<div class="code-missing">파일을 읽지 못했어요: ${esc(step.file)}</div>`; }
  const start = Math.max(1, step.line || 1);
  const end = Math.min(lines.length, step.endLine || start, start + 79);
  const lang = LANG[extname(step.file).slice(1).toLowerCase()] || "";
  const rows = [];
  for (let n = start; n <= end; n++) {
    rows.push(`<tr id="s${idx}-L${n}"><td class="ln">${n}</td><td class="src"><code class="language-${lang}">${esc(lines[n - 1] ?? "")}</code></td></tr>`);
  }
  const clipped = (step.endLine || start) > end ? `<div class="code-note">… ${(step.endLine - end)} lines more in the file</div>` : "";
  return `<div class="code"><div class="code-head"><span class="path">${esc(step.file)}</span><span class="range">${start}${end > start ? "–" + end : ""}</span></div><table>${rows.join("")}</table>${clipped}</div>`;
}

// ---------- page ----------
const created = walk.createdAt ? new Date(walk.createdAt) : new Date(statSync(walkPath).mtimeMs);
const createdStr = isNaN(created) ? "" : created.toLocaleString("ko-KR", { dateStyle: "medium", timeStyle: "short" });
const projectName = basename(walk.project || "");

const nav = walk.steps.map((s, i) =>
  `<a class="nav-item d${Math.min(4, s.depth || 0)}${!s.file ? " overview" : ""}" href="#step-${i}"><span class="n">${num(i)}</span><span class="t">${esc(s.title)}</span></a>`).join("\n");

const sections = walk.steps.map((s, i) => `
<section class="step" id="step-${i}">
  <header>
    <span class="num">${num(i)}</span>
    <h2>${esc(s.title)}</h2>
    ${withCode && s.file ? `<span class="where">${esc(s.file)}:${s.line}${s.endLine && s.endLine !== s.line ? "–" + s.endLine : ""}</span>` : ""}
  </header>
  <div class="body">${md(s.body || "", i)}</div>
  ${excerpt(s, i)}
</section>`).join("\n");

const html = `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(walk.title || walk.id)} — Code Walk</title>
${withCode ? `<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css" media="(prefers-color-scheme: light)">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css" media="(prefers-color-scheme: dark)">` : ""}
<style>
:root{--bg:#fff;--fg:#1f1f1f;--muted:#6b6b6b;--line:#e4e4e4;--panel:#f6f6f6;--accent:#2470b3;--code:#f3f3f3;--mark:#fff4c2}
@media(prefers-color-scheme:dark){:root{--bg:#1e1f22;--fg:#dfe1e5;--muted:#9a9ca3;--line:#36383d;--panel:#26272b;--accent:#7cb0ea;--code:#2b2d30;--mark:#4a4320}}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.65 -apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Pretendard","Segoe UI",Roboto,sans-serif}
a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
code,pre{font-family:"JetBrains Mono",ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:13px}
.layout{display:grid;grid-template-columns:300px minmax(0,1fr);min-height:100vh}
nav{position:sticky;top:0;height:100vh;overflow:auto;border-right:1px solid var(--line);background:var(--panel);padding:20px 12px}
nav h1{font-size:17px;margin:0 0 4px;line-height:1.3}
nav .meta{color:var(--muted);font-size:12px;margin:0 0 16px}
.nav-item{display:flex;gap:8px;padding:5px 8px;border-radius:6px;color:var(--fg);font-size:13px;line-height:1.35}
.nav-item:hover{background:var(--bg);text-decoration:none}
.nav-item.active{background:var(--bg);box-shadow:inset 3px 0 0 var(--accent)}
.nav-item .n{color:var(--muted);min-width:18px;text-align:right}
.nav-item.overview .t{font-weight:600}
.nav-item.d1{padding-left:22px}.nav-item.d2{padding-left:36px}.nav-item.d3{padding-left:50px}.nav-item.d4{padding-left:64px}
main{padding:28px 40px 80px;max-width:980px}
.step{padding:28px 0;border-top:1px solid var(--line);scroll-margin-top:16px}
.step:first-child{border-top:0;padding-top:8px}
.step header{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;margin-bottom:10px}
.step .num{display:inline-block;min-width:28px;height:28px;line-height:28px;text-align:center;border-radius:14px;background:var(--panel);color:var(--muted);font-size:13px;font-weight:600}
.step h2{font-size:19px;margin:0;line-height:1.35}
.step .where{color:var(--muted);font-size:12px;font-family:ui-monospace,Menlo,monospace}
.body p{margin:0 0 10px}.body ul{margin:0 0 10px 20px;padding:0}.body li{margin:3px 0}
.body code{background:var(--code);padding:1px 5px;border-radius:4px}
.body pre{background:var(--code);padding:10px 12px;border-radius:6px;overflow:auto}
.body pre.mermaid{background:transparent;padding:6px 0;text-align:center}
.body pre.mermaid svg{max-width:100%;height:auto}
.body b{font-weight:600}
.ref{font-size:12.5px;color:var(--accent)}
.code{margin-top:14px;border:1px solid var(--line);border-radius:8px;overflow-x:auto}
.code-head{display:flex;justify-content:space-between;padding:6px 12px;background:var(--panel);font-size:12px;color:var(--muted);font-family:ui-monospace,Menlo,monospace}
.code table{border-collapse:collapse;width:100%}
.code td{padding:0 12px;white-space:pre;vertical-align:top}
.code td.ln{width:1%;text-align:right;color:var(--muted);user-select:none;border-right:1px solid var(--line);background:var(--panel)}
.code tr:target td{background:var(--mark)}
.code td.src code{background:transparent;padding:0;display:block}
.code-note,.code-missing{padding:6px 12px;font-size:12px;color:var(--muted);background:var(--panel)}
.hljs{background:transparent!important;padding:0!important}
@media(max-width:860px){.layout{grid-template-columns:1fr}nav{position:static;height:auto;border-right:0;border-bottom:1px solid var(--line)}main{padding:20px 16px}}
@media print{nav{display:none}.layout{grid-template-columns:1fr}main{max-width:none}.step{break-inside:avoid}}
</style>
</head>
<body>
<div class="layout">
<nav>
  <h1>${esc(walk.title || walk.id)}</h1>
  <p class="meta">${esc(projectName)}${createdStr ? " · " + esc(createdStr) : ""} · ${walk.steps.length - (hasOverview ? 1 : 0)} steps</p>
  ${nav}
</nav>
<main>
${sections}
</main>
</div>
${withCode ? `<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>` : ""}
<script type="module">
import mermaid from "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs";
const dark = matchMedia("(prefers-color-scheme: dark)").matches;
mermaid.initialize({ startOnLoad: false, theme: dark ? "dark" : "neutral", securityLevel: "strict", flowchart: { useMaxWidth: true, htmlLabels: true } });
try { await mermaid.run({ querySelector: "pre.mermaid" }); } catch (e) { console.error(e); }
if (window.hljs) document.querySelectorAll(".code td.src code").forEach((el) => hljs.highlightElement(el));
const items = [...document.querySelectorAll(".nav-item")];
const steps = [...document.querySelectorAll(".step")];
// Step list click: smooth-scroll to the step and mark it (the URL hash still updates for sharing a link to a step).
items.forEach((a, i) => a.addEventListener("click", (ev) => {
  ev.preventDefault();
  steps[i].scrollIntoView({ behavior: "smooth", block: "start" });
  history.replaceState(null, "", "#step-" + i);
  items.forEach((x, j) => x.classList.toggle("active", i === j));
}));
// Highlight the nav item of the step in view while scrolling.
const io = new IntersectionObserver((entries) => {
  for (const en of entries) if (en.isIntersecting) {
    const i = steps.indexOf(en.target);
    items.forEach((a, j) => a.classList.toggle("active", i === j));
  }
}, { rootMargin: "-10% 0px -70% 0px" });
steps.forEach((s) => io.observe(s));
</script>
</body>
</html>
`;

const out = outArg ? resolve(outArg) : join(ROOT, "export", `${walk.id}.html`);
mkdirSync(join(out, ".."), { recursive: true });
writeFileSync(out, html);
console.log(out);
