#!/usr/bin/env node
/**
 * 촬영 전 점검용 — AI 어시스턴트에 질문 하나를 SSE 로 보내 도구 호출·답변을 요약한다.
 * 채팅 장면의 질문 문구를 고를 때 쓴다(어떤 위젯을 그리는지, 답변에 모델·벤더 이름이 섞이지 않는지).
 *
 *   node docs/intro/scripts/chat-probe.mjs "최근 3개월 권역별 매출과 이익률을 차트로 보여줘"
 */
import { PEOPLE, login } from './seed-demo.mjs';

const API = (process.env.INTRO_API ?? 'http://localhost:5010') + '/api/v1';
const token = await login(PEOPLE[0].username);
const res = await fetch(`${API}/ai/chat`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  body: JSON.stringify({ message: process.argv[2], sessionId: null }),
});
const reader = res.body.getReader();
const dec = new TextDecoder();
let buf = '';
let text = '';
const tools = [];
const t0 = Date.now();
for (;;) {
  const { value, done } = await reader.read();
  if (done) break;
  buf += dec.decode(value, { stream: true });
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (!line.startsWith('data:')) continue;
    let ev;
    try {
      ev = JSON.parse(line.slice(5));
    } catch {
      continue;
    }
    if (ev.type === 'text') text += ev.content;
    else if (ev.type === 'tool_use') tools.push(ev.toolName ?? ev.name);
    else if (ev.type === 'error') console.log('ERROR', JSON.stringify(ev));
  }
}
console.log(`도구: ${tools.join(', ')}`);
console.log(`소요: ${Math.round((Date.now() - t0) / 1000)}s`);
console.log(`벤더 노출: ${/claude|anthropic|sonnet|haiku|opus/i.test(text) ? '있음' : '없음'}`);
console.log('--- 답변 ---\n' + text);
