#!/usr/bin/env node
/**
 * 소개 자료 슬라이드(docs/intro/deck/index.html)를 16:9 PDF 한 권과 장별 PNG 로 만든다.
 *
 *   node docs/intro/scripts/build-pdf.mjs            # dist/intro/genia-data-intro.pdf + png/
 *   node docs/intro/scripts/build-pdf.mjs --png-only # 검토용 PNG 만
 *
 * HTML → Chromium 인쇄 경로를 쓴다(smart-dcim 매뉴얼 PDF 와 같은 방식). 슬라이드 한 장이 1920×1080 이고
 * deck.css 의 @page 가 같은 크기라 페이지 경계가 슬라이드 경계와 정확히 맞는다.
 * Playwright 는 firehub-web 의 devDependency 를 그대로 쓴다 — 툴체인을 늘리지 않기 위해서다.
 */
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const DECK = path.join(ROOT, 'docs/intro/deck/index.html');
const OUT_DIR = path.join(ROOT, 'dist/intro');
const OUT_PDF = path.join(OUT_DIR, 'genia-data-intro.pdf');

// 루트에는 playwright 가 없으므로 firehub-web 기준으로 해석한다.
const require = createRequire(path.join(ROOT, 'apps/firehub-web/package.json'));
const { chromium } = require('@playwright/test');

const pngOnly = process.argv.includes('--png-only');

// 시스템 크롬 — 이 머신에서 `playwright install` 은 압축 해제 단계에서 멈춘다.
const browser = await chromium.launch({ channel: 'chrome' });
try {
  // 장별 PNG 는 2배율(3840×2160) — 1배율이면 2880px 원본 캡처가 크게 줄어 글자가 뭉개진다.
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 2 });
  await page.goto(pathToFileURL(DECK).href, { waitUntil: 'load' });
  // 웹폰트·이미지 디코딩이 끝나야 인쇄 결과가 화면과 같다.
  await page.waitForFunction(() => document.body.dataset.ready === 'true'); // deck.js 가 잘라 보기·표시를 끝낼 때까지
  await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all([...document.images].map((img) => img.decode().catch(() => {})));
  });
  const missing = await page.evaluate(() =>
    [...document.images].filter((img) => !img.naturalWidth).map((img) => img.getAttribute('src')),
  );
  if (missing.length) throw new Error(`이미지가 없다(촬영을 먼저 돌린다): ${[...new Set(missing)].join(', ')}`);

  const pngDir = path.join(OUT_DIR, 'png');
  await mkdir(pngDir, { recursive: true });
  const slides = await page.locator('section.slide').all();
  for (let i = 0; i < slides.length; i++) {
    await slides[i].screenshot({ path: path.join(pngDir, `slide-${String(i + 1).padStart(2, '0')}.png`) });
  }
  console.log(`PNG ${slides.length}장 → ${pngDir}`);

  if (!pngOnly) {
    await page.emulateMedia({ media: 'print' });
    await page.pdf({ path: OUT_PDF, width: '1920px', height: '1080px', printBackground: true, preferCSSPageSize: true });
    console.log(`PDF → ${OUT_PDF}`);
  }
} finally {
  await browser.close();
}
