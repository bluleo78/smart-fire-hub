import { cn } from '../../../../lib/utils';

/* 셀 상태 표시 색상 — 시맨틱 토큰 사용 */
const STATUS_COLORS: Record<string, { dot: string; text: string }> = {
  정상: { dot: 'bg-success', text: 'text-success' },
  활성: { dot: 'bg-success', text: 'text-success' },
  완료: { dot: 'bg-success', text: 'text-success' },
  SUCCESS: { dot: 'bg-success', text: 'text-success' },
  ACTIVE: { dot: 'bg-success', text: 'text-success' },
  COMPLETED: { dot: 'bg-success', text: 'text-success' },
  점검중: { dot: 'bg-warning', text: 'text-warning' },
  경고: { dot: 'bg-warning', text: 'text-warning' },
  대기: { dot: 'bg-warning', text: 'text-warning' },
  PENDING: { dot: 'bg-warning', text: 'text-warning' },
  WARNING: { dot: 'bg-warning', text: 'text-warning' },
  수리중: { dot: 'bg-destructive', text: 'text-destructive' },
  오류: { dot: 'bg-destructive', text: 'text-destructive' },
  실패: { dot: 'bg-destructive', text: 'text-destructive' },
  FAILED: { dot: 'bg-destructive', text: 'text-destructive' },
  ERROR: { dot: 'bg-destructive', text: 'text-destructive' },
  INACTIVE: { dot: 'bg-destructive', text: 'text-destructive' },
};

interface CellRendererProps {
  value: unknown;
  isNumericColumn?: boolean;
}

/**
 * 서버가 보내는 10진 숫자 문자열(선택 '-'·정수부·선택 소수부). 서버는 BigDecimal.toPlainString·Long.toString 만 보내므로
 * 앞자리 0 은 지우지 않고, '+'·지수 표기는 정규식에 맞지 않아 기존 Number 경로로 표시된다.
 */
const PLAIN_DECIMAL_RE = /^(-?)(\d+)(\.\d+)?$/;

/**
 * 숫자 문자열을 Number 로 바꾸지 않고 정수부에만 천 단위 쉼표를 넣는다(WD-101).
 *
 * 왜: 데이터셋 행 API 는 JS double 로 깎이는 NUMERIC·BIGINT 값을 문자열로 보내는데(예: "123456789012.123456",
 * "9007199254740993"), Number() 나 toLocaleString 을 거치면 다시 깎이고 소수도 3자리로 잘린다.
 * Intl.NumberFormat 의 문자열 format 은 ES2023 기능이라 앱 lib(ES2022) 에서 타입이 막히고 구형 런타임에서는 조용히
 * Number 로 바뀌므로 쓰지 않는다. 형식이 맞지 않으면 null 을 돌려 호출부가 기존 경로를 타게 한다.
 */
function formatPlainDecimalString(value: string): string | null {
  const match = PLAIN_DECIMAL_RE.exec(value.trim());
  if (!match) return null;
  const [, sign, intPart, fracPart = ''] = match;
  // "-0" 처럼 0 만 남는 음수는 부호 없이 보인다. ko-KR 그룹 구분은 3자리 쉼표다.
  const negative = sign === '-' && /[1-9]/.test(intPart + fracPart);
  return `${negative ? '-' : ''}${intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${fracPart}`;
}

export function CellRenderer({ value, isNumericColumn }: CellRendererProps) {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground">—</span>;
  }

  const isNumber =
    isNumericColumn ||
    typeof value === 'number' ||
    (typeof value === 'string' && value.trim() !== '' && !isNaN(Number(value)));

  if (isNumber) {
    // 문자열 숫자는 정밀도 보존 경로(전 자릿수 그대로), 실제 JS number 는 기존처럼 toLocaleString(소수 최대 3자리).
    const text =
      (typeof value === 'string' ? formatPlainDecimalString(value) : null) ??
      Number(value).toLocaleString('ko-KR');
    return (
      <span
        className={cn('font-medium tabular-nums', 'text-foreground')}
        style={{ fontVariantNumeric: 'tabular-nums' }}
      >
        {text}
      </span>
    );
  }

  const str = String(value);
  const statusColor = STATUS_COLORS[str];
  if (statusColor) {
    return (
      <span className="flex items-center gap-1">
        <span className={cn('inline-block h-1.5 w-1.5 rounded-full shrink-0', statusColor.dot)} />
        <span className={cn('font-medium', statusColor.text)}>{str}</span>
      </span>
    );
  }

  if (str.length > 40) {
    return (
      <span className="block max-w-[160px] truncate text-foreground" title={str}>
        {str}
      </span>
    );
  }

  return <span className="text-foreground">{str}</span>;
}
