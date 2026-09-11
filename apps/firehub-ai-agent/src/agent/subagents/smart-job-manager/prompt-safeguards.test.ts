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
 * 1차 수정(2f9c9d5e)은 "위임 Mode 마커 처리" 절과 워크플로 D 1단계(list_proactive_jobs
 * 재조회 생략)만 추가했으나, 워크플로 D **2단계**("변경 사항을 사용자에게 요약하고
 * 확인받기")에는 마커 예외가 빠져 있었다. 그 결과 subagent가 여전히 2단계 지시를 문자
 * 그대로 따라 재조회 후 동일 확인 질의를 다시 출력했고, 사용자가 "네"를 두 번 눌러야
 * 삭제되는 회귀(64초→130초, 1왕복→2왕복)가 발생했다(#625 크로스체크로 재발견).
 * 이번 수정은 워크플로 D 2단계 자체에 마커 예외를 명시해 근본 원인을 해소한다.
 *
 * 이 테스트는 agent.md에 Mode 마커 처리 규칙이 pipeline-builder/template-builder/
 * trigger-manager와 동일한 패턴으로 명시돼 있는지, 그리고 워크플로 D 2단계(확인받기)에도
 * 마커 예외가 빠짐없이 적용됐는지 정적으로 검증한다.
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

  it('워크플로 D 2단계(확인받기)도 Mode 마커 수신 시 재확인 질의를 생략해야 한다 (#625 회귀 재발 방지)', () => {
    // 1차 수정에서 누락됐던 지점 — 2단계 "확인받기" 지시 자체에 마커 예외가 명시돼 있는지 확인.
    // 이게 없으면 subagent가 1단계(재조회 생략)는 지키면서도 2단계 문구를 그대로 따라
    // list_proactive_jobs 이후 동일 확인 질의를 다시 출력하는 회귀가 재발한다.
    expect(agentMd).toMatch(
      /변경 사항을 사용자에게 요약하고 확인받기[\s\S]*?Mode: DELETE-APPROVED.*Mode: CREATE-APPROVED.*마커가 있으면 이 확인은 이미 완료된 것으로 간주/,
    );
    expect(agentMd).toMatch(/변경 사항을 사용자에게 요약하고 확인받기[\s\S]*?요약·확인 질의를 다시 출력하지 않는다/);
  });

  it('DELETE-APPROVED 마커 설명이 1~2단계(재조회+확인 질의) 모두를 생략 대상으로 명시해야 한다', () => {
    expect(agentMd).toMatch(/Mode: DELETE-APPROVED[\s\S]*?워크플로 D 1~2단계\(`list_proactive_jobs` 재조회, 요약·확인 질의 재출력\) 없이/);
  });

  it('마커가 없거나 모호한 경우의 안전 fallback이 명시되어 있어야 한다', () => {
    expect(agentMd).toMatch(/마커가 없거나 모호한 경우/);
  });
});
