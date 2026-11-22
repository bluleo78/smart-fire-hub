import { toast } from 'sonner';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  applyFieldErrors,
  CloneNameExhaustedError,
  extractApiError,
  extractApiErrorAsync,
  GRAPH_READ_RESTRICTED_MESSAGE,
  handleApiError,
  isBadCredentialsError,
  isDuplicateName,
  isGraphReadRestricted,
  isMemberGoneError,
  isWrongCurrentPasswordError,
  listLoadErrorProps,
  mapFieldErrors,
  NameRetryExhaustedError,
} from './api-error';

// toast 는 부수효과만 확인한다 — 실제 sonner 렌더는 E2E 몫이다. vi.mock 은 호이스팅되므로 import 아래에 둬도
// import 보다 먼저 적용된다(import 순서 lint 를 지키기 위해 아래에 둔다).
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }));

/** axios.isAxiosError 는 isAxiosError === true 인 객체를 인정한다 — 실제 AxiosError 없이 응답 모양만 재현한다. */
function axiosErrorWith(data: unknown) {
  return { isAxiosError: true, response: { status: 400, data } };
}

/** 상태 코드까지 고르는 axiosErrorWith — 상태·code 로 판정하는 is*Error 도우미 테스트가 함께 쓴다. */
function axiosErrorWithStatus(status: number, data: unknown) {
  return { isAxiosError: true, response: { status, data } };
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

  it('필드 오류가 여럿이면 첫 문구에 나머지 개수를 붙인다 — 토스트만 쓰는 화면에서 다른 칸도 틀렸음을 알린다 (WD-640)', () => {
    const err = axiosErrorWith({
      status: 400,
      message: '입력값이 올바르지 않습니다.',
      errors: { description: '500자 이하로 입력하세요.', name: '데이터셋 이름을 입력하세요', tableName: '필수 입력값입니다.' },
    });
    expect(extractApiError(err, '실패')).toBe('500자 이하로 입력하세요. (외 2건)');
  });

  it('빈 문구는 개수에서 빼고, 필드 키(영어 식별자)는 토스트에 붙이지 않는다 (WD-640)', () => {
    const err = axiosErrorWith({ status: 400, message: '입력값이 올바르지 않습니다.', errors: { a: '', name: '필수 입력값입니다.' } });
    expect(extractApiError(err, '실패')).toBe('필수 입력값입니다.');
  });

  it('검증 실패 코드(VALIDATION_FAILED)가 있으면 code 없음과 같이 필드 문구를 쓴다 (WD-640)', () => {
    const err = axiosErrorWith({
      status: 400,
      code: 'VALIDATION_FAILED',
      message: '입력값이 올바르지 않습니다.',
      errors: { name: '필수 입력값입니다.', _global: '두 값이 같아야 합니다.' },
    });
    expect(extractApiError(err, '실패')).toBe('필수 입력값입니다. (외 1건)');
    const setError = vi.fn();
    expect(applyFieldErrors(err, setError, ['name'])).toBe(true);
    expect(setError).toHaveBeenCalledWith('name', { message: '필수 입력값입니다.' });
    expect(setError).toHaveBeenCalledWith('root', { message: '두 값이 같아야 합니다.' });
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

  it('403 + GRAPH_READ_RESTRICTED 만 참이다', () => {
    expect(isGraphReadRestricted(axiosErrorWithStatus(403, { code: 'GRAPH_READ_RESTRICTED' }))).toBe(true);
    expect(isGraphReadRestricted(axiosErrorWithStatus(403, { code: 'PASSWORD_CHANGE_REQUIRED' }))).toBe(false);
    expect(isGraphReadRestricted(axiosErrorWithStatus(403, { message: '권한 없음' }))).toBe(false);
    expect(isGraphReadRestricted(axiosErrorWithStatus(500, { code: 'GRAPH_READ_RESTRICTED' }))).toBe(false);
    expect(isGraphReadRestricted(new Error('x'))).toBe(false);
  });

  // 판정 조회 일시 장애(502)는 제한이 아니다 — 자물쇠 안내가 아니라 재시도 가능한 일반 오류로 남아야 한다.
  it('판정 조회 실패 502 는 제한이 아니다', () => {
    expect(isGraphReadRestricted(axiosErrorWithStatus(502, { message: '지식그래프 조회 중 ai-agent 호출 실패' }))).toBe(false);
    expect(isGraphReadRestricted(axiosErrorWithStatus(502, { code: 'GRAPH_READ_CHECK_FAILED' }))).toBe(false);
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

  it('404 + USER_NOT_IN_WORKSPACE 만 참이다 — 다른 404 를 「제거된 멤버」로 오인하지 않는다', () => {
    expect(isMemberGoneError(axiosErrorWithStatus(404, { code: 'USER_NOT_IN_WORKSPACE' }))).toBe(true);
    expect(isMemberGoneError(axiosErrorWithStatus(404, { message: '역할을 찾을 수 없습니다' }))).toBe(false);
    expect(isMemberGoneError(axiosErrorWithStatus(409, { code: 'USER_NOT_IN_WORKSPACE' }))).toBe(false);
    expect(isMemberGoneError(new Error('x'))).toBe(false);
  });
});

describe('isBadCredentialsError (WD-572)', () => {

  it('code 없는 401 은 자격 증명 오류다 — 본문이 비어 있어도', () => {
    expect(isBadCredentialsError(axiosErrorWithStatus(401, { status: 401, message: '아이디 또는 비밀번호가 올바르지 않습니다.' }))).toBe(true);
    expect(isBadCredentialsError(axiosErrorWithStatus(401, ''))).toBe(true);
    expect(isBadCredentialsError(axiosErrorWithStatus(401, undefined))).toBe(true);
  });

  it('비활성 계정 401(code 있음)·잠금 429·500·네트워크 오류는 아니다', () => {
    expect(isBadCredentialsError(axiosErrorWithStatus(401, { status: 401, code: 'ACCOUNT_DEACTIVATED', message: 'x' }))).toBe(false);
    expect(isBadCredentialsError(axiosErrorWithStatus(429, { status: 429, message: 'x' }))).toBe(false);
    expect(isBadCredentialsError(axiosErrorWithStatus(500, {}))).toBe(false);
    expect(isBadCredentialsError({ isAxiosError: true })).toBe(false);
    expect(isBadCredentialsError(new Error('x'))).toBe(false);
  });
});

describe('isWrongCurrentPasswordError (WD-572)', () => {

  it('code WRONG_CURRENT_PASSWORD 인 400 만 참이다', () => {
    expect(isWrongCurrentPasswordError(axiosErrorWithStatus(400, { status: 400, code: 'WRONG_CURRENT_PASSWORD', message: '현재 비밀번호가 올바르지 않습니다' }))).toBe(true);
  });

  it('다른 code·code 없는 400·403·429·500·네트워크 오류는 아니다', () => {
    expect(isWrongCurrentPasswordError(axiosErrorWithStatus(400, { status: 400, code: 'SAME_AS_CURRENT_PASSWORD', message: 'x' }))).toBe(false);
    // 문구가 같아도 code 가 없으면 아니다 — 문구가 아니라 code 로 판정한다
    expect(isWrongCurrentPasswordError(axiosErrorWithStatus(400, { status: 400, message: '현재 비밀번호가 올바르지 않습니다' }))).toBe(false);
    expect(isWrongCurrentPasswordError(axiosErrorWithStatus(403, { status: 403, code: 'WRONG_CURRENT_PASSWORD' }))).toBe(false);
    expect(isWrongCurrentPasswordError(axiosErrorWithStatus(429, {}))).toBe(false);
    expect(isWrongCurrentPasswordError(axiosErrorWithStatus(500, {}))).toBe(false);
    expect(isWrongCurrentPasswordError({ isAxiosError: true })).toBe(false);
  });
});

describe('isDuplicateName (WD-824)', () => {
  it('code DUPLICATE_NAME 인 409 만 참이다', () => {
    expect(isDuplicateName(axiosErrorWithStatus(409, { status: 409, error: 'Conflict', code: 'DUPLICATE_NAME', message: '이미 있는 쿼리 이름입니다. 다른 이름을 입력하세요.' }))).toBe(true);
  });

  it('편집 충돌 409·code 없는 409·다른 상태·네트워크 오류는 아니다', () => {
    // 같은 409 라도 EDIT_CONFLICT 는 충돌 안내 창 몫이다
    expect(isDuplicateName(axiosErrorWithStatus(409, { status: 409, code: 'EDIT_CONFLICT', message: 'x' }))).toBe(false);
    expect(isDuplicateName(axiosErrorWithStatus(409, { status: 409, message: 'x' }))).toBe(false);
    expect(isDuplicateName(axiosErrorWithStatus(400, { code: 'DUPLICATE_NAME' }))).toBe(false);
    expect(isDuplicateName({ isAxiosError: true })).toBe(false);
    expect(isDuplicateName(new Error('x'))).toBe(false);
  });
});

describe('extractApiError — 복제 이름 소진 (WD-834)', () => {
  it('CloneNameExhaustedError 면 그 안내 문구를 돌려준다', () => {
    expect(extractApiError(new CloneNameExhaustedError(), 'x')).toBe(
      '복사본 이름을 정하지 못했습니다. 쓰지 않는 복사본을 지우거나 원본 이름을 바꾼 뒤 다시 복제하세요.',
    );
  });
});

describe('NameRetryExhaustedError (WD-837)', () => {
  it('extractApiError 는 오류의 안내 문구를 그대로 돌려준다', () => {
    expect(extractApiError(new NameRetryExhaustedError('안내'), 'x')).toBe('안내');
  });

  it('extractApiErrorAsync 도 같다', async () => {
    await expect(extractApiErrorAsync(new NameRetryExhaustedError('안내'), 'x')).resolves.toBe('안내');
  });

  it('CloneNameExhaustedError 는 그 하위 형이다', () => {
    expect(new CloneNameExhaustedError()).toBeInstanceOf(NameRetryExhaustedError);
  });
});

describe('mapFieldErrors — 붙인 칸과 모르는 필드 문구를 돌려준다 (WD-865)', () => {
  it('아는 필드만 setError 하고 root 는 건드리지 않는다', () => {
    const setError = vi.fn();
    const err = axiosErrorWith({ errors: { description: '설명은 255자 이내여야 합니다', securityLevelId: '보안 등급이 없습니다' } });
    expect(mapFieldErrors(err, setError, ['name', 'description'])).toEqual({
      setFields: ['description'],
      unknownMessage: '보안 등급이 없습니다',
    });
    expect(setError).toHaveBeenCalledTimes(1);
    expect(setError).toHaveBeenCalledWith('description', { message: '설명은 255자 이내여야 합니다' });
  });

  it('필드 오류 400 이 아니면 null', () => {
    const setError = vi.fn();
    expect(mapFieldErrors(axiosErrorWith({ message: '이미 존재하는 역할' }), setError, ['name'])).toBeNull();
    // 보일 문구가 없는 errors({}·빈 문구)도 null — 호출부가 기존 400 분기로 잇는다
    expect(mapFieldErrors(axiosErrorWith({ errors: {} }), setError, ['name'])).toBeNull();
    expect(mapFieldErrors(axiosErrorWith({ errors: { foo: '' } }), setError, ['name'])).toBeNull();
    expect(setError).not.toHaveBeenCalled();
  });
});

describe('listLoadErrorProps (WD-978)', () => {
  it('403 이면 흐린 톤의 권한 안내를 돌려준다', () => {
    expect(listLoadErrorProps(axiosErrorWithStatus(403, { message: '권한이 없습니다' }), '쿼리')).toEqual({
      tone: 'muted',
      message: '쿼리 목록을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.',
    });
  });

  it('그 밖의 실패는 빨간 톤에 서버 사유를 돌려준다', () => {
    expect(listLoadErrorProps(axiosErrorWithStatus(500, { message: '서버 오류' }), '차트')).toEqual({
      tone: 'error',
      message: '서버 오류',
    });
  });

  it('서버 사유가 없으면 「<대상> 목록을 불러오지 못했습니다.」 로 보인다', () => {
    expect(listLoadErrorProps(new Error('Network Error'), 'API 연결')).toEqual({
      tone: 'error',
      message: 'API 연결 목록을 불러오지 못했습니다.',
    });
  });
});
