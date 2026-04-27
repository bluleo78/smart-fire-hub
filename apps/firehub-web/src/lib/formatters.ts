/**
 * 공통 포매터 유틸리티
 * 여러 페이지에서 중복 정의되던 함수들을 통합.
 */

/** 서버(UTC)에서 받은 LocalDateTime 문자열에 'Z'를 붙여 UTC로 파싱 */
function parseUtcDate(dateStr: string): Date {
  // 이미 타임존 정보가 있으면 그대로, 없으면 UTC로 간주
  if (/[Z+-]\d{0,4}$/.test(dateStr)) return new Date(dateStr);
  return new Date(dateStr + 'Z');
}

export function formatDate(dateStr: string | null): string {
  if (!dateStr) return '-';
  return parseUtcDate(dateStr).toLocaleString('ko-KR');
}

export function formatDateShort(dateStr: string): string {
  return parseUtcDate(dateStr).toLocaleDateString('ko-KR');
}

/**
 * 날짜만 표시 — `YYYY-MM-DD` zero-pad (이슈 #105).
 * `formatDateShort`는 로케일 의존이라 페이지 간 표시가 들쭉날쭉하여 별도 zero-pad 헬퍼 도입.
 */
export function formatDateOnly(dateStr: string | null | undefined): string {
  if (!dateStr) return '-';
  const d = parseUtcDate(dateStr);
  if (Number.isNaN(d.getTime())) return '-';
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** 두 자리 zero-pad. */
function pad2(n: number): string {
  return n.toString().padStart(2, '0');
}

/**
 * 절대시간 포맷 — `YYYY-MM-DD HH:mm:ss` (KST).
 * 페이지 간 일관성 확보를 위한 공통 포맷터 (이슈 #105).
 * - 모든 자릿수 zero-pad
 * - 로컬 타임존 기준 (KST)
 * - null/undefined → '-'
 */
export function formatDateTime(dateStr: string | null | undefined): string {
  if (!dateStr) return '-';
  const d = parseUtcDate(dateStr);
  if (Number.isNaN(d.getTime())) return '-';
  const yyyy = d.getFullYear();
  const mm = pad2(d.getMonth() + 1);
  const dd = pad2(d.getDate());
  const hh = pad2(d.getHours());
  const mi = pad2(d.getMinutes());
  const ss = pad2(d.getSeconds());
  return `${yyyy}-${mm}-${dd} ${hh}:${mi}:${ss}`;
}

/**
 * 절대시간 포맷(분 단위) — `YYYY-MM-DD HH:mm` (KST).
 * 목록 화면의 hover 툴팁에 적합 (초 단위 정밀도 불필요).
 */
export function formatDateTimeMinute(dateStr: string | null | undefined): string {
  if (!dateStr) return '-';
  const full = formatDateTime(dateStr);
  if (full === '-') return '-';
  return full.slice(0, 16);
}

/**
 * 상대시간 포맷 — `방금 전`, `5분 전`, `3시간 전`, `2일 전`, `3개월 전`.
 * 페이지 간 일관성 확보를 위한 공통 포맷터 (이슈 #105).
 * timeAgo와 달리 UTC 파싱(parseUtcDate)을 사용하여 서버 LocalDateTime 문자열을 정확히 처리.
 */
export function formatRelativeTime(dateStr: string | null | undefined): string {
  if (!dateStr) return '-';
  const d = parseUtcDate(dateStr);
  if (Number.isNaN(d.getTime())) return '-';
  const diff = Date.now() - d.getTime();
  const mins = Math.floor(diff / 60_000);
  if (mins < 1) return '방금 전';
  if (mins < 60) return `${mins}분 전`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}일 전`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months}개월 전`;
  const years = Math.floor(months / 12);
  return `${years}년 전`;
}

/**
 * IPv4/IPv6 주소를 사용자 친화적 형태로 변환 (이슈 #106).
 * - IPv6 loopback `0:0:0:0:0:0:0:1` 또는 `::1` → `localhost`
 * - IPv4 loopback `127.0.0.1` → `localhost`
 * - 그 외는 원본 유지
 * - null/undefined/빈문자열 → '-'
 */
export function formatIpAddress(ip: string | null | undefined): string {
  if (!ip) return '-';
  const trimmed = ip.trim();
  if (trimmed === '') return '-';
  // IPv6 loopback (raw 형태와 압축 형태 모두 처리)
  if (trimmed === '0:0:0:0:0:0:0:1' || trimmed === '::1') return 'localhost';
  if (trimmed === '127.0.0.1') return 'localhost';
  return trimmed;
}

/**
 * 데이터셋 타입 enum → 한글 라벨 (이슈 #107).
 * 사용자 화면에서는 영문 enum이 노출되지 않도록 매핑.
 */
export function getDatasetTypeLabel(type: string): string {
  switch (type) {
    case 'SOURCE':
      return '원본';
    case 'DERIVED':
      return '파생';
    case 'TEMP':
      return '임시';
    default:
      return type;
  }
}

const ISO_DATETIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/;

export function isNullValue(value: unknown): boolean {
  return value === null || value === undefined;
}

export function getRawCellValue(value: unknown): string {
  if (value === null || value === undefined) return '';
  return String(value);
}

export function formatCellValue(value: unknown, dataType?: string): string {
  if (value === null || value === undefined) return 'NULL';

  if (dataType === 'BOOLEAN' || typeof value === 'boolean') {
    const boolVal = typeof value === 'boolean' ? value : value === 'true';
    return boolVal ? '✓' : '✗';
  }

  const str = String(value);

  if (dataType === 'DATE') {
    return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(new Date(str));
  }

  if (dataType === 'TIMESTAMP' || ISO_DATETIME_RE.test(str)) {
    return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(str));
  }

  if (str.length > 200) {
    return str.slice(0, 200) + '…';
  }

  return str;
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

export type BadgeVariant =
  | 'default'
  | 'secondary'
  | 'destructive'
  | 'outline'
  | 'success'
  | 'warning'
  | 'info';

/** StatusBadge type 토큰 (components/ui/status-badge와 동일 정의 — 순환 import 회피용 별칭). */
export type StatusBadgeType =
  | 'active'
  | 'inactive'
  | 'success'
  | 'error'
  | 'warning'
  | 'info'
  | 'pending'
  | 'unknown';

/**
 * 실행 상태(JobExecution / Pipeline / Import) → StatusBadge type 매핑.
 * 의미↔색 통일 (이슈 #68): 완료=success, 실패=error, 실행중=info, 취소/건너뜀=inactive, 대기=pending.
 */
export function getExecutionStatusType(status: string): StatusBadgeType {
  switch (status) {
    case 'COMPLETED':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'RUNNING':
    case 'PROCESSING':
      return 'info';
    case 'PENDING':
      return 'pending';
    case 'CANCELLED':
    case 'SKIPPED':
      return 'inactive';
    default:
      return 'unknown';
  }
}

/**
 * 실행 상태(JobExecution / Pipeline / Import) → Badge variant 매핑.
 *
 * 의미 ↔ 색 매핑은 앱 전체에서 통일되어야 한다 (이슈 #68):
 * - 완료/성공 → success(녹색)
 * - 실패/오류 → destructive(빨강)
 * - 실행중/처리중 → info(파랑)
 * - 취소/건너뜀 → secondary(회색)
 * - 대기/그 외 → outline
 *
 * 신규 코드는 가능하면 `<StatusBadge type="..." />` (components/ui/status-badge)를 사용한다.
 * 이 함수는 기존 호출부 backward compat용으로 유지한다.
 */
export function getStatusBadgeVariant(status: string): BadgeVariant {
  switch (status) {
    case 'COMPLETED':
      return 'success';
    case 'FAILED':
      return 'destructive';
    case 'RUNNING':
    case 'PROCESSING':
      return 'info';
    case 'CANCELLED':
    case 'SKIPPED':
      return 'secondary';
    default:
      return 'outline';
  }
}

export function getStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    COMPLETED: '완료',
    FAILED: '실패',
    RUNNING: '실행중',
    PROCESSING: '처리중',
    PENDING: '대기',
    CANCELLED: '취소됨',
    SKIPPED: '건너뜀',
  };
  return labels[status] || status;
}

/**
 * 두 시점 사이의 경과 시간을 사람이 읽기 쉬운 형태로 반환한다.
 * completedAt이 null이면 "-"를 반환한다.
 * 예: "45초", "2분 30초", "1시간 5분"
 */
export function formatDuration(startedAt: string, completedAt: string | null): string {
  if (!completedAt) return '-';
  const diffMs = new Date(completedAt).getTime() - new Date(startedAt).getTime();
  if (diffMs < 0) return '-';
  const seconds = Math.floor(diffMs / 1000);
  if (seconds < 60) return `${seconds}초`;
  const minutes = Math.floor(seconds / 60);
  const remainSec = seconds % 60;
  if (minutes < 60) return remainSec > 0 ? `${minutes}분 ${remainSec}초` : `${minutes}분`;
  const hours = Math.floor(minutes / 60);
  const remainMin = minutes % 60;
  return remainMin > 0 ? `${hours}시간 ${remainMin}분` : `${hours}시간`;
}

/**
 * 상대 시간 포맷 (date string → "N분 전")
 */
export function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  return `${days}일 전`;
}

/**
 * 상대 시간 포맷 (elapsed ms → "N초 전")
 */
export function formatElapsedTime(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  if (seconds < 5) return '방금';
  if (seconds < 60) return `${seconds}초 전`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  return `${hours}시간 전`;
}
