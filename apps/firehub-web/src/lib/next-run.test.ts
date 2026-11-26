/**
 * next-run 단위 테스트 — cron 다음 실행 시간 계산/포맷.
 */
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import {
  browserTimeZone,
  formatExecutionStartInZone,
  formatInstantInZone,
  formatNextRun,
  formatNextRunShort,
  formatNextRunShortWithZone,
  formatNextRunWithZone,
  nextRunZoneLabel,
  timeZoneLabel,
} from './next-run';

describe('formatNextRun', () => {
  it('날짜/요일/시각 포함 문자열 반환', () => {
    const date = new Date('2026-04-11T00:00:00Z');
    const result = formatNextRun(date, 'Asia/Seoul');
    expect(typeof result).toBe('string');
    expect(result.length).toBeGreaterThan(0);
  });
});

describe('formatNextRunShort', () => {
  beforeAll(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-04-11T00:00:00Z'));
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('24시간 이내의 오늘 시각', () => {
    const date = new Date('2026-04-11T05:00:00Z');
    const result = formatNextRunShort(date, 'Asia/Seoul');
    expect(typeof result).toBe('string');
    expect(result).toMatch(/오늘|내일/);
  });

  it('24시간 초과는 월일+시간', () => {
    const date = new Date('2026-04-15T05:00:00Z');
    const result = formatNextRunShort(date, 'Asia/Seoul');
    expect(typeof result).toBe('string');
    expect(result).not.toMatch(/오늘|내일/);
  });
});

describe('formatNextRunShort — 지난 시각 (WD-1099)', () => {
  beforeAll(() => {
    vi.useFakeTimers();
    // KST 2026-04-11 15:00
    vi.setSystemTime(new Date('2026-04-11T06:00:00Z'));
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('오늘 이미 지난 시각은 「내일」이 아니라 「오늘」', () => {
    expect(formatNextRunShort(new Date('2026-04-11T00:00:00Z'), 'Asia/Seoul')).toBe('오늘 09:00');
  });
  it('어제 시각은 「내일」이 아니라 날짜', () => {
    expect(formatNextRunShort(new Date('2026-04-10T00:00:00Z'), 'Asia/Seoul')).toBe('4월 10일 09:00');
  });
  it('며칠 지난 시각도 날짜', () => {
    expect(formatNextRunShort(new Date('2026-04-08T00:00:00Z'), 'Asia/Seoul')).toBe('4월 8일 09:00');
  });
  it('미래 회귀: 오늘·내일·그 이후', () => {
    expect(formatNextRunShort(new Date('2026-04-11T12:00:00Z'), 'Asia/Seoul')).toBe('오늘 21:00');
    expect(formatNextRunShort(new Date('2026-04-12T00:00:00Z'), 'Asia/Seoul')).toBe('내일 09:00');
    expect(formatNextRunShort(new Date('2026-04-14T00:00:00Z'), 'Asia/Seoul')).toBe('4월 14일 09:00');
  });
  it('날짜 비교는 UTC 가 아니라 그 시간대 기준', () => {
    // KST 4/12 01:00 — 다음 실행 KST 4/12 00:30 (UTC 로는 4/11)
    vi.setSystemTime(new Date('2026-04-11T16:00:00Z'));
    try {
      expect(formatNextRunShort(new Date('2026-04-11T15:30:00Z'), 'Asia/Seoul')).toBe('오늘 00:30');
    } finally {
      // 단언이 실패해도 시계를 describe 의 기준(KST 4/11 15:00)으로 되돌려 다음 시험에 새지 않게 한다 (WD-1099)
      vi.setSystemTime(new Date('2026-04-11T06:00:00Z'));
    }
  });
  it('앞뒤 공백이 붙은 시간대는 다듬어서 Asia/Seoul 로 본다', () => {
    // 공백을 그대로 Intl 에 넘기면 RangeError 로 브라우저 시간대가 보이므로, 다듬은 값으로 표기해야 한다 (WD-1099)
    const date = new Date('2026-04-14T00:00:00Z');
    expect(formatNextRunShortWithZone(date, ' Asia/Seoul ')).toBe('4월 14일 09:00 KST');
  });
  // 편집 칸의 시간대는 Select 라 비울 수 없다 — 빈 값은 옛 저장 행뿐이고 서버 스케줄러가 그런 행을 Asia/Seoul 로
  // 돌리므로, 화면도 같은 기본값으로 읽어야 실제 실행 시각과 어긋나지 않는다.
  it('시간대가 비어 있으면 Asia/Seoul 로 본다', () => {
    const date = new Date('2026-04-14T00:00:00Z');
    expect(formatNextRunShortWithZone(date, null)).toBe('4월 14일 09:00 KST');
    expect(formatNextRunShortWithZone(date, '')).toBe('4월 14일 09:00 KST');
    expect(formatNextRunWithZone(new Date('2026-11-25T00:00:00Z'), null)).toContain('09:00 KST');
  });
});

describe('timeZoneLabel (WD-385)', () => {
  it('서머타임 지역은 그 날짜의 약어를 준다', () => {
    expect(timeZoneLabel(new Date(Date.UTC(2026, 9, 29, 5, 30)), 'America/New_York')).toBe('EDT');
    expect(timeZoneLabel(new Date(Date.UTC(2026, 11, 29, 5, 30)), 'America/New_York')).toBe('EST');
  });
  it('오프셋만 주는 지역은 고정 약어 표의 약어를 쓴다', () => {
    expect(timeZoneLabel(new Date(Date.UTC(2026, 9, 29, 5, 30)), 'Asia/Seoul')).toBe('KST');
    expect(timeZoneLabel(new Date(Date.UTC(2026, 9, 29, 5, 30)), 'UTC')).toBe('UTC');
  });
  it('표에 없는 지역은 UTC 오프셋으로 보인다', () => {
    expect(timeZoneLabel(new Date(Date.UTC(2026, 9, 29, 5, 30)), 'Asia/Kolkata')).toBe('UTC+5:30');
  });
});

describe('시간대 이름표를 붙인 다음 실행 (WD-1077)', () => {
  const winter = new Date('2026-11-25T14:00:00Z');
  const summer = new Date('2026-07-15T13:00:00Z');

  it('서울은 KST', () => {
    expect(nextRunZoneLabel(new Date('2026-11-25T00:00:00Z'), 'Asia/Seoul')).toBe('KST');
    expect(formatNextRunWithZone(new Date('2026-11-25T00:00:00Z'), 'Asia/Seoul')).toContain('09:00 KST');
  });
  it('뉴욕은 겨울 EST, 여름 EDT', () => {
    expect(formatNextRunWithZone(winter, 'America/New_York')).toContain('09:00 EST');
    expect(formatNextRunWithZone(summer, 'America/New_York')).toContain('09:00 EDT');
  });
  it('표에 없는 Asia/Kolkata 는 UTC+5:30', () => {
    expect(nextRunZoneLabel(winter, 'Asia/Kolkata')).toBe('UTC+5:30');
    expect(formatNextRunWithZone(winter, 'Asia/Kolkata')).toContain('UTC+5:30');
    expect(formatNextRunShortWithZone(winter, 'Asia/Kolkata')).toContain('UTC+5:30');
  });
  it('Intl 이 모르는 시간대(UTC+9)여도 던지지 않고 비어 있지 않은 문자열을 준다', () => {
    expect(() => formatNextRunWithZone(winter, 'UTC+9')).not.toThrow();
    expect(formatNextRunWithZone(winter, 'UTC+9').length).toBeGreaterThan(0);
    expect(() => formatNextRunShortWithZone(winter, 'UTC+9')).not.toThrow();
    expect(formatNextRunShortWithZone(winter, 'UTC+9').length).toBeGreaterThan(0);
  });
});

describe('formatNextRunShort — 올해가 아닌 날짜는 연도를 붙인다 (WD-1099)', () => {
  beforeAll(() => {
    vi.useFakeTimers();
    // KST 2027-01-02 15:00
    vi.setSystemTime(new Date('2027-01-02T06:00:00Z'));
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('해를 넘긴 낡은 시각은 연도와 함께 보인다', () => {
    expect(formatNextRunShort(new Date('2026-12-30T00:00:00Z'), 'Asia/Seoul')).toBe('2026년 12월 30일 09:00');
  });
  it('올해 날짜는 연도 없이 보인다', () => {
    expect(formatNextRunShort(new Date('2027-01-05T00:00:00Z'), 'Asia/Seoul')).toBe('1월 5일 09:00');
  });
});

describe('지난 시각 「지남」·달력 판정 (WD-1147)', () => {
  beforeAll(() => {
    vi.useFakeTimers();
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('목록 표기는 지난 시각 뒤에 「 · 지남」을 붙인다', () => {
    // KST 2026-04-11 15:00
    vi.setSystemTime(new Date('2026-04-11T06:00:00Z'));
    expect(formatNextRunShortWithZone(new Date('2026-04-11T00:00:00Z'), 'Asia/Seoul')).toBe('오늘 09:00 KST · 지남');
    expect(formatNextRunShortWithZone(new Date('2026-04-10T00:00:00Z'), 'Asia/Seoul')).toBe('4월 10일 09:00 KST · 지남');
    expect(formatNextRunShortWithZone(new Date('2025-12-30T00:00:00Z'), 'Asia/Seoul')).toBe(
      '2025년 12월 30일 09:00 KST · 지남',
    );
  });
  it('미래이거나 지금과 같은 시각이면 「지남」이 없다', () => {
    vi.setSystemTime(new Date('2026-04-11T06:00:00Z'));
    expect(formatNextRunShortWithZone(new Date('2026-04-11T09:00:00Z'), 'Asia/Seoul')).toBe('오늘 18:00 KST');
    expect(formatNextRunShortWithZone(new Date('2026-04-11T06:00:00Z'), 'Asia/Seoul')).toBe('오늘 15:00 KST');
  });
  it('상세·편집용 표기에는 「지남」을 붙이지 않는다', () => {
    vi.setSystemTime(new Date('2026-04-11T06:00:00Z'));
    expect(formatNextRunWithZone(new Date('2026-04-10T00:00:00Z'), 'Asia/Seoul')).not.toContain('지남');
  });
  it('서머타임으로 23시간인 날에도 「내일」은 달력 다음 날만', () => {
    // 뉴욕 2026-03-08 00:30 EST — 이날은 23시간
    vi.setSystemTime(new Date('2026-03-08T05:30:00Z'));
    // 3/10 00:30 EDT: 경과 23시간이지만 모레라서 「내일」이 아니다
    expect(formatNextRunShort(new Date('2026-03-10T04:30:00Z'), 'America/New_York')).toBe('3월 10일 00:30');
    expect(formatNextRunShort(new Date('2026-03-09T03:59:00Z'), 'America/New_York')).toBe('오늘 23:59');
    expect(formatNextRunShort(new Date('2026-03-09T04:30:00Z'), 'America/New_York')).toBe('내일 00:30');
  });
  it('23시간짜리 날을 건너 24시간 안이어도 달력으로 모레면 「내일」이 아니다', () => {
    // 뉴욕 3/7 23:30 EST. 3/9 00:10 EDT 는 23시간 40분 뒤(3/8 이 23시간)라 옛 diffHours<=24 판정은 「내일」로 읽었지만 달력으로는 모레다
    vi.setSystemTime(new Date('2026-03-08T04:30:00Z'));
    expect(formatNextRunShort(new Date('2026-03-09T04:10:00Z'), 'America/New_York')).toBe('3월 9일 00:10');
  });
  it('같은 해 판정은 숫자 연도 조각으로 한다 — 다른 해만 연도가 붙는다', () => {
    vi.setSystemTime(new Date('2026-04-11T06:00:00Z'));
    expect(formatNextRunShort(new Date('2026-04-20T00:00:00Z'), 'Asia/Seoul')).toBe('4월 20일 09:00');
    expect(formatNextRunShort(new Date('2027-04-20T00:00:00Z'), 'Asia/Seoul')).toBe('2027년 4월 20일 09:00');
    expect(formatNextRunShort(new Date('2025-04-20T00:00:00Z'), 'Asia/Seoul')).toBe('2025년 4월 20일 09:00');
  });
  it('Intl 이 모르는 시간대(UTC+9)의 지난 시각도 던지지 않고 「 · 지남」으로 끝난다', () => {
    vi.setSystemTime(new Date('2026-04-11T06:00:00Z'));
    const past = new Date('2026-04-10T00:00:00Z');
    expect(() => formatNextRunShortWithZone(past, 'UTC+9')).not.toThrow();
    expect(formatNextRunShortWithZone(past, 'UTC+9').endsWith(' · 지남')).toBe(true);
  });
  it('잘못된 시각(Invalid Date)이어도 던지지 않고 「지남」이 없다', () => {
    // 서버 nextExecuteAt 이 깨지면 Intl 이 RangeError 를 던져 목록 표 전체가 깨졌었다
    const bad = new Date('not-a-date');
    expect(() => formatNextRunShortWithZone(bad, 'Asia/Seoul')).not.toThrow();
    expect(() => formatNextRunWithZone(bad, 'Asia/Seoul')).not.toThrow();
    // 코드베이스 규칙(formatters.ts)처럼 시각 없음은 「-」, 「지남」은 붙지 않는다
    expect(formatNextRunShortWithZone(bad, 'Asia/Seoul')).toBe('-');
    expect(formatNextRunWithZone(bad, 'Asia/Seoul')).toBe('-');
  });
});

describe('formatInstantInZone', () => {
  it('서버 UTC 시각을 작업 시간대+약어로 보인다', () => {
    expect(formatInstantInZone('2026-11-24T16:39:05', 'Asia/Seoul')).toBe('2026. 11. 25. (수) 01:39 KST');
  });
  it('시간대가 없거나 비면 Asia/Seoul 로 본다', () => {
    expect(formatInstantInZone('2026-11-24T16:39:05', null)).toBe('2026. 11. 25. (수) 01:39 KST');
    expect(formatInstantInZone('2026-11-24T16:39:05', undefined)).toBe('2026. 11. 25. (수) 01:39 KST');
    expect(formatInstantInZone('2026-11-24T16:39:05', '  ')).toBe('2026. 11. 25. (수) 01:39 KST');
  });
  it('Z 표기가 있는 시각도 같은 순간으로 읽는다', () => {
    expect(formatInstantInZone('2026-11-24T16:39:05Z', 'America/New_York')).toBe('2026. 11. 24. (화) 11:39 EST');
  });
  it('시각이 없거나 깨졌으면 「-」', () => {
    expect(formatInstantInZone(null, 'Asia/Seoul')).toBe('-');
    expect(formatInstantInZone(undefined, 'Asia/Seoul')).toBe('-');
    expect(formatInstantInZone('not-a-date', 'Asia/Seoul')).toBe('-');
  });
});

describe('formatExecutionStartInZone', () => {
  const ISO = '2026-11-24T16:39:05';
  it('작업 조회 중이면 「-」', () => {
    expect(formatExecutionStartInZone(ISO, { pending: true, error: false, timezone: undefined })).toBe('-');
  });
  it('재조회가 실패해도 아는 시간대(캐시)를 유지한다', () => {
    expect(formatExecutionStartInZone(ISO, { pending: false, error: true, timezone: 'America/New_York' })).toBe(
      '2026. 11. 24. (화) 11:39 EST',
    );
  });
  it('작업을 읽었고 시간대가 null 이면 Asia/Seoul', () => {
    expect(formatExecutionStartInZone(ISO, { pending: false, error: false, timezone: null })).toBe(
      '2026. 11. 25. (수) 01:39 KST',
    );
  });
  it('시간대를 모르고 조회가 실패했으면 브라우저 시간대', () => {
    expect(formatExecutionStartInZone(ISO, { pending: false, error: true, timezone: undefined })).toBe(
      formatInstantInZone(ISO, browserTimeZone()),
    );
  });
});

// 인자 now 로 「지남」·오늘/내일을 재판정한다 — 열어 둔 목록이 1분마다 새 now 를 넘기는 기반(WD-1167)
describe('formatNextRunShortWithZone — now 인자 (WD-1167)', () => {
  const date = new Date('2026-04-11T09:00:00Z'); // KST 18:00

  it('실행 시각 전이면 「지남」 없음', () => {
    expect(formatNextRunShortWithZone(date, 'Asia/Seoul', new Date('2026-04-11T08:59:00Z'))).toBe('오늘 18:00 KST');
  });

  it('now 가 실행 시각을 넘으면 「· 지남」', () => {
    expect(formatNextRunShortWithZone(date, 'Asia/Seoul', new Date('2026-04-11T09:01:00Z'))).toBe(
      '오늘 18:00 KST · 지남',
    );
  });

  it('자정을 넘기면 「내일」이 「오늘」로 바뀐다', () => {
    const midnight = new Date('2026-04-11T15:30:00Z'); // KST 4/12 00:30
    expect(formatNextRunShortWithZone(midnight, 'Asia/Seoul', new Date('2026-04-11T14:00:00Z'))).toBe(
      '내일 00:30 KST',
    );
    expect(formatNextRunShortWithZone(midnight, 'Asia/Seoul', new Date('2026-04-11T15:10:00Z'))).toBe(
      '오늘 00:30 KST',
    );
  });
});
