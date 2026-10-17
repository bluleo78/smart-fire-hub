/**
 * 사용자 시간대 KST(UTC+9)의 단일 정의. 서머타임이 없어 상수 오프셋이면 충분하다.
 * 감사 조회 경계(audit-tools)와 도구 결과 표기(formatKst)가 같은 값을 쓰도록 여기 한 곳에 둔다 —
 * 사용자별 시간대가 생기면 이 파일만 일반화하면 된다.
 */
export const KST_UTC_OFFSET = '+09:00';
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/**
 * 시각을 KST 벽시계 `YYYY-MM-DD HH:mm` 로 포맷한다(WD-68).
 *
 * 왜: 약한 모델(opencode 경로)이 tool_result 의 ISO(UTC) 시각을 KST 로 직접 옮기다 월을 틀렸다.
 * 도구가 변환된 문자열을 병기하면 모델은 그대로 인용만 하면 된다. 호스트 시간대·로케일에 의존하지 않도록
 * `toLocaleString` 대신 상수 오프셋을 더한 뒤 UTC ISO 문자열에서 잘라낸다. 입력을 Date 로 한정해
 * 문자열 파싱 실패(Invalid Date → RangeError)가 도구 응답 전체를 깨뜨릴 여지를 두지 않는다.
 */
export function formatKst(value: Date): string {
  return new Date(value.getTime() + KST_OFFSET_MS).toISOString().slice(0, 16).replace('T', ' ');
}
