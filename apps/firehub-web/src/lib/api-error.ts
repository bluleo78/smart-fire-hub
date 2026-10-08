import axios from 'axios';
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

/** 지식그래프 읽기 제한 코드(WD-28) — api GraphReadGate.RESTRICTED_CODE 와 같은 값. */
export const GRAPH_READ_RESTRICTED_CODE = 'GRAPH_READ_RESTRICTED';

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
