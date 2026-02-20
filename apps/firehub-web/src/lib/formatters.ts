/**
 * 공통 포매터 유틸리티
 * 여러 페이지에서 중복 정의되던 함수들을 통합.
 */

export function formatDate(dateStr: string | null): string {
  if (!dateStr) return '-';
  return new Date(dateStr).toLocaleString('ko-KR');
}

export function formatDateShort(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('ko-KR');
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
