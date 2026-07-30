// property-mutation 단위 테스트 — Neo4j 세션을 모킹해 예약키 거부와 타입 강제 write를 검증한다.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const runMock = vi.fn();
const closeMock = vi.fn().mockResolvedValue(undefined);
vi.mock('./neo4j-client.js', () => ({ getSession: () => ({ run: runMock, close: closeMock }) }));

import { setEntityProperty } from './property-mutation.js';
import { GraphTargetMissingError } from './graph-mutation-guard.js';

// `RETURN count(n) AS updated` 결과 모킹 — 기본은 노드 1건을 찾은 정상 경로.
function updatedCount(n: number) {
  return { records: [{ get: (k: string) => (k === 'updated' ? n : undefined) }] };
}

describe('setEntityProperty', () => {
  beforeEach(() => { runMock.mockReset(); runMock.mockResolvedValue(updatedCount(1)); });

  it('number 타입은 숫자로 강제해 SET한다', async () => {
    await setEntityProperty('3:화재', '피해액', 'number', '30000000');
    const [cypher, params] = runMock.mock.calls[0];
    expect(cypher).toContain('SET n += $props');
    expect(params.key).toBe('3:화재');
    expect(params.props['피해액']).toBe(30000000);
  });

  it('date/text 타입은 문자열 그대로 SET한다', async () => {
    await setEntityProperty('3:화재', '발생일', 'date', '2026-01-15');
    expect(runMock.mock.calls[0][1].props['발생일']).toBe('2026-01-15');
  });

  it('예약 속성명은 거부한다(노드 정체성 보호)', async () => {
    await expect(setEntityProperty('3:화재', 'key', 'text', 'x')).rejects.toThrow();
    expect(runMock).not.toHaveBeenCalled();
  });

  it('대상 노드가 없으면 GraphTargetMissingError를 던진다(#310 무음 유실 방지)', async () => {
    runMock.mockResolvedValue(updatedCount(0));
    await expect(setEntityProperty('3:없는엔티티', '피해액', 'number', '100'))
      .rejects.toThrow(GraphTargetMissingError);
  });

  it('정정값이 기존 값과 같아 실제 write가 없어도 성공한다 — MATCH 성사 여부로 판정하기 때문', async () => {
    // SET이 같은 값을 써서 propertiesSet이 0이어도 count(n)은 1이다.
    runMock.mockResolvedValue(updatedCount(1));
    await expect(setEntityProperty('3:화재', '피해액', 'number', '100')).resolves.toBeUndefined();
  });
});
