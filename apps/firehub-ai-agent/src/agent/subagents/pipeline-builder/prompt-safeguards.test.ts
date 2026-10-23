import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { loadSubagents, resetSubagentCache } from '../../subagent-loader.js';
import { TIBERO_SQL_GUIDE } from '../../tibero-sql-guide.js';
import {
  findPgOnlySyntax,
  hasTiberoCoreRules,
  removedNonPgLines,
} from '../../tibero-prompt-lint.fixture.js';

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
    expect(rules).toMatch(/404|데이터셋을 찾을 수 없습니다/);
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
    expect(agent).toMatch(/404|데이터셋을 찾을 수 없습니다/);
    // placeholder/더미 SQL 우회 금지 언급
    expect(agent.toLowerCase()).toContain('placeholder');
  });

  it('agent.md STOP 체크리스트에 데이터셋 ID 유효성 항목이 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('STOP 체크리스트');
    expect(agent).toContain('데이터셋 ID 유효성');
  });
});

/**
 * 회귀 가드 (#598): delete_pipeline 삭제 확인 시 pipeline-builder가 실제 연결된
 * 트리거를 조회할 도구(list_triggers)가 화이트리스트에 없어, 도구 설명의 고정 문구
 * ("연결된 체인 트리거도 비활성화됩니다")를 검증 없이 그대로 노출했다. 이 문구는
 * PIPELINE_CHAIN 트리거에만 사실이며, 파이프라인이 직접 소유한 트리거(SCHEDULE 등)는
 * DB FK ON DELETE CASCADE로 영구 삭제되는데도 "비활성화"로 잘못 서술됐다.
 *
 * 이 테스트는 (1) list_triggers 가 tools 화이트리스트에 있는지, (2) agent.md/rules.md 에
 * 삭제 전 트리거 조회 의무·정확한 문구(영구 삭제 vs 비활성화 구분)가 남아있는지 정적으로 검증한다.
 */
describe('pipeline-builder delete_pipeline trigger disclosure safeguards (#598)', () => {
  it('frontmatter tools 화이트리스트에 list_triggers 가 선언되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    const fm = agent.match(/^---\n([\s\S]*?)\n---/)?.[1] ?? '';
    expect(fm).toMatch(/mcp__firehub__list_triggers/);
  });

  it('agent.md에 파이프라인 삭제 워크플로 절이 있고 list_triggers 조회를 요구해야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('파이프라인 삭제 워크플로');
    expect(agent).toMatch(/list_triggers\(pipelineId=id\)/);
    // 영구 삭제 vs 비활성화 구분
    expect(agent).toMatch(/영구 삭제/);
    expect(agent).toMatch(/CASCADE/);
  });

  it('agent.md/rules.md가 고정 문구를 그대로 인용하지 말라고 명시해야 한다', () => {
    const agent = readPrompt('agent.md');
    const rules = readPrompt('rules.md');
    expect(agent).toMatch(/고정 문구/);
    expect(rules).toMatch(/고정 문자열|고정 문구/);
    // "비활성화"가 자신 소유 트리거에는 오답이라는 구분 명시
    expect(rules).toMatch(/비활성화/);
    expect(rules).toMatch(/영구 삭제/);
  });

  it('rules.md에 delete_pipeline 전 list_triggers 호출 의무가 명시되어 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toMatch(/list_triggers\(pipelineId\)/);
    expect(rules).toContain('#598');
  });

  it('examples.md에 트리거 조회 후 정확한 영향 고지 예시가 있어야 한다', () => {
    const examples = fs.readFileSync(path.join(__dirname, 'examples.md'), 'utf-8');
    expect(examples).toMatch(/list_triggers\(pipelineId=47\)/);
    expect(examples).toMatch(/영구 삭제/);
  });

  it('메인 system-prompt.ts L3 트리거 매핑에 delete_pipeline 전용 list_triggers 의무가 명시되어야 한다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toMatch(/delete_pipeline.*#598/s);
    expect(section).toMatch(/list_triggers\(pipelineId\)/);
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

  /**
   * #682 (2026-09-17 운영 사고): `permission denied for schema data_t2` 를 "스키마명이 틀렸다"로
   * 오독해, 올바르던 `data_t2.` 한정자를 떼어낸 SQL 로 `update_pipeline` 을 호출했다. 서버
   * 검증기가 400 으로 막아 저장되지는 않았지만, 사용자에게는 "파이프라인 수정 실패"로 보였다.
   * 이 테스트는 두 오류 계열을 정반대로 다루라는 규칙이 프롬프트에서 사라지지 않는지 고정한다.
   */
  it('rules.md에 permission denied 오류를 SQL 수정으로 대응하지 말라는 규칙이 있어야 한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('permission denied for schema');
    expect(rules).toMatch(/#682/);
    // 권한 오류 = SQL 문제가 아니라는 판정
    expect(rules).toMatch(/스키마 이름이 틀렸다는 뜻이 아니다/);
    // 한정자 제거 금지 — 이번 사고의 실제 행동
    expect(rules).toMatch(/한정자[\s\S]{0,20}제거/);
    // 중단·보고로 끝내라는 지시
    expect(rules).toMatch(/중단하고|운영자/);
  });

  it('rules.md가 SQL을 실제로 고쳐야 하는 스키마 오류를 권한 오류와 구분한다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).toContain('허용되지 않는 스키마 참조');
    expect(rules).toContain('테이블 참조에 스키마가 없습니다');
    // 이쪽 메시지는 올바른 스키마명을 담고 있으므로 그대로 쓰라는 지시
    expect(rules).toMatch(/메시지에 적힌 이름을 그대로/);
  });

  /**
   * 워크스페이스마다 스키마명이 다르므로(`data` / `data_t{id}`), 프롬프트가 특정 스키마명을
   * "고정값"으로 단정하면 그 자체가 #682 계열 오독의 원인이 된다.
   */
  it('rules.md가 실행 계정 스키마를 특정 이름으로 단정하지 않는다', () => {
    const rules = readPrompt('rules.md');
    expect(rules).not.toMatch(/실행 계정은 `data` 스키마로 제한/);
  });
});

/**
 * Tibero 테넌트용 pipeline-builder 사본(tibero/*.md) 가드 (WD-79).
 *
 * Tibero 테넌트는 SQL 단계만 실행된다 — api(PipelineAsyncRunner)가 Python·API 수집·AI 분류·증분 SQL 단계를
 * 거부한다. PG 안내(Python 단계 + psycopg2/DB_URL, data."table" 표기, permission denied for schema 해석)를 그대로
 * 주면 실행되지 않는 파이프라인을 설계한다.
 */
describe('pipeline-builder Tibero 사본 (WD-79)', () => {
  const readTibero = (name: string) => fs.readFileSync(path.join(__dirname, 'tibero', name), 'utf-8');
  const FILES = ['agent.md', 'rules.md', 'step-types.md', 'examples.md'] as const;
  // 원본에서 지우거나 바꿔도 되는 PG 전용 줄 표지(수정 1차: temp/임시 데이터셋 자동 생성 — Tibero 는 출력 데이터셋 필수)
  const PG_MARKER =
    /::|DATE_TRUNC|INTERVAL|NOW\(\)|LIMIT|ST_|psycopg2|DB_URL|DB_SCHEMA|data\.|data_t|PostgreSQL|BIGSERIAL|SQLState|permission denied|스키마|GEOMETRY|"[a-z_]+"|Python|PYTHON|python|temp|TEMP|임시 데이터셋/;

  it.each(FILES)('tibero/%s 에 PG 전용 문법(psycopg2 포함)과 data."테이블" 표기가 없다', (name) => {
    const text = readTibero(name);
    expect(findPgOnlySyntax(text)).toEqual([]);
    expect(text).not.toMatch(/data\."|data_t\d/);
    expect(text).not.toContain('DB_URL');
  });

  it.each(FILES)('tibero/%s 는 PG 전용 줄 외에는 원본 줄을 그대로 지닌다(안전 가드 누락 방지)', (name) => {
    const original = fs.readFileSync(path.join(__dirname, name), 'utf-8');
    expect(removedNonPgLines(original, readTibero(name), PG_MARKER)).toEqual([]);
  });

  it('tibero/rules.md 가 api 거부 문구를 그대로 인용해 Python·API 수집·AI 분류·증분 단계 대신 SQL 단계로 안내한다', () => {
    const rules = readTibero('rules.md');
    expect(rules).toContain('Tibero 저장소 테넌트에서는 아직 Python 단계를 실행할 수 없습니다.');
    expect(rules).toContain('Tibero 저장소 테넌트에서는 아직 API 수집 단계를 실행할 수 없습니다.');
    expect(rules).toContain('Tibero 저장소 테넌트에서는 아직 AI 분류 단계를 실행할 수 없습니다.');
    expect(rules).toContain('Tibero 저장소 테넌트에서는 아직 증분 SQL 단계를 실행할 수 없습니다.');
    expect(rules).toMatch(/SQL 단계로/);
  });

  // 수정 1차: api 는 Tibero SQL 단계에 outputDatasetId 가 없으면 항상 거부한다(PipelineAsyncRunner
  // TIBERO_OUTPUTLESS_SELECT_UNSUPPORTED). 사본이 "미지정 → temp 자동 생성" 을 가르치면 실행이 항상 실패한다.
  it.each(FILES)('tibero/%s 는 임시(temp) 데이터셋 자동 생성을 안내하지 않는다', (name) => {
    const text = readTibero(name);
    expect(text).not.toMatch(/(temp|TEMP|임시 데이터셋|임시 테이블)[^\n]{0,20}자동\s*생성/);
    expect(text).not.toMatch(/미지정[^\n]{0,30}자동\s*생성/);
    expect(text).not.toMatch(/temp 테이블 참조|temp 자동/);
  });

  it('tibero/rules.md 거부 표에 출력 데이터셋 없는 SQL 단계 문구가 있고, 모든 SQL 단계에 outputDatasetId 를 지정하라고 한다', () => {
    const rules = readTibero('rules.md');
    expect(rules).toContain('Tibero 저장소 테넌트의 SQL 단계는 출력 데이터셋을 지정해야 합니다.');
    expect(rules).toContain('Tibero SQL 단계는 반드시 outputDatasetId 를 지정한다');
    expect(readTibero('agent.md')).toContain('Tibero SQL 단계는 반드시 outputDatasetId 를 지정한다');
    expect(readTibero('step-types.md')).toContain('Tibero SQL 단계는 반드시 outputDatasetId 를 지정한다');
  });

  it('tibero/examples.md 의 SQL 스텝 JSON 은 모두 outputDatasetId 를 가진다', () => {
    const examples = readTibero('examples.md');
    // ```json 블록마다 "scriptType": "SQL" 스텝 객체를 잘라 outputDatasetId 가 있는지 본다
    const blocks = [...examples.matchAll(/```json\n([\s\S]*?)```/g)].map((m) => m[1]);
    const sqlSteps = blocks.flatMap((b) => b.split(/\n\s*\},?\s*\n\s*\{/).filter((s) => s.includes('"scriptType": "SQL"')));
    expect(sqlSteps.length).toBeGreaterThan(2);
    for (const s of sqlSteps) expect(s, s).toContain('"outputDatasetId"');
  });

  it('tibero/rules.md 는 공용 TIBERO_SQL_GUIDE 를 그대로 옮겨 적는다', () => {
    expect(TIBERO_SQL_GUIDE.length).toBeGreaterThan(0);
    expect(readTibero('rules.md')).toContain(TIBERO_SQL_GUIDE.trim());
  });

  it('tibero/rules.md 는 테이블 없음(-8033)을 스키마 한정자를 지울 근거로 삼지 말라고 한다(#682 의 Tibero 판)', () => {
    const rules = readTibero('rules.md');
    expect(rules).toContain('JDBC-8033');
    expect(rules).toMatch(/한정자를 (지우지|떼지) 않는다/);
  });

  it('tibero 사본에도 #242 placeholder SQL 금지·abort 규칙이 남아 있다', () => {
    const rules = readTibero('rules.md');
    const agent = readTibero('agent.md');
    expect(rules).toMatch(/placeholder/i);
    expect(agent).toMatch(/placeholder/i);
  });

  it('로더가 TIBERO 에서 사본을 쓴다 — SQL 핵심 규칙과 Python 거부 안내를 담는다', () => {
    resetSubagentCache();
    const tibero = loadSubagents(undefined, 'TIBERO')['pipeline-builder'];
    expect(hasTiberoCoreRules(tibero.prompt)).toBe(true);
    expect(findPgOnlySyntax(tibero.prompt)).toEqual([]);
    expect(tibero.prompt).toContain('Tibero 저장소 테넌트에서는 아직 Python 단계를 실행할 수 없습니다.');
    resetSubagentCache();
  });
});
