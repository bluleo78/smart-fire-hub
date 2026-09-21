// loader 단위 테스트 — Neo4j 세션을 모킹해 MERGE 쿼리·파라미터 형태를 검증한다.
// 실제 DB 대상 멱등성 검증은 loader.integration.test.ts(별도) 담당.
import { VerifiedOntologyId } from './verified-ontology-id.js';
import { describe, it, expect, vi, beforeEach } from 'vitest';

// 관계 MERGE 는 `RETURN count(x) AS merged` 로 실제 반영 건수를 돌려받는다(무음 유실 방지) —
// 목도 집계 행을 내놓아야 한다. 값은 이 테스트들이 단언하지 않으므로 0 으로 충분하다.
const runMock = vi.fn().mockResolvedValue({ records: [{ get: () => 0 }] });
const closeMock = vi.fn().mockResolvedValue(undefined);

vi.mock('./neo4j-client.js', () => ({
  getSession: () => ({ run: runMock, close: closeMock }),
}));

import neo4j from 'neo4j-driver';
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
    await loadGraph(graph, 1, 3, 5 as VerifiedOntologyId);

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
            ontologyId: 999,
          },
        },
      ],
      relations: [],
    };
    await loadGraph(graph, 1, 3, 5 as VerifiedOntologyId);

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
    await loadGraph(graph, 1, 5, 7 as VerifiedOntologyId);

    const [nodeCypher, nodeParams] = runMock.mock.calls[0];
    const [relCypher, relParams] = runMock.mock.calls[1];
    expect(nodeCypher).toContain('n.schemaVersion = $schemaVersion');
    expect(relCypher).toContain('x.schemaVersion = $schemaVersion');
    // #308 회귀 가드: plain JS number를 바인딩하면 드라이버가 Cypher FLOAT(5.0)로 직렬화해
    // 읽기측 Integer 가정이 깨진다. 값뿐 아니라 "Integer 타입으로 넘겼는지"를 검증한다.
    expect(neo4j.isInt(nodeParams.schemaVersion)).toBe(true);
    expect(nodeParams.schemaVersion.toNumber()).toBe(5);
    expect(neo4j.isInt(relParams.schemaVersion)).toBe(true);
    expect(relParams.schemaVersion.toNumber()).toBe(5);
  });

  // #678: schema_version은 온톨로지마다 독립적으로 매겨지는 숫자라, 어느 온톨로지의 버전인지
  // 함께 남기지 않으면 "구버전" 판정이 불가능하다 — 노드/관계 MERGE 모두 ontologyId를 스탬프해야 한다.
  it('노드와 관계 MERGE 모두 ontologyId를 Cypher params로 전달한다', async () => {
    const graph = {
      entities: [
        { key: entityKey(incidentId, '사건'), type: 'Incident' as const, name: '사건' },
        { key: entityKey(causeId, '원인'), type: 'Cause' as const, name: '원인' },
      ],
      relations: [
        { subjectKey: entityKey(incidentId, '사건'), type: 'CAUSED_BY', objectKey: entityKey(causeId, '원인') },
      ],
    };
    await loadGraph(graph, 1, 5, 7 as VerifiedOntologyId);

    const [nodeCypher, nodeParams] = runMock.mock.calls[0];
    const [relCypher, relParams] = runMock.mock.calls[1];
    expect(nodeCypher).toContain('n.ontologyId = $ontologyId');
    expect(relCypher).toContain('x.ontologyId = $ontologyId');
    expect(neo4j.isInt(nodeParams.ontologyId)).toBe(true);
    expect(nodeParams.ontologyId.toNumber()).toBe(7);
    expect(neo4j.isInt(relParams.ontologyId)).toBe(true);
    expect(relParams.ontologyId.toNumber()).toBe(7);
  });
});
