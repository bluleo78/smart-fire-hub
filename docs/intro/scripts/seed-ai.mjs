#!/usr/bin/env node
/**
 * AI 에게 실제로 일을 시켜 소개 자료에 실을 AI 결과물을 만든다.
 *
 *   node docs/intro/scripts/seed-ai.mjs
 *
 * 전제: seed-demo.mjs 실행 완료(AI 자격증명 등록 포함), ai-agent 기동.
 *
 * 원칙: AI 의 분류 결과·리포트는 지어낸 문구를 API 로 꽂지 않는다. 화면의 "실행"·"지금 실행" 버튼과 같은
 * API 로 요청만 하고, 결과는 AI 가 만들 때까지 기다린다. 그래서 실행마다 문구가 조금씩 다르다 —
 * 촬영 스크립트는 문구가 아니라 구조(역할·테스트 ID)로 화면을 찾는다.
 * 채팅 장면(AI 어시스턴트 답변)은 화면에서 직접 물어야 위젯이 그려지므로 capture-screens.mjs 가 맡는다.
 */
import { PEOPLE, call, login } from './seed-demo.mjs';

const WAIT_MS = Number(process.env.INTRO_AI_WAIT_MS ?? 10 * 60 * 1000);

/** 조건이 참이 될 때까지 폴링한다. AI 작업은 수십 초~수 분 걸린다. */
async function waitFor(label, check, intervalMs = 5000) {
  const deadline = Date.now() + WAIT_MS;
  while (Date.now() < deadline) {
    const v = await check();
    if (v) {
      console.log(`✓ ${label}`);
      return v;
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`시간 초과: ${label}`);
}

const token = await login(PEOPLE[0].username);
const list = (r) => r?.content ?? r?.items ?? r ?? [];

// 1) 파이프라인 — 일 집계(SQL) + 고객 문의 AI 분류. INTRO_ONLY_JOB=1 이면 건너뛴다(리포트만 다시 만들 때).
let execId = null;
if (!process.env.INTRO_ONLY_JOB) {
  const pipeline = list(await call(token, 'GET', '/pipelines')).find((p) => p.name === '매출 데이터 일일 마감');
  const exec = await call(token, 'POST', `/pipelines/${pipeline.id}/execute`);
  execId = exec.id ?? exec.executionId;
  const done = await waitFor('파이프라인 실행 종료', async () => {
    const e = await call(token, 'GET', `/pipelines/${pipeline.id}/executions/${execId}`);
    return ['COMPLETED', 'FAILED', 'CANCELLED', 'SUCCESS'].includes(e.status) ? e : null;
  });
  if (done.status === 'FAILED') throw new Error(`파이프라인 실패: ${JSON.stringify(done).slice(0, 800)}`);
}

// 2) 스마트 작업 — 주간 브리핑을 지금 실행해 AI 가 리포트를 쓰게 한다
// sdk 유형은 스마트 작업에 모델을 싣지 않아 ai-agent 기본값(haiku)으로 돈다. 리포트 작성 단계가 파일을 남기지 않고
// 끝나는 경우가 있어(→ FAILED "리포트를 생성하지 못했습니다") 최대 3번까지 다시 실행한다. 실패한 실행은 이력에 남는다.
const job = list(await call(token, 'GET', '/proactive/jobs')).find((j) => j.name === '주간 매출·수금 브리핑');
let report = null;
for (let attempt = 1; attempt <= 3 && !report; attempt++) {
  const before = list(await call(token, 'GET', `/proactive/jobs/${job.id}/executions`)).length;
  await call(token, 'POST', `/proactive/jobs/${job.id}/execute`);
  const last = await waitFor(`주간 브리핑 실행 종료(${attempt}회차)`, async () => {
    const runs = list(await call(token, 'GET', `/proactive/jobs/${job.id}/executions`));
    if (runs.length <= before) return null;
    return ['COMPLETED', 'FAILED'].includes(runs[0].status) ? runs[0] : null;
  }, 10000);
  // COMPLETED 여도 리포트 파일 없이 답변 원문으로 대신 채운 실행이면 HTML 이 비어 리포트 뷰어가 "불러올 수 없습니다"를 띄운다.
  const detail = last.status === 'COMPLETED' ? await call(token, 'GET', `/proactive/jobs/${job.id}/executions/${last.id}`) : null;
  if (detail?.result?.htmlContent) report = last;
  else console.warn(`  ${attempt}회차 실패: ${last.errorMessage ?? 'HTML 리포트 없음(답변 원문으로 대체됨)'}`);
}
if (!report) throw new Error('스마트 작업 리포트를 3번 모두 만들지 못했다');
console.log('seed-ai 완료', { pipelineExecution: execId, jobExecution: report.id });
