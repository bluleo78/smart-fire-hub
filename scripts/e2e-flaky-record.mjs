#!/usr/bin/env node
// pre-push E2E 결과에서 재시도로 통과한(flaky) 테스트를 누적 기록한다 (WD-257, smart-workplace WP-225 이식).
// 게이트는 retries 로 flaky 를 삼켜 통과시키므로, 기록이 없으면 같은 spec 이 반복해 흔들려도 드러나지 않는다.
// 누적 파일을 보고 자주 등장하는 spec 부터 구조적으로 고친다(고정 대기 → 도착 대기 등).
//
// 사용: node scripts/e2e-flaky-record.mjs <playwright-json-report> <누적-로그-파일>
// 로그 형식(TSV): 기록시각 \t 커밋 \t 파일:줄 \t 테스트 제목
import { execSync } from 'node:child_process';
import { appendFileSync, existsSync, readFileSync } from 'node:fs';

const [reportPath, logPath] = process.argv.slice(2);
if (!reportPath || !logPath) {
  console.error('사용: node scripts/e2e-flaky-record.mjs <report.json> <log>');
  process.exit(2);
}
// 실행이 중간에 깨져 리포트가 없으면 기록할 것도 없다 — 게이트 결과는 호출부가 판정한다.
if (!existsSync(reportPath)) process.exit(0);

const report = JSON.parse(readFileSync(reportPath, 'utf8'));

/** 중첩 suite 를 펼쳐 status 가 flaky 인 테스트만 모은다. */
function collectFlaky(suites, out = []) {
  for (const suite of suites ?? []) {
    for (const spec of suite.specs ?? []) {
      if (spec.tests?.some((t) => t.status === 'flaky')) {
        out.push({ where: `${spec.file}:${spec.line}`, title: spec.title });
      }
    }
    collectFlaky(suite.suites, out);
  }
  return out;
}

const flaky = collectFlaky(report.suites);
if (flaky.length === 0) process.exit(0);

let sha = '';
try {
  sha = execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim();
} catch {
  // 커밋 식별은 부가 정보 — 못 구해도 기록은 남긴다.
}
const now = new Date().toISOString();
appendFileSync(logPath, flaky.map((f) => `${now}\t${sha}\t${f.where}\t${f.title}\n`).join(''));

console.log(`[e2e-flaky] 재시도로 통과한 테스트 ${flaky.length}건 — ${logPath} 에 기록`);
for (const f of flaky) console.log(`  ${f.where}  ${f.title}`);
