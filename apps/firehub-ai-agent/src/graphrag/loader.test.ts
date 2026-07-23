// loader 단위 테스트 — Neo4j 세션을 모킹해 MERGE 쿼리·파라미터 형태를 검증한다.
// 실제 DB 대상 멱등성 검증은 loader.integration.test.ts(별도) 담당.
import { describe, it, expect, vi, beforeEach } from 'vitest';

const runMock = vi.fn().mockResolvedValue({ records: [] });
const closeMock = vi.fn().mockResolvedValue(undefined);

vi.mock('./neo4j-client.js', () => ({
  getSession: () => ({ run: runMock, close: closeMock }),
}));

import { loadGraph } from './loader.js';
import { entityKey } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

// 5-6: entityKey는 typeId(entity_type_id) 기반 — CORE_ONTOLOGY의 고정 id를 조회해 사용한다.
const incidentId = entityTypeId(CORE_ONTOLOGY, 'Incident');
const causeId = entityTypeId(CORE_ONTOLOGY, 'Cause');

describe('loadGraph', () => {
  beforeEach(() => vi.clearAllMocks());

  it('노드에 속성 맵을 SET n += 로 저장', async () => {
    const graph = {
      entities: [
        { key: entityKey(incidentId, '2024 서울 창고 화재'), type: 'Incident' as const, name: '2024 서울 창고 화재', properties: { 피해액: 120_000_000 } },
      ],
      relations: [],
    };
    await loadGraph(graph, 1, 3);

    // 첫 run 호출 = 노드 MERGE. Cypher에 속성 병합(coalesce)이 포함되어야 한다.
    const [nodeCypher, nodeParams] = runMock.mock.calls[0];
    expect(nodeCypher).toContain('SET n += coalesce(e.properties, {})');
    expect(nodeParams.entities[0].properties).toEqual({ 피해액: 120_000_000 });
  });

  // 5-2: 속성 CRUD로 예약어(key/type/name/sourceChunkIds/schemaVersion)와 겹치는 속성명이 편집 시점
  // 검증을 우회해 들어와도, 적재 직전 sanitizeProperties가 걸러내 노드 정체성 필드를 덮어쓰지 않아야 한다.
  it('예약 노드 필드와 겹치는 속성명은 Cypher params에서 제거된다', async () => {
    const graph = {
      entities: [
        {
          key: entityKey(incidentId, '2024 서울 창고 화재'),
          type: 'Incident' as const,
          name: '2024 서울 창고 화재',
          properties: {
            피해액: 120_000_000,
            type: '가짜타입',
            name: '가짜이름',
            sourceChunkIds: '깨진값',
            schemaVersion: 999,
          },
        },
      ],
      relations: [],
    };
    await loadGraph(graph, 1, 3);

    const [, nodeParams] = runMock.mock.calls[0];
    expect(nodeParams.entities[0].properties).toEqual({ 피해액: 120_000_000 });
  });

  // 5-4: 노드/관계 MERGE 모두 적재 당시 온톨로지 schema_version을 파라미터로 받아 스탬프한다.
  it('노드와 관계 MERGE 모두 schemaVersion을 Cypher params로 전달한다', async () => {
    const graph = {
      entities: [
        { key: entityKey(incidentId, '사건'), type: 'Incident' as const, name: '사건' },
        { key: entityKey(causeId, '원인'), type: 'Cause' as const, name: '원인' },
      ],
      relations: [
        { subjectKey: entityKey(incidentId, '사건'), type: 'CAUSED_BY', objectKey: entityKey(causeId, '원인') },
      ],
    };
    await loadGraph(graph, 1, 5);

    const [nodeCypher, nodeParams] = runMock.mock.calls[0];
    const [relCypher, relParams] = runMock.mock.calls[1];
    expect(nodeCypher).toContain('n.schemaVersion = $schemaVersion');
    expect(nodeParams.schemaVersion).toBe(5);
    expect(relCypher).toContain('x.schemaVersion = $schemaVersion');
    expect(relParams.schemaVersion).toBe(5);
  });
});
