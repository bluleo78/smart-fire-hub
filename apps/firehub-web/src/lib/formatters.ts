/**
 * 공통 포매터 유틸리티
 * 여러 페이지에서 중복 정의되던 함수들을 통합.
 */

/**
 * 서버(UTC)에서 받은 LocalDateTime 문자열에 'Z'를 붙여 UTC로 파싱.
 *
 * 이 프로젝트의 날짜 계약: **타임존 표기가 없는 서버 문자열은 UTC로 간주한다.**
 * 날짜 문자열을 다루는 코드는 `new Date(str)`(브라우저 로컬 존으로 해석)을 직접 쓰지 말고
 * 반드시 이 헬퍼를 거쳐야 한다 — 그렇지 않으면 KST 브라우저에서 9시간 어긋난다 (#349).
 */
export function parseUtcDate(dateStr: string): Date {
  // 이미 타임존 정보가 있으면 그대로, 없으면 UTC로 간주.
  // 콜론 포함 오프셋(+09:00 등)도 인식해야 한다 — 이전 정규식은 숫자 런 앞에 콜론이 끼면
  // 매치하지 못해 "+09:00Z"처럼 Z를 중복 추가, Invalid Date를 유발했다 (#397).
  if (/(Z|[+-]\d{2}:?\d{2})$/.test(dateStr)) return new Date(dateStr);
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

/** 저장 방식 라벨 */
export function getStorageTypeLabel(type: string): string {
  switch (type) {
    case 'TABLE':
      return '테이블';
    case 'DOCUMENT':
      return '문서';
    case 'FILE':
      return '파일';
    default:
      return type;
  }
}

/** 출처 라벨 */
export function getOriginTypeLabel(type: string): string {
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

/**
 * 셀 날짜 포맷 대상인지 판별하는 모양 정규식 (#770).
 * `YYYY-MM-DD` 또는 `YYYY-MM-DD[T ]HH:mm[:ss[.fff]][Z|±HH[:mm]]` 만 허용한다.
 * 연도는 4자리(0001~9999)만 — 5자리 연도·부호 붙은 연도(`-0043-…`, `+292278994-…`)·`BC` 접미·
 * `infinity` 같은 값은 모양에서 걸러 원문을 그대로 보여준다.
 */
const CELL_DATETIME_SHAPE_RE =
  /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}(?::?\d{2})?)?)?$/;

/**
 * 셀 값 문자열이 "안전하게 날짜로 포맷해도 되는" 값인지 검사한다 (#770).
 *
 * JS `Date` 는 두 가지 방식으로 셀을 망친다.
 * 1. 해석 불가(Invalid Date) — `Intl.DateTimeFormat.format` 이 RangeError 를 던져 셀 하나의 예외가
 *    데이터 탭 전체를 에러 화면으로 만든다(`-infinity`, BC, 부호 붙은 연도 등).
 * 2. 조용한 굴림(roll-over) — `2024-02-30` → 3월 1일, `T24:00:00` → 다음날, `10000-01-01` → 해석은
 *    되지만 원문과 다른 값. 사용자 데이터를 다른 날짜로 보여주는 것도 결함이다.
 * 그래서 모양·필드 범위(월 1~12, 실재하는 일자, 시 0~23, 분·초 0~59, 연도 ≥ 1)를 먼저 확인한다.
 * 행 편집 다이얼로그(#772)도 "date/datetime-local 입력이 담을 수 있는 값인가" 판정에 같은 규칙을 쓴다.
 */
export function isFormattableDateString(str: string): boolean {
  const m = CELL_DATETIME_SHAPE_RE.exec(str);
  if (!m) return false;
  const [, y, mo, d, h, mi, sec] = m;
  const year = Number(y);
  const month = Number(mo);
  const day = Number(d);
  if (year < 1 || month < 1 || month > 12 || day < 1) return false;
  // 해당 월의 실제 마지막 날 — Date.UTC(year, month, 0) 은 month 월의 말일 (#770: 2월 30일 굴림 방지)
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  if (day > lastDay) return false;
  if (h !== undefined && (Number(h) > 23 || Number(mi) > 59)) return false;
  if (sec !== undefined && Number(sec) > 59) return false;
  return true;
}

/** 셀 원문 표시 — 200자 초과 시 절삭. */
function truncateCellText(str: string): string {
  return str.length > 200 ? str.slice(0, 200) + '…' : str;
}

/**
 * (#774) 셀 날짜 문자열을 데이터 탭 셀 표시와 같은 방식(`new Date(str)` → 브라우저 시간대)으로 해석하고,
 * 해석 결과가 **원문의 날짜·시각 성분을 그대로 보존하는지** 왕복 검증한다.
 *
 * 시간대(오프셋) 없는 벽시계 문자열은 브라우저 시간대에 그 시각이 존재하지 않으면 `Date` 가 조용히
 * 다른 시각으로 옮긴다 — 예: 서울 서머타임 시작 공백 `1988-05-08 02:30` → 03:30, 표준시 전환
 * `1908-04-01 00:00` → 00:02. 사용자 데이터를 다른 시각으로 보여주는 결함이므로, 해석 결과의 로컬
 * 연·월·일(·시·분·초)이 원문과 하나라도 다르면 null 을 돌려 호출부가 원문을 그대로 쓰게 한다.
 * DST 공백뿐 아니라 그 밖의 브라우저 정규화 차이(날짜만 있는 값이 UTC 로 해석돼 음수 시간대에서
 * 전날이 되는 경우 등)도 같은 판정으로 걸러진다.
 * 오프셋(`Z`, `+00:00`)이 붙은 값은 시점(instant)이라 로컬 시각으로 바꿔 보여주는 것이 의도이므로
 * 왕복 검증 대상이 아니다.
 * 모양·필드 범위 검사(isFormattableDateString)에 실패하거나 Invalid Date 여도 null.
 */
function parseCellDateLocal(str: string): Date | null {
  if (!isFormattableDateString(str)) return null;
  const d = new Date(str);
  if (Number.isNaN(d.getTime())) return null;
  const m = CELL_DATETIME_SHAPE_RE.exec(str);
  if (!m) return null;
  const [, y, mo, day, h, mi, sec, offset] = m;
  if (offset !== undefined) return d;
  // 원문 성분 vs 브라우저 로컬 해석 성분 — 시각이 없는 값은 날짜만, 초가 없는 값은 0초로 비교
  const expected = [Number(y), Number(mo), Number(day)];
  const actual = [d.getFullYear(), d.getMonth() + 1, d.getDate()];
  if (h !== undefined) {
    expected.push(Number(h), Number(mi), Number(sec ?? 0));
    actual.push(d.getHours(), d.getMinutes(), d.getSeconds());
  }
  return expected.every((v, i) => v === actual[i]) ? d : null;
}

/**
 * 날짜 셀 포맷. 포맷할 수 없는 값은 **절대 throw 하지 않고** 원문을 그대로 돌려준다 (#770).
 * 셀 값은 사용자 데이터라 UTC 계약(parseUtcDate)을 적용하지 않고 기존처럼 `new Date(str)` 로 해석한다.
 * 해석 결과가 원문 벽시계와 다르면(DST 공백 등, #774) 원문을 그대로 보여준다.
 */
function formatDateCell(str: string, options: Intl.DateTimeFormatOptions): string {
  const d = parseCellDateLocal(str);
  if (d === null) return truncateCellText(str);
  try {
    return new Intl.DateTimeFormat('ko-KR', options).format(d);
  } catch {
    // 방어: 어떤 경우에도 셀 렌더 예외가 탭 전체(PageErrorBoundary)를 무너뜨리지 않게 한다
    return truncateCellText(str);
  }
}

/**
 * (#772) 데이터 셀 TIMESTAMP 값을 `type=datetime-local` 입력값 `YYYY-MM-DDTHH:mm:ss[.SSS]` 로 바꾼다.
 * 데이터 탭 셀 표시(formatDateCell)와 **같은 해석**(`new Date(str)` → 브라우저 시간대 벽시계)을 써야
 * 행 편집 다이얼로그가 셀과 같은 시각을 보여 준다. 이 형식은 서버 `LocalDateTime.parse` 가 그대로 받는다.
 * 입력란이 담을 수 없는 값(모양 불일치·Invalid Date·변환 후 연도 0001~9999 밖)은 null.
 */
export function cellTimestampToLocalInput(str: string): string | null {
  // (#774) 브라우저 시간대에 존재하지 않는 벽시계 시각(DST 공백 등)은 null → 원문 텍스트 입력으로 보여 준다
  const d = parseCellDateLocal(str);
  if (d === null) return null;
  const year = d.getFullYear();
  if (year < 1 || year > 9999) return null;
  const p2 = (n: number) => String(n).padStart(2, '0');
  let local =
    `${String(year).padStart(4, '0')}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}` +
    `T${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}`;
  const ms = d.getMilliseconds();
  if (ms) local += `.${String(ms).padStart(3, '0')}`;
  return local;
}

export function formatCellValue(value: unknown, dataType?: string): string {
  if (value === null || value === undefined) return 'NULL';

  if (dataType === 'BOOLEAN' || typeof value === 'boolean') {
    const boolVal = typeof value === 'boolean' ? value : value === 'true';
    return boolVal ? '✓' : '✗';
  }

  const str = String(value);

  if (dataType === 'DATE') {
    return formatDateCell(str, { dateStyle: 'medium' });
  }

  if (dataType === 'TIMESTAMP' || ISO_DATETIME_RE.test(str)) {
    return formatDateCell(str, { dateStyle: 'medium', timeStyle: 'short' });
  }

  return truncateCellText(str);
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
 * 예: "45초", "2분 30초", "1시간 5분"
 *
 * - 완료된 경우(`completedAt` 있음): 두 시점의 차이.
 * - 실행 중인 경우(`completedAt` 이 null): `nowMs`(보통 `Date.now()`)를 끝으로 본다. 화면이 1초마다
 *   다시 그리도록 인자로 받는다 — 함수가 직접 `Date.now()` 를 읽으면 값이 변해도 리렌더가 안 된다.
 * - 둘 다 없으면 "-".
 *
 * **실행 중 계산은 파싱을 틀리기 쉽다** (#691). `startedAt` 은 타임존 표기가 없는 UTC 문자열이라
 * `new Date()` 로 직접 파싱하면 KST 브라우저에서 9시간 이른 시각이 되고, 진짜 현재인 `Date.now()`
 * 와 빼면 경과 시간이 9시간 부풀려진다. 완료된 경우에는 양쪽이 같은 방향으로 밀려 상쇄되므로
 * **실행 중일 때만** 드러난다 — 그래서 파싱은 반드시 `parseUtcDate` 한 곳을 거친다.
 */
export function formatDuration(
  startedAt: string | null,
  completedAt: string | null,
  nowMs?: number,
): string {
  if (!startedAt) return '-';
  const endMs = completedAt ? parseUtcDate(completedAt).getTime() : nowMs;
  if (endMs === undefined) return '-';
  const diffMs = endMs - parseUtcDate(startedAt).getTime();
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
 *
 * 서버 LocalDateTime 문자열을 parseUtcDate로 해석한다 (#349). 이전에는 `new Date(dateStr)`을
 * 직접 써서 타임존 없는 문자열을 브라우저 로컬(KST)로 파싱했고, UTC로 저장된 값에 대해
 * 9시간 오래된 것처럼 표시됐다 — 같은 값을 formatDate는 UTC로, timeAgo는 로컬로 정반대 해석했다.
 */
export function timeAgo(dateStr: string): string {
  const diff = Date.now() - parseUtcDate(dateStr).getTime();
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  return `${days}일 전`;
}

/**
 * 알림 목록용 상대/절대 시간 포맷 (#355).
 *
 * 7일 이내면 `timeAgo`, 넘으면 `M월 D일` 절대 날짜.
 * 원래 AINotificationPanel 안에 있었으나 분기 판정과 절대일자 폴백만 `new Date`(로컬 파싱)를
 * 써서 `timeAgo`(UTC 파싱)와 경계에서 최대 9시간 어긋났다. 계약 이탈이 재발하지 않도록
 * `parseUtcDate`를 쓰는 formatters로 옮긴다.
 */
export function relativeOrShortDate(dateStr: string): string {
  const d = parseUtcDate(dateStr);
  if (Number.isNaN(d.getTime())) return '-';
  const days = Math.floor((Date.now() - d.getTime()) / 86_400_000);
  if (days < 7) return timeAgo(dateStr);
  return d.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' });
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
