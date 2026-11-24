import { describeTimezone } from './timezone-data';

// 다음 실행 시각 「계산」은 화면에서 하지 않는다 — 서버 미리보기 API(useCronPreview)가 스케줄러와 같은 서머타임 규칙으로
// 계산한다(WD-391). 이 파일은 받은 시각의 표시 형식만 맡는다.

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
    // 고정 약어 표는 그 날짜 기준 약어·시차를 describeTimezone 이 계산한다(WD-417 — 표가 표준시/서머타임 쌍으로 바뀜)
    const known = describeTimezone(timezone, date);
    if (known.abbr && known.offset === normalized) return known.abbr;
    return normalized;
  } catch {
    return timezone;
  }
}

/** 보는 사람(브라우저)의 시간대 이름. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/**
 * 다음 실행 시각 뒤에 붙일 시간대 이름표(WD-1077). 고정 약어 표에 있으면 그 시각 기준 약어(KST·EST/EDT),
 * 표에 없는 지역(Asia/Kolkata 등)은 timeZoneLabel 의 「UTC+5:30」 로 대신한다 — 이름표가 비어 시각만 덩그러니 남지 않게.
 */
export function nextRunZoneLabel(date: Date, timezone: string): string {
  return describeTimezone(timezone, date).abbr || timeZoneLabel(date, timezone);
}

/**
 * 시간대 처리가 실패하면(백엔드는 「UTC+9」 같은 ZoneId 를 받지만 Intl 은 RangeError 로 거부한다) 브라우저 시간대로
 * 바꿔 보인다 — 한 행 때문에 목록 표나 상세 화면 전체가 깨지지 않게 한다. 이름표도 브라우저 시간대 것이라 어긋나지 않는다.
 */
function withZoneFallback(
  date: Date,
  timezone: string,
  format: (date: Date, tz: string) => string,
): string {
  try {
    return `${format(date, timezone)} ${nextRunZoneLabel(date, timezone)}`;
  } catch {
    const local = browserTimeZone();
    return `${format(date, local)} ${nextRunZoneLabel(date, local)}`;
  }
}

/** 상세·편집 미리보기용: 「2026. 11. 25. (수) 09:00 KST」 (WD-1077). */
export function formatNextRunWithZone(date: Date, timezone: string): string {
  return withZoneFallback(date, timezone, formatNextRun);
}

/** 목록용: 「내일 09:00 KST」 (WD-1077). */
export function formatNextRunShortWithZone(date: Date, timezone: string): string {
  return withZoneFallback(date, timezone, formatNextRunShort);
}
