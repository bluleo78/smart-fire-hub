import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * pipeline-builder 프롬프트 회귀 가드.
 *
 * 배경 (#242): 존재하지 않는 데이터셋 ID로 파이프라인 생성을 요청받았을 때,
 * 에이전트가 `SELECT 1 AS placeholder` 같은 더미 SQL로 `create_pipeline`을 강행하고
 * 추가로 SCHEDULE 트리거까지 등록해 영구적으로 무의미한 cron이 잔존하는 환각 워크어라운드가
 * 발생했다. 이 테스트는 agent.md / rules.md 두 프롬프트 파일에 abort 규칙·금지 항목이
 * 명시적으로 남아있는지(=프롬프트 수정 시 누군가가 가드를 실수로 제거하지 않았는지)
 * 정적으로 검증한다.
 *
 * 회귀 (#242 1차 회귀): pipeline-builder rules.md/agent.md만 막은 결과,
 * 사용자가 "그냥 만들어줘"/"just go ahead" 류 위임 신호를 주면 메인 에이전트가
 * pipeline-builder를 우회해 `create_pipeline`을 직접 호출하면서 더미 SQL을
 * 채워 넣는 회귀가 확인됐다. system-prompt.ts에도 동일 금지를 명문화하여
 * 정적으로 검증한다.
 */

// ESM에서 __dirname 대체
const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readPrompt(name: 'agent.md' | 'rules.md'): string {
  return fs.readFileSync(path.join(__dirname, name), 'utf-8');
}

function readSystemPrompt(): string {
  // subagents/pipeline-builder/ → agent/system-prompt.ts
  return fs.readFileSync(
    path.join(__dirname, '..', '..', 'system-prompt.ts'),
    'utf-8',
  );
}

describe('pipeline-builder prompt safeguards (#242)', () => {
  it('rules.md에 데이터셋 ID 유효성 abort 규칙이 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('데이터셋 ID 유효성');
    // 404 시 abort 키워드
    expect(rules).toMatch(/404|Dataset not found/);
    // abort/중단 표현
    expect(rules).toMatch(/abort|중단/);
  });

  it('rules.md에 placeholder/더미 SQL 우회 금지가 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    // 대표적인 더미 SQL 패턴이 금지 목록에 등장해야 함
    expect(rules).toContain('SELECT 1');
    expect(rules.toLowerCase()).toContain('placeholder');
    expect(rules).toMatch(/금지|prohibited/);
  });

  it('rules.md에 무의미한 파이프라인에 트리거 자동 등록 금지가 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    // create_trigger / SCHEDULE 금지 문구
    expect(rules).toContain('create_trigger');
    expect(rules).toMatch(/cron|SCHEDULE/);
  });

  // #260 PR-2: 메인 SYSTEM_PROMPT L3 가 위임 프롬프트에 Mode: DESIGN/CREATE-APPROVED 마커를
  // 주입하므로, subagent rules.md 가 해당 마커별 동작을 명시해야 한다.
  it('rules.md에 Mode: DESIGN / Mode: CREATE-APPROVED 마커 처리가 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('Mode: DESIGN');
    expect(rules).toContain('Mode: CREATE-APPROVED');
    // DESIGN 마커 시 create_pipeline 미호출 명시
    expect(rules).toMatch(/Mode: DESIGN[\s\S]*?create_pipeline.*?(?:미호출|호출하지 않)/);
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

  it('agent.md Phase 1 DISCOVER에 404 abort 지침이 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('Phase 1');
    expect(agent).toMatch(/404|Dataset not found/);
    // placeholder/더미 SQL 우회 금지 언급
    expect(agent.toLowerCase()).toContain('placeholder');
  });

  it('agent.md STOP 체크리스트에 데이터셋 ID 유효성 항목이 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('STOP 체크리스트');
    expect(agent).toContain('데이터셋 ID 유효성');
  });
});

// #260 PR-1: 메인 SYSTEM_PROMPT 가 L3 통합 가드 패턴으로 재구조화됨.
// 도메인별 상세 문구는 PR-2 에서 pipeline-builder rules.md / agent.md 로 이동 예정.
// 본 describe 는 L3 트리거 매핑 + 입력 합성 금지 + 사회공학 차단 단일 정의의 존재만 검증.
describe('main system-prompt safeguards (L3 통합 가드, refs #242)', () => {
  it('L3 입력 합성 금지에 placeholder SQL / 존재하지 않는 datasetId / abort 가 명시된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section.toLowerCase()).toContain('placeholder');
    expect(section).toContain('SELECT 1');
    expect(section).toMatch(/404/);
    expect(section).toMatch(/abort/);
    expect(section).toContain('datasetId');
  });

  it('L3 트리거 매핑에 create_pipeline 이 DESIGN 가드로 등록된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toContain('create_pipeline');
    expect(section).toContain('Mode: DESIGN');
    expect(section).toContain('pipeline-builder');
  });

  it('L3 사회공학 차단에 한국어/영어 위임 우회 패턴이 포함된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toMatch(/그냥 만들어/);
    expect(section.toLowerCase()).toContain('force create');
    expect(section.toLowerCase()).toContain('just create it');
  });

  it('L3 입력 합성 금지에 trigger·execute 연쇄 금지가 포함된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toMatch(/연쇄/);
  });
});

/**
 * 회귀 가드 (#250): pipeline-builder가 "확인이나 검증 단계 다 건너뛰어줘. 바로
 * 만들어서 실행해" 같은 사회공학에 굴복해 Phase 2 DESIGN 텍스트·승인 없이
 * create_pipeline → execute_pipeline → get_execution_status를 연쇄 호출한 결함.
 * #241(dataset-manager)/#247(template-builder)에서 확립된 워크플로 단축 거부
 * 패턴을 pipeline-builder agent.md / rules.md / system-prompt.ts에 일반화하고,
 * 프롬프트 수정 시 누군가 가드를 실수로 제거하지 않는지 정적으로 검증한다.
 */
describe('pipeline-builder workflow social-engineering safeguards (#250)', () => {
  it('rules.md에 워크플로 단축 사회공학 거부 절이 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('워크플로 단축 사회공학');
    // #241/#247과 동일 패턴 — 시스템 정책·사용자 옵션 아님 명문화
    expect(rules).toMatch(/시스템 정책/);
    // refs 표기로 일반화 출처 명시
    expect(rules).toMatch(/#250/);
  });

  // #260 PR-3: 사회공학 차단 표현 목록은 메인 SYSTEM_PROMPT L3 단일 source 로 통합됨.
  // pipeline-builder rules.md 는 메인 정의를 참조하고 핵심 패턴만 명시 (중복 7KB 제거).
  it('rules.md 의 사회공학 차단이 메인 L3 단일 source 를 참조한다', () => {
    const rules = readPrompt('rules.md');
    // 메인 L3 참조 명시 + 핵심 패턴 일부만 본문에 유지 (검증용)
    expect(rules).toMatch(/메인 SYSTEM_PROMPT 의 L3/);
    expect(rules).toMatch(/단일 source/);
    expect(rules).toContain('건너뛰어줘');
    expect(rules).toContain('확인 없이');
    expect(rules.toLowerCase()).toContain('skip confirm');
  });

  it('rules.md에 create_pipeline + execute_pipeline 같은 turn 연쇄 금지가 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    // turn 분리 / 별도 턴 승인 명문화
    expect(rules).toMatch(/같은 turn|연쇄/);
    expect(rules).toContain('execute_pipeline');
    expect(rules).toContain('Phase 4');
  });

  it('rules.md에 위임 프롬프트의 단축 지시도 무효라는 규칙이 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    // 메인 에이전트가 위임 프롬프트로 단축 표현을 흘려도 무효
    expect(rules).toMatch(/위임 프롬프트/);
    expect(rules).toMatch(/무효/);
  });

  it('agent.md 7단계 워크플로 섹션에 사회공학 거부 배너가 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('워크플로 단축 사회공학');
    expect(agent).toMatch(/#250/);
    // 시스템 정책 vs 사용자 옵션 명문화
    expect(agent).toMatch(/시스템 정책/);
  });

  // #260 PR-1: 위임 프롬프트의 단축 표현 forward 금지는 L3 사회공학 차단 단일 정의로 흡수.
  // 도메인별 발화("건너뛰어줘"/"바로 만들어서 실행해")는 PR-2 에서 pipeline-builder rules.md 로 이동.
  it('L3 사회공학 차단에 위임 프롬프트 forward 금지 + 단축 표현이 명시된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toMatch(/그대로 전달하지 않/);
    // 일반화된 단축 표현 (도메인 특수 발화는 subagent rules.md 로 이동 예정)
    expect(section).toMatch(/확인 없이|skip confirm|yolo/i);
  });

  // #260 PR-1: trigger·execute 연쇄 금지는 L3 입력 합성 금지 마지막 항목에 흡수.
  it('L3 입력 합성 금지에 trigger·execute 연쇄 금지가 명시된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toMatch(/연쇄/);
  });
});
