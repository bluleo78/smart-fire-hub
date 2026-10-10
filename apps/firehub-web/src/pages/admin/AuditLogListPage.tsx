import { useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { SearchInput } from '@/components/ui/search-input';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { SimplePagination } from '@/components/ui/simple-pagination';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useAuditLogs } from '@/hooks/queries/useAuditLogs';
import { useUsers } from '@/hooks/queries/useUsers';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { formatDateTime, formatIpAddress } from '@/lib/formatters';
import type { AuditLogResponse } from '@/types/auditLog';

/**
 * 액션 유형 옵션 목록
 * - 백엔드(AuditLogService.log) 호출부에서 사용 중인 enum 전수: CREATE/UPDATE/DELETE/LOGIN/LOGOUT/IMPORT/EXECUTE/DATA_EXPORT/STATUS_CHANGE
 *   + 온톨로지 도메인의 ONTOLOGY_* 4종 + 요소 단위 편집 10종.
 * - #109 회귀: DATA_EXPORT, STATUS_CHANGE 매핑 누락 보강.
 * - ONTOLOGY_* 는 OntologyService가 처음부터 기록해 왔지만 매핑이 없어 영문 raw로 노출되고 있었다.
 *   상태 전이를 ONTOLOGY_UPDATE에서 ONTOLOGY_STATUS_CHANGE로 분리하면서 함께 보강한다.
 * - #637 회귀: "지식 모델 요소 단위 편집" 이니셔티브(S1~S3)에서 OntologyElementService가
 *   기록하기 시작한 타입/관계/속성/도메인 세분화 actionType 10종이 매핑에서 누락돼 영문 raw로 노출되던 문제.
 *   신규 actionType 추가 시 apps/firehub-api에서 `grep -rn "ONTOLOGY_" --include=*.java`로
 *   실제 audit() 호출부 전수를 대조해 이 배열을 갱신해야 한다.
 */
const GENERAL_ACTION_TYPES = [
  { value: 'CREATE', label: '생성' },
  { value: 'UPDATE', label: '수정' },
  { value: 'DELETE', label: '삭제' },
  { value: 'LOGIN', label: '로그인' },
  { value: 'LOGOUT', label: '로그아웃' },
  { value: 'IMPORT', label: '임포트' },
  { value: 'EXECUTE', label: '실행' },
  { value: 'DATA_EXPORT', label: '데이터 내보내기' },
  { value: 'STATUS_CHANGE', label: '상태 변경' },
  { value: 'ONTOLOGY_CREATE', label: '지식 모델 생성' },
  { value: 'ONTOLOGY_UPDATE', label: '지식 모델 편집' },
  { value: 'ONTOLOGY_STATUS_CHANGE', label: '지식 모델 상태 변경' },
  { value: 'ONTOLOGY_DELETE', label: '지식 모델 삭제' },
  { value: 'ONTOLOGY_TYPE_ADD', label: '엔티티 타입 추가' },
  { value: 'ONTOLOGY_TYPE_UPDATE', label: '엔티티 타입 수정' },
  { value: 'ONTOLOGY_TYPE_DELETE', label: '엔티티 타입 삭제' },
  { value: 'ONTOLOGY_RELATION_ADD', label: '관계 추가' },
  { value: 'ONTOLOGY_RELATION_UPDATE', label: '관계 수정' },
  { value: 'ONTOLOGY_RELATION_DELETE', label: '관계 삭제' },
  { value: 'ONTOLOGY_PROPERTY_ADD', label: '속성 추가' },
  { value: 'ONTOLOGY_PROPERTY_UPDATE', label: '속성 수정' },
  { value: 'ONTOLOGY_PROPERTY_DELETE', label: '속성 삭제' },
  { value: 'ONTOLOGY_DOMAIN_UPDATE', label: '도메인 수정' },
];

/**
 * 데이터셋 보안 액션(WD-17·WD-44) — apps/firehub-api securitylevel 패키지의 감사 호출부 전수.
 * - DATASET_ACCESS_DENIED·DATASET_ACCESS 는 SecurityAuditRecorder 상수(거부 1건 / 감사 등급 데이터셋 접근 1건).
 * - 내보내기 거부는 별도 액션이 아니라 DATASET_ACCESS_DENIED(metadata.action=EXPORT)로 남는다.
 */
const DATASET_SECURITY_ACTION_TYPES = [
  { value: 'DATASET_ACCESS_DENIED', label: '데이터셋 접근 거부' },
  { value: 'DATASET_ACCESS', label: '감사 등급 데이터 접근' },
  { value: 'DATASET_SECURITY_LEVEL_CHANGE', label: '보안 등급 변경' },
  { value: 'DATASET_SECURITY_LEVEL_AUTO_RAISE', label: '보안 등급 자동 상향' },
  { value: 'DATASET_ACCESS_GRANT_ADD', label: '허용 목록 추가' },
  { value: 'DATASET_ACCESS_GRANT_REMOVE', label: '허용 목록 제거' },
];

/**
 * 보안 설정 액션 — 등급 정의(SecurityLevelService)·역할 열람 등급(RoleClearanceService) 변경과
 * AI 공급자 호스팅 위치 선언 변경(HostingChangeAuditor, WD-49).
 * - 호스팅 위치 선언은 security:settings 권한이 필요한 보안 결정이고, 등급의 AI 정책(외부 AI 금지 등)이 실제로 걸리는지를 바꾼다 —
 *   그래서 등급 정책과 같은 그룹에 둔다. 화면 용어(설정 › 호스팅 위치)를 그대로 쓴다.
 */
const SECURITY_SETTINGS_ACTION_TYPES = [
  { value: 'SECURITY_LEVEL_CREATE', label: '보안 등급 생성' },
  { value: 'SECURITY_LEVEL_UPDATE', label: '보안 등급 수정' },
  { value: 'SECURITY_LEVEL_DELETE', label: '보안 등급 삭제' },
  { value: 'SECURITY_LEVEL_REORDER', label: '보안 등급 순서 변경' },
  { value: 'SECURITY_LEVEL_DEFAULT_CHANGE', label: '기본 보안 등급 변경' },
  { value: 'ROLE_CLEARANCE_CHANGE', label: '역할 열람 등급 변경' },
  { value: 'AI_PROVIDER_HOSTING_CHANGE', label: 'AI 호스팅 위치 변경' },
];

/**
 * 멤버 관리 액션(WD-2·WD-3, UserService.audit) — 워크스페이스 멤버 추가·멤버십 정지/재활성화·제거.
 * - 기록은 되고 있었지만 매핑이 없어 영문 raw 로 보이고 필터로 찾을 수 없었다(WD-49).
 * - 문구는 사용자 관리 화면의 토스트·배지("멤버십이 정지되었습니다", "워크스페이스에서 제거")와 맞춘다.
 */
const MEMBER_ACTION_TYPES = [
  { value: 'MEMBER_ADD', label: '멤버 추가' },
  { value: 'MEMBER_SUSPEND', label: '멤버십 정지' },
  { value: 'MEMBER_REACTIVATE', label: '멤버십 재활성화' },
  { value: 'MEMBER_REMOVE', label: '멤버 제거' },
];

/**
 * 액션 필터 드롭다운 그룹 — 항목이 30개를 넘어 성격별로 나눠 찾기 쉽게 한다.
 * 라벨 매핑(ACTION_LABEL_MAP)은 그룹과 무관하게 평면 목록(ACTION_TYPES)에서 만든다.
 */
const ACTION_GROUPS = [
  { label: '일반', items: GENERAL_ACTION_TYPES },
  { label: '멤버 관리', items: MEMBER_ACTION_TYPES },
  { label: '데이터셋 보안', items: DATASET_SECURITY_ACTION_TYPES },
  { label: '보안 설정', items: SECURITY_SETTINGS_ACTION_TYPES },
];

/** 전체 액션 유형(평면) — 라벨 매핑용 */
const ACTION_TYPES = ACTION_GROUPS.flatMap((g) => g.items);

/**
 * 접근 거부·감사 등급 접근 metadata 값 → 한글 라벨(WD-44).
 * - action: AccessDenialAction enum, kind: SecurityAuditRecorder.AccessKind enum,
 *   reason: DatasetAccessPolicy/DatasetAccessGuard 의 거부 코드 전수.
 * - 모르는 코드(향후 추가)는 원문 그대로 보인다.
 */
const ACCESS_ACTION_LABELS: Record<string, string> = {
  VIEW: '조회',
  SQL: 'SQL',
  PIPELINE: '파이프라인',
  DATASET_REFS: '데이터셋 참조',
  EXPORT: '내보내기',
  AI: 'AI',
};
const ACCESS_KIND_LABELS: Record<string, string> = {
  ROW_VIEW: '행 조회',
  SQL: 'SQL',
  PIPELINE: '파이프라인',
  AI: 'AI',
};
const ACCESS_REASON_LABELS: Record<string, string> = {
  CLEARANCE_INSUFFICIENT: '열람 등급 부족',
  NOT_ON_ALLOWLIST: '허용 목록에 없음',
  EXPORT_DENIED: '내보내기 금지 등급',
  EXPORT_PERMISSION_REQUIRED: '제한 데이터 내보내기 권한 필요',
  AI_DENIED: 'AI 사용 금지 등급',
  AI_EXTERNAL_DENIED: '외부 AI 사용 금지 등급',
  SHARE_DENIED: '외부 공유 금지 등급',
  LEVEL_UNKNOWN: '등급 확인 불가',
};

/** metadata 값을 라벨 맵으로 바꾼다 — 없으면 원문 폴백. */
function labelOf(map: Record<string, string>, value: unknown): string {
  const raw = String(value);
  return map[raw] ?? raw;
}

/**
 * 접근 요약 항목 — DATASET_ACCESS_DENIED·DATASET_ACCESS 행만 대상.
 * 관리자가 Metadata JSON 을 읽지 않아도 동작·종류·사유·테이블을 한 줄로 보게 한다. 항목이 없으면 빈 배열.
 */
function accessSummaryParts(log: AuditLogResponse): string[] {
  if (log.actionType !== 'DATASET_ACCESS_DENIED' && log.actionType !== 'DATASET_ACCESS') return [];
  const m = log.metadata;
  if (!m) return [];
  return [
    m.action ? `동작: ${labelOf(ACCESS_ACTION_LABELS, m.action)}` : null,
    m.kind ? `종류: ${labelOf(ACCESS_KIND_LABELS, m.kind)}` : null,
    m.reason ? `사유: ${labelOf(ACCESS_REASON_LABELS, m.reason)}` : null,
    m.tableName ? `테이블: ${String(m.tableName)}` : null,
  ].filter((v): v is string => v !== null);
}

/**
 * 리소스 유형 옵션 목록
 * - 백엔드 호출부(AuthService/PipelineExecutionService/DatasetService/DataImportService/DataExportService/ApiConnectionNotifier)에서
 *   실제 사용 중인 resource 값 전수: auth/system/api_connection/pipeline/dataset (data_import는 dataimport 도메인에서 dataset으로 기록).
 * - #109 회귀: auth/system/api_connection 매핑 누락으로 영문 raw 값 노출되던 문제 해소.
 * - security_level(SecurityLevelService — 등급 정의 생성·수정·삭제·순서·기본 등급),
 *   query_result(QueryResultExportService — 쿼리 결과 서버 내보내기, resourceId=실행 기록 ID)도 사용 중이다.
 * - 신규 action/resource 추가 시 apps/firehub-api 에서 `auditLogService.log(`·`audit.record(` 호출부 전수를 대조한다.
 *   운영자 평면 감사(tenant·TENANT_*·플랫폼 계정 조치)는 tenant_id 가 NULL 이라 이 화면(테넌트 감사)에는 나오지 않는다.
 */
const RESOURCES = [
  { value: 'auth', label: '인증' },
  { value: 'user', label: '사용자' },
  { value: 'role', label: '역할' },
  { value: 'dataset', label: '데이터셋' },
  { value: 'pipeline', label: '파이프라인' },
  { value: 'data_import', label: '데이터 임포트' },
  { value: 'api_connection', label: 'API 연결' },
  { value: 'system', label: '시스템' },
  { value: 'ontology', label: '지식 모델' },
  { value: 'security_level', label: '보안 등급' },
  { value: 'query_result', label: '쿼리 결과' },
  // HostingChangeAuditor.RESOURCE — resourceId 는 슬롯 이름(CHAT/CLASSIFY/EMBEDDING)이다(WD-49).
  { value: 'ai_provider_hosting', label: 'AI 호스팅 위치' },
];

/** 결과 필터 옵션 목록 */
const RESULTS = [
  { value: 'SUCCESS', label: '성공' },
  { value: 'FAILURE', label: '실패' },
];

/**
 * 액션 유형 enum → 한글 라벨 매핑 (#109)
 * - 테이블 컬럼/상세 다이얼로그가 영문 raw 값을 그대로 표시하던 문제 해소.
 * - 알 수 없는(미래 추가) enum은 raw 값을 fallback으로 노출 + 콘솔 경고.
 */
const ACTION_LABEL_MAP: Record<string, string> = ACTION_TYPES.reduce(
  (acc, t) => ({ ...acc, [t.value]: t.label }),
  {} as Record<string, string>,
);

/** 리소스 enum → 한글 라벨 매핑 (#109) */
const RESOURCE_LABEL_MAP: Record<string, string> = RESOURCES.reduce(
  (acc, r) => ({ ...acc, [r.value]: r.label }),
  {} as Record<string, string>,
);

/** 액션 enum 값을 한글 라벨로 변환. 매핑 없으면 raw 반환 + 경고. */
function formatAuditAction(action: string | null | undefined): string {
  if (!action) return '-';
  const label = ACTION_LABEL_MAP[action];
  if (!label) {
    console.warn(`[AuditLog] Unknown actionType: ${action}`);
    return action;
  }
  return label;
}

/** 리소스 enum 값을 한글 라벨로 변환. 매핑 없으면 raw 반환 + 경고. */
function formatAuditResource(resource: string | null | undefined): string {
  if (!resource) return '-';
  const label = RESOURCE_LABEL_MAP[resource];
  if (!label) {
    console.warn(`[AuditLog] Unknown resource: ${resource}`);
    return resource;
  }
  return label;
}

/**
 * 날짜 문자열(YYYY-MM-DD)을 브라우저 로컬 자정/하루 끝의 절대 시각(ISO 8601, Z)으로 변환.
 * - 서버는 오프셋을 존중해 그 순간을 저장 TZ 벽시계로 바꿔 비교한다(WD-11) — 오프셋 없이 보내면 서버 저장 TZ 로
 *   해석돼 브라우저 하루와 어긋날 수 있다.
 * - endDate 는 하루의 끝(23:59:59.999 로컬)으로 설정해 inclusive 범위를 구현한다.
 */
function toIsoDateTime(dateStr: string, endOfDay = false): string {
  // YYYY-MM-DD를 로컬 타임존 기준 시작/끝 시각으로 파싱 후 UTC ISO 문자열로 변환
  const [year, month, day] = dateStr.split('-').map(Number);
  const date = endOfDay
    ? new Date(year, month - 1, day, 23, 59, 59, 999)
    : new Date(year, month - 1, day, 0, 0, 0, 0);
  return date.toISOString();
}

/**
 * 감사 로그 상세 보기 다이얼로그
 * - 테이블 행 클릭 시 표시: description 전문, IP, actionTime 등 전체 필드를 보여준다.
 */
function AuditLogDetailDialog({
  log,
  open,
  onClose,
}: {
  log: AuditLogResponse | null;
  open: boolean;
  onClose: () => void;
}) {
  if (!log) return null;
  const summaryParts = accessSummaryParts(log);

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>감사 로그 상세</DialogTitle>
        </DialogHeader>
        <div className="space-y-3 text-sm">
          {/* 기본 정보 행 */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-muted-foreground mb-1 text-xs">시간</p>
              <p className="font-medium">{formatDateTime(log.actionTime)}</p>
            </div>
            <div>
              <p className="text-muted-foreground mb-1 text-xs">사용자</p>
              <p className="font-medium">{log.username}</p>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-muted-foreground mb-1 text-xs">액션</p>
              <p>{formatAuditAction(log.actionType)}</p>
            </div>
            <div>
              <p className="text-muted-foreground mb-1 text-xs">리소스</p>
              <p>{formatAuditResource(log.resource)}{log.resourceId ? ` (${log.resourceId})` : ''}</p>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-muted-foreground mb-1 text-xs">결과</p>
              <Badge variant={log.result === 'SUCCESS' ? 'default' : 'destructive'}>
                {log.result === 'SUCCESS' ? '성공' : '실패'}
              </Badge>
            </div>
            <div>
              <p className="text-muted-foreground mb-1 text-xs">IP 주소</p>
              <p title={log.ipAddress ?? undefined}>{formatIpAddress(log.ipAddress)}</p>
            </div>
          </div>

          {/* 설명 전문: truncate 없이 전체 표시 */}
          <div>
            <p className="text-muted-foreground mb-1 text-xs">설명</p>
            <p className="bg-muted rounded-md p-3 whitespace-pre-wrap break-words">
              {log.description ?? '-'}
            </p>
          </div>

          {/* 접근 요약(WD-44) — 접근 거부·감사 등급 접근 행에서 metadata 를 한국어 한 줄로. 항목이 없으면 숨김 */}
          {summaryParts.length > 0 && (
            <div>
              <p className="text-muted-foreground mb-1 text-xs">접근 요약</p>
              <p data-testid="audit-access-summary">{summaryParts.join(' · ')}</p>
            </div>
          )}

          {/* 에러 메시지 (실패인 경우) */}
          {log.errorMessage && (
            <div>
              <p className="text-muted-foreground mb-1 text-xs">에러 메시지</p>
              <p className="bg-muted text-destructive rounded-md p-3 whitespace-pre-wrap break-words">
                {log.errorMessage}
              </p>
            </div>
          )}

          {/*
            User Agent 표시
            - 브라우저/OS 추적용 — 누가 어떤 디바이스에서 액션했는지 식별
            - 긴 문자열을 break-all 로 줄바꿈 처리, monospace 로 가독성 확보
            - userAgent 가 null/empty 인 경우 섹션 자체를 숨김 (시각 노이즈 방지)
          */}
          {log.userAgent && (
            <div>
              <p className="text-muted-foreground mb-1 text-xs">User Agent</p>
              <p className="bg-muted rounded-md p-3 font-mono text-xs break-all">
                {log.userAgent}
              </p>
            </div>
          )}

          {/*
            Metadata(JSON payload) 표시
            - 변경 전/후 값, 요청 파라미터 등 변경 감사의 핵심 데이터
            - JSON.stringify(..., null, 2) 로 들여쓰기, monospace + 스크롤
            - max-h-64 + overflow-auto 로 긴 payload 도 다이얼로그를 깨뜨리지 않게 함
            - metadata 가 null 이거나 빈 객체이면 섹션 자체를 숨김
          */}
          {log.metadata && Object.keys(log.metadata).length > 0 && (
            <div>
              <p className="text-muted-foreground mb-1 text-xs">Metadata</p>
              <pre className="bg-muted rounded-md p-3 font-mono text-xs max-h-64 overflow-auto whitespace-pre">
                {JSON.stringify(log.metadata, null, 2)}
              </pre>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** 감사 로그 목록 페이지 — 날짜 범위 필터 + 행 클릭 상세 보기 */
export default function AuditLogListPage() {
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounceValue(search, 300);
  /**
   * 사용자 필터 (#89): 빈 문자열이면 전체, 숫자 문자열이면 해당 user_id 정확 일치.
   * Select value는 string 만 허용하므로 number 변환은 API 호출 시 수행.
   */
  const [userId, setUserId] = useState<string>('');
  const [actionType, setActionType] = useState<string>('');
  const [resource, setResource] = useState<string>('');
  const [result, setResult] = useState<string>('');
  /** 날짜 범위 필터: YYYY-MM-DD 형식으로 저장 */
  const [startDate, setStartDate] = useState<string>('');
  const [endDate, setEndDate] = useState<string>('');
  const [page, setPage] = useState(0);
  /** 페이지당 표시 건수: 사용자가 selector 로 변경 가능 (기본 20) */
  const [pageSize, setPageSize] = useState(20);

  /** 상세 보기 다이얼로그 상태 */
  const [selectedLog, setSelectedLog] = useState<AuditLogResponse | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  // WD-15: 결과에 실제로 반영된 조건 기준 — 검색어는 디바운스된 값으로 본다(입력 중 300ms 동안 문구가 앞서가지 않게).
  const hasAnyFilter = Boolean(debouncedSearch || userId || actionType || resource || result || startDate || endDate);
  /** 모든 필터를 비우고 첫 페이지로 — 빈 결과의 "필터 초기화" 가 쓴다. 입력 중인 원본 검색어도 지운다. */
  const resetAllFilters = () => {
    setSearch('');
    setUserId('');
    setActionType('');
    setResource('');
    setResult('');
    setStartDate('');
    setEndDate('');
    setPage(0);
  };

  const handleSearchChange = (value: string) => {
    setSearch(value);
    setPage(0);
  };

  /**
   * 날짜 범위 역전 검사 (#541)
   * - date input은 세그먼트(연/월/일) 단위로 개별 편집이 가능해 브라우저의 min/max 네이티브 제약이
   *   편집 도중 우회될 수 있다. 시작일 > 종료일인 상태가 실제로 만들어질 수 있으므로 렌더 시점에
   *   직접 검사해, 역전된 경우 API 호출을 보류하고 "결과 없음"과 구분되는 안내를 노출한다.
   */
  const isDateRangeInverted = Boolean(startDate && endDate && startDate > endDate);

  const { data: logs, isLoading, isError } = useAuditLogs({
    search: debouncedSearch || undefined,
    // userId 필터 (#89): "all"/'' → undefined, 숫자 문자열은 number 변환
    userId: userId ? Number(userId) : undefined,
    actionType: actionType || undefined,
    resource: resource || undefined,
    result: result || undefined,
    // 날짜 범위를 ISO datetime으로 변환하여 API에 전달
    startDate: startDate ? toIsoDateTime(startDate) : undefined,
    endDate: endDate ? toIsoDateTime(endDate, true) : undefined,
    page,
    size: pageSize,
    // 날짜 범위가 역전된 상태에서는 무의미한 조회이므로 API 호출 자체를 보류한다
    enabled: !isDateRangeInverted,
  });

  /**
   * 사용자 dropdown 옵션 로드 (#89)
   * - 관리자 페이지이므로 GET /users 권한 보유 가정 (user:read)
   * - 한 페이지당 100명까지 노출. 더 많은 사용자가 있으면 향후 검색 가능한 Combobox로 확장 고려.
   */
  const { data: usersPage } = useUsers({ size: 100 });

  const handleFilterChange = (setter: (v: string) => void) => (value: string) => {
    setter(value === 'all' ? '' : value);
    setPage(0);
  };

  /** 날짜 필터 변경 시 페이지 리셋 */
  const handleDateChange = (setter: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setter(e.target.value);
    setPage(0);
  };

  /** 행 클릭 → 상세 보기 다이얼로그 열기 */
  const handleRowClick = (log: AuditLogResponse) => {
    setSelectedLog(log);
    setDetailOpen(true);
  };

  return (
    <div className="space-y-6">
      <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">감사 로그</h1>

      <div className="flex flex-wrap items-center gap-4">
        <SearchInput
          placeholder="설명으로 검색..."
          value={search}
          onChange={handleSearchChange}
        />

        {/*
          사용자 필터 dropdown (#89)
          - free-text 검색은 username 부분 일치라 동명이인/오타 노이즈 발생 → user_id 정확 일치 필터 추가.
          - 옵션은 GET /users 결과(최대 100명) 기반. SelectValue placeholder로 "전체 사용자" 표시.
          - aria-label로 스크린리더 사용자가 필터 의도를 알 수 있도록 한다.
        */}
        <Select value={userId || 'all'} onValueChange={handleFilterChange(setUserId)}>
          <SelectTrigger className="w-[180px]" aria-label="사용자 필터">
            <SelectValue placeholder="전체 사용자" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체 사용자</SelectItem>
            {usersPage?.content.map((u) => (
              <SelectItem key={u.id} value={String(u.id)}>
                {u.name} ({u.username})
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={actionType || 'all'} onValueChange={handleFilterChange(setActionType)}>
          {/* 폭 180px — '감사 등급 데이터 접근' 같은 긴 라벨이 잘리지 않게. 그래도 잘리면 title 로 전체 라벨을 보인다 */}
          <SelectTrigger className="w-[180px]" aria-label="액션 유형 필터">
            <SelectValue placeholder="액션 유형" title={actionType ? formatAuditAction(actionType) : '전체 액션'} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체 액션</SelectItem>
            {ACTION_GROUPS.map((g) => (
              <SelectGroup key={g.label}>
                <SelectLabel>{g.label}</SelectLabel>
                {g.items.map((t) => (
                  <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>
                ))}
              </SelectGroup>
            ))}
          </SelectContent>
        </Select>

        <Select value={resource || 'all'} onValueChange={handleFilterChange(setResource)}>
          <SelectTrigger className="w-[140px]" aria-label="리소스 필터">
            <SelectValue placeholder="리소스" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체 리소스</SelectItem>
            {RESOURCES.map((r) => (
              <SelectItem key={r.value} value={r.value}>{r.label}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={result || 'all'} onValueChange={handleFilterChange(setResult)}>
          <SelectTrigger className="w-[120px]" aria-label="결과 필터">
            <SelectValue placeholder="결과" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체 결과</SelectItem>
            {RESULTS.map((r) => (
              <SelectItem key={r.value} value={r.value}>{r.label}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        {/*
          날짜 범위 필터: 시작일 ~ 종료일
          max-sm:flex-wrap — 150px 고정 Input 2개 + `~`가 min-content 325px 덩어리라
          320px(가용 272px)에서 <main>을 29px 넘겼다(#357, WCAG SC 1.4.10).
          폭을 flex-1로 줄이면 123px가 되어 date input 내부 텍스트(mm/dd/yyyy)가 잘리므로,
          줄바꿈으로 해결한다. max-sm 한정이라 sm 이상 데스크톱 표현은 그대로다.
        */}
        <div className="flex items-center gap-2 max-sm:flex-wrap">
          <Input
            type="date"
            aria-label="시작 날짜"
            className="w-[150px]"
            value={startDate}
            max={endDate || undefined}
            onChange={handleDateChange(setStartDate)}
          />
          <span className="text-muted-foreground text-sm">~</span>
          <Input
            type="date"
            aria-label="종료 날짜"
            className="w-[150px]"
            value={endDate}
            min={startDate || undefined}
            onChange={handleDateChange(setEndDate)}
          />
        </div>
      </div>

      {/*
        날짜 범위 역전 인라인 경고 (#541)
        - date input의 min/max 속성은 달력 UI에서만 강제되고 세그먼트 타이핑 편집으로는 우회될 수 있어,
          "감사 로그가 없습니다"라는 일반 빈 상태와 구분되는 명시적 안내가 필요하다.
      */}
      {isDateRangeInverted && (
        <p role="alert" className="text-sm text-destructive">
          날짜 범위가 올바르지 않습니다. 시작일은 종료일 이전이어야 합니다.
        </p>
      )}

      <div className="rounded-md border">
        <Table aria-label="감사 로그">
          <TableHeader>
            <TableRow>
              <TableHead>시간</TableHead>
              <TableHead>사용자</TableHead>
              <TableHead>액션</TableHead>
              <TableHead>리소스</TableHead>
              <TableHead>설명</TableHead>
              <TableHead>결과</TableHead>
              <TableHead>IP</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isDateRangeInverted ? (
              // 날짜 범위 역전 상태 — "결과 없음"과 구분되는 전용 메시지 (#541)
              <TableEmptyRow colSpan={7} message="날짜 범위가 올바르지 않아 조회할 수 없습니다." />
            ) : isLoading ? (
              <TableSkeletonRows columns={7} rows={5} />
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-destructive">
                  데이터를 불러오는데 실패했습니다.
                </TableCell>
              </TableRow>
            ) : logs && logs.content.length > 0 ? (
              logs.content.map((log) => (
                // 행 클릭 시 상세 보기 다이얼로그를 열어 truncate된 description 전문을 표시한다
                <TableRow
                  key={log.id}
                  className="row-hover cursor-pointer"
                  onClick={() => handleRowClick(log)}
                >
                  <TableCell className="whitespace-nowrap text-sm">
                    {formatDateTime(log.actionTime)}
                  </TableCell>
                  <TableCell className="font-medium">{log.username}</TableCell>
                  <TableCell>{formatAuditAction(log.actionType)}</TableCell>
                  <TableCell>{formatAuditResource(log.resource)}</TableCell>
                  <TableCell className="max-w-xs truncate">{log.description ?? '-'}</TableCell>
                  <TableCell>
                    <Badge variant={log.result === 'SUCCESS' ? 'default' : 'destructive'}>
                      {log.result === 'SUCCESS' ? '성공' : '실패'}
                    </Badge>
                  </TableCell>
                  <TableCell
                    className="text-sm text-muted-foreground"
                    title={log.ipAddress ?? undefined}
                  >
                    {formatIpAddress(log.ipAddress)}
                  </TableCell>
                </TableRow>
              ))
            ) : (
              // WD-15: admin 과 같은 빈 상태 — 필터가 걸린 빈 결과는 "기록 없음" 과 구분하고 한 번에 되돌릴 행동을 준다.
              // searchKeyword 분기(검색 초기화)는 쓰지 않는다: 같은 동작에 라벨이 둘로 갈리고, 날짜·셀렉트까지 지워 라벨과 어긋난다.
              <TableEmptyRow
                colSpan={7}
                message={hasAnyFilter ? '조건에 맞는 감사 로그가 없습니다.' : '감사 로그가 없습니다.'}
                emptyAction={
                  hasAnyFilter ? (
                    <Button variant="outline" size="sm" onClick={resetAllFilters}>
                      필터 초기화
                    </Button>
                  ) : undefined
                }
              />
            )}
          </TableBody>
        </Table>
      </div>

      {logs && (
        <SimplePagination
          page={page}
          totalPages={logs.totalPages}
          onPageChange={setPage}
          totalElements={logs.totalElements}
          pageSize={pageSize}
          onPageSizeChange={(size) => {
            setPageSize(size);
            setPage(0);
          }}
        />
      )}

      {/* 감사 로그 상세 보기 다이얼로그 */}
      <AuditLogDetailDialog
        log={selectedLog}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
      />
    </div>
  );
}
