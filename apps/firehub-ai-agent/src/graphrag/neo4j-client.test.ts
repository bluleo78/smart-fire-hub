import { describe, it, expect, vi, afterEach } from 'vitest';

// 주의: './neo4j-client' 자체를 부분 목킹(vi.mock + importOriginal)하면
// readWholeGraph 내부에서 같은 모듈의 getSession()을 호출할 때 ESM 바인딩이
// 모듈 내부 지역 참조를 그대로 사용하므로 목킹이 가로채지 못한다.
// 대신 실제 I/O 경계인 'neo4j-driver' 패키지를 목킹해 getDriver/getSession을
// 실제로 거치면서 세션만 가짜로 대체한다.
const runMock = vi.fn();
const closeMock = vi.fn();
vi.mock('neo4j-driver', () => ({
  default: {
    driver: () => ({ session: () => ({ run: runMock, close: closeMock }) }),
    auth: { basic: () => ({}) },
  },
}));

import { readWholeGraph, renameEntityType } from './neo4j-client.js';

// neo4j Integer 흉내 — toNumber() 제공.
const int = (n: number) => ({ toNumber: () => n });
const rec = (obj: Record<string, unknown>) => ({ get: (k: string) => obj[k] });

describe('readWholeGraph', () => {
  afterEach(() => vi.clearAllMocks());

  it('노드/엣지를 매핑하고 sourceChunkCount를 number로 변환한다', async () => {
    runMock
      .mockResolvedValueOnce({ records: [
        rec({ key: 'incident:a', type: 'Incident', name: '화재A', sourceChunkCount: int(3) }),
        rec({ key: 'building:b', type: 'Building', name: '상가', sourceChunkCount: int(1) }),
        rec({ key: 'regulation:c', type: 'Regulation', name: '규정', sourceChunkCount: int(0) }), // 고립 노드
      ] })
      .mockResolvedValueOnce({ records: [
        rec({ subjectKey: 'incident:a', type: 'OCCURRED_AT', objectKey: 'building:b' }),
      ] });

    const g = await readWholeGraph();
    expect(g.nodes).toHaveLength(3);
    expect(g.nodes[0]).toEqual({ key: 'incident:a', type: 'Incident', name: '화재A', sourceChunkCount: 3 });
    expect(g.nodes[2].sourceChunkCount).toBe(0); // 고립 노드도 포함
    expect(g.edges).toEqual([{ subjectKey: 'incident:a', type: 'OCCURRED_AT', objectKey: 'building:b' }]);
    expect(closeMock).toHaveBeenCalled(); // 세션 정리
  });

  // 5-4: schemaVersion이 있는 노드는 number로 변환해 포함하고, 스탬프 도입 이전 레거시 노드(속성
  // 없음 → null)는 필드 자체를 생략한다 — "0/구버전"과 "값 없음"을 UI가 혼동하지 않게 하는 계약.
  it('schemaVersion이 있으면 number로 포함하고, 없으면(레거시 노드) 필드를 생략한다', async () => {
    runMock
      .mockResolvedValueOnce({ records: [
        rec({ key: 'incident:a', type: 'Incident', name: '화재A', sourceChunkCount: int(1), schemaVersion: int(3) }),
        rec({ key: 'legacy:b', type: 'Building', name: '레거시', sourceChunkCount: int(1), schemaVersion: null }),
      ] })
      .mockResolvedValueOnce({ records: [] });

    const g = await readWholeGraph();
    expect(g.nodes[0].schemaVersion).toBe(3);
    expect(g.nodes[1]).not.toHaveProperty('schemaVersion');
  });
});

// 5-5: 타입 리네임 — key 접두어("<oldType>:")를 고정 길이만큼 잘라 newType으로 치환하고 type도 갱신한다.
describe('renameEntityType', () => {
  afterEach(() => vi.clearAllMocks());

  it('MATCH·SET에 oldType/newType 파라미터를 전달하고 마이그레이션된 노드 수를 반환한다', async () => {
    runMock.mockResolvedValueOnce({ records: [rec({ migrated: int(3) })] });

    const migrated = await renameEntityType('Cause', 'RootCause');

    expect(runMock).toHaveBeenCalledWith(
      expect.stringContaining('MATCH (n:Entity {type: $oldType})'),
      { oldType: 'Cause', newType: 'RootCause' },
    );
    const [cypher] = runMock.mock.calls[0];
    expect(cypher).toContain('SET n.key = $newType');
    expect(cypher).toContain('n.type = $newType');
    expect(migrated).toBe(3);
    expect(closeMock).toHaveBeenCalled();
  });
});
