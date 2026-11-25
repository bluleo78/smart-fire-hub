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

/** 형식기 종류별 로캘·옵션 — formatNextRunShort 가 쓰는 형식기를 한곳에서 정의한다. 옵션은 예전 toLocale 호출과 같다. */
const FORMATTER_SPECS = {
  // 달력 날짜 판정용 숫자 조각 (formatToParts)
  ymd: { locale: 'en-US', options: { year: 'numeric', month: 'numeric', day: 'numeric' } },
  time: { locale: 'ko-KR', options: { hour: '2-digit', minute: '2-digit', hour12: false } },
  monthDay: { locale: 'ko-KR', options: { month: 'long', day: 'numeric' } },
  yearMonthDay: { locale: 'ko-KR', options: { year: 'numeric', month: 'long', day: 'numeric' } },
} as const satisfies Record<string, { locale: string; options: Intl.DateTimeFormatOptions }>;

/** 종류+시간대별 형식기 캐시 — 목록 행마다 Intl.DateTimeFormat 을 새로 만드는 비용을 피한다. 생성에 성공한 것만 담는다. */
const formatterCache = new Map<string, Intl.DateTimeFormat>();

function getFormatter(kind: keyof typeof FORMATTER_SPECS, timezone: string): Intl.DateTimeFormat {
  const key = `${kind}|${timezone}`;
  let formatter = formatterCache.get(key);
  if (!formatter) {
    const spec = FORMATTER_SPECS[kind];
    // 잘못된 시간대면 여기서 RangeError 가 나야 withZoneFallback 이 브라우저 시간대로 바꾼다 — 실패는 캐시하지 않는다
    formatter = new Intl.DateTimeFormat(spec.locale, { timeZone: timezone, ...spec.options });
    formatterCache.set(key, formatter);
  }
  return formatter;
}

/**
 * 어떤 시각이 그 시간대 달력으로 몇 년 몇 번째 날인지 구한다(WD-1147).
 * ko-KR 날짜 문자열을 쪼개 비교하면 로캘 표기가 바뀔 때 조용히 깨지고, 24시간 경과로 「내일」을 재면
 * 서머타임으로 23·25시간인 날에 어긋나므로 Intl 이 주는 숫자 조각(formatToParts)으로 달력 날짜를 직접 비교한다.
 * dayNumber 는 달력 날짜를 1970-01-01 부터 센 일수라 월말·연말을 넘겨도 「다음 날」이 정확히 +1 이다.
 */
function zonedYmd(date: Date, timezone: string): { year: number; dayNumber: number } {
  const parts: Record<string, number> = {};
  for (const p of getFormatter('ymd', timezone).formatToParts(date)) parts[p.type] = Number(p.value);
  return { year: parts.year, dayNumber: Date.UTC(parts.year, parts.month - 1, parts.day) / 86400000 };
}

/**
 * 목록 페이지용 간결한 포맷. 날짜 판정은 모두 그 시간대의 달력 기준이다(WD-1147).
 * - 그 시간대 기준 날짜가 오늘이면(이미 지난 시각 포함) → "오늘 09:00"
 * - 아직 오지 않았고 그 시간대 달력으로 내일이면 → "내일 09:00" (서머타임 날에도 24시간 경과가 아니라 날짜로 본다)
 * - 그 외(이미 지난 날짜 포함) → "4월 3일 09:00", 올해가 아니면 "2026년 12월 30일 09:00"
 * 지난 날짜를 「내일」로 보이면 서버가 갱신하지 못한 낡은 시각이 곧 실행될 것처럼 읽힌다(WD-1099).
 * 「지남」 표시는 이 함수가 아니라 formatNextRunShortWithZone 이 붙인다.
 */
export function formatNextRunShort(date: Date, timezone: string, now: Date = new Date()): string {
  const time = getFormatter('time', timezone).format(date);

  const today = zonedYmd(now, timezone);
  const target = zonedYmd(date, timezone);
  if (target.dayNumber === today.dayNumber) {
    return `오늘 ${time}`;
  }
  // 「내일」은 아직 오지 않은 시각만 — 지난 날짜는 날짜 표기로 (WD-1099)
  if (target.dayNumber - today.dayNumber === 1 && date > now) {
    return `내일 ${time}`;
  }

  // 올해가 아닌 날짜(해를 넘긴 낡은 시각 등)는 연도를 붙여 올해 날짜로 읽히지 않게 한다 (WD-1099)
  const formatted = getFormatter(target.year === today.year ? 'monthDay' : 'yearMonthDay', timezone).format(date);
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

/** 작업 시간대가 비었을 때의 기본값 — 서버 스케줄러가 시간대 없는 작업을 Asia/Seoul 로 돌리는 규칙과 같다 (WD-1099). */
const DEFAULT_JOB_TIMEZONE = 'Asia/Seoul';

/**
 * 시간대 처리가 실패하면(백엔드는 「UTC+9」 같은 ZoneId 를 받지만 Intl 은 RangeError 로 거부한다) 브라우저 시간대로
 * 바꿔 보인다 — 한 행 때문에 목록 표나 상세 화면 전체가 깨지지 않게 한다. 이름표도 브라우저 시간대 것이라 어긋나지 않는다.
 */
function withZoneFallback(
  date: Date,
  timezone: string | null | undefined,
  format: (date: Date, tz: string) => string,
): string {
  // 앞뒤 공백을 떼고, 비었으면 서버 스케줄러처럼 Asia/Seoul 로 본다 — 빈 값·공백 섞인 값을 그대로 Intl 에 넘기면
  // RangeError 로 브라우저 시간대가 보여 실제 실행 시각과 어긋난다 (WD-1099)
  // 서버 nextExecuteAt 을 못 읽어 Invalid Date 가 되면 Intl 이 RangeError 를 던지는데, 그건 시간대 문제가 아니라 시각 문제라
  // 브라우저 시간대로 바꿔도 소용없다 — 목록 전체가 깨지지 않게 시각 없음 표기 「-」 로 대신한다(formatters.ts 와 같은 규칙)
  if (Number.isNaN(date.getTime())) return '-';
  const zone = timezone?.trim() || DEFAULT_JOB_TIMEZONE;
  try {
    return `${format(date, zone)} ${nextRunZoneLabel(date, zone)}`;
  } catch {
    const local = browserTimeZone();
    return `${format(date, local)} ${nextRunZoneLabel(date, local)}`;
  }
}

/** 상세·편집 미리보기용: 「2026. 11. 25. (수) 09:00 KST」 (WD-1077). */
export function formatNextRunWithZone(date: Date, timezone: string | null | undefined): string {
  return withZoneFallback(date, timezone, formatNextRun);
}

/**
 * 목록용: 「내일 09:00 KST」 (WD-1077). 지난 시각은 「4월 10일 09:00 KST · 지남」 (WD-1099, WD-1147).
 * 「지남」은 서버가 갱신하지 못한 낡은 시각이 곧 실행될 것처럼 읽히지 않게 목록에서만 붙인다(상세·편집 표기에는 없음).
 * 시간대 대체 경로를 거친 결과에도 붙도록 withZoneFallback 뒤에서 판정한다.
 */
export function formatNextRunShortWithZone(date: Date, timezone: string | null | undefined): string {
  // 시계를 한 번만 읽어 날짜 문구(오늘/내일)와 「지남」 판정이 어긋나지 않게 한다
  const now = new Date();
  const text = withZoneFallback(date, timezone, (d, tz) => formatNextRunShort(d, tz, now));
  // 잘못된 시각은 NaN 비교가 false 라 「지남」이 붙지 않는다
  return date.getTime() < now.getTime() ? `${text} · 지남` : text;
}
