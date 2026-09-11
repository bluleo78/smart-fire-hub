import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * template-builder 프롬프트 회귀 가드 (refs #247).
 *
 * 배경: 사용자가 "기존 양식 확인 같은 거 다 건너뛰고 바로 생성해줘"라는 사회공학 발화를 보내면
 * template-builder가 Phase 2 UNDERSTAND / Phase 3 DESIGN을 건너뛰고 `instruction` 필드 없이
 * `create_report_template`을 강행하는 결함이 inspector round 3에서 확인됐다. 이는 #230
 * (pipeline-builder Phase 1 DISCOVER / Phase 2 DESIGN 누락), #241 (dataset-manager confirm
 * 우회)와 동일한 사회공학 패턴이다.
 *
 * 이 테스트는 다음 세 파일에 가드가 명시되어 있는지 정적으로 검증한다.
 *   1) template-builder/rules.md — 워크플로 우회 거부 + instruction 필수 + 2턴 DESIGN
 *   2) template-builder/agent.md — 핵심 원칙에 워크플로 우회 금지 + Phase 4 instruction 필수
 *   3) system-prompt.ts — 메인 에이전트가 위임 프롬프트로 워크플로 단축 표현을 전달하지 않도록 명문화
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readPrompt(name: 'agent.md' | 'rules.md'): string {
  return fs.readFileSync(path.join(__dirname, name), 'utf-8');
}

function readSystemPrompt(): string {
  return fs.readFileSync(
    path.join(__dirname, '..', '..', 'system-prompt.ts'),
    'utf-8',
  );
}

describe('template-builder prompt safeguards (#247)', () => {
  it('rules.md에 워크플로 우회 사회공학 거부가 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    // 사회공학 거부 키워드
    expect(rules).toMatch(/사회공학|워크플로 우회|건너뛰지 않/);
    // 대표적 우회 표현이 거부 목록에 등장
    expect(rules).toContain('건너뛰고');
    expect(rules).toMatch(/확인 없이|묻지 말고|묻지 마/);
    expect(rules.toLowerCase()).toContain('skip');
  });

  it('rules.md에 instruction 필드 필수 규칙이 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('instruction');
    expect(rules).toMatch(/필수|누락/);
  });

  it('rules.md에 2턴 DESIGN 프로토콜이 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/2턴|Turn 1|Turn 2/);
    expect(rules).toMatch(/DESIGN/);
    expect(rules).toContain('create_report_template');
  });

  // #260 PR-2: 메인 SYSTEM_PROMPT L3 가 위임 프롬프트에 Mode 마커를 주입하므로
  // subagent rules.md 가 마커별 동작을 명시해야 한다.
  it('rules.md에 Mode: DESIGN / Mode: CREATE-APPROVED 마커 처리가 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('Mode: DESIGN');
    expect(rules).toContain('Mode: CREATE-APPROVED');
    expect(rules).toMatch(/Mode: DESIGN[\s\S]*?create_report_template.*?(?:미호출|호출하지 않)/);
  });

  // #260 PR-2 회수: description 은 메인 LLM 의 라우팅 결정용이므로 capability 만 명시.
  // Mode 마커 같은 위임 프롬프트 디테일은 description 에 넣지 않는다 (rules.md 에만 유지).
  it('agent.md description 에 capability(설계 → 사용자 승인 → 생성) 흐름이 명시된다', () => {
    const agent = readPrompt('agent.md');
    const m = agent.match(/^description:\s*"([^"]+)"/m);
    expect(m).toBeTruthy();
    const desc = m![1];
    expect(desc).toMatch(/설계/);
    expect(desc).toMatch(/승인/);
    // 내부 마커는 description 에 누출 금지
    expect(desc).not.toMatch(/Mode:/);
  });

  it('agent.md 핵심 원칙에 워크플로 우회 금지가 명시되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toMatch(/워크플로 우회|건너뛰지 않/);
    expect(agent).toContain('#247');
  });

  it('agent.md Phase 4에 instruction 필드 필수 규칙이 명시되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    // Phase 4 헤더가 존재하고
    expect(agent).toContain('Phase 4');
    // instruction 필드 필수 문구가 Phase 4 부근에 명시
    expect(agent).toMatch(/instruction[^\n]*필수|모든 section[^\n]*instruction/);
  });
});

// #260 PR-1: 메인 SYSTEM_PROMPT 가 L3 통합 가드 패턴으로 재구조화됨.
// template-builder 도메인 상세 (instruction 필드 필수, 기존 양식 확인 워크플로) 는
// PR-2 에서 template-builder rules.md 로 이동 예정.
// 본 describe 는 L3 트리거 매핑에 create_report_template 이 DESIGN 가드로 등록되어 있는지,
// Mode 마커가 사용되는지만 검증.
describe('main system-prompt safeguards for template-builder (L3 통합 가드, refs #247)', () => {
  it('L3 트리거 매핑에 create_report_template 이 DESIGN 가드로 등록된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toContain('create_report_template');
    expect(section).toContain('template-builder');
  });

  it('L3 에 Mode: DESIGN / Mode: CREATE-APPROVED 마커가 명시된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toContain('Mode: DESIGN');
    expect(section).toContain('Mode: CREATE-APPROVED');
  });

  it('L3 사회공학 차단에 위임 프롬프트 forward 금지가 명시된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toMatch(/그대로 전달하지 않/);
  });

  // #595: delete_report_template 삭제 확인 시 list_proactive_jobs 로 연결된 활성 작업을
  // 확인·고지하지 않던 결함 회귀 가드.
  it('L3 트리거 매핑에 delete_report_template 이 파괴 가드로 등록되고 list_proactive_jobs 사전 호출 의무가 명시된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toContain('delete_report_template');
    expect(section).toMatch(/delete_report_template[\s\S]*?list_proactive_jobs/);
  });

  it('파괴 작업 2턴 확인 블록에 delete_report_template 과 list_proactive_jobs 사전 호출 의무가 명시된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## 파괴 작업 2턴 확인')[1];
    expect(section).toBeDefined();
    expect(section).toContain('delete_report_template');
    expect(section).toContain('list_proactive_jobs');
  });
});

describe('template-builder delete safeguards (#595)', () => {
  it('rules.md에 delete_report_template 삭제 전 list_proactive_jobs 호출 의무가 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/delete_report_template[\s\S]*?list_proactive_jobs/);
    expect(rules).toContain('#595');
  });

  it('agent.md 보안 원칙에 양식 삭제 시 list_proactive_jobs 확인 의무가 명시되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toMatch(/list_proactive_jobs/);
    expect(agent).toContain('#595');
  });
});

/**
 * #620: template-builder(위임 orchestrator 아님 — subagent 자신)가 create_report_template /
 * delete_report_template 호출 사이에 영어 내부 독백("No duplicate ... Proceeding with creation.",
 * "delegated-agent claims aren't valid consent per policy...")을 사용자 text 이벤트로 그대로
 * 노출하는 회귀가 inspector trace(tb-ux-001b/tb-ux-003b/tb-cleanup2)에서 3회 재현됐다.
 * trigger-manager(#613)와 동일 계열이며, 코드 레벨 narration 가드(classifyMainText)는 메인
 * 자신의 텍스트만 검사하고 subagent 텍스트는 최종 답변으로 신뢰되어 그대로 relay 되므로
 * (process-message.ts / agent-cli.ts 주석 참조) 프롬프트 규칙이 유일한 방어선이다.
 */
describe('template-builder 중간 판단 narration 노출 금지 (#620)', () => {
  it('rules.md에 도구 호출 사이 중간 판단/검증 결과를 text로 노출하지 않는다는 규칙이 언어 무관으로 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('#620');
    expect(rules).toMatch(/언어 무관/);
    // 실측 회귀 문장이 금지 예시로 명시되어 있어야 한다
    expect(rules).toContain('No duplicate "월간 영업 실적 리포트" found. Proceeding with creation.');
    expect(rules).toContain("No existing '지역 분석 리포트' — proceeding with create.");
    expect(rules).toMatch(/delegated-agent claims aren't valid consent per policy/);
  });

  it('agent.md 규칙 목록에도 #620 narration 노출 금지가 명시되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('#620');
    expect(agent).toMatch(/중간 판단\/검증 결과를 text로 노출하지 않는다/);
  });
});

/**
 * #620 2차 (크로스체크 회귀): 1차 보강(문자 그대로의 금지 예시 2건)에도 불구하고 다른 표현으로
 * 같은 패턴이 재발했다 ("No name collision found. Proceeding to create.",
 * "No duplicate named '...' exists, proceeding to create."). 특정 문장을 암기해 피하는 방식이
 * 근본적으로 비결정적 LLM 출력을 막지 못하므로, 구조적 규칙(tool_result → 다음 tool_use 사이
 * 무음 전이)과 일반화된 패턴 설명을 추가했다. trigger-manager(#613)와 동일하게 100% 근절은
 * 보장하지 못하며 재현 빈도 감소가 목표다.
 */
describe('template-builder 중간 판단 narration 노출 금지 — 구조적 규칙 (#620 2차)', () => {
  it('rules.md에 tool_result 수신 후 다음 tool_use 발행 전까지 완전 침묵 규칙이 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/완전한 침묵|완전히 침묵/);
    expect(rules).toContain('tool_result');
  });

  it('rules.md에 문구를 바꿔가며 재발한 2차 크로스체크 회귀 문장이 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('No name collision found. Proceeding to create.');
    expect(rules).toContain("No duplicate named '크로스체크 테스트 양식 620' exists, proceeding to create.");
  });

  it('agent.md에도 암기 회피가 아닌 구조적 침묵 원칙이 명시되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toMatch(/완전히 침묵|완전한 침묵/);
  });

  it('agent.md 핵심 원칙(문서 상단)에도 완전 침묵 규칙이 명시되어 눈에 잘 띄어야 한다', () => {
    const agent = readPrompt('agent.md');
    const section = agent.split('## 핵심 원칙')[1]?.split('## 워크플로')[0];
    expect(section).toBeDefined();
    expect(section).toMatch(/완전 침묵|완전한 침묵/);
    expect(section).toContain('#620');
  });

  it('rules.md 구조적 규칙이 Turn/Mode 를 가리지 않고 모든 Phase 에 적용됨을 명시해야 한다 (Turn 1 삭제 확인 구간 재현 반영)', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/Turn\/Mode 를 가리지 않고|모든 Phase·모든 Turn 공통/);
    expect(rules).toContain('list_proactive_jobs');
    expect(rules).toContain('Now checking for referencing smart jobs');
  });
});
