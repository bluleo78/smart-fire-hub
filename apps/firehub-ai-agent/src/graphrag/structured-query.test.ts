// structured-query 단위 테스트 — 순수 빌더(화이트리스트/파라미터 조립)와 실행 함수(Neo4j 세션 모킹)를 검증한다.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import neo4j from 'neo4j-driver';

// structuredQuery 실행 테스트용 Neo4j 세션 모킹 — vi.mock 은 호이스팅되므로 모듈 최상단에 둔다.
const runMock = vi.fn();
const closeMock = vi.fn().mockResolvedValue(undefined);

vi.mock('./neo4j-client.js', () => ({
  getSession: () => ({ run: runMock, close: closeMock }),
}));

import { buildStructuredCypher, structuredQuery } from './structured-query.js';
import { CORE_ONTOLOGY } from './ontology.js';

describe('buildStructuredCypher', () => {
  it('number gt 술어를 백틱·파라미터로 조립', () => {
    const r = buildStructuredCypher(CORE_ONTOLOGY, 'Incident',
      [{ property: '피해액', operator: 'gt', value: 100_000_000 }]);
    expect('cypher' in r).toBe(true);
    if ('cypher' in r) {
      expect(r.cypher).toContain('n.type = $entityType');
      expect(r.cypher).toContain('n.`피해액` > $p0');
      expect(r.params).toMatchObject({ entityType: 'Incident', p0: 100_000_000 });
    }
  });

  it('온톨로지 미정의 속성은 거부(화이트리스트)', () => {
    const r = buildStructuredCypher(CORE_ONTOLOGY, 'Incident',
      [{ property: '해킹', operator: 'gt', value: 1 }]);
    expect('error' in r).toBe(true);
  });

  it('미정의 엔티티 타입은 거부', () => {
    const r = buildStructuredCypher(CORE_ONTOLOGY, 'Nope', []);
    expect('error' in r).toBe(true);
  });

  it('contains 연산자는 CONTAINS 로 매핑', () => {
    const r = buildStructuredCypher(CORE_ONTOLOGY, 'Incident',
      [{ property: '피해액', operator: 'contains', value: '1억' }]);
    expect('cypher' in r).toBe(true);
    if ('cypher' in r) {
      expect(r.cypher).toContain('n.`피해액` CONTAINS $p0');
      expect(r.params.p0).toBe('1억');
    }
  });
});

describe('structuredQuery', () => {
  beforeEach(() => vi.clearAllMocks());

  it('빌더 결과 Cypher 를 실행하고 Neo4j Integer 속성을 number 로 변환', async () => {
    runMock.mockResolvedValue({
      records: [
        {
          get: (col: string) => {
            const row: Record<string, unknown> = {
              key: 'Incident::2024 서울 창고 화재',
              type: 'Incident',
              name: '2024 서울 창고 화재',
              props: {
                key: 'Incident::2024 서울 창고 화재',
                type: 'Incident',
                name: '2024 서울 창고 화재',
                sourceChunkIds: [1, 2],
                피해액: neo4j.int(120_000_000),
              },
              sourceChunkIds: [1, 2],
            };
            return row[col];
          },
        },
      ],
    });

    const result = await structuredQuery(CORE_ONTOLOGY, 'Incident',
      [{ property: '피해액', operator: 'gt', value: 100_000_000 }]);

    expect(runMock).toHaveBeenCalledTimes(1);
    const [cypher] = runMock.mock.calls[0];
    expect(cypher).toContain('n.`피해액` > $p0');

    expect(result.entities).toEqual([
      {
        key: 'Incident::2024 서울 창고 화재',
        type: 'Incident',
        name: '2024 서울 창고 화재',
        properties: { 피해액: 120_000_000 },
      },
    ]);
    expect(result.sourceChunkIds).toEqual([1, 2]);
    expect(result.truncated).toBe(false);
    expect(closeMock).toHaveBeenCalledTimes(1);
  });
});
