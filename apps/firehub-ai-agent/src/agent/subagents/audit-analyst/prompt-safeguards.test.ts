import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * audit-analyst 프롬프트 회귀 가드 (#246).
 *
 * 배경: inspector round 3에서 audit-analyst가 "관리자 전용" 표시에도
 *   (a) 권한 고지 없이 분석을 진행하고
 *   (b) 사용자가 묻지 않은 부수 계정의 이메일(PII)을 자발적으로 응답에
 *       옮기는 회귀가 발견됐다 (trace: audit-001).
 *
 * 이 테스트는 agent.md / rules.md / system-prompt.ts 세 파일에
 * 권한 사전 고지 + PII 자발적 노출 금지 규칙이 정적으로 남아 있는지
 * (=프롬프트 수정 시 누군가가 가드를 실수로 제거하지 않았는지) 검증한다.
 */

// ESM에서 __dirname 대체
const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readPrompt(name: 'agent.md' | 'rules.md'): string {
  return fs.readFileSync(path.join(__dirname, name), 'utf-8');
}

function readSystemPrompt(): string {
  // subagents/audit-analyst/ → agent/system-prompt.ts
  return fs.readFileSync(
    path.join(__dirname, '..', '..', 'system-prompt.ts'),
    'utf-8',
  );
}

describe('audit-analyst prompt safeguards (#246)', () => {
  it('agent.md에 Phase 1.5 관리자 권한 사전 고지 단계가 명시되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('Phase 1.5');
    expect(agent).toContain('PERMIT_NOTICE');
    expect(agent).toContain('관리자 전용');
    expect(agent).toContain('audit:read');
  });

  it('agent.md에 권한 에러 수신 시 즉시 중단 규칙이 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    // 403 또는 권한 없음 키워드
    expect(agent).toMatch(/403|권한 없음|권한 에러/);
    // 즉시 중단 표현
    expect(agent).toMatch(/즉시.*중단|분석을 중단/);
  });

  it('agent.md Phase 4 REPORT에 PII 비노출 원칙이 명시되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('PII 비노출');
    expect(agent).toContain('명시적으로 요청');
    // 마스킹 패턴 예시
    expect(agent).toMatch(/마스킹|\*\*\*@/);
  });

  it('rules.md에 PII 자발적 노출 금지가 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('PII 자발적 노출 금지');
    expect(rules).toContain('명시적으로 요청하지 않은');
    expect(rules).toMatch(/이메일/);
  });

  it('rules.md에 관리자 권한 사전 고지 규칙이 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('관리자 권한 사전 고지');
    expect(rules).toContain('관리자 전용');
  });
});

describe('main system-prompt safeguards (#246)', () => {
  it('system-prompt.ts에 L5 PII 마스킹 섹션이 명시되어 있어야 한다', () => {
    const sp = readSystemPrompt();
    expect(sp).toContain('## L5. PII 마스킹 (전역)');
    expect(sp).toContain('list_audit_logs');
    // 이메일/마스킹 키워드
    expect(sp).toMatch(/이메일/);
    expect(sp).toMatch(/마스킹/);
  });

  it('system-prompt.ts에 마스킹 또는 집계로만 표시 원칙이 있어야 한다', () => {
    const sp = readSystemPrompt();
    expect(sp).toMatch(/마스킹|집계/);
    // 잘못된 예시(원본 이메일 노출)가 ❌ 표시로 등장
    expect(sp).toMatch(/❌/);
  });

  it('system-prompt.ts에 audit:read 권한 및 권한 없음 처리 규칙이 있어야 한다', () => {
    const sp = readSystemPrompt();
    // L2 응답 출력 규칙에 audit:read 및 권한 없음 안내 포함
    expect(sp).toContain('audit:read');
    expect(sp).toMatch(/권한이 없습니다|권한 없음/);
  });
});
