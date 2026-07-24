import type { ValidationErrorDetail } from '../types/dataImport';

/**
 * ImportResponse.errorDetails 는 백엔드가 audit_log 메타데이터에 저장할 때
 * `{ errors: ValidationErrorDetail[] }` 를 JSON.stringify 한 "문자열"로 들어온다
 * (JSONB 컬럼 안에 중첩된 문자열 — 이중 인코딩). 다만 향후 백엔드가 객체 그대로
 * 내려줄 가능성도 배제할 수 없어 문자열/객체 양쪽을 방어적으로 처리한다.
 */
export function parseErrorDetails(raw: unknown): ValidationErrorDetail[] | null {
  if (!raw) return null;
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw;
    const errors = (obj as { errors?: unknown }).errors;
    return Array.isArray(errors) ? (errors as ValidationErrorDetail[]) : null;
  } catch {
    return null;
  }
}
