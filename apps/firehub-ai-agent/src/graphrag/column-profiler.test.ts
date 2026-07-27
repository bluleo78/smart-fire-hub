// column-profiler 단위 테스트 — collapse 표, 통계 정확성, GEOMETRY 제외, 결정성 검증.
import { describe, it, expect } from 'vitest';
import { profileColumns, ProfilerColumn } from './column-profiler.js';

const cols: ProfilerColumn[] = [
  { columnName: 'id', dataType: 'INTEGER', isPrimaryKey: true },
  { columnName: 'name', dataType: 'VARCHAR', isPrimaryKey: false },
  { columnName: 'occurred_at', dataType: 'TIMESTAMP', isPrimaryKey: false },
  { columnName: 'geom', dataType: 'GEOMETRY', isPrimaryKey: false },
];
const rows = [
  { id: 1, name: '서울', occurred_at: '2026-01-01', geom: 'POINT(127 37)' },
  { id: 2, name: '부산', occurred_at: '2026-01-02', geom: 'POINT(129 35)' },
  { id: 3, name: '서울', occurred_at: null, geom: null },
];

describe('profileColumns', () => {
  it('컬럼 dataType을 온톨로지 dataType으로 collapse한다', () => {
    const p = profileColumns(cols, rows);
    const by = Object.fromEntries(p.map((x) => [x.columnName, x.ontologyDataType]));
    expect(by.id).toBe('number');        // INTEGER → number
    expect(by.name).toBe('text');        // VARCHAR → text
    expect(by.occurred_at).toBe('date'); // TIMESTAMP → date
    expect(by.geom).toBeNull();          // GEOMETRY → null(제외 표시)
  });

  it('DECIMAL→number, DATE→date, TEXT/BOOLEAN→text, 미지타입→text', () => {
    const p = profileColumns(
      [
        { columnName: 'a', dataType: 'DECIMAL', isPrimaryKey: false },
        { columnName: 'b', dataType: 'DATE', isPrimaryKey: false },
        { columnName: 'c', dataType: 'TEXT', isPrimaryKey: false },
        { columnName: 'd', dataType: 'BOOLEAN', isPrimaryKey: false },
        { columnName: 'e', dataType: 'SOMETHINGNEW', isPrimaryKey: false },
      ],
      [],
    );
    const by = Object.fromEntries(p.map((x) => [x.columnName, x.ontologyDataType]));
    expect(by).toEqual({ a: 'number', b: 'date', c: 'text', d: 'text', e: 'text' });
  });

  it('null비율·고유값수·카디널리티·샘플값을 계산한다', () => {
    const p = profileColumns(cols, rows);
    const name = p.find((x) => x.columnName === 'name')!;
    expect(name.nullRatio).toBeCloseTo(0);      // 빈값 없음
    expect(name.distinctCount).toBe(2);          // 서울, 부산
    expect(name.cardinalityRatio).toBeCloseTo(2 / 3);
    expect(name.sampleValues).toEqual(['서울', '부산']); // 중복 제거 + 순서 유지
    const at = p.find((x) => x.columnName === 'occurred_at')!;
    expect(at.nullRatio).toBeCloseTo(1 / 3);     // 1개 null
    expect(at.isPrimaryKey).toBe(false);
  });

  it('isPrimaryKey를 그대로 전달한다', () => {
    const p = profileColumns(cols, rows);
    expect(p.find((x) => x.columnName === 'id')!.isPrimaryKey).toBe(true);
  });

  it('sampleValues는 최대 5개', () => {
    const many = Array.from({ length: 10 }, (_, i) => ({ v: `x${i}` }));
    const p = profileColumns([{ columnName: 'v', dataType: 'TEXT', isPrimaryKey: false }], many);
    expect(p[0].sampleValues).toHaveLength(5);
  });

  it('빈 표본이면 통계는 0', () => {
    const p = profileColumns([{ columnName: 'v', dataType: 'TEXT', isPrimaryKey: false }], []);
    expect(p[0]).toMatchObject({ distinctCount: 0, nullRatio: 0, cardinalityRatio: 0, sampleValues: [] });
  });

  it('결정적 — 동일 입력이면 동일 출력', () => {
    expect(profileColumns(cols, rows)).toEqual(profileColumns(cols, rows));
  });
});
