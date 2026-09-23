import type { ResolvedSettingResponse } from '../types/settings';

/**
 * 응답 배열을 키로 인덱싱한다 — 필드 단위 조회(기본값 힌트·저장 여부 판정)에 쓴다.
 *
 * 예전에는 여기에 필드 상태 판정(잠금/편집 가능)도 있었다. SMTP 가 워크스페이스 전용 폼으로
 * 옮겨가고(#712) AI 동작 설정은 전부 편집 가능하며 임베딩은 잠금이 하드코딩이라, 판정 함수의
 * 소비자가 사라져 지웠다.
 */
export function indexSettingsByKey(
  settings: ResolvedSettingResponse[],
): Record<string, ResolvedSettingResponse> {
  return Object.fromEntries(settings.map((s) => [s.key, s]));
}
