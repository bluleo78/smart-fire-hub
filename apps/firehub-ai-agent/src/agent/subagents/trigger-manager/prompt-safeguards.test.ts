import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * trigger-manager 프롬프트 회귀 가드 (refs #577).
 *
 * 배경: agent.md 본문은 `list_pipelines`(삭제 Turn 1 후보 탐색·N+1 규칙)와
 * 파이프라인/데이터셋 존재 확인을 요구하는데, frontmatter `tools` 화이트리스트에는
 * 트리거 CRUD 4개만 선언되어 있었다. subagent-loader가 frontmatter.tools를 그대로
 * AgentDefinition.tools로 넘기므로 subagent는 절차를 수행할 수 없었고,
 * "확인 못 함 … 생성할까요?"라고 답한 뒤 메인 에이전트가 뒤늦게 404를 보정하는
 * 모순 응답(trig-001/trig-013), ID만 있는 삭제 요청에서 도구 호출 0건으로
 * pipelineId를 되묻는 문제(trig-009)가 발생했다.
 *
 * 이 테스트는 (1) 본문·rules.md·examples.md가 언급하는 모든 firehub 도구가
 * 화이트리스트에 선언돼 있는지, (2) 존재 확인 규칙이 프롬프트에 남아있는지 정적으로 검증한다.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readPrompt(name: 'agent.md' | 'rules.md' | 'examples.md'): string {
  return fs.readFileSync(path.join(__dirname, name), 'utf-8');
}

/** frontmatter의 `tools:` 목록에서 mcp__firehub__ 도구명(접두사 제거)을 추출한다. */
function frontmatterTools(agentMd: string): Set<string> {
  const fm = agentMd.match(/^---\n([\s\S]*?)\n---/)?.[1] ?? '';
  const names = new Set<string>();
  for (const m of fm.matchAll(/^\s*-\s*mcp__firehub__([a-z_]+)\s*$/gm)) names.add(m[1]);
  return names;
}

/** 본문에서 언급되는 firehub 도구명(list_/get_/create_/update_/delete_ 접두 동사형)을 추출한다. */
function mentionedTools(body: string): Set<string> {
  const names = new Set<string>();
  for (const m of body.matchAll(/\b(?:mcp__firehub__)?((?:list|get|create|update|delete)_[a-z_]+)\b/g)) {
    names.add(m[1]);
  }
  return names;
}

describe('trigger-manager prompt safeguards (#577)', () => {
  const agentMd = readPrompt('agent.md');
  const tools = frontmatterTools(agentMd);

  it('frontmatter tools에 사전 확인용 조회 도구가 선언되어 있어야 한다', () => {
    expect(tools).toContain('list_pipelines');
    expect(tools).toContain('get_pipeline');
    expect(tools).toContain('get_dataset');
    // 기존 CRUD 4개는 유지
    for (const t of ['list_triggers', 'create_trigger', 'update_trigger', 'delete_trigger']) {
      expect(tools).toContain(t);
    }
  });

  it('agent.md/rules.md/examples.md가 언급하는 모든 firehub 도구는 tools 화이트리스트에 있어야 한다', () => {
    const body = agentMd.replace(/^---\n[\s\S]*?\n---/, '');
    const mentioned = new Set<string>([
      ...mentionedTools(body),
      ...mentionedTools(readPrompt('rules.md')),
      ...mentionedTools(readPrompt('examples.md')),
    ]);
    const missing = [...mentioned].filter((t) => !tools.has(t));
    expect(missing, `본문이 요구하지만 화이트리스트에 없는 도구: ${missing.join(', ')}`).toEqual([]);
  });

  it('agent.md Phase 1에 get_pipeline/get_dataset 존재 확인 + 404 시 생성 금지 규칙이 있어야 한다', () => {
    expect(agentMd).toMatch(/get_pipeline\(id=pipelineId\)/);
    expect(agentMd).toMatch(/get_dataset\(id=datasetId\)/);
    expect(agentMd).toMatch(/404면 \*\*create_trigger\/update_trigger를 호출하지 않고\*\*/);
    // list_triggers의 [] 반환을 존재 증거로 삼지 말 것
    expect(agentMd).toMatch(/list_triggers\(pipelineId\)`는 존재하지 않는 pipelineId에도 `\[\]`를 반환/);
  });

  it('agent.md 삭제 Turn 1에 trigger ID만 주어진 경우 list_pipelines 탐색 규칙이 있어야 한다', () => {
    expect(agentMd).toMatch(/trigger ID만 주어진 경우[\s\S]*?`list_pipelines`를 먼저 1회 호출/);
    expect(agentMd).toMatch(/도구 호출 없이 사용자에게 pipelineId를 되묻지 않는다/);
    // pipelineId 추측(1,2,3…) 금지 — 실측 회귀: list_pipelines 없이 list_triggers(1..5) 호출
    expect(agentMd).toMatch(/pipelineId를 1, 2, 3…처럼 추측해서 `list_triggers`를 부르는 것은 금지/);
  });

  it('Mode: DESIGN 위임에서도 조회 도구(get_pipeline/get_dataset)는 호출한다는 규칙이 있어야 한다', () => {
    // 실측 회귀: 메인이 "Mode: DESIGN"으로 위임하자 subagent가 조회 도구를 전혀 부르지 않고
    // "존재하는 값이 맞는지 확인해 주세요"라고 사용자에게 검증을 떠넘겼다.
    expect(agentMd).toMatch(/`Mode: DESIGN` 마커가 있어도\*\* 이 확인 호출은 수행한다/);
    const examples = readPrompt('examples.md');
    expect(examples).toMatch(/get_pipeline\(id=99999\)/);
    expect(examples).toMatch(/get_dataset\(id=999999\)/);
    expect(examples).toMatch(/트리거 32번 삭제해줘[\s\S]*?`list_pipelines` 1회 호출/);
  });

  it('rules.md에 존재 확인 규칙 절이 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/## 대상 존재 확인/);
    expect(rules).toMatch(/존재 증거로 사용 금지/);
  });

  it('agent.md/rules.md/examples.md에 삭제 Turn 1 탐색 중 영어 라벨 금지 규칙이 있어야 한다 (refs #613)', () => {
    // 실측 회귀: "트리거 32번 삭제해줘" 요청에서 list_pipelines→list_triggers 순차 탐색 직후
    // 최종 응답이 "Found: " / "Found it: " 같은 영어 라벨/문장으로 시작(비결정적, 관찰 1/4).
    expect(agentMd).toMatch(/Found: [\s\S]*?Found it: /);
    expect(agentMd).toMatch(/탐색 구간은 도구 호출만 반복하고 사용자 텍스트를 출력하지 않는다/);
    // 실측 회귀 v2: "Found:" 패턴을 막은 뒤에도 "Mode is DESIGN, so..." 같은
    // 내부 판단 근거를 설명하는 영어 문장이 새로 관찰됨 — 한국어/영어 무관하게 금지해야 한다.
    expect(agentMd).toMatch(/이 금지는 \*\*한국어\/영어 무관\*\*하며/);
    expect(agentMd).toMatch(/Mode is DESIGN, so no delete call/);

    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/탐색 중 영어 라벨 \(refs #613\)/);

    const examples = readPrompt('examples.md');
    expect(examples).toMatch(/Found: 트리거 32번은 파이프라인/);
    expect(examples).toMatch(/Found it: trigger ID 32/);
  });

  // #624: 메인 SYSTEM_PROMPT가 delete_trigger 확인 승인 재위임에 `Mode: DELETE-APPROVED`
  // 마커를 붙이는데(pipeline-builder/template-builder와 동일 계약, refs #621), trigger-manager
  // rules.md에는 이 마커의 처리 규칙이 전혀 없어 fresh 인스턴스가 매번 get_pipeline/list_triggers를
  // 재조회하고 동일 재확인 문장을 반복 출력하던 결함 — 재발 방지 회귀 테스트.
  it('rules.md에 Mode: DESIGN / Mode: CREATE-APPROVED / Mode: DELETE-APPROVED 마커 처리가 명시되어 있어야 한다 (#621, #624)', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('Mode: DESIGN');
    expect(rules).toContain('Mode: CREATE-APPROVED');
    expect(rules).toContain('Mode: DELETE-APPROVED');
    // DELETE-APPROVED 수신 시 Turn 1 재조회 없이 곧바로 delete_trigger를 호출해야 한다는 규칙 확인
    expect(rules).toMatch(/Mode: DELETE-APPROVED[\s\S]*?곧바로 `delete_trigger`\s*를?\s*호출/);
    // CREATE-APPROVED가 delete 확인 승인에는 적용되지 않는다는 구분 명시 확인 (#621류 오적용 방지)
    expect(rules).toMatch(/Mode: CREATE-APPROVED[\s\S]*?delete_trigger[\s\S]*?적용되지 않는다/);
    // 삭제 전 확인 절 자체가 DELETE-APPROVED 예외를 인지하고 있는지 확인
    expect(rules).toMatch(/삭제 전 확인[^\n]*Mode: DELETE-APPROVED/);
  });
});
