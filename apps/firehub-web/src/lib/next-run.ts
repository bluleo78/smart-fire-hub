import { CronExpressionParser } from 'cron-parser';

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
