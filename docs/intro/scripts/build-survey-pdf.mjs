#!/usr/bin/env node
/**
 * 고객 사전 질문지(docs/intro/customer-data-survey.md)를 A4 PDF 로 만든다.
 *
 *   node docs/intro/scripts/build-survey-pdf.mjs   # docs/intro/genia-data-survey.pdf
 *
 * 원본은 Markdown 하나로 유지하고 PDF 는 매번 다시 뽑는다 — 문구를 고칠 때 두 곳을 맞출 필요가 없게.
 * 질문지는 제목·문단·목록·표·굵게·구분선만 쓰므로 그만큼만 변환하는 작은 변환기를 둔다(의존성을 늘리지 않기 위해).
 * 인쇄는 build-pdf.mjs 와 같이 firehub-web 의 Playwright + 시스템 크롬을 쓴다.
 */
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const SRC = path.join(ROOT, 'docs/intro/customer-data-survey.md');
const OUT_HTML = path.join(ROOT, 'dist/intro/genia-data-survey.html');
const OUT_PDF = path.join(ROOT, 'docs/intro/genia-data-survey.pdf');

const require = createRequire(path.join(ROOT, 'apps/firehub-web/package.json'));
const { chromium } = require('@playwright/test');

const esc = (s) => s.replace(/&(?!nbsp;)/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
/** 인라인 서식 — 굵게만 쓴다. */
const inline = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');

/** 질문지에 쓰는 Markdown 부분집합 → HTML. 표는 "(예)" 행을 예시 행으로, 빈 행을 작성 칸으로 그린다. */
function toHtml(md) {
  const lines = md.split('\n');
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (/^#\s/.test(line)) out.push(`<h1>${inline(line.slice(2))}</h1>`);
    else if (/^##\s/.test(line)) out.push(`<h2>${inline(line.slice(3))}</h2>`);
    else if (/^---\s*$/.test(line)) out.push('<hr>');
    else if (/^- /.test(line)) {
      const items = [];
      while (i < lines.length && /^- /.test(lines[i])) items.push(`<li>${inline(lines[i++].slice(2))}</li>`);
      out.push(`<ul>${items.join('')}</ul>`);
      continue;
    } else if (/^\|/.test(line)) {
      const rows = [];
      while (i < lines.length && /^\|/.test(lines[i])) rows.push(lines[i++]);
      const cells = (r) => r.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
      const head = cells(rows[0]);
      const body = rows.slice(2).map(cells);
      // 질문·답변 2열 표는 질문 칸을 좁히고 답변 칸을 넓힌다
      const qa = head.length === 2 && head[1] === '답변';
      const tr = body
        .map((r) => {
          const empty = r.every((c) => c === '');
          const example = /^\(예\)/.test(r[0]);
          return `<tr class="${empty ? 'blank' : ''} ${example ? 'example' : ''}">${r.map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`;
        })
        .join('');
      out.push(`<table class="${qa ? 'qa' : 'grid'}"><thead><tr>${head.map((h) => `<th>${inline(h)}</th>`).join('')}</tr></thead><tbody>${tr}</tbody></table>`);
      continue;
    } else if (line.trim() === '&nbsp;') out.push('<div class="free"></div>');
    else if (line.trim()) out.push(`<p>${inline(line)}</p>`);
    i++;
  }
  return out.join('\n');
}

const md = await readFile(SRC, 'utf8');
const html = `<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><title>Gen:iA Data 데이터 현황 사전 질문지</title>
<style>
  @page { size: A4; margin: 16mm 15mm 16mm; }
  :root { --brand: #1d4ed8; --ink: #0f172a; --muted: #475569; --line: #cbd5e1; --soft: #f1f5f9; }
  * { box-sizing: border-box; }
  body { font-family: Pretendard, 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif; color: var(--ink); font-size: 10pt; line-height: 1.55; margin: 0; }
  .brand { display: flex; align-items: center; gap: 8px; color: var(--brand); font-weight: 700; font-size: 10.5pt; letter-spacing: .01em; margin-bottom: 6mm; }
  .brand i { width: 18px; height: 18px; border-radius: 5px; background: var(--brand); display: inline-block; }
  h1 { font-size: 19pt; margin: 0 0 4mm; letter-spacing: -.01em; }
  h2 { font-size: 12.5pt; margin: 8mm 0 2.5mm; padding-left: 9px; border-left: 4px solid var(--brand); break-after: avoid; }
  p { margin: 0 0 2.5mm; }
  ul { margin: 0 0 3mm; padding-left: 5mm; color: var(--muted); }
  li { margin: 0.6mm 0; }
  hr { border: 0; border-top: 1px solid var(--line); margin: 6mm 0; }
  table { width: 100%; border-collapse: collapse; margin: 2mm 0 3mm; break-inside: auto; }
  tr { break-inside: avoid; }
  th { background: var(--soft); font-weight: 600; text-align: left; font-size: 9pt; color: var(--muted); }
  th, td { border: 1px solid var(--line); padding: 2.2mm 2.5mm; vertical-align: top; }
  tr.example td { color: #94a3b8; font-size: 9pt; }
  tr.blank td { height: 9mm; }
  table.qa td:first-child { width: 52%; }
  table.qa td:last-child { height: 13mm; }
  .free { border: 1px solid var(--line); height: 35mm; margin-top: 2mm; }
</style></head>
<body><div class="brand"><i></i>Gen:iA Data</div>
${toHtml(md)}
</body></html>`;

await writeFile(OUT_HTML, html);
const browser = await chromium.launch({ channel: 'chrome' });
try {
  const page = await browser.newPage();
  await page.goto(pathToFileURL(OUT_HTML).href, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  await page.pdf({ path: OUT_PDF, format: 'A4', printBackground: true, preferCSSPageSize: true });
  console.log(`PDF → ${OUT_PDF}`);
} finally {
  await browser.close();
}
