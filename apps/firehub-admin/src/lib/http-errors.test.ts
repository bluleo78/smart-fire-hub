import { describe, expect, it } from 'vitest';

import { serverFieldErrors, serverMessage } from './http-errors';

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

describe('serverFieldErrors (WD-311)', () => {
  const withErrors = (status: number, errors?: Record<string, string>) => ({
    isAxiosError: true,
    response: { status, data: { status, error: 'x', message: 'Validation failed', errors } },
  });

  it('400 의 필드 오류 맵을 돌려준다', () => {
    expect(serverFieldErrors(withErrors(400, { name: '공백일 수 없습니다' }))).toEqual({
      name: '공백일 수 없습니다',
    });
  });

  it('필드 오류가 없거나 400 이 아니면 undefined', () => {
    expect(serverFieldErrors(withErrors(400))).toBeUndefined();
    expect(serverFieldErrors(withErrors(400, {}))).toBeUndefined();
    expect(serverFieldErrors(withErrors(422, { name: 'x' }))).toBeUndefined();
    expect(serverFieldErrors(new Error('x'))).toBeUndefined();
  });
});
