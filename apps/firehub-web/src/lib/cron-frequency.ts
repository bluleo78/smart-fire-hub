import cronstrue from 'cronstrue/i18n';

/** 1분보다 잦은 실행 주기 거부 문구(WD-366) — 서버 ProactiveCron.TOO_FREQUENT_MESSAGE 와 같다. */
export const CRON_TOO_FREQUENT_MESSAGE =
  '실행 주기는 1분보다 짧게 할 수 없습니다 — 「초」 칸에는 숫자 하나만 쓸 수 있습니다 (예: 0 * * * * * → 매분)';

/**
 * 1분에 한 번보다 자주 실행되는 cron 이면 거부 문구를, 아니면 undefined 를 돌려준다(WD-366).
 *
 * 파이프라인 트리거·스마트 작업은 초 단위로 돌 이유가 없는데, 「* * * * * *」(매초)가 저장되면 실행이 끝나자마자 다음이 이어진다.
 * 분 이상 칸은 아무리 잦아도 1분 간격이 최소라 6칸 식의 초 칸만 본다 — 숫자 하나면 1분에 최대 한 번이다.
 * 5칸 식(초 칸 없음)과 형식이 틀린 식은 판정하지 않는다(형식 검사가 따로 거른다).
 */
export function cronTooFrequentMessage(cron: string | undefined | null): string | undefined {
  const fields = (cron ?? '').trim().split(/\s+/);
  if (fields.length !== 6) return undefined;
  return /^\d+$/.test(fields[0]) ? undefined : CRON_TOO_FREQUENT_MESSAGE;
}

/** 형식 오류 문구 — 트리거 추가·편집 창 저장 검사가 함께 쓴다. */
export const CRON_INVALID_MESSAGE = '유효하지 않은 Cron 표현식입니다';

/**
 * 트리거 저장 전 cron 검사(형식 → 1분보다 잦음) — 추가·편집 창이 같은 규칙·문구를 쓰게 한 곳에 둔다.
 * 빈 값 안내는 창마다 문구가 같아 호출부에 둔다.
 */
export function cronInputError(cron: string): string | undefined {
  try {
    cronstrue.toString(cron, { locale: 'ko' });
  } catch {
    return CRON_INVALID_MESSAGE;
  }
  return cronTooFrequentMessage(cron);
}
