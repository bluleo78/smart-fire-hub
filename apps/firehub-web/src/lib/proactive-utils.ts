import {
  extractApiError,
  isBadRequestError,
  isForbiddenError,
  isNotFoundError,
  isReportHtmlMissing,
  isReportLevelBlocked,
  isUserSwitchedError,
  REPORT_HTML_MISSING_MESSAGE,
  REPORT_LOAD_ERROR_MESSAGE,
  USER_SWITCHED_MESSAGE,
} from './api-error';

/**
 * Proactive 메시지 공통 유틸리티
 */

/**
 * 리포트를 받았지만 작업 작성자가 아니라 전문을 열 수 없을 때의 안내 (WD-162).
 * 알림 패널·메시지 카드가 같은 문구를 쓰도록 한곳에 둔다 — 화면마다 복제하면 어긋난다.
 */
export const REPORT_OWNER_ONLY_NOTICE = '이 리포트 전문은 작업을 만든 사람만 열 수 있습니다.';

/**
 * 리포트 조회가 404 일 때의 안내 (WD-162).
 * 서버 404 는 「리포트가 없음」과 「작성자가 아님」을 구분해 주지 않으므로(존재 여부를 숨김) 두 경우를 함께 말한다.
 */
export const REPORT_NOT_FOUND_NOTICE =
  '리포트를 찾을 수 없습니다. 리포트 전문은 작업을 만든 사람만 열 수 있습니다.';

/** 실행 상세 조회가 404 일 때의 안내 (WD-162) — 리포트 안내와 같은 이유로 두 경우를 함께 말한다. */
export const EXECUTION_NOT_FOUND_NOTICE =
  '실행 정보를 찾을 수 없습니다. 실행 결과는 작업을 만든 사람만 열 수 있습니다.';

/** 작업 목록 — 주소의 작업 id 가 잘못돼 작업 상세로 보낼 수 없을 때의 대체 목적지(WD-661) */
const JOB_LIST_FALLBACK = { path: '/ai-insights/jobs', label: '작업 목록' } as const;

/**
 * 리포트 목록 — 작업을 읽을 수 있다고 아직 모를 때의 대체 목적지(WD-667·WD-671).
 * 작업 상세로 보내면 삭제(404)·작성자 아님(403)일 때 오류·권한 안내 화면에 떨어지므로 누구나 열 수 있는 목록으로 보낸다.
 */
export const REPORT_LIST_FALLBACK = { path: '/ai-insights/reports', label: '리포트 목록' } as const;

/**
 * 실행 상세·리포트 보기에서 돌아갈 기록이 없을 때 보낼 곳(WD-661·WD-671) — 같은 주소 파라미터를 쓰는 두 화면이 한 규칙을 쓴다.
 * - 작업 id 가 잘못되면 작업 목록
 * - 화면 자신의 조회(실행 단건·리포트 HTML)가 한 번이라도 성공했으면 작업을 읽을 수 있으니 작업 상세
 * - 그 밖(조회 중·멈춘 요청·403·404·다른 오류·잘못된 실행 id 로 조회하지 않음)은 리포트 목록
 *
 * 목적지만 정하려고 작업을 따로 조회하지 않는다(WD-671) — 예전엔 기록 없이 열 때마다 GET /jobs/{id} 를 더 부르고,
 * 응답을 기다리는 동안 돌아가기를 잠가 요청이 멈추면 돌아갈 길이 없었다.
 * jobReadable 은 그 화면이 지금 목적지를 읽을 수 있는지 **호출부가 정해** 넘긴다 — 리포트 뷰어는 조회 오류가 있으면
 * 캐시 데이터가 있어도 false 로 넘긴다(WD-1177): 화면이 권한·오류 안내인 동안 목적지를 작업 상세로 알리면
 * 그 안내 화면으로 되돌아가기 때문이다. 실행 상세는 실행 조회 데이터가 있으면 true 로 넘겨, 재조회가 잠깐 실패해도
 * 목적지가 도중에 뒤집히지 않게 한다.
 */
export function jobBackFallback(
  jobId: number | null | undefined,
  jobReadable: boolean,
): { path: string; label: string } {
  if (jobId == null) return JOB_LIST_FALLBACK;
  return jobReadable ? { path: `/ai-insights/jobs/${jobId}`, label: '작업 상세' } : REPORT_LIST_FALLBACK;
}

export interface Section {
  key: string;
  label: string;
  content: string;
  data?: unknown;
}

/**
 * 프로액티브 실행 결과 또는 메시지 content에서 표시할 섹션 목록을 추출한다.
 * - sections 배열이 있으면 그대로 사용 (기존 실행 결과)
 * - summary 문자열이 있으면 단일 섹션으로 반환 (ChatDeliveryChannel의 요약)
 * - rawText가 있으면 단일 섹션으로 반환 (폴백)
 */
export function getSections(content: Record<string, unknown>): Section[] {
  if (Array.isArray(content.sections)) {
    return content.sections as Section[];
  }
  if (typeof content.summary === 'string' && content.summary) {
    return [{ key: 'summary', label: '요약', content: content.summary }];
  }
  if (typeof content.rawText === 'string') {
    return [{ key: 'content', label: '분석 결과', content: content.rawText }];
  }
  return [];
}

/**
 * 리포트 HTML 조회 오류 → 사용자 안내 문구 (WD-1098).
 * 뷰어·모달·실행 상세가 같은 규칙을 쓰도록 한곳에 둔다 — 화면마다 복제하면 어긋난다.
 * - 400: 서버 문구(완료 아님·결과 없음 같은 사용자용 원인). 없으면 고정 문구.
 * - 404 + HTML 없음: 서버 문구 그대로(작성자 확인을 통과한 뒤라 보여도 된다).
 * - 그 밖의 404: 작업 없음·작성자 아님을 함께 말하는 안내(내부 문구 숨김).
 * - 사용자 전환: 화면을 다시 여는 중이라는 안내.
 * - 그 밖(5xx·403·네트워크 등): 서버 원문을 숨기고 고정 문구.
 * 403(권한 부족)·등급 차단은 호출부가 먼저 전용 안내로 가른다 — 이 함수는 그 밖의 오류 문구만 정한다.
 */
export function reportHtmlErrorText(error: unknown): string {
  if (isUserSwitchedError(error)) return USER_SWITCHED_MESSAGE;
  if (isBadRequestError(error)) return extractApiError(error, REPORT_LOAD_ERROR_MESSAGE);
  if (isReportHtmlMissing(error)) return REPORT_HTML_MISSING_MESSAGE;
  if (isNotFoundError(error)) return REPORT_NOT_FOUND_NOTICE;
  return REPORT_LOAD_ERROR_MESSAGE;
}

/** 리포트 HTML 조회 오류의 화면 갈래 — 등급 차단·권한 부족(403)은 전용 안내, 그 밖은 문구. */
export type ReportHtmlErrorKind =
  | { kind: 'level-blocked' }
  | { kind: 'forbidden' }
  | { kind: 'message'; text: string };

/**
 * 리포트 HTML 조회 오류를 화면 갈래로 분류한다 (WD-1098).
 * 갈래 우선순위(등급 차단 > 권한 부족 > 그 밖의 문구)를 한곳에 둬 뷰어·모달·실행 상세가 어긋나지 않게 한다.
 * 등급 차단도 403 이므로 반드시 권한 부족보다 먼저 가른다. 오류가 없으면 null.
 */
export function reportHtmlErrorKind(error: unknown): ReportHtmlErrorKind | null {
  if (!error) return null;
  if (isReportLevelBlocked(error)) return { kind: 'level-blocked' };
  if (isForbiddenError(error)) return { kind: 'forbidden' };
  return { kind: 'message', text: reportHtmlErrorText(error) };
}

/** 리포트 본문을 무엇으로 표시할지 — 실행 상세·리포트 모달·리포트 뷰어 세 화면이 공유하는 판정 결과. */
export type ReportBodyState =
  | { state: 'level-blocked' }
  | { state: 'forbidden' }
  | { state: 'html' }
  | { state: 'fallback' }
  | { state: 'message'; text: string };

/**
 * 리포트 본문 표시 상태 판정 (WD-1177·WD-1098).
 * 실행 상세·리포트 모달·리포트 뷰어 세 화면이 한 우선순위를 쓰게 한곳에 둔다 — 화면마다 복제하면 어긋난다.
 * 우선순위: 등급 차단 → 권한 부족(403) → 캐시 HTML → 폴백 → 문구.
 * - 403 이어도 등급 차단·권한 부족일 때만 전용 상태로 가른다(그 밖의 오류는 캐시 HTML 이 이긴다).
 *   권한 403 은 캐시 HTML 보다 앞선다 — 권한을 잃은 뒤 재조회가 403 이면 남은 본문을 보여주면 안 되기 때문이다(WD-1177).
 * - 문구는 오류가 있으면 reportHtmlErrorKind 가 만든 서버 사유 문구, 오류가 없으면 '리포트가 없습니다.'
 * - `meta` 로 실행(메타) 조회의 오류와 읽힘 여부를 함께 넘기면, 실행을 못 읽은 경우 그 문구가 HTML 오류 문구보다
 *   먼저다(WD-1098) — HTML 없음 404 를 먼저 보이면 섹션을 못 읽었을 뿐인 실행도 「리포트가 없다」로 읽힌다.
 *
 * @param htmlError 리포트 HTML 조회 오류
 * @param rawHtml 캐시된 HTML 본문(없으면 null)
 * @param hasFallback 마크다운 폴백에 보일 섹션이 있는가
 * @param meta 실행(메타) 조회 오류와 읽힘 여부 — 모달만 넘긴다(실행 상세는 실행을 읽은 뒤에만 이 자리에 온다)
 */
export function reportBodyState(
  htmlError: unknown,
  rawHtml: string | null,
  hasFallback: boolean,
  meta?: { error?: unknown; executionLoaded?: boolean },
): ReportBodyState {
  const htmlKind = reportHtmlErrorKind(htmlError);
  const metaKind = reportHtmlErrorKind(meta?.error);
  if (htmlKind?.kind === 'level-blocked' || metaKind?.kind === 'level-blocked') return { state: 'level-blocked' };
  if (htmlKind?.kind === 'forbidden' || metaKind?.kind === 'forbidden') return { state: 'forbidden' };
  if (rawHtml) return { state: 'html' };
  if (hasFallback) return { state: 'fallback' };
  if (meta?.executionLoaded === false && metaKind?.kind === 'message') {
    return { state: 'message', text: metaKind.text };
  }
  if (htmlKind?.kind === 'message') return { state: 'message', text: htmlKind.text };
  return { state: 'message', text: '리포트가 없습니다.' };
}
