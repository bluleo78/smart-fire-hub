/**
 * DatasetListWidget 저장 방식 배지 회귀 테스트 (WD-760)
 *
 * 예전 위젯은 자체 판정(`DOCUMENT ? 문서 : 테이블`)을 써서 파일(FILE) 데이터셋을 「테이블」로 보였다.
 * 이제 공용 라벨 맵(getStorageTypeLabel)을 써서 FILE 은 「파일」, 테이블·문서는 그대로 보인다.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import DatasetListWidget from './DatasetListWidget';

/** 데이터셋 이름이 든 행(button)의 텍스트 — 저장 방식·출처 배지가 같은 행에 있다 */
function rowText(name: string): string {
  return screen.getByRole('button', { name: new RegExp(name) }).textContent ?? '';
}

describe('DatasetListWidget 저장 방식 배지 (WD-760)', () => {
  it('FILE 은 「파일」, TABLE 은 「테이블」, DOCUMENT 는 「문서」로 보인다', () => {
    render(
      <DatasetListWidget
        displayMode="side"
        input={{
          items: [
            { id: 1, name: '현장 사진 묶음', storageType: 'FILE', originType: 'SOURCE' },
            { id: 2, name: '출동 통계', storageType: 'TABLE', originType: 'DERIVED' },
            { id: 3, name: '점검 보고서', storageType: 'DOCUMENT', originType: 'SOURCE' },
          ],
        }}
      />,
    );

    // 파일 데이터셋은 더 이상 「테이블」로 보이지 않는다
    expect(rowText('현장 사진 묶음')).toContain('파일');
    expect(rowText('현장 사진 묶음')).not.toContain('테이블');
    // 기존 저장 방식 라벨은 그대로 (출처 배지도 함께)
    expect(rowText('출동 통계')).toContain('테이블');
    expect(rowText('출동 통계')).toContain('파생');
    expect(rowText('점검 보고서')).toContain('문서');
  });
});
