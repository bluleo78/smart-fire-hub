import { describe, expect, it } from 'vitest';

import {
  CRON_INVALID_MESSAGE,
  CRON_TOO_FREQUENT_MESSAGE,
  cronInputError,
  cronTooFrequentMessage,
} from './cron-frequency';

// WD-366 — 1분보다 잦은 cron 거부(서버 ProactiveCron.describeTooFrequent 와 같은 규칙)
describe('cronTooFrequentMessage', () => {
  it.each(['* * * * * *', '*/10 * * * * *', '0,30 * * * * *', '0-5 * * * * *'])(
    '%s 는 1분보다 잦아 거부한다',
    (cron) => {
      expect(cronTooFrequentMessage(cron)).toBe(CRON_TOO_FREQUENT_MESSAGE);
    }
  );

  it.each(['* * * * *', '0 9 * * *', '0 * * * * *', ' 30 */5 * * * * '])('%s 는 통과한다', (cron) => {
    expect(cronTooFrequentMessage(cron)).toBeUndefined();
  });

  it('비었거나 칸 수가 틀린 식은 판정하지 않는다', () => {
    expect(cronTooFrequentMessage('')).toBeUndefined();
    expect(cronTooFrequentMessage(undefined)).toBeUndefined();
    expect(cronTooFrequentMessage('* *')).toBeUndefined();
  });
});

describe('cronInputError', () => {
  it('형식 오류를 먼저, 그다음 1분보다 잦은 주기를 알린다', () => {
    expect(cronInputError('invalid-cron')).toBe(CRON_INVALID_MESSAGE);
    expect(cronInputError('* * * * * *')).toBe(CRON_TOO_FREQUENT_MESSAGE);
    expect(cronInputError('0 9 * * *')).toBeUndefined();
  });
});
