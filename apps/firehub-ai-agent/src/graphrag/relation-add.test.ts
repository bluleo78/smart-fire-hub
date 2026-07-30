// relation-add 단위 테스트 — Neo4j 세션을 모킹해 "반영 건수 확인" 가드를 검증한다(#310).
// 핵심: 판정 기준은 "MATCH가 양 끝점을 바인딩했는가"이지 "엣지가 새로 생겼는가"가 아니다.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const runMock = vi.fn();
const closeMock = vi.fn().mockResolvedValue(undefined);
vi.mock('./neo4j-client.js', () => ({ getSession: () => ({ run: runMock, close: closeMock }) }));

import { addRelation } from './relation-add.js';
import { GraphTargetMissingError } from './graph-mutation-guard.js';

// `RETURN count(x) AS merged` 결과 모킹 — 실제 드라이버는 Integer를 주지만 plain number도 허용된다.
function mergedCount(n: number) {
  return { records: [{ get: (k: string) => (k === 'merged' ? n : undefined) }] };
}

describe('addRelation', () => {
  beforeEach(() => {
    runMock.mockReset();
    closeMock.mockClear();
  });

  it('양 끝점이 바인딩되면 엣지를 MERGE하고 성공한다', async () => {
    runMock.mockResolvedValue(mergedCount(1));

    await addRelation(3, '1:2026-001', 'CAUSED_BY', '2:누전', [18]);

    const [cypher, params] = runMock.mock.calls[0];
    // 반영 여부를 확인할 수 있도록 RETURN 절이 반드시 붙어 있어야 한다.
    expect(cypher).toContain('RETURN count(x) AS merged');
    expect(params.subjectKey).toBe('1:2026-001');
    expect(params.objectKey).toBe('2:누전');
    expect(params.sourceChunkIds).toEqual([18]);
  });

  it('이미 같은 엣지가 있어도 성공한다 — 새로 생성된 엣지 수가 아니라 MATCH 바인딩 여부로 판정하기 때문', async () => {
    // MERGE가 기존 엣지를 재사용하면 relationshipsCreated는 0이지만 count(x)는 1이다.
    runMock.mockResolvedValue(mergedCount(1));

    await expect(addRelation(3, '1:2026-001', 'CAUSED_BY', '2:누전', [18])).resolves.toBeUndefined();
  });

  it('끝점이 없어 아무 것도 반영되지 않으면 GraphTargetMissingError를 던진다(#310 무음 유실 방지)', async () => {
    runMock.mockResolvedValue(mergedCount(0));

    await expect(addRelation(3, '1:2026-001', 'CAUSED_BY', '9:없는엔티티', [18]))
      .rejects.toThrow(GraphTargetMissingError);
    // 실패해도 세션은 반드시 닫혀야 한다(finally).
    expect(closeMock).toHaveBeenCalled();
  });

  it('실패 메시지에 주어/목적어 키가 들어가 어느 끝점이 문제인지 알 수 있다', async () => {
    runMock.mockResolvedValue(mergedCount(0));

    await expect(addRelation(3, '1:2026-001', 'CAUSED_BY', '9:없는엔티티', [18]))
      .rejects.toThrow(/9:없는엔티티/);
  });

  it('집계 행 자체가 없으면 "대상 부재"가 아니라 코딩 오류로 구분해 던진다', async () => {
    // RETURN 별칭이 틀린 상황 — 이를 0으로 뭉뚱그리면 사용자에게 틀린 사유가 나간다.
    runMock.mockResolvedValue({ records: [] });

    const err = await addRelation(3, '1:2026-001', 'CAUSED_BY', '2:누전', [18]).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(Error);
    expect(err).not.toBeInstanceOf(GraphTargetMissingError);
  });
});
