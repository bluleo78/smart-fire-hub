import { describe, expect, it } from 'vitest';

import { filenameFromContentDisposition } from './download';

// 서버(SavedQueryController.exportRun 등)가 내려주는 Content-Disposition 형식: filename="안전문자" + filename*=UTF-8''퍼센트인코딩
describe('filenameFromContentDisposition', () => {
  it('RFC 5987 filename* 의 한글 이름을 우선한다', () => {
    const header =
      "attachment; filename=\"query_result_20261010_상위1000행.csv\"; filename*=UTF-8''" +
      encodeURIComponent('query_result_20261010_상위1000행.csv');
    expect(filenameFromContentDisposition(header, 'fallback.csv')).toBe('query_result_20261010_상위1000행.csv');
  });

  it('filename* 가 없으면 따옴표 filename 을 쓴다', () => {
    expect(filenameFromContentDisposition('attachment; filename="a b.xlsx"', 'f')).toBe('a b.xlsx');
  });

  it('따옴표 없는 filename 도 읽는다', () => {
    expect(filenameFromContentDisposition('attachment; filename=plain.csv', 'f')).toBe('plain.csv');
  });

  it('헤더가 없거나 이름이 없으면 fallback', () => {
    expect(filenameFromContentDisposition(undefined, 'fallback.csv')).toBe('fallback.csv');
    expect(filenameFromContentDisposition('attachment', 'fallback.csv')).toBe('fallback.csv');
  });

  it('filename* 퍼센트 인코딩이 깨졌으면 filename 으로 넘어간다', () => {
    expect(filenameFromContentDisposition("attachment; filename=\"ok.csv\"; filename*=UTF-8''%E0%A4%A", 'f')).toBe(
      'ok.csv',
    );
  });
});
