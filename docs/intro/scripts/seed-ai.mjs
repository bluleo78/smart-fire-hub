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

/**
 * AI 채팅(SSE)에 한 번 묻고 답이 끝날 때까지 기다린 뒤 세션 ID 를 돌려준다.
 * 화면 채팅과 같은 엔드포인트라 대화 이력(AI 패널 세션 목록)에도 남는다.
 */
async function aiChat(token, message, sessionId) {
  const res = await fetch(`${process.env.INTRO_API ?? 'http://localhost:5010'}/api/v1/ai/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ message, sessionId }),
  });
  if (!res.ok) throw new Error(`AI 채팅 실패 → ${res.status} ${await res.text()}`);
  const dec = new TextDecoder();
  let buf = '';
  let sid = sessionId;
  for await (const chunk of res.body) {
    buf += dec.decode(chunk, { stream: true });
    let i;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const data = buf.slice(0, i).split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5)).join('');
      buf = buf.slice(i + 2);
      try {
        const ev = JSON.parse(data);
        if (ev.type === 'done') sid = ev.sessionId ?? sid;
        if (ev.type === 'error') throw new Error(`AI 채팅 오류: ${JSON.stringify(ev).slice(0, 300)}`);
        if (ev.type === 'tool_result' && ev.isError) console.warn(`  도구 오류: ${String(ev.result).slice(0, 200)}`);
      } catch (e) {
        if (e.message.startsWith('AI 채팅 오류')) throw e; // 그 밖의 파싱 실패(연결 알림 등)는 건너뛴다
      }
    }
  }
  return sid;
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

// 2) 지식그래프 적재 — API 엔드포인트가 없고 AI 에이전트 도구(graphrag_*)로만 돌아서 채팅으로 시킨다.
//    표 투영은 결정적(LLM 없음), 문서 적재는 AI 가 문서에서 거래처·이슈·관계를 뽑는다. 뽑다가 애매한 이름 쌍
//    (예: "다솜" ↔ "다솜오토텍")은 AI 가 병합하지 않고 AI 검수 대기열에 올린다 — 검수 화면의 항목은 이렇게 생긴다.
if (!process.env.INTRO_ONLY_JOB) {
  const datasets = list(await call(token, 'GET', '/datasets?size=50'));
  const dsId = (name) => datasets.find((d) => d.name === name).id;
  await aiChat(token, `데이터셋 ${dsId('거래처 정보')}(거래처 정보)과 데이터셋 ${dsId('매출 전표')}(매출 전표)을 graphrag_project_table 도구로 지식그래프에 투영해줘. 투영만 하고 다른 작업은 하지 마.`);
  console.log('✓ 표 → 지식그래프 투영');
  // 문서 적재는 비용이 큰 작업이라 에이전트가 한 번 확인을 묻는다 — 같은 세션에서 "네"로 답한다.
  const sid = await aiChat(token, `graphrag_ingest 도구로 문서 데이터셋 ${dsId('사내 문서')}(사내 문서)을 지식그래프에 적재해줘.`);
  await aiChat(token, '네', sid);
  const reviews = await call(token, 'GET', '/graphrag/review-items?status=pending');
  console.log(`✓ 문서 → 지식그래프 적재(AI 검수 대기 ${list(reviews).length}건)`);
}

// 3) 스마트 작업 — 주간 브리핑을 지금 실행해 AI 가 리포트를 쓰게 한다
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
