// loadTableGraph 단위 테스트 — Neo4j 세션을 모킹해 sourceDatasetIds MERGE 파라미터를 검증한다.
import { describe, it, expect, vi, beforeEach } from 'vitest';

const runMock = vi.fn().mockResolvedValue({ records: [] });
const closeMock = vi.fn().mockResolvedValue(undefined);

vi.mock('./neo4j-client.js', () => ({
  getSession: () => ({ run: runMock, close: closeMock }),
}));

import neo4j from 'neo4j-driver';
import { loadTableGraph } from './loader.js';
import { entityKey } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

const incidentId = entityTypeId(CORE_ONTOLOGY, 'Incident');

describe('loadTableGraph', () => {
  beforeEach(() => vi.clearAllMocks());

  it('노드에 sourceDatasetIds를 누적하는 Cypher와 datasetId 파라미터를 쓴다', async () => {
    const graph = {
      entities: [{ key: entityKey(incidentId, 'A'), type: 'Incident' as const, name: 'A' }],
      relations: [],
    };
    await loadTableGraph(graph, 77, 3);

    const [nodeCypher, nodeParams] = runMock.mock.calls[0];
    expect(nodeCypher).toContain('n.sourceDatasetIds');
    expect(nodeCypher).not.toContain('sourceChunkIds'); // 표 경로는 chunk provenance를 건드리지 않음
    expect(nodeParams.datasetId).toBe(77);
    // #308: FLOAT 저장을 막기 위해 Integer로 바인딩한다.
    expect(neo4j.isInt(nodeParams.schemaVersion)).toBe(true);
    expect(nodeParams.schemaVersion.toNumber()).toBe(3);
  });

  it('관계에도 sourceDatasetIds를 쓴다', async () => {
    const graph = {
      entities: [
        { key: '1:a', type: 'Incident' as const, name: 'a' },
        { key: '2:b', type: 'Building' as const, name: 'b' },
      ],
      relations: [{ subjectKey: '1:a', type: 'OCCURRED_AT' as const, objectKey: '2:b' }],
    };
    await loadTableGraph(graph, 88, 1);
    const [relCypher, relParams] = runMock.mock.calls[1];
    expect(relCypher).toContain('x.sourceDatasetIds');
    expect(relParams.datasetId).toBe(88);
  });
});
