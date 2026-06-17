/**
 * formatters 단위 테스트 — 날짜/셀/상태/경과시간 포매터.
 */
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import {
  formatCellValue,
  formatDate,
  formatDateOnly,
  formatDateShort,
  formatDateTime,
  formatDateTimeMinute,
  formatDuration,
  formatElapsedTime,
  formatFileSize,
  formatIpAddress,
  formatRelativeTime,
  getOriginTypeLabel,
  getRawCellValue,
  getStatusBadgeVariant,
  getStatusLabel,
  getStorageTypeLabel,
  isNullValue,
  timeAgo,
} from './formatters';

describe('formatDate', () => {
  it('null이면 "-" 반환', () => {
    expect(formatDate(null)).toBe('-');
  });

  it('타임존 정보 없는 UTC 문자열을 로컬 문자열로 변환', () => {
    const result = formatDate('2026-04-11T00:00:00');
    expect(typeof result).toBe('string');
    expect(result).not.toBe('-');
  });

  it('Z 접미사가 있는 문자열도 처리', () => {
    const result = formatDate('2026-04-11T00:00:00Z');
    expect(typeof result).toBe('string');
  });
});

describe('formatDateShort', () => {
  it('날짜만 반환', () => {
    const result = formatDateShort('2026-04-11T00:00:00');
    expect(typeof result).toBe('string');
    expect(result.length).toBeGreaterThan(0);
  });
});

describe('isNullValue', () => {
  it('null/undefined만 true', () => {
    expect(isNullValue(null)).toBe(true);
    expect(isNullValue(undefined)).toBe(true);
    expect(isNullValue(0)).toBe(false);
    expect(isNullValue('')).toBe(false);
    expect(isNullValue(false)).toBe(false);
  });
});

describe('getRawCellValue', () => {
  it('null/undefined은 빈 문자열', () => {
    expect(getRawCellValue(null)).toBe('');
    expect(getRawCellValue(undefined)).toBe('');
  });

  it('숫자/문자/불리언은 String 변환', () => {
    expect(getRawCellValue(42)).toBe('42');
    expect(getRawCellValue('hello')).toBe('hello');
    expect(getRawCellValue(true)).toBe('true');
  });
});

describe('formatCellValue', () => {
  it('null/undefined은 "NULL"', () => {
    expect(formatCellValue(null)).toBe('NULL');
    expect(formatCellValue(undefined)).toBe('NULL');
  });

  it('BOOLEAN dataType은 ✓/✗', () => {
    expect(formatCellValue(true, 'BOOLEAN')).toBe('✓');
    expect(formatCellValue(false, 'BOOLEAN')).toBe('✗');
    expect(formatCellValue('true', 'BOOLEAN')).toBe('✓');
    expect(formatCellValue('false', 'BOOLEAN')).toBe('✗');
  });

  it('typeof boolean은 BOOLEAN dataType 없이도 ✓/✗', () => {
    expect(formatCellValue(true)).toBe('✓');
    expect(formatCellValue(false)).toBe('✗');
  });

  it('DATE dataType 포맷팅', () => {
    const result = formatCellValue('2026-04-11', 'DATE');
    expect(typeof result).toBe('string');
  });

  it('TIMESTAMP dataType 포맷팅', () => {
    const result = formatCellValue('2026-04-11T12:00:00', 'TIMESTAMP');
    expect(typeof result).toBe('string');
  });

  it('ISO datetime 문자열 자동 포맷', () => {
    const result = formatCellValue('2026-04-11T12:00:00');
    expect(typeof result).toBe('string');
  });

  it('200자 초과 문자열은 자르고 … 추가', () => {
    const long = 'a'.repeat(250);
    const result = formatCellValue(long);
    expect(result.length).toBe(201);
    expect(result.endsWith('…')).toBe(true);
  });

  it('일반 문자열은 그대로', () => {
    expect(formatCellValue('hello')).toBe('hello');
    expect(formatCellValue(42)).toBe('42');
  });
});

describe('formatFileSize', () => {
  it('바이트 단위', () => {
    expect(formatFileSize(0)).toBe('0 B');
    expect(formatFileSize(1023)).toBe('1023 B');
  });

  it('KB 단위', () => {
    expect(formatFileSize(1024)).toBe('1.0 KB');
    expect(formatFileSize(2048)).toBe('2.0 KB');
  });

  it('MB 단위', () => {
    expect(formatFileSize(1024 * 1024)).toBe('1.0 MB');
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5.0 MB');
  });
});

describe('getStatusBadgeVariant', () => {
  // 이슈 #68: 의미↔색 매핑 통일 — COMPLETED는 success(녹색), 진행중은 info(파랑)
  it.each([
    ['COMPLETED', 'success'],
    ['FAILED', 'destructive'],
    ['RUNNING', 'info'],
    ['PROCESSING', 'info'],
    ['CANCELLED', 'secondary'],
    ['SKIPPED', 'secondary'],
    ['PENDING', 'outline'],
    ['UNKNOWN', 'outline'],
  ])('%s → %s', (status, expected) => {
    expect(getStatusBadgeVariant(status)).toBe(expected);
  });
});

describe('getStatusLabel', () => {
  it('알려진 상태 라벨', () => {
    expect(getStatusLabel('COMPLETED')).toBe('완료');
    expect(getStatusLabel('FAILED')).toBe('실패');
    expect(getStatusLabel('RUNNING')).toBe('실행중');
    expect(getStatusLabel('PROCESSING')).toBe('처리중');
    expect(getStatusLabel('PENDING')).toBe('대기');
    expect(getStatusLabel('CANCELLED')).toBe('취소됨');
    expect(getStatusLabel('SKIPPED')).toBe('건너뜀');
  });

  it('알 수 없는 상태는 원본 반환', () => {
    expect(getStatusLabel('CUSTOM_STATUS')).toBe('CUSTOM_STATUS');
  });
});

describe('formatDuration', () => {
  it('completedAt이 null이면 "-"', () => {
    expect(formatDuration('2026-04-11T00:00:00Z', null)).toBe('-');
  });

  it('음수 duration은 "-"', () => {
    expect(formatDuration('2026-04-11T00:00:10Z', '2026-04-11T00:00:00Z')).toBe('-');
  });

  it('초 단위', () => {
    expect(formatDuration('2026-04-11T00:00:00Z', '2026-04-11T00:00:45Z')).toBe('45초');
  });

  it('분만 있는 경우', () => {
    expect(formatDuration('2026-04-11T00:00:00Z', '2026-04-11T00:02:00Z')).toBe('2분');
  });

  it('분 + 초', () => {
    expect(formatDuration('2026-04-11T00:00:00Z', '2026-04-11T00:02:30Z')).toBe('2분 30초');
  });

  it('시간만 있는 경우', () => {
    expect(formatDuration('2026-04-11T00:00:00Z', '2026-04-11T01:00:00Z')).toBe('1시간');
  });

  it('시간 + 분', () => {
    expect(formatDuration('2026-04-11T00:00:00Z', '2026-04-11T01:05:00Z')).toBe('1시간 5분');
  });
});

describe('formatDateTime / formatDateTimeMinute / formatDateOnly', () => {
  // 이슈 #105: zero-pad YYYY-MM-DD HH:mm:ss 통일 포맷터.
  // 로컬 타임존 의존 결과가 환경마다 달라질 수 있어 패턴만 검증한다.
  it('formatDateTime — null/빈문자열은 "-"', () => {
    expect(formatDateTime(null)).toBe('-');
    expect(formatDateTime(undefined)).toBe('-');
    expect(formatDateTime('')).toBe('-');
  });

  it('formatDateTime — 잘못된 입력은 "-"', () => {
    expect(formatDateTime('not-a-date')).toBe('-');
  });

  it('formatDateTime — UTC 문자열을 zero-pad 절대시간으로', () => {
    const result = formatDateTime('2026-04-11T03:05:09Z');
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  });

  it('formatDateTimeMinute — 분 단위 16자', () => {
    const result = formatDateTimeMinute('2026-04-11T03:05:09Z');
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    expect(result.length).toBe(16);
  });

  it('formatDateOnly — YYYY-MM-DD zero-pad', () => {
    const result = formatDateOnly('2026-04-11T03:05:09Z');
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('formatDateOnly — null은 "-"', () => {
    expect(formatDateOnly(null)).toBe('-');
  });
});

describe('formatRelativeTime', () => {
  // 이슈 #105: 페이지 간 일관 상대시간.
  beforeAll(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-04-11T12:00:00Z'));
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('null/빈문자열은 "-"', () => {
    expect(formatRelativeTime(null)).toBe('-');
    expect(formatRelativeTime(undefined)).toBe('-');
    expect(formatRelativeTime('')).toBe('-');
  });

  it('1분 미만은 "방금 전"', () => {
    expect(formatRelativeTime('2026-04-11T11:59:30Z')).toBe('방금 전');
  });

  it('분/시간/일/개월/년 단위', () => {
    expect(formatRelativeTime('2026-04-11T11:55:00Z')).toBe('5분 전');
    expect(formatRelativeTime('2026-04-11T10:00:00Z')).toBe('2시간 전');
    expect(formatRelativeTime('2026-04-09T12:00:00Z')).toBe('2일 전');
    expect(formatRelativeTime('2026-02-10T12:00:00Z')).toBe('2개월 전');
    expect(formatRelativeTime('2024-04-11T12:00:00Z')).toBe('2년 전');
  });

  it('서버 LocalDateTime(타임존 미부착) 도 UTC로 처리', () => {
    expect(formatRelativeTime('2026-04-11T11:55:00')).toBe('5분 전');
  });
});

describe('formatIpAddress', () => {
  // 이슈 #106: IPv6 loopback 정규화.
  it('null/빈문자열은 "-"', () => {
    expect(formatIpAddress(null)).toBe('-');
    expect(formatIpAddress(undefined)).toBe('-');
    expect(formatIpAddress('')).toBe('-');
    expect(formatIpAddress('   ')).toBe('-');
  });

  it('IPv6 loopback raw → localhost', () => {
    expect(formatIpAddress('0:0:0:0:0:0:0:1')).toBe('localhost');
  });

  it('IPv6 loopback 압축 → localhost', () => {
    expect(formatIpAddress('::1')).toBe('localhost');
  });

  it('IPv4 loopback → localhost', () => {
    expect(formatIpAddress('127.0.0.1')).toBe('localhost');
  });

  it('일반 IP는 그대로', () => {
    expect(formatIpAddress('192.168.1.1')).toBe('192.168.1.1');
    expect(formatIpAddress('2001:db8::1')).toBe('2001:db8::1');
  });
});

describe('getStorageTypeLabel', () => {
  it('TABLE → 테이블', () => expect(getStorageTypeLabel('TABLE')).toBe('테이블'));
  it('DOCUMENT → 문서', () => expect(getStorageTypeLabel('DOCUMENT')).toBe('문서'));
});

describe('getOriginTypeLabel', () => {
  it('SOURCE → 원본', () => expect(getOriginTypeLabel('SOURCE')).toBe('원본'));
  it('DERIVED → 파생', () => expect(getOriginTypeLabel('DERIVED')).toBe('파생'));
  it('TEMP → 임시', () => expect(getOriginTypeLabel('TEMP')).toBe('임시'));
});

describe('timeAgo / formatElapsedTime', () => {
  beforeAll(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-04-11T12:00:00Z'));
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('timeAgo: 1분 미만은 "방금 전"', () => {
    expect(timeAgo('2026-04-11T11:59:30Z')).toBe('방금 전');
  });

  it('timeAgo: 분 단위', () => {
    expect(timeAgo('2026-04-11T11:55:00Z')).toBe('5분 전');
  });

  it('timeAgo: 시간 단위', () => {
    expect(timeAgo('2026-04-11T10:00:00Z')).toBe('2시간 전');
  });

  it('timeAgo: 일 단위', () => {
    expect(timeAgo('2026-04-09T12:00:00Z')).toBe('2일 전');
  });

  it('formatElapsedTime: 5초 미만은 "방금"', () => {
    expect(formatElapsedTime(3000)).toBe('방금');
  });

  it('formatElapsedTime: 초/분/시 단위', () => {
    expect(formatElapsedTime(10_000)).toBe('10초 전');
    expect(formatElapsedTime(5 * 60_000)).toBe('5분 전');
    expect(formatElapsedTime(3 * 3600_000)).toBe('3시간 전');
  });
});
