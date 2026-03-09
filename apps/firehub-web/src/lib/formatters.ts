/**
 * 공통 포매터 유틸리티
 * 여러 페이지에서 중복 정의되던 함수들을 통합.
 */

/** 서버(UTC)에서 받은 LocalDateTime 문자열에 'Z'를 붙여 UTC로 파싱 */
function parseUtcDate(dateStr: string): Date {
  // 이미 타임존 정보가 있으면 그대로, 없으면 UTC로 간주
  if (/[Z+\-]\d{0,4}$/.test(dateStr)) return new Date(dateStr);
  return new Date(dateStr + 'Z');
}

export function formatDate(dateStr: string | null): string {
  if (!dateStr) return '-';
  return parseUtcDate(dateStr).toLocaleString('ko-KR');
}

export function formatDateShort(dateStr: string): string {
  return parseUtcDate(dateStr).toLocaleDateString('ko-KR');
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

export type BadgeVariant = 'default' | 'secondary' | 'destructive' | 'outline';

export function getStatusBadgeVariant(status: string): BadgeVariant {
  switch (status) {
    case 'COMPLETED':
      return 'default';
    case 'FAILED':
      return 'destructive';
    case 'RUNNING':
    case 'PROCESSING':
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
