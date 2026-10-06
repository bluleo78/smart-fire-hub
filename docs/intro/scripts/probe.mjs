#!/usr/bin/env node
/**
 * 장면 구성 전 점검용 — 주요 화면을 한 장씩 찍어 PROBE_OUT 에 남긴다(소개 자료 원본이 아니다).
 *
 *   PROBE_OUT=/tmp/probe node docs/intro/scripts/probe.mjs /data/datasets /analytics/dashboards/1
 */
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { PEOPLE, PASSWORD } from './seed-demo.mjs';
import { WEB, loginUi, openDesktop, settle } from './lib-browser.mjs';

const OUT = process.env.PROBE_OUT ?? '/tmp/intro-probe';
await mkdir(OUT, { recursive: true });
const { browser, page } = await openDesktop();
try {
  await loginUi(page, PEOPLE[0].username, PASSWORD);
  for (const url of process.argv.slice(2)) {
    await page.goto(WEB + url);
    await settle(page, Number(process.env.PROBE_WAIT ?? 2500));
    const name = url.replace(/[^a-z0-9]+/gi, '_').replace(/^_|_$/g, '') || 'home';
    await page.screenshot({ path: path.join(OUT, `${name}.png`) });
    console.log(`${name}.png`);
  }
} finally {
  await browser.close();
}
