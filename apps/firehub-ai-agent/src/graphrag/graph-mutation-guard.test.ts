// graph-mutation-guard 단위 테스트 — 반영 건수 판정이 "대상 부재"와 "코딩 오류"를 섞지 않는지 검증한다(#310).
import { describe, expect, it } from 'vitest';
import { GraphTargetMissingError, affectedCount } from './graph-mutation-guard.js';

function row(field: string, value: unknown) {
  return { records: [{ get: (k: string) => (k === field ? value : undefined) }] };
}

describe('affectedCount', () => {
  it('plain number 집계값을 그대로 읽는다', () => {
    expect(affectedCount(row('merged', 2), 'merged')).toBe(2);
  });

  it('neo4j Integer(toNumber 보유)도 숫자로 변환한다', () => {
    expect(affectedCount(row('merged', { toNumber: () => 5 }), 'merged')).toBe(5);
  });

  it('0은 그대로 0으로 읽어 호출측이 대상 부재로 판정할 수 있게 한다', () => {
    expect(affectedCount(row('merged', 0), 'merged')).toBe(0);
  });

  it('집계 행이 없으면 던진다 — count 집계는 0행이어도 항상 1행을 주므로 이건 쿼리 오류다', () => {
    expect(() => affectedCount({ records: [] }, 'merged')).toThrow(/집계 행이 없습니다/);
  });

  it('별칭이 틀려 필드가 없으면 던진다 — 0으로 뭉뚱그리면 틀린 사유가 사용자에게 나간다', () => {
    expect(() => affectedCount(row('other', 1), 'merged')).toThrow(/집계 필드가 없습니다/);
  });

  it('구조 오류는 GraphTargetMissingError가 아니다 — 409(대상 부재)가 아닌 502로 떨어져야 한다', () => {
    try {
      affectedCount({ records: [] }, 'merged');
      expect.unreachable();
    } catch (e) {
      expect(e).toBeInstanceOf(Error);
      expect(e).not.toBeInstanceOf(GraphTargetMissingError);
    }
  });
});
