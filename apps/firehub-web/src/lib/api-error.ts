import axios from 'axios';
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { toast } from 'sonner';

import type { ErrorResponse } from '@/types/auth';

/**
 * ErrorResponse 에서 사용자에게 보여 줄 메시지를 고른다.
 *
 * errors 는 (a) 코드 없는 검증 실패(사람이 읽는 문장)와 (b) CodedApiException 의 details(화면 분기용
 * 기계 값, 예: {userId:"5"})로 쓰인다(#787). (b)만 code 를 싣고(GlobalExceptionHandler) 토스트에 "5" 가
 * 뜨면 안 되므로, code 가 있으면 errors 를 건너뛰고 message → fallback 순으로 고른다.
 */
function pickBestMessage(errData: ErrorResponse, fallback: string): string {
  if (errData.code == null && errData.errors) {
    const firstFieldMsg = Object.values(errData.errors)[0];
    if (firstFieldMsg) return firstFieldMsg;
  }
  return errData.message || fallback;
}

/**
 * Axios 에러에서 백엔드 ErrorResponse.message를 추출한다.
 * responseType: 'blob' 요청에서 서버가 에러를 반환하면 error.response.data가
 * Blob 형태로 오므로, 이를 텍스트로 읽어 JSON 파싱을 시도한다.
 */
export function extractApiError(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error) && error.response?.data) {
    const data = error.response.data;
    // responseType: 'blob' 요청의 에러 응답은 Blob 형태로 전달된다
    if (data instanceof Blob) {
      // 동기 콘텍스트에서는 fallback 반환 — 비동기 변환은 extractApiErrorAsync 사용
      return fallback;
    }
    const errData = data as ErrorResponse;
    return pickBestMessage(errData, fallback);
  }
  return fallback;
}

/**
 * Blob 응답 타입을 포함한 비동기 에러 메시지 추출.
 * responseType: 'blob' API 호출의 catch 블록에서 사용한다.
 */
export async function extractApiErrorAsync(
  error: unknown,
  fallback: string,
): Promise<string> {
  if (axios.isAxiosError(error) && error.response?.data) {
    const data = error.response.data;
    if (data instanceof Blob) {
      try {
        const text = await data.text();
        const parsed = JSON.parse(text) as ErrorResponse;
        return pickBestMessage(parsed, fallback);
      } catch {
        return fallback;
      }
    }
    const errData = data as ErrorResponse;
    return pickBestMessage(errData, fallback);
  }
  return fallback;
}

/**
 * Bean Validation 400(code 없음, 필드별 errors)을 폼 필드 오류로 옮긴다 (WD-270).
 *
 * 폼 상단 오류 한 줄만으로는 어느 칸이 문제인지 알 수 없으므로, 폼이 아는 필드(fields)는 해당 입력 아래에 표시한다.
 * 폼이 모르는 필드의 문구는 버리지 않고 root(폼 상단)로 모은다 — 서버 DTO 에 필드가 늘어도 문구가 조용히 사라지지 않게.
 * code 가 있는 errors 는 화면 분기용 기계 값(#787)이라 건드리지 않는다.
 *
 * @returns 하나라도 setError 했으면 true — 호출부는 이때 자체 폴백 문구를 건너뛴다.
 */
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly string[],
): boolean {
  if (!axios.isAxiosError(error) || error.response?.status !== 400) return false;
  const data = error.response.data as ErrorResponse | undefined;
  if (!data?.errors || data.code != null) return false;

  const entries = Object.entries(data.errors).filter(([, message]) => message);
  const known = entries.filter(([field]) => fields.includes(field));
  const unknownMessages = entries.filter(([field]) => !fields.includes(field)).map(([, message]) => message);
  for (const [field, message] of known) setError(field as Path<T>, { message });
  if (unknownMessages.length > 0) setError('root', { message: unknownMessages.join(' ') });
  return entries.length > 0;
}

export function handleApiError(error: unknown, fallback: string): void {
  toast.error(extractApiError(error, fallback));
}

/**
 * Blob responseType 요청의 에러를 처리하고 토스트로 표시한다.
 */
export async function handleApiErrorAsync(
  error: unknown,
  fallback: string,
): Promise<void> {
  const message = await extractApiErrorAsync(error, fallback);
  toast.error(message);
}

/** 권한 부족(403) 응답인가 — 목록 조회 실패를 「데이터 없음」과 구분해 권한 안내를 보일 때 쓴다 (WD-122). */
export function isForbiddenError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 403;
}

/**
 * 찾을 수 없음(404) 응답인가 (WD-162).
 * 스마트 작업 리포트·실행 조회는 「없음」과 「작업 작성자가 아님」을 똑같이 404 로 숨기므로, 화면이 두 경우를 함께 말하는 안내를 고를 때 쓴다.
 */
export function isNotFoundError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 404;
}

/**
 * 충돌(409) 응답인가 (WD-367) — 요청 사이 다른 탭·사람이 같은 것을 먼저 바꿔 서버 상태와 화면이 어긋났다(예: 이미 대시보드에 추가된 차트).
 * 코드까지 보는 편집 충돌은 isEditConflict 를 쓴다.
 */
export function isConflictError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 409;
}

/** 워크스페이스 멤버가 아닌 사용자 404 의 코드(WD-288) — api UserNotFoundException.NOT_IN_WORKSPACE_CODE 와 같은 값. */
export const USER_NOT_IN_WORKSPACE_CODE = 'USER_NOT_IN_WORKSPACE';

/** 그 404 의 안내 문구(WD-288) — api UserNotFoundException.NOT_IN_WORKSPACE_MESSAGE 와 같다. 서버 message 가 없을 때의 대체 문구로 쓴다. */
export const MEMBER_GONE_MESSAGE = '이 워크스페이스에서 찾을 수 없는 사용자입니다. 이미 제거되었을 수 있습니다.';

/**
 * 사용자 관리 대상이 이 워크스페이스의 멤버가 아니라는 404 인가(WD-288) — 열어 둔 사이 다른 관리자가 제거한 경우다.
 * 상태 코드만 보지 않고 code 까지 봐서, 없는 경로·역할 같은 다른 404 를 「제거된 멤버」로 오인하지 않는다.
 */
export function isMemberGoneError(error: unknown): boolean {
  return (
    axios.isAxiosError(error) &&
    error.response?.status === 404 &&
    (error.response.data as ErrorResponse | undefined)?.code === USER_NOT_IN_WORKSPACE_CODE
  );
}

/** 리포트 등급 차단 코드(WD-198) — api ProactiveJobService.REPORT_LEVEL_BLOCKED_CODE 와 같은 값. */
const REPORT_LEVEL_BLOCKED_CODE = 'REPORT_SECURITY_LEVEL_BLOCKED';

/** 리포트 등급 차단 안내 문구(WD-198, 사람이 정한 문구). 서버 message 와 같지만 web 이 상수로 들고 화면마다 복제하지 않는다. */
export const REPORT_LEVEL_BLOCKED_MESSAGE = '현재 보안 등급으로 열 수 없는 리포트입니다';

/**
 * 리포트 등급 차단(403 + code) 인가(WD-198) — 리포트를 만들 때 읽은 데이터의 등급이 작성자의 현재 등급보다 높다.
 * 조회 권한 부족(403, code 없음)과 구분해야 「조회 권한이 필요합니다」로 잘못 안내하지 않는다.
 */
export function isReportLevelBlocked(error: unknown): boolean {
  return (
    axios.isAxiosError(error) &&
    error.response?.status === 403 &&
    (error.response.data as ErrorResponse | undefined)?.code === REPORT_LEVEL_BLOCKED_CODE
  );
}

/** 지식그래프 읽기 제한 코드(WD-28) — api GraphReadGate.RESTRICTED_CODE 와 같은 값. */
const GRAPH_READ_RESTRICTED_CODE = 'GRAPH_READ_RESTRICTED';

/**
 * 그래프 읽기 제한 안내 문구(스펙 원문). 서버 message 를 쓰지 않고 web 이 이 상수를 쓴다 — 코드와 문구를 한곳에 둬
 * 화면마다 문자열을 복제하다 어긋나는 일을 막는다.
 */
export const GRAPH_READ_RESTRICTED_MESSAGE =
  '이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 표시할 수 없습니다.';

/**
 * 그래프 읽기 제한(403 + code) 인가. 메시지 문자열이 아니라 코드로 판별한다 — 화면 문구는 web 이 스펙 원문을 직접 쓴다.
 */
export function isGraphReadRestricted(error: unknown): boolean {
  return (
    axios.isAxiosError(error) &&
    error.response?.status === 403 &&
    (error.response.data as ErrorResponse | undefined)?.code === GRAPH_READ_RESTRICTED_CODE
  );
}

/** 편집 충돌 코드(WD-353) — api EditConflictException.CODE 와 같은 값. */
const EDIT_CONFLICT_CODE = 'EDIT_CONFLICT';

/**
 * 편집 충돌(409 + code) 인가(WD-353) — 편집을 시작한 뒤 다른 사람·다른 탭이 먼저 저장했다.
 * 같은 409 인 이름 중복과 코드로 구분한다 — 충돌이면 토스트 대신 EditConflictDialog(다시 불러오기 / 덮어쓰기)를 띄운다.
 */
export function isEditConflict(error: unknown): boolean {
  return (
    axios.isAxiosError(error) &&
    error.response?.status === 409 &&
    (error.response.data as ErrorResponse | undefined)?.code === EDIT_CONFLICT_CODE
  );
}
