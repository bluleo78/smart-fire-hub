import { CronExpressionParser } from 'cron-parser';

import { TIMEZONE_OPTIONS } from './timezone-data';

/**
 * cron 표현식과 타임존으로 다음 실행 시간을 계산한다.
 * 실패 시 null 반환.
 */
export function getNextRunDate(cronExpression: string, timezone: string): Date | null {
  try {
    const interval = CronExpressionParser.parse(cronExpression, { tz: timezone });
    return interval.next().toDate();
  } catch {
    return null;
  }
}

/**
 * 다음 실행 시간을 사람이 읽기 쉬운 형식으로 포맷한다.
 * 편집 폼용: "2026-04-03 (목) 09:00 KST"
 */
export function formatNextRun(date: Date, timezone: string): string {
  const formatted = date.toLocaleDateString('ko-KR', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
  });
  const time = date.toLocaleTimeString('ko-KR', {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  return `${formatted} ${time}`;
}

/**
 * 목록 페이지용 간결한 포맷.
 * 24시간 이내 → "내일 09:00", 그 외 → "4월 3일 09:00"
 */
export function formatNextRunShort(date: Date, timezone: string): string {
  const now = new Date();
  const diffMs = date.getTime() - now.getTime();
  const diffHours = diffMs / (1000 * 60 * 60);

  const time = date.toLocaleTimeString('ko-KR', {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  if (diffHours <= 24) {
    const todayInTz = new Date().toLocaleDateString('ko-KR', { timeZone: timezone });
    const dateInTz = date.toLocaleDateString('ko-KR', { timeZone: timezone });
    return todayInTz === dateInTz ? `오늘 ${time}` : `내일 ${time}`;
  }

  const formatted = date.toLocaleDateString('ko-KR', {
    timeZone: timezone,
    month: 'long',
    day: 'numeric',
  });
  return `${formatted} ${time}`;
}

/**
 * 그 시각에 해당 시간대가 쓰는 짧은 이름(KST·EDT·UTC+9 등). 같은 실행 시각을 다른 시간대로 보이는 화면들이
 * 어느 시간대 기준인지 붙여 보이기 위해 쓴다(WD-385).
 * - 서머타임이 있는 지역은 Intl 이 그 날짜의 약어(EST/EDT)를 준다.
 * - Intl 이 「GMT+9」처럼 오프셋만 주는 지역은, 고정 약어 표(KST·JST 등)의 오프셋이 그 시각과 같으면 그 약어를, 아니면 「UTC+9」를 쓴다.
 */
export function timeZoneLabel(date: Date, timezone: string): string {
  try {
    const part = (opts: Intl.DateTimeFormatOptions) =>
      new Intl.DateTimeFormat('en-US', { timeZone: timezone, ...opts })
        .formatToParts(date)
        .find((p) => p.type === 'timeZoneName')?.value ?? '';
    const short = part({ timeZoneName: 'short' });
    if (short && !short.startsWith('GMT')) return short;
    // 「GMT+9」·「GMT」 → 「UTC+9」·「UTC+0」
    const offset = part({ timeZoneName: 'shortOffset' }).replace(/^GMT/, 'UTC') || 'UTC';
    const normalized = offset === 'UTC' ? 'UTC+0' : offset;
    const known = TIMEZONE_OPTIONS.find((o) => o.value === timezone);
    if (known && known.offset === normalized) return known.abbr;
    return normalized;
  } catch {
    return timezone;
  }
}

/** 보는 사람(브라우저)의 시간대 이름. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
