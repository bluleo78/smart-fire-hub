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

/** PG 원본(<파일명>) — Tibero 사본과 짝으로 읽는다. */
function readPg(name: string): string {
  return fs.readFileSync(path.join(__dirname, name), 'utf-8');
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
  // 원본에서 지우거나 바꿔도 되는 PG 전용 줄 표지(PG 진단 라벨 HINT/Position 포함).
  // ILIKE(WD-759): 0건 재조회 SQL 예시 줄은 PG 는 ILIKE, Tibero 사본은 UPPER … LIKE 로 바꿔 둔다.
  const PG_MARKER =
    /::|DATE_TRUNC|INTERVAL|NOW\(\)|LIMIT|ILIKE|ST_|SQLState|HINT|Position|42703|42P01|42601|PostgreSQL|search_dataset_rows|큰따옴표|"[a-z_]+"/;

  it.each(FILES)('tibero/%s 에 PG 전용 문법·행 검색 도구·SQLState 규칙이 없다', (name) => {
    const text = readTibero(name);
    expect(findPgOnlySyntax(text)).toEqual([]);
    // WD-744: 행 검색 도구는 tools(frontmatter)에만 둔다 — 꺼진 조합에서는 로더가 빼고, 본문에 이름이 없어야
    // 숨겨진 테넌트가 없는 도구를 부르지 않는다. 노출될 때의 안내는 메인 프롬프트 행 검색 줄이 맡는다.
    expect(text.replace(/^---\n[\s\S]*?\n---\n/, '')).not.toContain('search_dataset_rows');
    expect(text).not.toMatch(/SQLState|42703|42P01|42601/);
  });

  it.each(FILES)('tibero/%s 는 PG 전용 줄 외에는 원본 줄을 그대로 지닌다(안전 가드 누락 방지)', (name) => {
    expect(removedNonPgLines(readPg(name), readTibero(name), PG_MARKER)).toEqual([]);
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

  // WD-744: 행 검색 도구는 방언이 아니라 「행 검색 가능」 플래그로 가른다.
  it('로더의 행 검색 도구는 플래그를 따른다 — Tibero·켬이면 넣고, PG·끔이면 뺀다', () => {
    resetSubagentCache();
    const tiberoOn = loadSubagents(undefined, 'TIBERO', true)['data-analyst'];
    expect(tiberoOn.tools).toContain('mcp__firehub__search_dataset_rows');
    expect(tiberoOn.prompt).not.toContain('search_dataset_rows');
    const pgOff = loadSubagents(undefined, 'POSTGRESQL', false)['data-analyst'];
    expect(pgOff.tools).not.toContain('mcp__firehub__search_dataset_rows');
    expect(pgOff.tools).toContain('mcp__firehub__execute_analytics_query');
    // 끈 결과가 기본 캐시를 오염시키지 않는다.
    expect(loadSubagents(undefined, 'POSTGRESQL')['data-analyst'].tools).toContain(
      'mcp__firehub__search_dataset_rows',
    );
    resetSubagentCache();
  });
});

/**
 * 사실 질문은 등록된 데이터 먼저(WD-759) — data-analyst 쪽.
 *
 * data-analyst 는 텍스트 필터 대부분을 실행하고 메인은 그 결과를 그대로 relay 한다(#572). 메인 프롬프트에만 규칙을 두면
 * "띄어쓰기만 다른 표기로 0건 → 없다고 단정" 실패가 위임 경로로 재발하므로, 웹 순서·0건 재조회(같은 대상 확인)·무조회 단정
 * 금지를 PG 원본과 Tibero 사본 모두에 둔다. Tibero 사본에 ILIKE 가 없는지는 위 'data-analyst Tibero 사본' 검사가 본다.
 */
describe('data-analyst 사실 질문 규칙 (WD-759)', () => {
  const VARIANTS = [
    ['PG', readPg, `\`WHERE REPLACE(col, ' ', '') ILIKE '%Alpha5%'\``, `\`WHERE col ILIKE '%Alpha%'\``],
    [
      'Tibero',
      readTibero,
      `\`WHERE UPPER(REPLACE(col, ' ', '')) LIKE UPPER('%Alpha5%')\``,
      `\`WHERE UPPER(col) LIKE UPPER('%Alpha%')\``,
    ],
  ] as const;

  it.each(VARIANTS)('%s: WebSearch 원칙 한 줄이 참조용·사실 답은 마지막 수단·외부 전달 금지를 함께 말한다', (_n, read) => {
    const line = read('agent.md')
      .split('\n')
      .find((l) => l.includes('**WebSearch**'));
    expect(line).toBeDefined();
    expect(line).toContain('SQL 패턴·통계 기법 참조용이다');
    expect(line).toContain('사실 질문의 답은 웹보다 등록된 데이터셋이 먼저다');
    expect(line).toContain('마지막 수단으로 웹을 쓰고');
    expect(line).toContain('"등록된 데이터에서는 찾지 못해 웹에서 찾았습니다"');
    expect(line).toContain('쿼리 결과 데이터는 외부로 전달하지 않는다');
    // 옛 "참조 목적만" 문구가 남으면 새 규칙과 부딪힌다
    expect(line).not.toContain('참조 목적만');
  });

  it.each(VARIANTS)('%s: 0건 재조회(방언별 SQL·같은 대상 확인)와 무조회 단정 금지가 있다', (_n, read, compact, word) => {
    const md = read('agent.md');
    expect(md).toContain('**텍스트 조건이 0건이면 "없다" 고 결론 내기 전에 다시 조회한다 (WD-759)**');
    expect(md).toContain(`① 공백을 빼고 대소문자를 무시해 비교한다: ${compact}`);
    expect(md).toContain(`② 그래도 0건이면 특징 단어 하나로 넓힌다: ${word}`);
    // 넓힌 조회가 이웃 대상(Alpha 3·Alpha 7)을 돌려줄 때 그 값으로 답하지 않고, 찾지 못했다고 알린다
    expect(md).toContain('②로 찾은 행이 사용자가 물은 **바로 그 대상인지** 확인한다');
    expect(md).toContain('물은 대상은 등록된 데이터에서 찾지 못했다고 말한다(비슷한 항목은 후보로만 알린다)');
    expect(md).toContain('**조회 없이 데이터 내용의 있음/없음을 단정하지 않는다 (WD-759)**');
  });
});
