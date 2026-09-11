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

function readPrompt(name: 'agent.md' | 'rules.md'): string {
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
