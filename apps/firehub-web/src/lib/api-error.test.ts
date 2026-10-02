import { toast } from 'sonner';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { extractApiError, extractApiErrorAsync, handleApiError } from './api-error';

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
