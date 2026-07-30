// property-mutation 단위 테스트 — Neo4j 세션을 모킹해 예약키 거부와 타입 강제 write를 검증한다.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const runMock = vi.fn();
const closeMock = vi.fn().mockResolvedValue(undefined);
vi.mock('./neo4j-client.js', () => ({ getSession: () => ({ run: runMock, close: closeMock }) }));

import { coercePropertyValue, PropertyValueInvalidError, setEntityProperty } from './property-mutation.js';
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

  it('date 타입은 YYYY-MM-DD 문자열로 SET한다', async () => {
    await setEntityProperty('3:화재', '발생일', 'date', '2026-01-15');
    expect(runMock.mock.calls[0][1].props['발생일']).toBe('2026-01-15');
  });

  it('date 타입에 형식을 벗어난 정정값이 오면 그래프를 건드리지 않고 거절한다(#311)', async () => {
    await expect(setEntityProperty('3:화재', '발생일', 'date', '작년겨울'))
      .rejects.toThrow(PropertyValueInvalidError);
    expect(runMock).not.toHaveBeenCalled();
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

// #311 — 정정값은 "사람이 대신 정규화한 값"이므로 dataType 규약을 만족해야 한다.
// 검증이 없으면 date에 '작년겨울'이 그대로 적재되어 정규화 검수 자체가 무의미해진다.
describe('coercePropertyValue', () => {
  it('number는 숫자로 강제하고, 숫자가 아니면 거절한다', () => {
    expect(coercePropertyValue('number', ' 30000000 ')).toBe(30000000);
    expect(() => coercePropertyValue('number', '삼천만원 정도')).toThrow(PropertyValueInvalidError);
  });

  it('number에 공백만 오면 거절한다 — Number("")가 0이라 0이 조용히 적재되던 구멍', () => {
    expect(() => coercePropertyValue('number', '   ')).toThrow(PropertyValueInvalidError);
  });

  it('date는 YYYY-MM-DD로 정규화하고 관용 표기도 받는다', () => {
    expect(coercePropertyValue('date', '2026-01-05')).toBe('2026-01-05');
    expect(coercePropertyValue('date', '2026.1.5')).toBe('2026-01-05');
    expect(coercePropertyValue('date', '2026년 1월 5일')).toBe('2026-01-05');
  });

  it('date에 날짜가 아닌 값은 거절하고 사유에 형식을 안내한다', () => {
    expect(() => coercePropertyValue('date', '작년겨울')).toThrow(/YYYY-MM-DD/);
  });

  it('date에 달력상 존재하지 않는 날짜는 거절한다(Date가 다음 달로 굴려버리는 값)', () => {
    expect(() => coercePropertyValue('date', '2026-02-31')).toThrow(/존재하지 않는 날짜/);
    expect(() => coercePropertyValue('date', '2026-13-01')).toThrow(PropertyValueInvalidError);
  });

  it('text는 앞뒤 공백을 없애고 연속 공백을 한 칸으로 접는다', () => {
    expect(coercePropertyValue('text', '  전기적   요인 ')).toBe('전기적 요인');
  });

  it('text가 공백뿐이거나 상한(1000자)을 넘으면 거절한다', () => {
    expect(() => coercePropertyValue('text', '   ')).toThrow(PropertyValueInvalidError);
    expect(() => coercePropertyValue('text', 'a'.repeat(1001))).toThrow(/1000자/);
  });
});
