/**
 * 소개 자료 시드 3단계 — 데이터셋 보안 등급을 매긴다(보안 등급 장면).
 *
 *   node docs/intro/scripts/seed-security.mjs
 *
 * seed-ai.mjs 다음에 돈다. 기본 4등급(공개·내부·민감·기밀) 중 「민감」은 AI 정책이 「자체 호스팅만」이라
 * 외부 AI 에이전트가 그 데이터셋을 읽지 못한다. AI 가 거래처 정보를 지식그래프에 투영하고 문의 원문을 분류하는
 * seed-ai 단계가 끝난 뒤에 등급을 올려야 AI 결과물이 실제 실행으로 만들어진다.
 *
 * 등급은 화면과 같은 API(데이터셋 › 보안 탭의 등급 변경)로 바꾼다. 개인 연락처(구매 담당자)·고객 문의 원문을
 * 「민감」으로 두는 것은 실제 조직이 흔히 하는 분류다 — 매출·매입 전표는 기본 등급(내부) 그대로 둔다.
 */
import { call, login, PEOPLE } from './seed-demo.mjs';

const token = await login(PEOPLE[0].username);
const list = (r) => r?.content ?? r?.items ?? r ?? [];
const levels = await call(token, 'GET', '/security-levels');
const levelId = (name) => {
  const l = levels.find((x) => x.name === name);
  if (!l) throw new Error(`보안 등급 「${name}」이 없다 — 기본 4등급 시드(V133)를 확인한다`);
  return l.id;
};
const datasets = list(await call(token, 'GET', '/datasets?size=100'));
const dsId = (name) => {
  const d = datasets.find((x) => x.name === name);
  if (!d) throw new Error(`데이터셋 「${name}」이 없다 — seed-demo.mjs 를 먼저 돌린다`);
  return d.id;
};

const plan = [
  ['거래처 정보', '민감', '구매 담당자 이름·연락처가 들어 있는 개인정보'],
  ['고객 문의 접수', '민감', '고객이 보낸 문의 원문(개인정보·거래 조건 포함)'],
];
for (const [name, level, reason] of plan) {
  await call(token, 'PUT', `/datasets/${dsId(name)}/security-level`, { securityLevelId: levelId(level), reason });
  console.log(`  ${name} → ${level}`);
}
console.log('seed-security 완료');
