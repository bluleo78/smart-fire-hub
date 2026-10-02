import { describe, expect, it } from 'vitest';

import { serverMessage } from './http-errors';

/** axios.isAxiosError 가 인정하는 최소 모양. */
const axiosError = (status: number, message?: string) => ({
  isAxiosError: true,
  response: { status, data: { status, error: 'x', message } },
});

describe('serverMessage', () => {
  it('기본은 400 만 서버 메시지를 돌려준다(기존 호출 호환)', () => {
    expect(serverMessage(axiosError(400, '잘못된 요청'))).toBe('잘못된 요청');
    expect(serverMessage(axiosError(409, '충돌'))).toBeUndefined();
  });

  it('상태 목록을 주면 그 상태들의 메시지를 돌려준다', () => {
    expect(
      serverMessage(axiosError(409, '운영자 계정은 비활성화할 수 없습니다'), [400, 409]),
    ).toBe('운영자 계정은 비활성화할 수 없습니다');
    expect(serverMessage(axiosError(500, '서버 오류'), [400, 409])).toBeUndefined();
  });

  it('axios 에러가 아니면 undefined', () => {
    expect(serverMessage(new Error('x'), [400, 409])).toBeUndefined();
  });
});
