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

describe('main system-prompt safeguards (#242 회귀)', () => {
  it('system-prompt.ts에 메인 에이전트 직접 호출 시 데이터셋 ID 유효성 검증 의무가 명시되어 있어야 한다', () => {
    const sp = readSystemPrompt();
    expect(sp).toContain('데이터셋 ID 유효성');
    expect(sp).toContain('메인 에이전트 직접 호출 금지');
    // 404 시 즉시 abort
    expect(sp).toMatch(/404|Dataset not found/);
    expect(sp).toMatch(/abort|중단/);
  });

  it('system-prompt.ts에 placeholder/더미 SQL 자동 생성 금지가 명시되어 있어야 한다', () => {
    const sp = readSystemPrompt();
    expect(sp).toContain('SELECT 1');
    expect(sp.toLowerCase()).toContain('placeholder');
    expect(sp).toMatch(/금지/);
  });

  it('system-prompt.ts에 pipeline-builder 거부 우회 금지가 명시되어 있어야 한다', () => {
    const sp = readSystemPrompt();
    // 서브에이전트 거부를 메인이 직접 호출로 우회하는 회귀 패턴 금지
    expect(sp).toContain('위임 거부 우회 금지');
  });

  it('system-prompt.ts에 한국어/영어 위임 신호 우회 패턴이 금지 목록에 있어야 한다', () => {
    const sp = readSystemPrompt();
    // 한국어
    expect(sp).toContain('그냥 만들어줘');
    // 영어
    expect(sp.toLowerCase()).toContain('just go ahead');
  });

  it('system-prompt.ts에 트리거·실행 동반 호출 금지가 명시되어 있어야 한다', () => {
    const sp = readSystemPrompt();
    expect(sp).toContain('create_trigger');
    expect(sp).toContain('execute_pipeline');
    expect(sp).toMatch(/SCHEDULE|cron/);
  });
});
