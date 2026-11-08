import { describe, expect, it, vi } from 'vitest';

import { applyServerFieldErrors, serverFieldErrors, serverMessage } from './http-errors';

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

describe('applyServerFieldErrors (WD-654)', () => {
  const validation400 = (errors: Record<string, string>) => ({
    isAxiosError: true,
    response: { status: 400, data: { status: 400, error: 'x', code: 'VALIDATION_FAILED', message: 'm', errors } },
  });
  type Form = { name: string; slug: string };

  it('아는 칸은 setError, 칸 밖 키(_global·모르는 필드)는 이어서 돌려준다', () => {
    const setError = vi.fn();
    const rest = applyServerFieldErrors<Form>(
      validation400({ name: '이름 오류', _global: '전체 오류', extra: '서버 칸 오류' }),
      setError,
      ['name', 'slug'],
      { shouldFocus: true },
    );
    expect(setError).toHaveBeenCalledTimes(1);
    expect(setError).toHaveBeenCalledWith('name', { message: '이름 오류' }, { shouldFocus: true });
    expect(rest).toBe('전체 오류 서버 칸 오류');
  });

  it('칸 밖 문구가 없으면 빈 문자열, 검증 400 이 아니면 undefined', () => {
    const setError = vi.fn();
    expect(applyServerFieldErrors<Form>(validation400({ slug: 's' }), setError, ['name', 'slug'])).toBe('');
    expect(setError).toHaveBeenCalledWith('slug', { message: 's' }, { shouldFocus: false });
    expect(applyServerFieldErrors<Form>(new Error('x'), setError, ['name'])).toBeUndefined();
  });
});
