// relation-add 단위 테스트 — Neo4j 세션을 모킹해 "반영 건수 확인" 가드(#310)와
// 온톨로지 스키마 준수 검증(#319)을 검증한다.
// 핵심: 반영 판정 기준은 "MATCH가 양 끝점을 바인딩했는가"이지 "엣지가 새로 생겼는가"가 아니다.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const runMock = vi.fn();
const closeMock = vi.fn().mockResolvedValue(undefined);
vi.mock('./neo4j-client.js', () => ({ getSession: () => ({ run: runMock, close: closeMock }) }));

import { addRelation } from './relation-add.js';
import { GraphTargetMissingError, OntologyConformanceError } from './graph-mutation-guard.js';
import { CORE_ONTOLOGY } from './ontology.js';

// `RETURN count(x) AS merged` 결과 모킹 — 실제 드라이버는 Integer를 주지만 plain number도 허용된다.
function mergedCount(n: number) {
  return { records: [{ get: (k: string) => (k === 'merged' ? n : undefined) }] };
}
// 끝점 타입 조회 결과 모킹. null을 주면 "타입 스탬프가 없는 노드"를 재현한다.
function endpoints(subjectType: string | null, objectType: string | null) {
  return { records: [{ get: (k: string) => (k === 'subjectType' ? subjectType : objectType) }] };
}
// 끝점 조회 → 엣지 MERGE 순서로 응답을 분기(호출 순서가 아니라 Cypher 내용 기준이라 코드 변경에 견고).
function mockGraph(endpointResult: unknown, mergeResult: unknown = mergedCount(1)) {
  runMock.mockImplementation(async (cypher: string) =>
    (cypher.includes('AS subjectType') ? endpointResult : mergeResult));
}

// CORE_ONTOLOGY 허용 트리플: Incident -CAUSED_BY-> Cause.
const INCIDENT_KEY = '1:2026-001';
const CAUSE_KEY = '3:누전';

describe('addRelation', () => {
  beforeEach(() => {
    runMock.mockReset();
    closeMock.mockClear();
  });

  it('허용 트리플이고 양 끝점이 바인딩되면 엣지를 MERGE하고 성공한다', async () => {
    mockGraph(endpoints('Incident', 'Cause'));

    await addRelation(CORE_ONTOLOGY, INCIDENT_KEY, 'CAUSED_BY', CAUSE_KEY, [18]);

    const mergeCall = runMock.mock.calls.find(([cypher]) => cypher.includes('MERGE'));
    // 반영 여부를 확인할 수 있도록 RETURN 절이 반드시 붙어 있어야 한다.
    expect(mergeCall?.[0]).toContain('RETURN count(x) AS merged');
    expect(mergeCall?.[1].subjectKey).toBe(INCIDENT_KEY);
    expect(mergeCall?.[1].objectKey).toBe(CAUSE_KEY);
    expect(mergeCall?.[1].sourceChunkIds).toEqual([18]);
  });

  it('이미 같은 엣지가 있어도 성공한다 — 새로 생성된 엣지 수가 아니라 MATCH 바인딩 여부로 판정하기 때문', async () => {
    // MERGE가 기존 엣지를 재사용하면 relationshipsCreated는 0이지만 count(x)는 1이다.
    mockGraph(endpoints('Incident', 'Cause'), mergedCount(1));

    await expect(addRelation(CORE_ONTOLOGY, INCIDENT_KEY, 'CAUSED_BY', CAUSE_KEY, [18])).resolves.toBeUndefined();
  });

  it('끝점이 없으면 GraphTargetMissingError를 던진다(#310 무음 유실 방지)', async () => {
    mockGraph({ records: [] });

    await expect(addRelation(CORE_ONTOLOGY, INCIDENT_KEY, 'CAUSED_BY', '9:없는엔티티', [18]))
      .rejects.toThrow(GraphTargetMissingError);
    // 실패해도 세션은 반드시 닫혀야 한다(finally).
    expect(closeMock).toHaveBeenCalled();
    // 끝점이 없으면 MERGE 자체를 시도하지 않는다.
    expect(runMock.mock.calls.some(([cypher]) => cypher.includes('MERGE'))).toBe(false);
  });

  it('실패 메시지에 주어/목적어 키가 들어가 어느 끝점이 문제인지 알 수 있다', async () => {
    mockGraph({ records: [] });

    await expect(addRelation(CORE_ONTOLOGY, INCIDENT_KEY, 'CAUSED_BY', '9:없는엔티티', [18]))
      .rejects.toThrow(/9:없는엔티티/);
  });

  it('MERGE 결과 집계 행 자체가 없으면 "대상 부재"가 아니라 코딩 오류로 구분해 던진다', async () => {
    // RETURN 별칭이 틀린 상황 — 이를 0으로 뭉뚱그리면 사용자에게 틀린 사유가 나간다.
    mockGraph(endpoints('Incident', 'Cause'), { records: [] });

    const err = await addRelation(CORE_ONTOLOGY, INCIDENT_KEY, 'CAUSED_BY', CAUSE_KEY, [18]).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(Error);
    expect(err).not.toBeInstanceOf(GraphTargetMissingError);
  });

  // ── 온톨로지 스키마 준수(#319) ──
  it('온톨로지에 없는 관계 타입은 그래프를 건드리기 전에 거부한다(#319)', async () => {
    mockGraph(endpoints('Incident', 'Cause'));

    await expect(addRelation(CORE_ONTOLOGY, INCIDENT_KEY, 'NOT_IN_ONTOLOGY', CAUSE_KEY, [18]))
      .rejects.toThrow(OntologyConformanceError);
    expect(runMock).not.toHaveBeenCalled(); // 세션 조회조차 하지 않는다.
  });

  it('관계 타입은 있지만 허용되지 않은 (주어타입, 관계, 목적어타입) 조합이면 거부한다(#319)', async () => {
    // CAUSED_BY는 Incident -> Cause만 허용 — Building을 주어로 쓰면 스키마 위반이다.
    mockGraph(endpoints('Building', 'Cause'));

    await expect(addRelation(CORE_ONTOLOGY, INCIDENT_KEY, 'CAUSED_BY', CAUSE_KEY, [18]))
      .rejects.toThrow(/Building -CAUSED_BY-> Cause/);
    expect(runMock.mock.calls.some(([cypher]) => cypher.includes('MERGE'))).toBe(false);
  });

  it('끝점에 타입 스탬프가 없으면 트리플 대조를 건너뛰고 관계 타입 검증만으로 통과시킨다', async () => {
    // 구버전 적재 노드 등 — 여기서 막으면 정상 승인이 진단 불가능한 사유로 거부된다.
    mockGraph(endpoints(null, null));

    await expect(addRelation(CORE_ONTOLOGY, INCIDENT_KEY, 'CAUSED_BY', CAUSE_KEY, [18])).resolves.toBeUndefined();
  });
});
