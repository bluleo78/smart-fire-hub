import { toast } from 'sonner';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  applyFieldErrors,
  extractApiError,
  extractApiErrorAsync,
  GRAPH_READ_RESTRICTED_MESSAGE,
  handleApiError,
  isGraphReadRestricted,
  isMemberGoneError,
} from './api-error';

// toast 는 부수효과만 확인한다 — 실제 sonner 렌더는 E2E 몫이다. vi.mock 은 호이스팅되므로 import 아래에 둬도
// import 보다 먼저 적용된다(import 순서 lint 를 지키기 위해 아래에 둔다).
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }));

/** axios.isAxiosError 는 isAxiosError === true 인 객체를 인정한다 — 실제 AxiosError 없이 응답 모양만 재현한다. */
function axiosErrorWith(data: unknown) {
  return { isAxiosError: true, response: { status: 400, data } };
}

describe('extractApiError — errors 맵의 두 의미 구분 (#787)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('code 가 있는 응답(CodedApiException)의 errors(details)는 쓰지 않고 message 를 쓴다', () => {
    const err = axiosErrorWith({
      status: 409,
      error: 'Conflict',
      message: '이미 이 워크스페이스의 멤버입니다(정지됨). 상세에서 재활성화하세요',
      errors: { userId: '5' },
      code: 'MEMBER_SUSPENDED',
    });
    expect(extractApiError(err, '실패')).toBe('이미 이 워크스페이스의 멤버입니다(정지됨). 상세에서 재활성화하세요');
  });

  it('code 가 없는 Bean Validation 응답은 첫 필드 메시지를 쓴다(기존 동작 유지)', () => {
    const err = axiosErrorWith({
      status: 400,
      error: 'Bad Request',
      message: 'Validation failed',
      errors: { email: '올바른 이메일 형식이 아닙니다' },
    });
    expect(extractApiError(err, '실패')).toBe('올바른 이메일 형식이 아닙니다');
  });

  it('code 가 없는 임포트 검증(error_N)도 첫 메시지를 쓴다', () => {
    const err = axiosErrorWith({ status: 400, error: 'Bad Request', message: '검증 실패', errors: { error_0: '3행: 날짜 형식 오류' } });
    expect(extractApiError(err, '실패')).toBe('3행: 날짜 형식 오류');
  });

  it('code 가 있고 message 가 비면 fallback', () => {
    const err = axiosErrorWith({ status: 409, error: 'Conflict', message: '', errors: { userId: '5' }, code: 'X' });
    expect(extractApiError(err, '실패')).toBe('실패');
  });

  it('errors 없으면 message, axios 에러가 아니면 fallback', () => {
    expect(extractApiError(axiosErrorWith({ status: 409, error: 'Conflict', message: '충돌' }), '실패')).toBe('충돌');
    expect(extractApiError(new Error('boom'), '실패')).toBe('실패');
  });

  it('Blob 응답(responseType: blob)에서도 같은 규칙을 적용한다', async () => {
    const body = JSON.stringify({ status: 409, error: 'Conflict', message: '정지된 멤버', errors: { userId: '5' }, code: 'MEMBER_SUSPENDED' });
    const err = axiosErrorWith(new Blob([body], { type: 'application/json' }));
    await expect(extractApiErrorAsync(err, '실패')).resolves.toBe('정지된 멤버');
  });

  it('handleApiError 는 고른 메시지를 토스트로 띄운다 — details 값("5")이 아니다', () => {
    handleApiError(axiosErrorWith({ status: 409, error: 'Conflict', message: '정지된 멤버', errors: { userId: '5' }, code: 'MEMBER_SUSPENDED' }), '실패');
    expect(toast.error).toHaveBeenCalledWith('정지된 멤버');
  });
});

describe('isGraphReadRestricted', () => {
  const err = (status: number, data: unknown) => ({ isAxiosError: true, response: { status, data } });

  it('403 + GRAPH_READ_RESTRICTED 만 참이다', () => {
    expect(isGraphReadRestricted(err(403, { code: 'GRAPH_READ_RESTRICTED' }))).toBe(true);
    expect(isGraphReadRestricted(err(403, { code: 'PASSWORD_CHANGE_REQUIRED' }))).toBe(false);
    expect(isGraphReadRestricted(err(403, { message: '권한 없음' }))).toBe(false);
    expect(isGraphReadRestricted(err(500, { code: 'GRAPH_READ_RESTRICTED' }))).toBe(false);
    expect(isGraphReadRestricted(new Error('x'))).toBe(false);
  });

  // 판정 조회 일시 장애(502)는 제한이 아니다 — 자물쇠 안내가 아니라 재시도 가능한 일반 오류로 남아야 한다.
  it('판정 조회 실패 502 는 제한이 아니다', () => {
    expect(isGraphReadRestricted(err(502, { message: '지식그래프 조회 중 ai-agent 호출 실패' }))).toBe(false);
    expect(isGraphReadRestricted(err(502, { code: 'GRAPH_READ_CHECK_FAILED' }))).toBe(false);
  });

  it('제한 안내 문구 상수가 스펙 원문과 같다', () => {
    expect(GRAPH_READ_RESTRICTED_MESSAGE).toBe('이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 표시할 수 없습니다.');
  });
});

describe('applyFieldErrors — 서버 필드 오류를 폼 필드로 (WD-270)', () => {
  const FIELDS = ['currentPassword', 'newPassword'] as const;

  it('아는 필드는 해당 필드에 setError 하고 true', () => {
    const setError = vi.fn();
    const err = axiosErrorWith({ status: 400, message: 'Validation failed', errors: { newPassword: '8~128자' } });
    expect(applyFieldErrors(err, setError, FIELDS)).toBe(true);
    expect(setError).toHaveBeenCalledTimes(1);
    expect(setError).toHaveBeenCalledWith('newPassword', { message: '8~128자' });
  });

  it('모르는 필드 문구는 버리지 않고 root 로 모은다', () => {
    const setError = vi.fn();
    const err = axiosErrorWith({ status: 400, errors: { newPassword: 'A', extra: 'B', other: 'C' } });
    expect(applyFieldErrors(err, setError, FIELDS)).toBe(true);
    expect(setError).toHaveBeenCalledWith('newPassword', { message: 'A' });
    expect(setError).toHaveBeenCalledWith('root', { message: 'B C' });
  });

  it('code 가 있는 errors(details)·errors 없음·400 아님·axios 아님은 건드리지 않고 false', () => {
    const setError = vi.fn();
    expect(applyFieldErrors(axiosErrorWith({ errors: { userId: '5' }, code: 'MEMBER_SUSPENDED' }), setError, FIELDS)).toBe(false);
    expect(applyFieldErrors(axiosErrorWith({ message: '현재 비밀번호가 올바르지 않습니다' }), setError, FIELDS)).toBe(false);
    expect(
      applyFieldErrors({ isAxiosError: true, response: { status: 409, data: { errors: { newPassword: 'x' } } } }, setError, FIELDS),
    ).toBe(false);
    expect(applyFieldErrors(new Error('boom'), setError, FIELDS)).toBe(false);
    expect(setError).not.toHaveBeenCalled();
  });
});

describe('isMemberGoneError (WD-288)', () => {
  const err = (status: number, data: unknown) => ({ isAxiosError: true, response: { status, data } });

  it('404 + USER_NOT_IN_WORKSPACE 만 참이다 — 다른 404 를 「제거된 멤버」로 오인하지 않는다', () => {
    expect(isMemberGoneError(err(404, { code: 'USER_NOT_IN_WORKSPACE' }))).toBe(true);
    expect(isMemberGoneError(err(404, { message: '역할을 찾을 수 없습니다' }))).toBe(false);
    expect(isMemberGoneError(err(409, { code: 'USER_NOT_IN_WORKSPACE' }))).toBe(false);
    expect(isMemberGoneError(new Error('x'))).toBe(false);
  });
});
