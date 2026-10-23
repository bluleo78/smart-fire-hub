/**
 * 도구 설명의 방언별 분기 테스트 (WD-79).
 * PG 설명은 변경 전 코드에서 덤프한 픽스처와 바이트 단위로 같아야 하고, Tibero 설명에는 PG 표현이 없어야 한다.
 */
import { describe, it, expect, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { z } from 'zod/v4';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { registerAllTools, jsonResult, TIBERO_UNSUPPORTED_TOOLS } from './firehub-mcp-server.js';
import { FireHubApiClient } from './api-client.js';
import type { DbDialect } from '../agent/db-dialect.js';

type Dump = Record<string, { description: string; schema: unknown }>;

/** 등록되는 모든 도구의 설명과 입력 필드 설명을 수집한다(등록 호출 자체를 가로챈다) */
function dump(dialect?: DbDialect): Dump {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const client: any = Object.create(FireHubApiClient.prototype);
  for (const n of Object.getOwnPropertyNames(FireHubApiClient.prototype)) {
    if (n !== 'constructor') client[n] = vi.fn();
  }
  client.withPurpose = vi.fn(() => client);
  const out: Dump = {};
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const safe: any = (name: string, description: string, schema: any) => {
    // 중첩 객체(pipeline steps 등)의 필드 설명까지 비교하도록 JSON Schema 로 직렬화한다.
    out[name] = { description, schema: z.toJSONSchema(z.object(schema ?? {}), { unrepresentable: 'any' }) };
    return { name, description, inputSchema: schema, handler: async () => ({}) };
  };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  registerAllTools(client, safe, jsonResult as any, dialect ? { dbDialect: dialect } : {});
  return Object.fromEntries(Object.entries(out).sort(([a], [b]) => a.localeCompare(b)));
}

const fixture: Dump = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), '__fixtures__/pg-tool-descriptions.json'), 'utf8'),
);

describe('PG 도구 설명 고정', () => {
  it('옵션 없음 = 변경 전 픽스처와 동일', () => {
    expect(dump()).toEqual(fixture);
  });
  it("dbDialect 'POSTGRESQL' 도 동일", () => {
    expect(dump('POSTGRESQL')).toEqual(fixture);
  });
});

describe('Tibero 도구 설명', () => {
  const t = dump('TIBERO');
  const text = (n: string) => JSON.stringify(t[n]);

  it.each(['execute_analytics_query', 'execute_sql_query', 'create_dataset'])(
    '%s 에는 PostgreSQL·data." 가 없다',
    (n) => {
      expect(text(n)).not.toContain('PostgreSQL');
      expect(text(n)).not.toContain('data.\\"');
    },
  );
  it('테이블 참조는 genia_t 형식', () => {
    expect(text('execute_analytics_query')).toContain('genia_t');
    expect(text('execute_sql_query')).toContain('genia_t');
    expect(text('create_dataset')).toContain('genia_t');
  });
  it('파이프라인 scriptContent 설명 두 곳도 Tibero 표현', () => {
    expect(text('create_pipeline')).toContain('genia_t');
    expect(text('create_pipeline')).not.toContain('data.\\"');
    expect(text('update_pipeline')).toContain('genia_t');
    expect(text('update_pipeline')).not.toContain('data.\\"');
  });
  it('컬럼·테이블 이름 길이 안내는 Tibero 기준(30자)이며 63자가 남지 않는다', () => {
    for (const n of ['create_dataset', 'add_dataset_column', 'create_pipeline', 'update_pipeline']) {
      expect(text(n), n).not.toContain('63자');
      expect(text(n), n).toContain('30자');
    }
  });
  // WD-79 수정 1차: api(PipelineAsyncRunner)는 Tibero SQL 단계에 출력 데이터셋이 없으면 실행을 거부한다 —
  // "미지정 시 임시 데이터셋 자동 생성" 안내가 남아 있으면 모델이 항상 실패하는 파이프라인을 만든다.
  it('파이프라인 outputDatasetId 설명은 Tibero 의 출력 데이터셋 필수 규칙이며 임시 데이터셋 자동 생성 안내가 없다', () => {
    for (const n of ['create_pipeline', 'update_pipeline']) {
      expect(text(n), n).not.toMatch(/임시 데이터셋[^"]{0,6}자동 생성|자동 생성됨/);
      expect(text(n), n).toContain('Tibero 저장소 테넌트의 SQL 단계는 출력 데이터셋을 지정해야 합니다.');
    }
  });
  // Tibero 저장소는 GEOMETRY 타입을 지원하지 않는다(웹도 같은 이유로 숨긴다) — 열거값·SRID 안내가 남으면 모델이 항상 실패하는 컬럼을 만든다.
  it('create_dataset·add_dataset_column 에는 GEOMETRY 타입과 SRID 안내가 없다', () => {
    for (const n of ['create_dataset', 'add_dataset_column']) {
      expect(text(n), n).not.toContain('GEOMETRY');
      expect(text(n), n).not.toContain('SRID');
    }
  });
  // pythonConfig.outputColumns 는 Python 단계 설정이다 — Tibero 안내는 AI 분류가 아니라 Python 단계 미지원(api 거부 문구)이어야 한다.
  it('파이프라인 pythonConfig.outputColumns 설명은 Python 단계 미지원을 알린다', () => {
    for (const n of ['create_pipeline', 'update_pipeline']) {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const schema = t[n].schema as any;
      const desc: string = schema.properties.steps.items.properties.pythonConfig.properties.outputColumns.description;
      expect(desc, n).toContain('Python 단계를 실행할 수 없');
      expect(desc, n).not.toContain('AI 분류');
    }
  });
  it('create_dataset 은 테이블이 놓이는 스키마를 알려준다', () => {
    expect(t.create_dataset.description).toContain('genia_t{id}');
  });
  it('바뀌는 도구 외에는 PG 와 같다', () => {
    const changed = new Set([
      'execute_analytics_query', 'execute_sql_query', 'create_dataset', 'add_dataset_column', 'create_pipeline', 'update_pipeline',
    ]);
    for (const [n, v] of Object.entries(fixture)) {
      if (changed.has(n) || TIBERO_UNSUPPORTED_TOOLS.has(n)) continue;
      expect(t[n], n).toEqual(v);
    }
  });
});
