/**
 * failure-streak: 같은 도구가 같은 종류의 오류로 연속 실패하는 retry 폭주를 탐지한다.
 * 두 레이어(MCP 도구 래퍼=경고, 실행 루프=강제중단)가 공유하는 순수 로직.
 * agent가 동일 시그니처로 실패해도 다음 턴에 또 시도하여 토큰을 폭주 소모하는 패턴 차단용.
 */

/** 경고(Tier1)/강제중단(Tier2) 임계값 — 전 레이어 공통 기본값. */
export const WARN_AT = 4;
export const HALT_AT = 8;

/**
 * Tier1 경고 힌트 앞에 붙는 마커. 경고가 붙은 결과를 Tier2(루프)가 읽어도
 * 카운트 키가 바뀌지 않도록 normalizeErrorType가 이 마커 이후를 잘라낸다.
 */
export const FAILURE_WARN_SENTINEL = '⟦fs-warn⟧';

/** 모델에게 주입하는 경고 문구(이슈번호 미포함). */
export const FAILURE_WARN_HINT =
  FAILURE_WARN_SENTINEL +
  '\n[시스템] 이 도구가 동일한 오류로 여러 번 연속 실패하고 있습니다. ' +
  '다른 접근을 시도하고, 그래도 해결되지 않으면 사용자에게 현재 상황과 막힌 지점을 보고하세요.';

/**
 * 오류 메시지를 "에러종류"로 정규화한다(느슨하게 묶기).
 * sentinel 이후 제거 → 소문자화 → 따옴표 식별자 제거 → 숫자 제거 → 공백 정리 → 절단(120).
 * 예) `column "foo" does not exist` 와 `column "bar" does not exist` → 동일 종류.
 */
export function normalizeErrorType(text: string): string {
  if (!text) return 'unknown';
  let s = text.split(FAILURE_WARN_SENTINEL)[0];
  if (!s.trim()) return 'unknown';
  s = s.toLowerCase();
  s = s.replace(/["'`][^"'`]*["'`]/g, '"x"');
  s = s.replace(/\d+/g, '#');
  s = s.replace(/\s+/g, ' ').trim();
  return s.slice(0, 120);
}

export interface FailureRecord {
  warn: boolean;
  halt: boolean;
  errorType: string;
  count: number;
}

export interface FailureTracker {
  /** 도구 1회 결과를 기록하고 경고/중단 판정을 반환. */
  record(toolName: string, resultText: string, isError: boolean): FailureRecord;
}

/** 키 구분자(도구명에 등장하지 않는 제어문자). */
const SEP = '␟';

/**
 * createTracker: 호출(메시지 턴) 스코프 연속 실패 카운터.
 * 키 = `${toolName}${SEP}${errorType}` (입력값 제외 = 느슨).
 * 같은 도구가 한 번이라도 성공하면 그 도구의 모든 키를 리셋(self-correction 보호).
 */
export function createTracker(opts: { warnAt?: number; haltAt?: number } = {}): FailureTracker {
  const warnAt = opts.warnAt ?? WARN_AT;
  const haltAt = opts.haltAt ?? HALT_AT;
  const counts = new Map<string, number>();
  const warned = new Set<string>();

  return {
    record(toolName, resultText, isError) {
      if (!isError) {
        const prefix = toolName + SEP;
        for (const k of [...counts.keys()]) {
          if (k.startsWith(prefix)) {
            counts.delete(k);
            warned.delete(k);
          }
        }
        return { warn: false, halt: false, errorType: '', count: 0 };
      }
      const errorType = normalizeErrorType(resultText);
      const key = toolName + SEP + errorType;
      const count = (counts.get(key) ?? 0) + 1;
      counts.set(key, count);
      let warn = false;
      if (count >= warnAt && !warned.has(key)) {
        warn = true;
        warned.add(key);
      }
      return { warn, halt: count >= haltAt, errorType, count };
    },
  };
}

/** Tier2 강제중단 시 사용자에게 보여줄 요약 메시지. */
export function buildHaltMessage(toolName: string, lastError: string): string {
  const raw = lastError.split(FAILURE_WARN_SENTINEL)[0].trim();
  const summary = raw.length > 200 ? raw.slice(0, 200) + '…' : raw;
  return `'${toolName}' 도구가 동일한 오류로 반복 실패하여 자동으로 중단했습니다. 마지막 오류: ${summary} 다른 접근이 필요합니다.`;
}
