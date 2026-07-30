// synonym-merge 단위 테스트 — Neo4j 세션을 모킹해 keeper 판정과 Cypher 호출을 검증한다.
import { describe, it, expect, vi, beforeEach } from 'vitest';

const runMock = vi.fn();
const closeMock = vi.fn().mockResolvedValue(undefined);

vi.mock('./neo4j-client.js', () => ({
  getSession: () => ({ run: runMock, close: closeMock }),
}));

import { mergeEntities } from './synonym-merge.js';
import { entityKey } from './resolver.js';
import { pickCanonicalName } from './semantic-resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';
import { GraphTargetMissingError } from './graph-mutation-guard.js';

// 5-6: entityKey는 typeId 기반 — CORE_ONTOLOGY의 고정 id를 조회해 사용한다.
const causeId = entityTypeId(CORE_ONTOLOGY, 'Cause');

describe('mergeEntities', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    runMock.mockResolvedValue({ records: [] });
  });

  it('canonical(가장 긴 이름) 기준으로 keeper를 삼아 나머지를 삭제한다 — chunk 수와 무관', async () => {
    const keyA = entityKey(causeId, '누전'); // chunk 3개(더 많음)지만 이름이 짧다 — keeper가 되면 안 됨(C1 회귀 가드).
    const keyB = entityKey(causeId, '분전반 누전'); // chunk 1개(더 적음)지만 이름이 더 길다 — pickCanonicalName 규칙상 keeper.
    // 1번째 run: 노드 조회. A가 3개, B가 1개.
    runMock.mockResolvedValueOnce({
      records: [
        { get: (k: string) => (k === 'key' ? keyA : [1, 2, 3]) },
        { get: (k: string) => (k === 'key' ? keyB : [4]) },
      ],
    });
    // out/in 관계 조회는 빈 결과.
    runMock.mockResolvedValue({ records: [] });

    await mergeEntities(CORE_ONTOLOGY, 'Cause', '누전', '분전반 누전');

    // keeper는 chunk 수가 아니라 canonical(가장 긴) 이름 — 여기서는 B(분전반 누전).
    const setCall = runMock.mock.calls.find(([cypher]) => cypher.includes('SET k.sourceChunkIds'));
    expect(setCall?.[1]).toEqual({ keeperKey: keyB, merged: [4, 1, 2, 3] });
    const deleteCall = runMock.mock.calls.find(([cypher]) => cypher.includes('DETACH DELETE'));
    expect(deleteCall?.[1]).toEqual({ loserKey: keyA });
  });

  it('둘 중 하나가 그래프에 없으면 병합하지 않고 실패를 던진다(#310 무음 유실 방지)', async () => {
    const keyA = entityKey(causeId, '전기적 요인');
    runMock.mockResolvedValueOnce({
      records: [{ get: (k: string) => (k === 'key' ? keyA : [1]) }],
    });

    // 예전에는 조용히 return해 호출측이 승인 성공으로 처리했고, 병합 결정이 유실됐다.
    await expect(mergeEntities(CORE_ONTOLOGY, 'Cause', '전기적 요인', '분전반의 누전'))
      .rejects.toThrow(GraphTargetMissingError);

    expect(runMock).toHaveBeenCalledTimes(1); // 노드 조회 1회만, 그래프 변경 호출 없음.
  });

  it('pickCanonicalName의 타이브레이크(길이 동률 시 사전순)를 그대로 따른다', async () => {
    const nameA = '전기적 요인';
    const nameB = '분전반의 누전';
    const keyA = entityKey(causeId, nameA);
    const keyB = entityKey(causeId, nameB);
    const expectedKeeper = entityKey(causeId, pickCanonicalName([nameA, nameB]));
    runMock.mockResolvedValueOnce({
      records: [
        { get: (k: string) => (k === 'key' ? keyA : [1]) },
        { get: (k: string) => (k === 'key' ? keyB : [1]) },
      ],
    });
    runMock.mockResolvedValue({ records: [] });

    await mergeEntities(CORE_ONTOLOGY, 'Cause', nameA, nameB);

    const setCall = runMock.mock.calls.find(([cypher]) => cypher.includes('SET k.sourceChunkIds'));
    expect(setCall?.[1].keeperKey).toBe(expectedKeeper);
  });

  it('관계가 존재하면 out/in 방향 모두 keeper로 재배선한 뒤 loser를 삭제한다 (I1: 지금까지 빈 결과만 테스트되어 reconnectRelations가 실행된 적이 없었음)', async () => {
    const keyA = entityKey(causeId, '누전'); // loser
    const keyB = entityKey(causeId, '분전반 누전'); // keeper(가장 긴 이름)
    // 상대 노드는 CORE_ONTOLOGY에 없는 임의 typeId — mergeEntities는 상대 노드 key를 그대로 통과시킬 뿐
    // entityTypeId로 재검증하지 않으므로, 구분되는 값이기만 하면 된다.
    const otherOutKey = entityKey(999, '화재');
    const otherInKey = entityKey(998, '스파크');

    // cypher 내용에 따라 응답을 분기 — 호출 순서에 의존하지 않아 코드 변경에도 견고하다.
    runMock.mockImplementation(async (cypher: string) => {
      if (cypher.includes('RETURN n.key')) {
        // 노드 조회: A, B 모두 존재.
        return {
          records: [
            { get: (k: string) => (k === 'key' ? keyA : [1]) },
            { get: (k: string) => (k === 'key' ? keyB : [2]) },
          ],
        };
      }
      if (cypher.includes('MATCH (l:Entity {key: $loserKey})-[r:REL]->(o:Entity)')) {
        // loser의 out 관계: 정상 관계 1개 + 자기참조(상대가 keeper 자신) 관계 1개.
        // 실제 Neo4j라면 WHERE o.key <> $keeperKey로 후자는 애초에 반환되지 않지만,
        // 모킹 레이어에서는 그 필터가 동작하지 않으므로 JS 방어 필터를 직접 검증한다.
        return {
          records: [
            {
              get: (k: string) => {
                if (k === 'type') return 'CAUSES';
                if (k === 'otherKey') return otherOutKey;
                return [10, 20];
              },
            },
            {
              get: (k: string) => {
                if (k === 'type') return 'SELF_REF';
                if (k === 'otherKey') return keyB; // keeper 자신 — 재배선 대상이면 안 됨.
                return [999];
              },
            },
          ],
        };
      }
      if (cypher.includes('MATCH (o:Entity)-[r:REL]->(l:Entity {key: $loserKey})')) {
        // loser의 in 관계: otherInKey -[:TRIGGERS]-> loser.
        return {
          records: [
            {
              get: (k: string) => {
                if (k === 'type') return 'TRIGGERS';
                if (k === 'otherKey') return otherInKey;
                return [30];
              },
            },
          ],
        };
      }
      return { records: [] };
    });

    await mergeEntities(CORE_ONTOLOGY, 'Cause', '누전', '분전반 누전');

    const calls = runMock.mock.calls;
    const outMergeCall = calls.find(([cypher]) => cypher.includes('MERGE (k)-[x:REL {type: $type}]->(o)'));
    expect(outMergeCall?.[1]).toEqual({ keeperKey: keyB, otherKey: otherOutKey, type: 'CAUSES', sourceChunkIds: [10, 20] });
    const inMergeCall = calls.find(([cypher]) => cypher.includes('MERGE (o)-[x:REL {type: $type}]->(k)'));
    expect(inMergeCall?.[1]).toEqual({ keeperKey: keyB, otherKey: otherInKey, type: 'TRIGGERS', sourceChunkIds: [30] });

    // 자기참조(otherKey === keeperKey) 관계는 재배선 MERGE 호출을 유발하지 않아야 한다.
    const selfLoopMergeCall = calls.find(([cypher, params]) => cypher.includes('MERGE (k)-[x:REL') && params?.otherKey === keyB);
    expect(selfLoopMergeCall).toBeUndefined();

    // 재배선(MERGE)이 DETACH DELETE보다 먼저 실행되어야 한다 — 삭제 후 재배선하면 관계가 유실된다.
    const outMergeIdx = calls.indexOf(outMergeCall!);
    const inMergeIdx = calls.indexOf(inMergeCall!);
    const deleteIdx = calls.findIndex(([cypher]) => cypher.includes('DETACH DELETE'));
    expect(outMergeIdx).toBeGreaterThanOrEqual(0);
    expect(inMergeIdx).toBeGreaterThan(outMergeIdx);
    expect(deleteIdx).toBeGreaterThan(inMergeIdx);

    const deleteCall = calls[deleteIdx];
    expect(deleteCall[1]).toEqual({ loserKey: keyA });
  });
});
