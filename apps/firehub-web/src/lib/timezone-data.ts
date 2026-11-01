/**
 * 스마트 작업 「타임존」 목록과 시간대 이름표(약어·UTC 시차).
 *
 * 약어·시차를 연중 고정 문자열로 두면 서머타임 기간에 틀린 값이 보였다(WD-417) — 10월 뉴욕을 「EST, UTC-5」(실제 EDT, UTC-4)로,
 * 시드니를 「AEST, UTC+10」(실제 AEDT, UTC+11)로 보였다. 그래서 시차는 그 시각 기준으로 Intl 에서 계산하고, 약어는 표준시·서머타임
 * 쌍 중 계산한 시차가 표준시와 같은지로 고른다. 약어를 Intl 의 timeZoneName 'short' 로 바로 얻지 않는 이유: 브라우저 언어에 따라
 * 「AEDT」 대신 「GMT+11」이 나오는 등 환경마다 달라, 같은 화면이 사람마다 다르게 보인다.
 */
export interface TimezoneOption {
  /** 저장·실행에 쓰는 IANA 이름 */
  value: string;
  /** 표준시 약어 */
  stdAbbr: string;
  /** 서머타임 약어 — 서머타임이 없는 시간대는 없다 */
  dstAbbr?: string;
  /** 표준시의 UTC 시차(분). 그 시각의 시차가 이와 다르면 서머타임이다. */
  stdOffsetMinutes: number;
}

export const TIMEZONE_OPTIONS: TimezoneOption[] = [
  { value: 'Asia/Seoul', stdAbbr: 'KST', stdOffsetMinutes: 540 },
  { value: 'Asia/Tokyo', stdAbbr: 'JST', stdOffsetMinutes: 540 },
  { value: 'Asia/Shanghai', stdAbbr: 'CST', stdOffsetMinutes: 480 },
  { value: 'Asia/Singapore', stdAbbr: 'SGT', stdOffsetMinutes: 480 },
  { value: 'America/New_York', stdAbbr: 'EST', dstAbbr: 'EDT', stdOffsetMinutes: -300 },
  { value: 'America/Chicago', stdAbbr: 'CST', dstAbbr: 'CDT', stdOffsetMinutes: -360 },
  { value: 'America/Denver', stdAbbr: 'MST', dstAbbr: 'MDT', stdOffsetMinutes: -420 },
  { value: 'America/Los_Angeles', stdAbbr: 'PST', dstAbbr: 'PDT', stdOffsetMinutes: -480 },
  { value: 'Europe/London', stdAbbr: 'GMT', dstAbbr: 'BST', stdOffsetMinutes: 0 },
  { value: 'Europe/Paris', stdAbbr: 'CET', dstAbbr: 'CEST', stdOffsetMinutes: 60 },
  { value: 'Europe/Berlin', stdAbbr: 'CET', dstAbbr: 'CEST', stdOffsetMinutes: 60 },
  { value: 'Australia/Sydney', stdAbbr: 'AEST', dstAbbr: 'AEDT', stdOffsetMinutes: 600 },
  { value: 'Pacific/Auckland', stdAbbr: 'NZST', dstAbbr: 'NZDT', stdOffsetMinutes: 720 },
  { value: 'UTC', stdAbbr: 'UTC', stdOffsetMinutes: 0 },
];

/**
 * 시간대의 그 시각 UTC 시차(분)를 Intl 로 구한다. 'longOffset' 은 「GMT-04:00」·「GMT+05:30」·「GMT」(0) 꼴이다.
 * 해석하지 못하면 null — 부르는 쪽이 표준시로 대신한다.
 */
export function utcOffsetMinutes(timeZone: string, at: Date): number | null {
  try {
    const part = new Intl.DateTimeFormat('en-US', { timeZone, timeZoneName: 'longOffset' })
      .formatToParts(at)
      .find((p) => p.type === 'timeZoneName')?.value;
    if (!part) return null;
    if (part === 'GMT' || part === 'UTC') return 0;
    const m = /^(?:GMT|UTC)([+-])(\d{1,2})(?::?(\d{2}))?$/.exec(part);
    if (!m) return null;
    const minutes = Number(m[2]) * 60 + Number(m[3] ?? 0);
    return m[1] === '-' ? -minutes : minutes;
  } catch {
    return null;
  }
}

/** 시차(분)를 「UTC-4」·「UTC+5:30」·「UTC+0」 꼴로 쓴다. */
export function formatUtcOffset(minutes: number): string {
  const sign = minutes < 0 ? '-' : '+';
  const abs = Math.abs(minutes);
  const h = Math.floor(abs / 60);
  const m = abs % 60;
  return `UTC${sign}${h}${m ? `:${String(m).padStart(2, '0')}` : ''}`;
}

/**
 * 시간대의 그 시각 기준 이름표 — 약어와 UTC 시차(WD-417).
 * 목록에 없는 시간대는 약어 없이 시차만 준다(계산도 못 하면 둘 다 빈 문자열).
 */
export function describeTimezone(timeZone: string, at: Date): { abbr: string; offset: string } {
  const option = TIMEZONE_OPTIONS.find((t) => t.value === timeZone);
  const minutes = utcOffsetMinutes(timeZone, at) ?? option?.stdOffsetMinutes ?? null;
  if (minutes === null) return { abbr: '', offset: '' };
  const offset = formatUtcOffset(minutes);
  if (!option) return { abbr: '', offset };
  const isDst = option.dstAbbr !== undefined && minutes !== option.stdOffsetMinutes;
  return { abbr: isDst ? option.dstAbbr! : option.stdAbbr, offset };
}
