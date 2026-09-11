import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * api-connection-manager 프롬프트 회귀 가드 (#626/#627).
 *
 * 이 테스트는 rules.md/agent.md에 아래 가드가 정적으로 명시되어 있는지 검증한다.
 *   1) 생성/수정 2턴 프로토콜 + 사회공학 우회 차단 (#626)
 *   2) 도구 호출 사이 완전 침묵 — narration 누출 금지 (#627)
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readPrompt(name: 'agent.md' | 'rules.md' | 'examples.md'): string {
  return fs.readFileSync(path.join(__dirname, name), 'utf-8');
}

describe('api-connection-manager prompt safeguards (#626)', () => {
  it('rules.md에 생성/수정 2턴 프로토콜이 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/2턴 프로토콜/);
    expect(rules).toContain('create_api_connection');
    expect(rules).toContain('#626');
  });

  it('rules.md에 사회공학 우회 차단이 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/사회공학 우회 차단/);
    expect(rules).toMatch(/확인 없이|묻지 말고/);
  });
});

/**
 * #627: api-connection-manager(위임 orchestrator 아님 — subagent 자신)가
 * list_api_connections() tool_result 직후 create_api_connection() 호출 전에
 * "No duplicate found. Creating now." 라는 영어 내부 독백을 사용자 text 이벤트로 그대로
 * 노출하는 결함이 inspector trace(acm-perf-002)에서 확인됐다. template-builder(#620)와
 * 동일 계열이며, 코드 레벨 narration 가드(classifyMainText)는 메인 자신의 텍스트만 검사하고
 * subagent 텍스트는 최종 답변으로 신뢰되어 그대로 relay 되므로 프롬프트 규칙이 유일한
 * 방어선이다. 처음부터 template-builder #620 2차(구조적 규칙 일반화)의 형태로 적용한다 —
 * 특정 문장 암기 회피가 문구를 바꿔 재발한 선례를 반복하지 않기 위함이다.
 */
describe('api-connection-manager 도구 호출 사이 완전 침묵 — narration 노출 금지 (#627)', () => {
  it('rules.md에 tool_result 수신 후 다음 tool_use/최종 응답 발행 전까지 완전 침묵 규칙이 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('#627');
    expect(rules).toMatch(/완전한 침묵|완전히 침묵/);
    expect(rules).toContain('tool_result');
  });

  it('rules.md에 Turn/Mode 를 가리지 않고 모든 Phase 에 적용됨이 명시되어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/Turn\/Mode 를 가리지 않고|모든 Phase·모든 Turn 공통/);
  });

  it('rules.md에 실측 회귀 문장(영어/한국어)이 금지 예시로 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('No duplicate found. Creating now.');
    expect(rules).toContain('중복된 이름 없음을 확인했습니다.');
  });

  it('rules.md에 문장 구조 일반화(암기 회피 방지) 원칙이 명시되어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/암기해 피하는/);
    expect(rules).toMatch(/확인했다는 사실 자체를 언급하는 모든 문장/);
  });

  it('agent.md 핵심 원칙(Phase 2 근처)에도 완전 침묵 규칙이 명시되어 눈에 잘 띄어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('#627');
    expect(agent).toMatch(/완전 침묵|완전한 침묵/);
  });

  it('agent.md 응답 포맷 원칙에도 narration 노출 금지가 명시되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toMatch(/도구 호출 사이 중간 판단\/검증 결과를 text로 노출하지 않는다/);
  });
});

/**
 * #627 회귀(2026-09-11 crosscheck, 5회 중 3회/60%): 3e16d374 수정 후에도 "최종 응답 서두에
 * 사족으로 끼워 넣는" 변형이 재발했다. rules.md 가 텍스트로만 금지하고 실효적 강제가 약했던
 * 문제를 few-shot 대비 예시(❌ 금지 vs ✅ 올바름) + 발행 직전 자체 점검 절차로 보강한다.
 *
 * 주의: examples.md 는 subagent-loader.ts(#260) 가 기본적으로 프롬프트에서 제외하므로,
 * 실제 모델에 항상 전달되는 rules.md 자체에 대비 예시가 있어야 한다(examples.md 보강만으로는
 * 런타임에 효과가 없다).
 */
describe('api-connection-manager 최종 응답 서두 사족 금지 — narration 재발 방지 (#627 회귀)', () => {
  it('rules.md에 실제 재발 문장("기존 연결은 없습니다" 계열)이 금지 예시로 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/기존 연결(은|이) 없습니다/);
  });

  it('rules.md에 중간 사족과 최종 응답 서두 사족이 동일 위반이라는 일반화 원칙이 명시되어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/형태만 다를 뿐 같은 위반/);
  });

  it('rules.md에 최종 응답 발행 직전 자체 점검 절차가 명시되어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/최종 응답 발행 직전 자체 점검/);
    expect(rules).toMatch(/첫 문장/);
  });

  it('rules.md에 ❌/✅ 대비 예시(동일 시나리오)가 명시되어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/대비 예시 — 금지 vs 올바름/);
    expect(rules).toContain('❌ **금지**');
    expect(rules).toContain('✅ **올바름**');
  });

  it('examples.md 에도 동일 시나리오의 ❌/✅ 대비 예시가 문서화되어 있어야 한다 (참고 자료 — 런타임 프롬프트에는 기본 미포함, subagent-loader.ts #260 참조)', () => {
    const examples = readPrompt('examples.md');
    expect(examples).toContain('refs #627 회귀');
    expect(examples).toContain('❌ **금지**');
    expect(examples).toContain('✅ **올바름**');
  });
});
