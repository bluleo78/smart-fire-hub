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
  relativeOrShortDate,
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

  // #397 회귀: 콜론 포함 오프셋(+09:00)을 타임존 "없음"으로 오판해 'Z'를 중복 추가하고
  // Invalid Date가 되던 버그. OffsetDateTime을 직렬화하는 백엔드 필드(예: OntologySummary.updatedAt)에서 발생.
  it('콜론 포함 타임존 오프셋(+09:00)이 있는 문자열도 Invalid Date 없이 처리', () => {
    const result = formatDate('2026-08-30T12:34:56.789012+09:00');
    expect(result).not.toBe('-');
    expect(result.toLowerCase()).not.toContain('invalid');
  });

  it('콜론 포함 음수 오프셋(-05:00)이 있는 문자열도 Invalid Date 없이 처리', () => {
    const result = formatDate('2026-08-30T12:34:56-05:00');
    expect(result.toLowerCase()).not.toContain('invalid');
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

  // (#770) JS Date 가 해석하지 못하거나(Invalid Date → Intl.format 이 RangeError) 다른 날짜로
  // 굴려 버리는(roll-over) 값은 포맷하지 않고 원문 그대로 보여야 한다. 셀 하나의 예외가
  // 데이터 탭 전체를 에러 화면으로 만들었다.
  describe('파싱 불가·범위 밖 날짜는 크래시 없이 원문 반환 (#770)', () => {
    it.each([
      'infinity',
      '-infinity',
      '0044-03-15 BC',
      '10000-01-01',
      '0000-01-01',
      '2024-02-30',
      'not-a-date',
    ])('DATE %s → 원문', (v) => {
      expect(formatCellValue(v, 'DATE')).toBe(v);
    });

    it.each([
      'infinity',
      '-infinity',
      '0044-03-15 10:00:00 BC',
      // 현재 API(#769 미수정)가 ±infinity·BC 에 대해 내려주는 문자열
      '-0043-03-15T01:00:00.000+00:00',
      '-292269054-12-02T23:00:00.000+00:00',
      '+292278994-08-16T23:00:00.000+00:00',
      '10000-01-01 00:00:00',
      '2024-01-01T24:00:00',
      '2024-02-30T10:00:00',
      '24:00:00',
      'garbage',
    ])('TIMESTAMP %s → 원문', (v) => {
      expect(formatCellValue(v, 'TIMESTAMP')).toBe(v);
    });

    it('TIMESTAMP 컬럼의 숫자(epoch 등)는 epoch 로 해석하지 않고 숫자 원문', () => {
      expect(formatCellValue(1700000000000, 'TIMESTAMP')).toBe('1700000000000');
    });

    it('dataType 없이 ISO 처럼 생긴 잘못된 문자열(TEXT 컬럼 등)도 원문', () => {
      expect(formatCellValue('2024-99-99T00:00:00')).toBe('2024-99-99T00:00:00');
      expect(formatCellValue('2024-01-01T24:00:00')).toBe('2024-01-01T24:00:00');
    });

    it('원문이 200자를 넘으면 기존 규칙대로 절삭', () => {
      const long = 'infinity' + 'x'.repeat(300);
      expect(formatCellValue(long, 'TIMESTAMP')).toBe(long.slice(0, 200) + '…');
    });

    it('정상 값의 포맷은 기존과 동일하다', () => {
      const dateFmt = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' });
      const tsFmt = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
      expect(formatCellValue('2024-05-01', 'DATE')).toBe(dateFmt.format(new Date('2024-05-01')));
      expect(formatCellValue('2024-05-01T01:00:00.000+00:00', 'TIMESTAMP')).toBe(
        tsFmt.format(new Date('2024-05-01T01:00:00.000+00:00')),
      );
      expect(formatCellValue('9999-12-31T15:00:00.000+00:00', 'TIMESTAMP')).toBe(
        tsFmt.format(new Date('9999-12-31T15:00:00.000+00:00')),
      );
      expect(formatCellValue('2026-04-11T12:00:00')).toBe(tsFmt.format(new Date('2026-04-11T12:00:00')));
    });
  });

  // (#774) 브라우저 시간대(vitest TZ=Asia/Seoul)에 존재하지 않는 벽시계 시각은 Date 가 다른 시각으로 옮긴다.
  // 해석 결과가 원문 날짜·시각 성분과 다르면 원문을 그대로 보여야 한다.
  describe('브라우저 시간대에 없는 벽시계 시각은 원문 표시 (#774)', () => {
    it.each([
      // 서울 서머타임 시작 공백(02:00~03:00) — Date 는 03:30 으로 민다
      '1988-05-08 02:30:00',
      '1988-05-08 02:30',
      '1987-05-10 02:30',
      '1988-05-08T02:30:00',
      // 표준시 전환(1908-04-01 00:00 이전 LMT) — Date 는 00:02:08 로 민다
      '1908-04-01 00:00:00',
    ])('TIMESTAMP %s → 원문', (v) => {
      expect(formatCellValue(v, 'TIMESTAMP')).toBe(v);
    });

    it('dataType 없이 ISO 모양으로 온 DST 공백 시각도 원문', () => {
      expect(formatCellValue('1988-05-08T02:30:00')).toBe('1988-05-08T02:30:00');
    });

    it('공백 바로 앞뒤의 실재 시각·오프셋 붙은 시점 값은 기존처럼 포맷한다', () => {
      const tsFmt = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
      for (const v of ['1988-05-08 01:59:00', '1988-05-08 03:00:00', '2024-01-02 10:15:00', '1900-01-01 00:00']) {
        expect(formatCellValue(v, 'TIMESTAMP')).toBe(tsFmt.format(new Date(v)));
      }
      // 오프셋 붙은 값은 시점이므로 로컬 시각으로 바꿔 보여주는 것이 의도 (1988-05-07T17:30Z = 서울 03:30)
      expect(formatCellValue('1988-05-07T17:30:00.000+00:00', 'TIMESTAMP')).toBe(
        tsFmt.format(new Date('1988-05-07T17:30:00.000+00:00')),
      );
    });
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

  /**
   * 실행 중(completedAt === null) 경과 시간 — #691 의 회귀 테스트.
   *
   * **완료 케이스만으로는 잡히지 않는다.** 시작·종료를 둘 다 같은 방향으로 밀면 차이에서 상쇄되고,
   * 실행 중일 때만 진짜 현재(Date.now())와 섞여 9시간이 드러난다(운영: "소요 564m 58s" vs 실제 25분).
   */
  it('실행 중이면 nowMs 를 끝으로 보고, 타임존 표기 없는 시작 시각을 UTC로 해석한다 (#691)', () => {
    const nowMs = Date.UTC(2026, 8, 18, 3, 43, 27);
    expect(formatDuration('2026-09-18T03:17:33', null, nowMs)).toBe('25분 54초');
  });

  it('완료 시각이 있으면 nowMs 는 무시한다', () => {
    const nowMs = Date.UTC(2026, 8, 18, 9, 0, 0);
    expect(formatDuration('2026-09-18T03:17:33', '2026-09-18T03:18:33', nowMs)).toBe('1분');
  });

  it('완료 시각도 nowMs 도 없으면 "-"', () => {
    expect(formatDuration('2026-09-18T03:17:33', null)).toBe('-');
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

  // #397 회귀: 콜론 포함 오프셋(+09:00)이 "-"(Invalid Date)로 새지 않아야 한다.
  it('formatDateTime — 콜론 포함 오프셋(+09:00)도 정상 포맷', () => {
    const result = formatDateTime('2026-08-30T12:34:56.789012+09:00');
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  });

  it('formatDateOnly — 콜론 포함 오프셋(+09:00)도 정상 포맷', () => {
    const result = formatDateOnly('2026-08-30T12:34:56.789012+09:00');
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2}$/);
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

  /**
   * #349 회귀 — 타임존 표기가 없는 서버 LocalDateTime 문자열은 UTC로 해석해야 한다.
   * 수정 전에는 `new Date(str)`가 브라우저 로컬 존으로 파싱해, KST 브라우저에서 방금 저장된 값이
   * "9시간 전"으로 보였다. formatDate/formatRelativeTime과 해석이 일치하는지도 함께 고정한다.
   */
  it('timeAgo: 타임존 없는 문자열을 UTC로 해석한다 (#349)', () => {
    // 시스템 시각 12:00:00Z 기준 5분 전 값 — 'Z' 유무와 무관하게 같은 결과여야 한다
    expect(timeAgo('2026-04-11T11:55:00')).toBe('5분 전');
    expect(timeAgo('2026-04-11T11:55:00')).toBe(timeAgo('2026-04-11T11:55:00Z'));
  });

  it('timeAgo와 formatRelativeTime이 같은 문자열을 같게 해석한다 (#349)', () => {
    expect(timeAgo('2026-04-11T10:00:00')).toBe(formatRelativeTime('2026-04-11T10:00:00'));
  });

  /**
   * #355 회귀 — `relativeOrShortDate`는 7일 분기 판정·절대일자 폴백·timeAgo 위임을
   * 모두 같은 UTC 파싱 규칙으로 해야 한다. 이관 전에는 분기만 `new Date`(로컬)라
   * KST 브라우저에서 경계가 최대 9시간 어긋났다.
   */
  it('relativeOrShortDate: 7일 미만은 timeAgo에 위임한다 (#355)', () => {
    expect(relativeOrShortDate('2026-04-05T12:00:00Z')).toBe('6일 전');
  });

  it('relativeOrShortDate: 7일 이상은 절대 날짜로 표시한다 (#355)', () => {
    expect(relativeOrShortDate('2026-04-04T12:00:00Z')).toBe(
      new Date('2026-04-04T12:00:00Z').toLocaleDateString('ko-KR', {
        month: 'short',
        day: 'numeric',
      }),
    );
  });

  it('relativeOrShortDate: 타임존 없는 문자열도 UTC로 해석해 분기가 일치한다 (#355)', () => {
    // 경계 근처(6일 22시간 전) — 로컬(KST) 파싱이면 9시간 밀려 7일 이상으로 오판한다
    expect(relativeOrShortDate('2026-04-04T14:00:00')).toBe('6일 전');
    expect(relativeOrShortDate('2026-04-04T14:00:00')).toBe(
      relativeOrShortDate('2026-04-04T14:00:00Z'),
    );
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
