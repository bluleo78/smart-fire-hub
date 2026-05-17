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
 */

// ESM에서 __dirname 대체
const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readPrompt(name: 'agent.md' | 'rules.md'): string {
  return fs.readFileSync(path.join(__dirname, name), 'utf-8');
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
