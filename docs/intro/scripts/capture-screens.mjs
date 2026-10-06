#!/usr/bin/env node
/**
 * 소개 자료 화면 촬영 스크립트.
 *
 *   node docs/intro/scripts/capture-screens.mjs
 *   INTRO_SHOTS_ONLY=dashboard,ai-chat node ...   # 일부만 다시 찍기
 *
 * 전제: seed-demo.mjs → seed-ai.mjs 까지 끝난 격리 스택(README 참조). 운영·공유 DB 를 찍지 않는다.
 *
 * 규격: 뷰포트 1440×810(16:9) · deviceScaleFactor 2 → 2880×1620.
 * 화면 요소는 가능한 한 data-testid·역할(role)·고정 UI 문구로 찾는다 — AI 답변 문구는 실행마다 달라진다.
 *
 * 벤더 노출 방지: 모든 촬영 직전에 화면 글자에서 AI 모델·벤더 이름을 찾고, 있으면 그 장면을 실패로 처리한다.
 * 소개 자료는 "AI 에이전트"라는 일반 용어로만 설명한다(README 작성 규칙).
 */
import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { PEOPLE, PASSWORD, call, login } from './seed-demo.mjs';
import { ROOT, WEB, loginUi, openDesktop, settle } from './lib-browser.mjs';

const SHOTS = path.join(ROOT, 'docs/intro/deck/shots');
const AI_WAIT = 5 * 60 * 1000;
const ONLY = process.env.INTRO_SHOTS_ONLY ? new Set(process.env.INTRO_SHOTS_ONLY.split(',')) : null;
const VENDOR = /claude|anthropic|sonnet|haiku|opus|sk-ant/i;

/** 화면 글자에 벤더·모델 이름이 있으면 던진다 — 입력창 값(value)과 iframe(리포트 뷰어) 안까지 본다. */
async function assertNoVendor(page, name) {
  for (const frame of page.frames()) {
    const hit = await vendorHit(frame);
    if (hit) throw new Error(`${name}: 화면에 벤더 이름이 보인다 — "${hit.replace(/\s+/g, ' ')}"`);
  }
}

async function vendorHit(frame) {
  return frame.evaluate((src) => {
    const re = new RegExp(src, 'i');
    const texts = [document.body?.innerText, ...[...document.querySelectorAll('input,textarea')].map((e) => e.value)];
    for (const t of texts) {
      const m = t && t.match(re);
      if (m) return t.slice(Math.max(0, m.index - 30), m.index + 40);
    }
    return null;
  }, VENDOR.source).catch(() => null); // 지도 타일 등 접근할 수 없는 프레임은 건너뛴다
}

async function shoot(page, name) {
  await assertNoVendor(page, name);
  await page.screenshot({ path: path.join(SHOTS, `${name}.png`) });
  console.log(`  ${name}.png`);
}

/**
 * 슬라이드 번호 표시용 좌표 기록 — 요소의 실제 위치를 원본 px(=CSS px × 배율)로 남긴다.
 * deck.js 가 이 좌표로 빨간 박스·지시선을 그리므로, 화면이 바뀌어도 재촬영만 하면 표시가 따라온다.
 */
const BOXES = {};
async function mark(page, shot, key, locator, dpr = 2) {
  const box = await locator.first().boundingBox();
  if (!box) throw new Error(`${shot}:${key} 표시 대상이 화면에 없다`);
  BOXES[shot] ??= {};
  BOXES[shot][key] = { x: Math.round(box.x * dpr), y: Math.round(box.y * dpr), w: Math.round(box.width * dpr), h: Math.round(box.height * dpr) };
}

/** 여러 요소를 함께 감싸는 영역을 기록한다. */
async function markUnion(page, shot, key, locators, dpr = 2) {
  const boxes = await Promise.all(locators.map((l) => l.first().boundingBox()));
  if (boxes.some((b) => !b)) throw new Error(`${shot}:${key} 표시 대상이 화면에 없다`);
  const x = Math.min(...boxes.map((b) => b.x));
  const y = Math.min(...boxes.map((b) => b.y));
  const r = Math.max(...boxes.map((b) => b.x + b.width));
  const btm = Math.max(...boxes.map((b) => b.y + b.height));
  BOXES[shot] ??= {};
  BOXES[shot][key] = { x: Math.round(x * dpr), y: Math.round(y * dpr), w: Math.round((r - x) * dpr), h: Math.round((btm - y) * dpr) };
}

/** 기록한 좌표를 shots/boxes.js 로 쓴다. 일부만 다시 찍을 때는 기존 좌표와 합친다. */
async function writeBoxes() {
  const file = path.join(SHOTS, 'boxes.js');
  let prev = {};
  try {
    const text = await readFile(file, 'utf8');
    prev = JSON.parse(text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1));
  } catch {
    /* 첫 촬영 */
  }
  await writeFile(file, `// capture-screens.mjs 가 생성 — 손으로 고치지 않는다.\nwindow.SHOT_BOXES = ${JSON.stringify({ ...prev, ...BOXES }, null, 1)};\n`);
}

/** 장면 하나를 실행한다 — 실패해도 나머지는 계속 찍고, 끝에 실패 목록을 보고한다. */
const failures = [];
async function scene(name, fn) {
  if (ONLY && !ONLY.has(name)) return;
  console.log(`▶ ${name}`);
  try {
    await fn();
  } catch (e) {
    failures.push(`${name}: ${e.message.split('\n')[0]}`);
    console.error(`  ✗ ${e.message.split('\n')[0]}`);
  }
}

/** 표시 기록 실패는 촬영을 막지 않는다 — 없는 표시는 슬라이드에서 숨겨지고(deck.js) 경고만 남긴다. */
const soft = (p) => p.catch((e) => console.warn(`  (표시 생략) ${e.message.split('\n')[0]}`));

/** 지도 타일이 다 그려질 때까지 — maplibre 는 idle 이벤트 뒤에도 페이드가 남아 여유를 둔다. */
async function waitMap(page) {
  await page.locator('.maplibregl-canvas').first().waitFor({ timeout: 20000 });
  await page.waitForTimeout(4000);
}

/** AI 어시스턴트에 질문하고 답이 끝날 때까지 기다린다(전송 버튼이 다시 보이면 끝). */
async function askAi(page, question) {
  // 상단 칩을 누르면 사이드 패널이 열린다(머물러 있으면 3초 뒤 드롭다운이 떠 클릭을 가로채므로 바로 누른다)
  await page.getByText('AI 어시스턴트', { exact: true }).first().click();
  const panel = page.getByTestId('ai-side-panel');
  await panel.waitFor({ timeout: 20000 });
  await page.mouse.move(700, 600);
  const input = panel.locator('textarea').first();
  await input.fill(question);
  await input.press('Enter');
  await page.getByRole('button', { name: '응답 중단' }).waitFor({ timeout: 30000 }).catch(() => {});
  await page.getByRole('button', { name: '응답 중단' }).waitFor({ state: 'detached', timeout: AI_WAIT });
  await settle(page, 2500);
  return panel;
}

async function main() {
  await mkdir(SHOTS, { recursive: true });
  // 전체 촬영이면 이전 원본을 비운다 — 한 장면이 실패했을 때 지난 실행의 다른 데이터 화면이 섞여 남지 않게.
  if (!ONLY) {
    for (const f of await readdir(SHOTS)) if (f.endsWith('.png') || f === 'boxes.js') await rm(path.join(SHOTS, f));
  }
  const token = await login(PEOPLE[0].username);
  const list = (r) => r?.content ?? r?.items ?? r ?? [];
  const datasets = list(await call(token, 'GET', '/datasets?size=50'));
  const dsId = (name) => datasets.find((d) => d.name === name)?.id;
  const queries = list(await call(token, 'GET', '/analytics/queries?size=50'));
  const queryId = (name) => queries.find((q) => q.name === name)?.id;
  const charts = list(await call(token, 'GET', '/analytics/charts?size=50'));
  const chartId = (name) => charts.find((c) => c.name === name)?.id;
  const dashboardId = list(await call(token, 'GET', '/analytics/dashboards'))[0].id;
  const pipelineId = list(await call(token, 'GET', '/pipelines'))[0].id;
  const job = list(await call(token, 'GET', '/proactive/jobs'))[0];
  const runs = list(await call(token, 'GET', `/proactive/jobs/${job.id}/executions`)).filter((e) => e.status === 'COMPLETED');
  // COMPLETED 라도 HTML 리포트가 없는 실행이 있다(seed-ai.mjs 참고) — 리포트가 있는 가장 최근 실행을 찍는다
  let jobRun = null;
  for (const r of runs) {
    const d = await call(token, 'GET', `/proactive/jobs/${job.id}/executions/${r.id}`);
    if (d.result?.htmlContent) { jobRun = r; break; }
  }

  const { browser, page } = await openDesktop();
  await loginUi(page, PEOPLE[0].username, PASSWORD);

  // 1장(표지)·8장 — 대시보드
  await scene('dashboard', async () => {
    await page.goto(`${WEB}/analytics/dashboards/${dashboardId}`);
    await settle(page, 3000);
    await soft(mark(page, 'dashboard', 'actions', page.getByRole('button', { name: /편집/ }).locator('xpath=..')));
    await soft(mark(page, 'dashboard', 'refresh', page.getByText('300초').first()));
    await soft(mark(page, 'dashboard', 'gauge', page.getByText('수금률(최근 90일 만기)').locator('xpath=../..')));
    await shoot(page, 'dashboard');
    // 아래쪽(지도·히트맵)까지 보이게 스크롤한 컷
    await page.mouse.move(900, 500);
    await page.mouse.wheel(0, 700);
    await waitMap(page).catch(() => {});
    await settle(page, 1500);
    await soft(mark(page, 'dashboard-lower', 'map', page.getByText('거래처 분포(최근 90일 매출)').locator('xpath=../..')));
    await soft(mark(page, 'dashboard-lower', 'heat', page.getByText('권역·제품군별 매출').locator('xpath=../..')));
    await shoot(page, 'dashboard-lower');
  });

  // 데이터셋 목록 + 새 데이터셋 유형 선택
  await scene('datasets', async () => {
    await page.goto(`${WEB}/data/datasets`);
    await settle(page, 2000);
    await soft(mark(page, 'datasets', 'filters', page.getByPlaceholder('데이터셋 검색...').locator('xpath=..')));
    await soft(mark(page, 'datasets', 'table', page.locator('table').first()));
    await soft(mark(page, 'datasets', 'add', page.getByRole('button', { name: /데이터셋 추가/ })));
    await shoot(page, 'datasets');
    await page.getByRole('button', { name: /데이터셋 추가/ }).first().click();
    await page.getByRole('dialog').waitFor({ timeout: 10000 });
    await settle(page, 800);
    await soft(mark(page, 'dataset-type', 'dialog', page.getByRole('dialog')));
    await shoot(page, 'dataset-type');
    await page.keyboard.press('Escape');
  });

  // 데이터셋 상세: 필드·데이터·지도
  await scene('dataset-detail', async () => {
    const id = dsId('거래처 정보');
    await page.goto(`${WEB}/data/datasets/${id}`);
    await settle(page);
    await page.getByRole('tab', { name: '필드' }).click();
    await settle(page, 1200);
    await soft(mark(page, 'dataset-fields', 'tabs', page.getByRole('tablist').first()));
    await soft(mark(page, 'dataset-fields', 'geom', page.getByText('GEOMETRY').first()));
    await soft(mark(page, 'dataset-fields', 'actions', page.getByRole('button', { name: /쿼리 작성/ }).locator('xpath=..')));
    await shoot(page, 'dataset-fields');
    await page.getByRole('tab', { name: '데이터' }).click();
    await settle(page, 1500);
    await shoot(page, 'dataset-data');
    await page.getByRole('tab', { name: '지도' }).click();
    await waitMap(page);
    await soft(mark(page, 'dataset-map', 'map', page.locator('.maplibregl-canvas').first()));
    await shoot(page, 'dataset-map');
  });

  // AI 어시스턴트: 말로 묻고, AI 가 데이터를 조회해 표·차트로 답한다
  await scene('ai-chat', async () => {
    await page.goto(`${WEB}/`);
    await settle(page);
    const question = '최근 3개월 영업권역별 매출과 매출총이익률을 분석해서 차트로 보여줘';
    const panel = await askAi(page, question);
    await page.getByTestId('inline-chart-title').first().waitFor({ timeout: 30000 });
    await settle(page, 2000);
    // 차트 위젯 컷 — 조회 단계(스키마 조회·행 수 조회·분석 쿼리)와 차트가 함께 보이게
    await page.getByTestId('inline-chart-title').first().scrollIntoViewIfNeeded();
    await settle(page, 800);
    await mark(page, 'ai-chat-chart', 'panel', panel);
    await soft(markUnion(page, 'ai-chat-chart', 'steps', [panel.getByText('스키마 조회').first(), panel.getByText('분석').first()]));
    await soft(mark(page, 'ai-chat-chart', 'chart', page.getByTestId('inline-chart-title').first().locator('xpath=../../..')));
    await shoot(page, 'ai-chat-chart');
    // 질문 컷 — 대화 맨 위(질문과 답변 첫 표)
    await panel.getByText(question).first().scrollIntoViewIfNeeded();
    await panel.getByText(question).first().evaluate((el) => el.scrollIntoView({ block: 'start' }));
    await settle(page, 800);
    await soft(mark(page, 'ai-chat-top', 'question', panel.getByText(question).first()));
    await mark(page, 'ai-chat-top', 'panel', panel);
    await shoot(page, 'ai-chat-top');
    // 답변 끝(핵심 발견·다음 분석 제안)
    await page.mouse.move(1300, 400);
    await page.mouse.wheel(0, 4000);
    await settle(page, 800);
    await shoot(page, 'ai-chat');
  });

  // SQL 편집기(스키마 탐색 + 결과)
  await scene('query', async () => {
    await page.goto(`${WEB}/analytics/queries/${queryId('권역별 매출·이익률')}`);
    await settle(page);
    await page.getByRole('button', { name: '실행' }).first().click();
    await settle(page, 2000);
    await soft(mark(page, 'query', 'schema', page.getByText('테이블 목록').locator('xpath=..')));
    await soft(mark(page, 'query', 'editor', page.locator('.cm-editor').first()));
    await soft(markUnion(page, 'query', 'result', [page.getByText('결과', { exact: true }).first(), page.locator('table').last()]));
    await soft(mark(page, 'query', 'tochart', page.getByRole('button', { name: /차트로 만들기/ })));
    await shoot(page, 'query');
  });

  // 차트 빌더(17종 차트)
  await scene('chart', async () => {
    await page.goto(`${WEB}/analytics/charts/${chartId('권역·제품군별 매출')}`);
    await settle(page);
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await settle(page, 2500);
    await soft(mark(page, 'chart', 'types', page.getByText('차트 타입').locator('xpath=..')));
    await soft(mark(page, 'chart', 'axis', page.getByText('축 설정').locator('xpath=..')));
    await soft(mark(page, 'chart', 'preview', page.getByText('미리보기').locator('xpath=../..')));
    await soft(mark(page, 'chart', 'add', page.getByRole('button', { name: /대시보드에 추가/ })));
    await shoot(page, 'chart');
  });

  // 파이프라인 DAG + 트리거
  await scene('pipeline', async () => {
    await page.goto(`${WEB}/pipelines/${pipelineId}`);
    await settle(page, 2000);
    await soft(mark(page, 'pipeline', 'sql', page.locator('.react-flow__node').nth(0)));
    await soft(mark(page, 'pipeline', 'ai', page.locator('.react-flow__node').nth(1)));
    await soft(mark(page, 'pipeline', 'run', page.getByRole('button', { name: '실행' }).first()));
    await shoot(page, 'pipeline');
    await page.getByRole('tab', { name: '트리거' }).click();
    await settle(page, 1500);
    await soft(markUnion(page, 'pipeline-triggers', 'list', [page.getByText('매일 새벽 3시').first(), page.getByText('문의 접수 시 즉시').first()]));
    await shoot(page, 'pipeline-triggers');
    await page.getByRole('tab', { name: '실행 이력' }).click();
    await settle(page, 1500);
    await shoot(page, 'pipeline-runs');
  });

  // AI 분류 결과(고객 문의 원문 → 긴급도·근거)
  await scene('ai-classify', async () => {
    await page.goto(`${WEB}/analytics/queries/${queryId('오늘 고객 문의 긴급도(AI 분류)')}`);
    await settle(page);
    await page.getByRole('button', { name: '실행' }).first().click();
    await settle(page, 2000);
    const head = page.locator('table thead th');
    await soft(markUnion(page, 'ai-classify', 'source', [head.filter({ hasText: '문의내용' }), page.locator('table tbody tr').nth(7).locator('td').nth(2)]));
    await soft(markUnion(page, 'ai-classify', 'ai', [head.filter({ hasText: '긴급도' }), head.filter({ hasText: '판단근거' }), page.locator('table tbody tr').nth(7).locator('td').last()]));
    await shoot(page, 'ai-classify');
  });

  // 스마트 작업 + AI 가 쓴 리포트
  await scene('smart-job', async () => {
    await page.goto(`${WEB}/ai-insights/jobs/${job.id}`);
    await settle(page, 1500);
    await soft(markUnion(page, 'smart-job', 'info', [page.getByText('기본 정보').first(), page.getByText('다음 실행').first()]));
    await soft(mark(page, 'smart-job', 'run', page.getByRole('button', { name: '지금 실행' })));
    await shoot(page, 'smart-job');
    await page.goto(`${WEB}/ai-insights/jobs/${job.id}/executions/${jobRun.id}/report`);
    await settle(page, 3000);
    await soft(mark(page, 'report', 'pdf', page.getByRole('button', { name: /PDF/ })));
    await soft(mark(page, 'report', 'body', page.locator('iframe').first()));
    await shoot(page, 'report');
  });

  // 권한·감사
  await scene('admin', async () => {
    const roles = list(await call(token, 'GET', '/roles'));
    const userRole = roles.find((r) => r.name === 'USER') ?? roles[0];
    await page.goto(`${WEB}/admin/roles/${userRole.id}`);
    await settle(page, 1500);
    await shoot(page, 'roles');
    await page.goto(`${WEB}/admin/audit-logs`);
    await settle(page, 2000);
    await soft(mark(page, 'audit', 'filters', page.getByPlaceholder('설명으로 검색...').locator('xpath=..')));
    await soft(mark(page, 'audit', 'table', page.locator('table').first()));
    await shoot(page, 'audit');
    await page.goto(`${WEB}/admin/users`);
    await settle(page, 1500);
    await shoot(page, 'users');
  });

  await writeBoxes();
  await browser.close();
  if (failures.length) {
    console.error(`\n실패 ${failures.length}건:\n- ${failures.join('\n- ')}`);
    process.exitCode = 1;
  }
}

await main();
