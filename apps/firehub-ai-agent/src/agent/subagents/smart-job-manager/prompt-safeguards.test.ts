import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * smart-job-manager 프롬프트 회귀 가드 (#625).
 *
 * 배경: 메인 SYSTEM_PROMPT는 파괴 작업 승인 재위임 시 `Mode: DELETE-APPROVED` 마커를
 * 표준으로 사용하는데(#621), `pipeline-builder`/`template-builder`(#621)와
 * `trigger-manager`(#624)에는 이 마커를 인식하는 규칙이 이식됐지만 `smart-job-manager`에는
 * 전혀 없었다(agent.md에 `Mode:` 문자열 0회 등장). 그 결과 삭제 승인 위임 턴에서도
 * `list_proactive_jobs`를 매번 재조회한 뒤에야 `delete_proactive_job`을 호출해
 * 사용자 승인부터 최종 응답까지 64초가 걸렸다(삭제 자체는 성공, 순수 성능/구조 결함).
 *
 * 이 테스트는 agent.md에 Mode 마커 처리 규칙이 pipeline-builder/template-builder/
 * trigger-manager와 동일한 패턴으로 명시돼 있는지 정적으로 검증한다.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readAgentMd(): string {
  return fs.readFileSync(path.join(__dirname, 'agent.md'), 'utf-8');
}

describe('smart-job-manager prompt safeguards (#625)', () => {
  const agentMd = readAgentMd();

  it('agent.md에 Mode: DESIGN / Mode: CREATE-APPROVED / Mode: DELETE-APPROVED 마커 처리가 명시되어 있어야 한다 (#621, #624, #625)', () => {
    expect(agentMd).toContain('Mode: DESIGN');
    expect(agentMd).toContain('Mode: CREATE-APPROVED');
    expect(agentMd).toContain('Mode: DELETE-APPROVED');
  });

  it('Mode: DELETE-APPROVED 수신 시 list_proactive_jobs 재조회 없이 곧바로 delete_proactive_job을 호출해야 한다', () => {
    expect(agentMd).toMatch(/Mode: DELETE-APPROVED[\s\S]*?곧바로 `delete_proactive_job`\s*을?\s*호출/);
  });

  it('Mode: CREATE-APPROVED가 delete_proactive_job 확인 승인에는 적용되지 않는다는 구분이 명시되어 있어야 한다 (#621류 오적용 방지)', () => {
    expect(agentMd).toMatch(/Mode: CREATE-APPROVED[\s\S]*?delete_proactive_job[\s\S]*?적용되지 않는다/);
  });

  it('워크플로 D 1단계가 Mode 마커 수신 시 list_proactive_jobs 조회 생략 예외를 인지하고 있어야 한다', () => {
    expect(agentMd).toMatch(/작업 수정\/삭제 워크플로[\s\S]*?Mode: DELETE-APPROVED.*Mode: CREATE-APPROVED.*마커가 있으면 이 조회를 생략/);
  });

  it('마커가 없거나 모호한 경우의 안전 fallback이 명시되어 있어야 한다', () => {
    expect(agentMd).toMatch(/마커가 없거나 모호한 경우/);
  });
});
