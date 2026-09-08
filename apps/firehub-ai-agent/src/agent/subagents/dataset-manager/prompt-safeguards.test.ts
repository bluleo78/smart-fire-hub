import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * dataset-manager 프롬프트 회귀 가드 (#571).
 *
 * 배경: `delete_dataset` MCP 도구는 애초에 `{success, datasetId}` 만 반환해
 * rules.md의 "삭제된 객체 이름·시각을 응답에 반드시 포함" 규칙을 지킬 근거가
 * 없었다(1차 원인). b73b2be1 에서 도구가 `datasetName`/`deletedAt` 을 반환하도록
 * 고쳤지만, inspector 크로스체크(2026-09-09 04:55, crosscheck-571b.sse) 에서
 * tool_result 에 `deletedAt` 이 있어도 최종 응답 텍스트에는 여전히 시각이
 * 빠지는 회귀가 확인됐다(도구 레벨 수정만으로는 불충분 — 2차 원인).
 *
 * 재조사 결과 `delete_dataset` 은 메인 SYSTEM_PROMPT 의 "파괴 작업 2턴 확인"
 * 절이 "위임·직접 모두" 허용하는 도구라, dataset-manager 로 위임되지 않고
 * 메인 에이전트가 직접 호출·요약하는 경로가 실제로도 관측됐다(라이브 curl
 * 재현으로 확인). rules.md/agent.md 만 고치면 이 직접 호출 경로는 여전히
 * deletedAt 을 요약에서 누락시키므로, system-prompt.ts 의 Turn 2 지시에도
 * 동일한 요구를 명시했다 — 이것이 3차 수정이자 실질적 근본 원인 수정이다.
 *
 * 이 테스트는 rules.md/agent.md 가 "시각 출처는 tool_result의 deletedAt 필드"라는
 * 점과 "이름만 있고 시각이 빠진 요약은 규칙 위반"이라는 점을 명시적으로 남기고
 * 있는지(=프롬프트 수정 시 누군가 이 가드를 실수로 완화/제거하지 않았는지) 정적으로
 * 검증한다. system-prompt.ts 쪽 동일 요구는 inspector-regression.test.ts 에서
 * 검증한다.
 *
 * 주의: 이 테스트는 LLM 이 실제로 규칙을 준수하는지(=최종 응답 텍스트에 시각이
 * 실제로 나타나는지)는 검증하지 못한다 — 프롬프트 지시 준수 여부는 결정적 단위
 * 테스트로 검증 불가능하며, 라이브 재현(`POST /agent/chat`, curl)으로만 확인
 * 가능하다. 이 테스트는 "지시 자체가 문서에서 사라지는" 회귀만 잡는다.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readPrompt(name: 'agent.md' | 'rules.md'): string {
  return fs.readFileSync(path.join(__dirname, name), 'utf-8');
}

describe('dataset-manager prompt safeguards (#571)', () => {
  it('rules.md "실행 후 요약" 절이 deletedAt 필드를 시각의 단일 출처로 명시한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('실행 후 요약');
    // 시각 출처가 tool_result의 deletedAt 필드로 명시되어야 함 (환각 방지)
    expect(rules).toMatch(/delete_dataset.*deletedAt|deletedAt.*필드/s);
  });

  it('rules.md가 이름만 포함하고 시각이 빠진 요약을 규칙 위반으로 명시한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/시각이 빠진 요약.*규칙 위반|규칙 위반.*시각/s);
  });

  it('agent.md 핵심 기억사항에도 delete_dataset 최종 요약의 deletedAt 포함 의무가 명시된다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('핵심 기억사항');
    const memo = agent.split('핵심 기억사항')[1];
    expect(memo).toMatch(/delete_dataset.*deletedAt|deletedAt.*delete_dataset/s);
  });
});
