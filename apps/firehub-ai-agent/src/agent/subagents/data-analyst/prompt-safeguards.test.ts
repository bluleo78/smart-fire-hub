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
 * data-analyst 프롬프트 회귀 가드.
 *
 * 배경: data-analyst가 직원 명단 등 고정 참고 데이터를 SQL VALUES 절에 인라인하여
 * 매 쿼리·재시도마다 동일 페이로드가 tool_use/tool_result에 echo되는 토큰 낭비 패턴이
 * 발견됐다. rules.md에 "10행 이상 참고 데이터는 VALUES 인라인 금지, 데이터셋 JOIN 유도"
 * 규칙이 남아있는지(=프롬프트 수정 시 가드가 실수로 제거되지 않았는지) 정적으로 검증한다.
 */

// ESM에서 __dirname 대체
const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readRules(): string {
  return fs.readFileSync(path.join(__dirname, 'rules.md'), 'utf-8');
}

/** Tibero 테넌트용 사본(tibero/<파일명>) — WD-79. */
function readTibero(name: string): string {
  return fs.readFileSync(path.join(__dirname, 'tibero', name), 'utf-8');
}

function readAnalyticsTools(): string {
  // data-analyst/ → subagents → agent → src, 그 후 mcp/tools/analytics-tools.ts
  return fs.readFileSync(
    path.join(__dirname, '..', '..', '..', 'mcp', 'tools', 'analytics-tools.ts'),
    'utf-8',
  );
}

describe('data-analyst prompt safeguards (#273)', () => {
  it('rules.md에 10행 이상 참고 데이터 VALUES 인라인 금지 규칙이 있어야 한다', () => {
    const rules = readRules();
    expect(rules).toContain('참고 데이터');
    expect(rules).toContain('VALUES');
    expect(rules).toContain('인라인');
    expect(rules).toMatch(/10\s*행\s*이상/);
  });

  it('rules.md가 데이터셋 JOIN 유도 + 생성은 dataset-manager 안내를 명시해야 한다', () => {
    const rules = readRules();
    expect(rules).toMatch(/JOIN/);
    expect(rules).toContain('dataset-manager');
    expect(rules).toMatch(/권장|안내/);
  });

  it('rules.md에 소량(10행 미만) 일회성 인라인 허용 예외가 있어야 한다', () => {
    const rules = readRules();
    expect(rules).toMatch(/10\s*행\s*미만/);
    expect(rules).toContain('허용');
  });

  it('Tibero 사본 rules.md 에도 참고 데이터 VALUES 인라인 금지·소량 예외가 남아 있다', () => {
    const rules = readTibero('rules.md');
    expect(rules).toMatch(/10\s*행\s*이상/);
    expect(rules).toMatch(/10\s*행\s*미만/);
    expect(rules).toContain('VALUES');
  });

  it('execute_analytics_query description에 참고 데이터 JOIN 가이드가 있어야 한다', () => {
    const src = readAnalyticsTools();
    // tool description은 모델 프롬프트에 노출됨 — 동일 임계값(10행) + 데이터셋 JOIN 유도
    expect(src).toMatch(/10행 이상의 고정 참고 데이터/);
    expect(src).toMatch(/데이터셋으로 만들어 JOIN/);
  });
});

/**
 * Tibero 테넌트용 data-analyst 사본(tibero/*.md) 가드 (WD-79).
 *
 * 분석·집계는 사용자가 AI 로 가장 먼저·자주 하는 일이라 data-analyst 가 Tibero 문법을 가장 정확히 알아야 한다.
 * examples.md 는 기본 로드에서 빠지지만 FIREHUB_SUBAGENT_INCLUDE_EXAMPLES=1 이면 들어가므로 파일을 직접 읽어 검사한다.
 */
describe('data-analyst Tibero 사본 (WD-79)', () => {
  const FILES = ['agent.md', 'rules.md', 'examples.md'] as const;
  // 원본에서 지우거나 바꿔도 되는 PG 전용 줄 표지(PG 진단 라벨 HINT/Position 포함)
  const PG_MARKER =
    /::|DATE_TRUNC|INTERVAL|NOW\(\)|LIMIT|ST_|SQLState|HINT|Position|42703|42P01|42601|PostgreSQL|search_dataset_rows|큰따옴표|"[a-z_]+"/;

  it.each(FILES)('tibero/%s 에 PG 전용 문법·행 검색 도구·SQLState 규칙이 없다', (name) => {
    const text = readTibero(name);
    expect(findPgOnlySyntax(text)).toEqual([]);
    expect(text).not.toContain('search_dataset_rows');
    expect(text).not.toMatch(/SQLState|42703|42P01|42601/);
  });

  it.each(FILES)('tibero/%s 는 PG 전용 줄 외에는 원본 줄을 그대로 지닌다(안전 가드 누락 방지)', (name) => {
    const original = fs.readFileSync(path.join(__dirname, name), 'utf-8');
    expect(removedNonPgLines(original, readTibero(name), PG_MARKER)).toEqual([]);
  });

  // 수정 1차(Minor): 컬럼 이름 오류의 자기 교정 안내 — Tibero 코드는 확인 목록에 없으므로 코드 없이 쓴다.
  it.each(['agent.md', 'rules.md'])('tibero/%s 오류 절에 컬럼 오류 자기 교정 안내가 있다', (name) => {
    expect(readTibero(name)).toContain('컬럼 오류 원문 → Phase 1 컬럼 목록과 다시 대조해 고친다');
  });

  it('tibero/rules.md 는 공용 TIBERO_SQL_GUIDE 를 그대로 옮겨 적는다(메인 프롬프트와 같은 규칙)', () => {
    expect(TIBERO_SQL_GUIDE.length).toBeGreaterThan(0);
    expect(readTibero('rules.md')).toContain(TIBERO_SQL_GUIDE.trim());
  });

  it('SQL 예시는 따옴표 없는 genia_t{id} 한정 이름을 쓴다(따옴표 소문자는 Tibero 에서 다른 이름)', () => {
    for (const name of ['rules.md', 'examples.md']) {
      const text = readTibero(name);
      expect(text, name).toContain('genia_t');
      expect(text, name).not.toMatch(/FROM\s+"|JOIN\s+"/);
    }
  });

  it('로더가 TIBERO 에서 사본을 쓴다 — 핵심 규칙 포함, 행 검색 도구는 tools 에서도 빠진다', () => {
    resetSubagentCache();
    const tibero = loadSubagents(undefined, 'TIBERO')['data-analyst'];
    expect(hasTiberoCoreRules(tibero.prompt)).toBe(true);
    expect(findPgOnlySyntax(tibero.prompt)).toEqual([]);
    expect(tibero.prompt).not.toContain('search_dataset_rows');
    expect(tibero.tools).not.toContain('mcp__firehub__search_dataset_rows');
    expect(tibero.tools).toContain('mcp__firehub__execute_analytics_query');

    const pg = loadSubagents(undefined, 'POSTGRESQL')['data-analyst'];
    expect(pg.tools).toContain('mcp__firehub__search_dataset_rows');
    resetSubagentCache();
  });
});
