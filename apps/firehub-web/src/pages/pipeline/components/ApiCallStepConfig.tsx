import {
  Check,
  ChevronDown,
  ChevronRight,
  ChevronsUpDown,
  Globe,
  Key,
  Play,
  Plus,
  Settings2,
  Trash2,
} from 'lucide-react';
import { useId, useLayoutEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { client } from '@/api/client';
import { IntegerTextInput } from '@/components/IntegerTextInput';
import { Button } from '@/components/ui/button';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Separator } from '@/components/ui/separator';
import { Textarea } from '@/components/ui/textarea';
import { useApiConnectionsSelectable } from '@/hooks/queries/useApiConnections';
import { useIntegerDraftStore } from '@/hooks/useIntegerDrafts';
import { useIsMounted } from '@/hooks/useIsMounted';
import { API_FULL_URL_PLACEHOLDER, AUTH_TYPE_LABEL, AUTH_TYPE_OPTIONS } from '@/lib/api-connection-form';
import { httpUrlError } from '@/lib/http-url';
import { cn } from '@/lib/utils';
import { columnNameLengthError } from '@/lib/validations/dataset';

import { type ApiConfigSection, sectionOf } from '../hooks/errorFieldOrder';
import type { FocusRequest } from '../hooks/pipelineEditorReducer';
import {
  API_CUSTOM_URL_FIELD,
  API_PAGE_SIZE_FIELD,
  API_PAGE_SIZE_RANGE,
  API_PATH_FIELD,
  apiPageSizeError,
  KV_ROWS_KEYS,
  kvRowField,
  mappingRowField,
  mappingSourceField,
  mappingTargetField,
} from '../hooks/stepColumnValidation';
import ApiCallPreview from './ApiCallPreview';
import { InlineFieldError } from './InlineFieldError';

interface FieldMapping {
  sourceField: string;
  targetColumn: string;
}

interface InlineAuth {
  authType: string;
  placement?: string;
  headerName?: string;
  paramName?: string;
  apiKey?: string;
  token?: string;
  /** (WD-681) Basic 인증 아이디·비밀번호 — 서버·실행기가 저장 연결과 같은 해석 경로(username·password)로 읽는다 */
  username?: string;
  password?: string;
}

interface Pagination {
  type: 'NONE' | 'OFFSET';
  pageSize?: number;
  offsetParam?: string;
  limitParam?: string;
  totalPath?: string;
}

interface RetryConfig {
  maxRetries: number;
  initialBackoffMs: number;
  maxBackoffMs: number;
}

interface ApiCallStepConfigProps {
  apiConfig: Record<string, unknown>;
  apiConnectionId: number | null;
  onChange: (config: Record<string, unknown>) => void;
  onConnectionChange: (id: number | null) => void;
  readOnly: boolean;
  /**
   * 저장 시 검증 오류 조회(usePipelineValidation 이 채운 이 스텝의 오류, 키는 stepColumnValidation 의 mapping*Field).
   * 매핑 행 아래 인라인 문구와 입력의 aria-invalid 에 쓴다(WD-154).
   */
  fieldError?: (field: string) => string | undefined;
  /** 이 스텝의 저장 검증 오류 키 — 오류가 든 접힌 절을 편다(WD-633) */
  errorFields?: readonly string[];
  /** 저장 실패 포커스 요청(리듀서 focusRequest) — 저장 시도마다 새 객체라, 바뀌면 오류가 남은 절을 다시 편다(WD-633) */
  errorFocusRequest?: FocusRequest | null;
}

interface PreviewResult {
  success: boolean;
  rawJson: string | null;
  rows: Array<Record<string, unknown>>;
  columns: string[];
  totalColumns?: number;
  hiddenColumns?: number;
  totalExtractedRows: number;
  errorMessage: string | null;
  resolvedUrl: string | null;
}

type KvPair = { key: string; value: string };

function getConfig<T>(apiConfig: Record<string, unknown>, key: string, defaultValue: T): T {
  return (apiConfig[key] as T) ?? defaultValue;
}

// API 호출 스텝 고급 설정의 허용 범위 (#560)
const MAX_RETRIES_RANGE = { min: 0, max: 10 } as const;
const TIMEOUT_MS_RANGE = { min: 1000, max: 300_000 } as const; // 1초 ~ 5분
const MAX_DURATION_MS_RANGE = { min: 1000, max: 86_400_000 } as const; // 1초 ~ 24시간

function recordToKvPairs(obj: Record<string, string>): KvPair[] {
  return Object.entries(obj).map(([key, value]) => ({ key, value }));
}

function kvPairsToRecord(pairs: KvPair[]): Record<string, string> {
  const result: Record<string, string> = {};
  for (const { key, value } of pairs) {
    if (key.trim()) result[key.trim()] = value;
  }
  return result;
}

/**
 * 미리보기 요청 payload 를 저장되는 설정(apiConfig·apiConnectionId)만으로 만드는 순수 함수(WD-935).
 * 호출 시작과 응답 도착 양쪽에서 같은 방식으로 만들어, 응답이 돌아왔을 때 요청에 영향을 주는 값이
 * 그대로인지 비교하는 데 쓴다. 편집 중 행(headerRows·queryParamRows)이 있으면 화면과 같게 그것을 먼저 본다.
 */
function buildPreviewPayload(apiConfig: Record<string, unknown>, apiConnectionId: number | null) {
  const path = getConfig<string>(apiConfig, 'path', '');
  const customUrl = getConfig<string>(apiConfig, 'customUrl', '');
  const method = getConfig<string>(apiConfig, 'method', 'GET');
  const inlineAuth = getConfig<InlineAuth>(apiConfig, 'inlineAuth', { authType: 'NONE' });
  const headerRows = getConfig<KvPair[] | undefined>(apiConfig, KV_ROWS_KEYS.headers, undefined);
  const queryParamRows = getConfig<KvPair[] | undefined>(apiConfig, KV_ROWS_KEYS.queryParams, undefined);
  return {
    apiConnectionId: apiConnectionId ?? undefined,
    // 직접 URL 은 앞뒤 공백을 뺀 값으로 호출한다(WD-909 검증과 같은 값)
    url: apiConnectionId !== null ? path : customUrl.trim(),
    method,
    headers: Array.isArray(headerRows)
      ? kvPairsToRecord(headerRows)
      : getConfig<Record<string, string>>(apiConfig, 'headers', {}),
    queryParams: Array.isArray(queryParamRows)
      ? kvPairsToRecord(queryParamRows)
      : getConfig<Record<string, string>>(apiConfig, 'queryParams', {}),
    body: method === 'POST' ? getConfig<string>(apiConfig, 'body', '') : undefined,
    dataPath: getConfig<string>(apiConfig, 'dataPath', ''),
    fieldMappings: getConfig<FieldMapping[]>(apiConfig, 'fieldMappings', []),
    inlineAuth: inlineAuth.authType !== 'NONE' ? inlineAuth : undefined,
    timeoutMs: getConfig<number>(apiConfig, 'timeoutMs', 30000),
  };
}

/** 응답 컬럼에 영향을 주는 요청 전체의 비교 키 — 매핑(fieldMappings)은 응답의 결과가 아니라 입력이라 뺀다(WD-935) */
function previewRequestKey(apiConfig: Record<string, unknown>, apiConnectionId: number | null): string {
  return JSON.stringify({ ...buildPreviewPayload(apiConfig, apiConnectionId), fieldMappings: undefined });
}

interface SectionProps {
  title: string;
  icon?: React.ReactNode;
  defaultOpen?: boolean;
  children: React.ReactNode;
  /** 절 안 칸에 저장 검증 오류가 있다 */
  hasError?: boolean;
  /** 저장 실패 포커스 요청 — 바뀔 때(저장 시도) 오류가 있으면 편다 */
  focusRequest?: FocusRequest | null;
  /** 숨은 숫자 칸 오류를 다시 펴 달라는 요청 번호(편집기 저장 차단) — 바뀔 때 오류가 있으면 편다 (WD-906 후속) */
  revealSeq?: number;
}

/**
 * 접을 수 있는 설정 절. 오류가 있으면 마운트 때와 저장을 시도할 때마다 펴서 오류 문구·포커스할 칸을 그린다(WD-633).
 * 렌더 중에 열어야 같은 커밋에 칸이 생겨 포커스가 늦지 않다.
 */
function Section({ title, icon, defaultOpen = false, children, hasError = false, focusRequest = null, revealSeq = 0 }: SectionProps) {
  const [open, setOpen] = useState(defaultOpen || hasError);
  const [prevFocusRequest, setPrevFocusRequest] = useState(focusRequest);
  const [prevRevealSeq, setPrevRevealSeq] = useState(revealSeq);
  if (focusRequest !== prevFocusRequest) {
    setPrevFocusRequest(focusRequest);
    if (focusRequest && hasError) setOpen(true);
  }
  if (revealSeq !== prevRevealSeq) {
    setPrevRevealSeq(revealSeq);
    if (hasError) setOpen(true);
  }
  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <CollapsibleTrigger asChild>
        <button className="flex w-full items-center justify-between py-1.5 text-sm font-medium hover:text-foreground/80 transition-colors">
          <span className="flex items-center gap-1.5">
            {icon}
            {title}
          </span>
          {open ? (
            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
          )}
        </button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="pb-2 space-y-2">{children}</div>
      </CollapsibleContent>
    </Collapsible>
  );
}

interface KvEditorProps {
  pairs: KvPair[];
  onChange: (pairs: KvPair[]) => void;
  readOnly: boolean;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  /** 행 번호 → 저장 검증 오류 키(WD-359). 오류 문구 조회와 저장 실패 포커스 칸(이름 입력)의 data-error-field 에 쓴다 */
  rowField?: (i: number) => string;
  /** 저장 검증 오류 조회 — 있으면 그 행 아래에 보인다 */
  fieldError?: (field: string) => string | undefined;
  /** 오류 문구 요소 id 접두사(입력의 aria-describedby 연결용) */
  errorIdPrefix?: string;
}

function KvEditor({
  pairs,
  onChange,
  readOnly,
  keyPlaceholder = '키',
  valuePlaceholder = '값',
  rowField,
  fieldError,
  errorIdPrefix = 'kv',
}: KvEditorProps) {
  const handleAdd = () => onChange([...pairs, { key: '', value: '' }]);
  const handleRemove = (i: number) => onChange(pairs.filter((_, idx) => idx !== i));
  const handleChange = (i: number, field: 'key' | 'value', val: string) => {
    const next = pairs.map((p, idx) => (idx === i ? { ...p, [field]: val } : p));
    onChange(next);
  };

  return (
    <div className="space-y-1.5">
      {pairs.map((pair, i) => {
        const field = rowField?.(i);
        const message = field ? fieldError?.(field) : undefined;
        const errorId = `${errorIdPrefix}-error-${i}`;
        return (
        <div key={i} className="space-y-0.5">
        <div className="flex gap-1.5 items-center">
          <Input
            className="h-7 text-xs"
            placeholder={keyPlaceholder}
            value={pair.key}
            disabled={readOnly}
            /* 행 오류(이름 없음·같은 이름)는 이름 칸의 문제라 저장 실패 포커스는 이름 입력으로 간다(WD-633) */
            data-error-field={field}
            aria-invalid={message ? true : undefined}
            aria-describedby={message ? errorId : undefined}
            onChange={(e) => handleChange(i, 'key', e.target.value)}
          />
          <Input
            className="h-7 text-xs"
            placeholder={valuePlaceholder}
            value={pair.value}
            disabled={readOnly}
            aria-invalid={message ? true : undefined}
            aria-describedby={message ? errorId : undefined}
            onChange={(e) => handleChange(i, 'value', e.target.value)}
          />
          {!readOnly && (
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7 shrink-0"
              onClick={() => handleRemove(i)}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </Button>
          )}
        </div>
        <InlineFieldError id={errorId} message={message} />
        </div>
        );
      })}
      {!readOnly && (
        <Button variant="outline" size="sm" className="text-xs" onClick={handleAdd}>
          <Plus className="h-3 w-3" />
          추가
        </Button>
      )}
    </div>
  );
}

/** API 연결 선택 Combobox — 첫 항목 "직접 입력", 나머지 저장된 연결 */
function ConnectionCombobox({
  connections,
  value,
  onChange,
  disabled = false,
}: {
  connections: { id: number; name: string; authType: string; baseUrl: string }[];
  value: number | null;
  onChange: (id: number | null) => void;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const selected = value !== null ? connections.find((c) => c.id === value) : null;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          role="combobox"
          aria-expanded={open}
          disabled={disabled}
          className="w-full justify-between font-normal h-8 text-xs"
        >
          <span className={cn('truncate', !selected && 'text-muted-foreground')}>
            {selected ? `${selected.name} — ${selected.baseUrl}` : '직접 입력'}
          </span>
          <ChevronsUpDown className=" h-3.5 w-3.5 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[--radix-popover-trigger-width] p-0" align="start">
        <Command>
          <CommandInput placeholder="연결 검색..." className="text-xs" />
          <CommandList>
            <CommandEmpty>연결을 찾을 수 없습니다.</CommandEmpty>
            <CommandGroup>
              <CommandItem
                value="__inline__"
                onSelect={() => { onChange(null); setOpen(false); }}
              >
                <Check className={cn('h-3.5 w-3.5', value === null ? 'opacity-100' : 'opacity-0')} />
                <span className="font-medium">직접 입력</span>
                <span className="ml-auto text-muted-foreground text-xs">URL + 인증 수동</span>
              </CommandItem>
              {connections.map((conn) => (
                <CommandItem
                  key={conn.id}
                  value={`${conn.name} ${conn.baseUrl}`}
                  onSelect={() => { onChange(conn.id); setOpen(false); }}
                >
                  <Check className={cn('h-3.5 w-3.5', value === conn.id ? 'opacity-100' : 'opacity-0')} />
                  <span className="font-medium">{conn.name}</span>
                  <span className="ml-auto text-muted-foreground text-xs truncate max-w-[200px]">{conn.baseUrl}</span>
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}

export default function ApiCallStepConfig({
  apiConfig,
  apiConnectionId,
  onChange,
  onConnectionChange,
  readOnly,
  fieldError,
  errorFields = [],
  errorFocusRequest = null,
}: ApiCallStepConfigProps) {
  // 숫자 칸 무효 원문 보관소(편집기가 이 스텝 범위로 좁혀 내려 준다) — 칸이 언마운트돼도 오류 원문·저장 차단이 남는다 (WD-906 후속)
  const integerDrafts = useIntegerDraftStore();
  // 고급 설정의 숫자 칸 중 무효 원문이 맡겨진 칸이 있으면, 다시 마운트될 때·저장 차단 때 절을 펴서 오류를 보인다
  const advancedHasIntegerDraft = ['maxRetries', 'timeoutMs', 'maxDurationMs'].some(
    (key) => integerDrafts?.get(key) !== undefined,
  );
  // 접근성: 라벨↔입력 연결용 id 접두사 (#432).
  // 이 패널은 스텝이 바뀔 때마다 key 로 재마운트되고 여러 곳에서 재사용될 수 있어 하드코딩 id 는 충돌한다.
  const baseId = useId();

  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null);
  // 「테스트 호출」 때 직접 URL 이 비었거나 형식이 틀리면 토스트 대신 URL 칸 아래에 보이는 오류(WD-909). 입력·연결 변경 때 지운다.
  const [previewUrlError, setPreviewUrlError] = useState<string | undefined>(undefined);
  const customUrlRef = useRef<HTMLInputElement>(null);
  // selectable 엔드포인트: 일반 사용자도 접근 가능 (파이프라인 에디터 권한)
  const { data: savedConnections } = useApiConnectionsSelectable();

  const authMode = apiConnectionId ? 'saved' : 'inline';

  const update = (key: string, value: unknown) => onChange({ ...apiConfig, [key]: value });

  // 항상 가장 최근에 렌더된 설정 — await 이후(미리보기 응답)에 부르는 콜백은 호출을 시작한 렌더의 클로저를 쥐고 있어
  // 그 사이 사용자가 고친 값을 옛 값으로 덮어쓴다(WD-935). 응답 시점에는 이 ref 로 최신 값을 읽는다.
  // 렌더 중 ref 대입은 린트(react-hooks) 규칙에 어긋나므로 layout effect 로 갱신한다(페인트 전에 반영).
  const latestRef = useRef({ apiConfig, apiConnectionId });
  useLayoutEffect(() => {
    latestRef.current = { apiConfig, apiConnectionId };
  }, [apiConfig, apiConnectionId]);
  // 마운트 여부 — 이 패널은 스텝마다 key 로 재마운트되므로, 호출 중 다른 스텝에 갔다 오면 옛 인스턴스의 응답 처리기가
  // 새 인스턴스에서 고친 값을 덮을 수 있다. 언마운트된 인스턴스는 응답 뒤 쓰기를 하지 않는다(WD-935).
  const isMounted = useIsMounted();

  // saved 모드: path (apiConnectionId + path → 백엔드에서 baseUrl + path 조합)
  const path = getConfig<string>(apiConfig, 'path', '');
  // inline 모드: customUrl (full URL)
  const customUrl = getConfig<string>(apiConfig, 'customUrl', '');
  // 선택된 연결 정보 (baseUrl prefix 표시용)
  const selectedConn = apiConnectionId
    ? (savedConnections?.find((c) => c.id === apiConnectionId) ?? null)
    : null;
  const method = getConfig<string>(apiConfig, 'method', 'GET');
  const dataPath = getConfig<string>(apiConfig, 'dataPath', '');
  const body = getConfig<string>(apiConfig, 'body', '');
  const inlineAuth = getConfig<InlineAuth>(apiConfig, 'inlineAuth', { authType: 'NONE' });
  const pagination = getConfig<Pagination>(apiConfig, 'pagination', { type: 'NONE' });
  // 페이지 크기 범위 오류 — 저장 검증(usePipelineValidation)과 같은 함수라 입력 중에도 같은 문구를 바로 보인다(WD-362)
  const pageSizeError = apiPageSizeError(pagination);
  const retry = getConfig<RetryConfig>(apiConfig, 'retry', {
    maxRetries: 3,
    initialBackoffMs: 1000,
    maxBackoffMs: 10000,
  });
  const timeoutMs = getConfig<number>(apiConfig, 'timeoutMs', 30000);
  const maxDurationMs = getConfig<number>(apiConfig, 'maxDurationMs', 3600000);
  const fieldMappings = getConfig<FieldMapping[]>(apiConfig, 'fieldMappings', []);
  const headersRecord = getConfig<Record<string, string>>(apiConfig, 'headers', {});
  const queryParamsRecord = getConfig<Record<string, string>>(apiConfig, 'queryParams', {});

  // URL 칸 저장 오류(usePipelineValidation: 빈 값 #44·형식 오류 WD-909) — 칸 아래 문구로 보이고 입력과 연결한다(WD-633)
  // 저장 오류가 있으면 그것을 먼저 보인다 — 같은 칸에 문구가 둘 뜨지 않게 한다(WD-909)
  const customUrlError = fieldError?.(API_CUSTOM_URL_FIELD) ?? previewUrlError;
  const pathError = fieldError?.(API_PATH_FIELD);
  const customUrlErrorId = `${baseId}-custom-url-error`;
  const pathErrorId = `${baseId}-path-error`;

  // 편집 중 행 목록은 화면 전용 키(headerRows·queryParamRows)로 설정에도 함께 둔다 — 같은 이름·이름 없는 행은 객체 설정(headers·queryParams)에
  // 담기지 않아 조용히 버려지므로, 저장 전 검증(usePipelineValidation)이 이 목록을 보고 행 아래에 안내한다(WD-359).
  // 스텝을 바꿨다 돌아와도 편집 중 행이 남도록 초기값도 이 키를 먼저 본다(없으면 서버 설정 객체에서 만든다).
  const headerRows = getConfig<KvPair[] | undefined>(apiConfig, KV_ROWS_KEYS.headers, undefined);
  const queryParamRows = getConfig<KvPair[] | undefined>(apiConfig, KV_ROWS_KEYS.queryParams, undefined);
  // 주의(WD-935): 아래 초기값 규칙(편집 중 행 우선, 없으면 객체 설정)은 buildPreviewPayload 의 headers·queryParams 읽기와 같아야 한다 — 한쪽만 바꾸면 화면과 호출 요청이 어긋난다.
  const [headerPairs, setHeaderPairs] = useState<KvPair[]>(() =>
    Array.isArray(headerRows) ? headerRows : recordToKvPairs(headersRecord),
  );
  const [queryParamPairs, setQueryParamPairs] = useState<KvPair[]>(() =>
    Array.isArray(queryParamRows) ? queryParamRows : recordToKvPairs(queryParamsRecord),
  );

  // 저장 오류가 든 절(WD-633) — 접힌 절을 펼 때 쓴다
  const erroredSections = new Set<ApiConfigSection | null>(errorFields.map(sectionOf));

  const handleHeadersChange = (pairs: KvPair[]) => {
    setHeaderPairs(pairs);
    // 한 번에 두 키를 바꾼다 — update 를 두 번 부르면 뒤 호출이 앞 호출의 변경을 덮는다
    onChange({ ...apiConfig, headers: kvPairsToRecord(pairs), [KV_ROWS_KEYS.headers]: pairs });
  };

  const handleQueryParamsChange = (pairs: KvPair[]) => {
    setQueryParamPairs(pairs);
    onChange({ ...apiConfig, queryParams: kvPairsToRecord(pairs), [KV_ROWS_KEYS.queryParams]: pairs });
  };

  /**
   * 연결 모드 전환 시 기존 url/path/customUrl 필드를 초기화한다.
   * - 과거 'url' 필드도 함께 제거해 레거시 데이터를 정리한다.
   * - 직접 입력 인증(inlineAuth)도 함께 버린다 — 연결 모드에선 인증 UI 가 숨겨져 안 보이는 비밀이
   *   저장되지 않게, 직접 입력으로 돌아오면 인증이 「없음」으로 시작하게 한다 (WD-934).
   */
  const handleConnectionChange = (id: number | null) => {
    onConnectionChange(id);
    setPreviewUrlError(undefined);
    const cleaned = { ...apiConfig };
    delete cleaned['url'];       // 과거 필드 제거
    delete cleaned['path'];
    delete cleaned['customUrl'];
    delete cleaned['inlineAuth'];
    onChange(cleaned);
  };

  const handleAuthChange = (partial: Partial<InlineAuth>) => {
    update('inlineAuth', { ...inlineAuth, ...partial });
  };

  const handlePaginationChange = (partial: Partial<Pagination>) => {
    update('pagination', { ...pagination, ...partial });
  };

  const handleRetryChange = (partial: Partial<RetryConfig>) => {
    update('retry', { ...retry, ...partial });
  };

  const handleMappingChange = (i: number, field: keyof FieldMapping, value: string) => {
    const next = fieldMappings.map((m, idx) => (idx === i ? { ...m, [field]: value } : m));
    update('fieldMappings', next);
  };

  const handleAddMapping = () => {
    update('fieldMappings', [
      ...fieldMappings,
      { sourceField: '', targetColumn: '' },
    ]);
  };

  const handleRemoveMapping = (i: number) => {
    update('fieldMappings', fieldMappings.filter((_, idx) => idx !== i));
  };

  const handlePreview = async () => {
    // saved 모드: path 필요, inline 모드: customUrl 필요
    if (apiConnectionId !== null && !path) {
      toast.error('경로(Path)를 입력하세요.');
      return;
    }
    // 직접 모드는 빈 값·형식 오류를 토스트 대신 URL 칸 오류로 안내하고 그 칸으로 포커스한다(WD-909) — 저장 검증과 같은 함수·문구
    // 이전 성공 결과가 새 URL 오류 옆에 남지 않게 검증 전에 먼저 비운다
    setPreviewResult(null);
    const directUrl = customUrl.trim();
    if (apiConnectionId === null) {
      const message = directUrl ? httpUrlError(directUrl) : 'URL을 입력하세요';
      if (message) {
        setPreviewUrlError(message);
        customUrlRef.current?.focus();
        return;
      }
    }
    // 요청 전체(매핑 제외) 키 스냅샷 — 응답이 돌아왔을 때 최신 설정의 키와 비교해,
    // 호출 중 연결·URL·메서드·헤더·본문·데이터 경로·인증 등을 바꿨다면 옛 요청의 컬럼으로 매핑을 채우지 않는다(WD-935).
    const requestKey = previewRequestKey(apiConfig, apiConnectionId);
    setPreviewLoading(true);
    try {
      // 백엔드 ApiCallPreviewService: apiConnectionId + path 또는 customUrl로 URL 계산
      const payload = buildPreviewPayload(apiConfig, apiConnectionId);
      const { data } = await client.post<PreviewResult>(
        '/pipelines/api-call/preview',
        payload,
      );
      // 언마운트됐으면(다른 스텝으로 갔다 돌아온 경우 등) 화면 상태·설정 모두 건드리지 않는다(WD-935)
      if (!isMounted()) return;
      setPreviewResult(data);

      // 응답이 비어 있던 매핑을 자동으로 채운다 — 단, 호출 시작 값이 아니라 응답 시점의 최신 설정 기준으로 판단한다(WD-935):
      // 호출 대상이 바뀌었거나 사용자가 그 사이 매핑을 직접 채웠으면 건너뛰고, 채울 때도 최신 설정에 병합해 다른 필드 수정을 보존한다.
      const latest = latestRef.current;
      const latestMappings = getConfig<FieldMapping[]>(latest.apiConfig, 'fieldMappings', []);
      if (
        data.success &&
        data.columns.length > 0 &&
        latestMappings.every((m) => !m.sourceField) &&
        previewRequestKey(latest.apiConfig, latest.apiConnectionId) === requestKey
      ) {
        const autoMappings: FieldMapping[] = data.columns.map((col) => ({
          sourceField: col,
          targetColumn: col,
        }));
        onChange({ ...latest.apiConfig, fieldMappings: autoMappings });
      }
    } catch (err: unknown) {
      if (!isMounted()) return;
      const message =
        (err as { response?: { data?: { message?: string } } })?.response?.data?.message ??
        '미리보기 호출에 실패했습니다';
      setPreviewResult({
        success: false,
        rawJson: null,
        rows: [],
        columns: [],
        totalExtractedRows: 0,
        errorMessage: message,
        resolvedUrl: null,
      });
    } finally {
      if (isMounted()) setPreviewLoading(false);
    }
  };

  return (
    <div className="space-y-3">
      {/* 기본 설정: API 연결 → URL/Path → 메서드 */}
      <div className="space-y-2">
        <div className="flex items-center gap-1.5 text-sm font-medium">
          <Globe className="h-3.5 w-3.5" />
          기본 설정
        </div>

        {/* API 연결 선택 (검색 가능 Combobox) */}
        <ConnectionCombobox
          connections={savedConnections ?? []}
          value={apiConnectionId}
          // 한 번의 onChange 로 처리한다 — 두 번 연속 호출하면 같은 렌더의 apiConfig 로 서로 덮어쓴다 (WD-934)
          onChange={handleConnectionChange}
          disabled={readOnly}
        />

        {/*
          saved 모드: baseUrl prefix + path. 경로 칸은 연결 정보(selectedConn)를 못 찾아도(삭제·선택 불가 연결, 로딩 중) 그려
          경로 오류를 보이고 포커스할 수 있게 한다(WD-633).
        */}
        {apiConnectionId !== null && (
          <div className="space-y-2">
            {selectedConn && (
              <div className="space-y-1.5">
                <Label htmlFor={`${baseId}-base-url`} className="text-xs">URL *</Label>
                <Input
                  id={`${baseId}-base-url`}
                  value={selectedConn.baseUrl}
                  disabled
                  className="text-xs h-8 font-mono bg-muted"
                />
              </div>
            )}
            <div className="space-y-1.5">
              <Label htmlFor={`${baseId}-path`} className="text-xs">추가 경로</Label>
              <Input
                id={`${baseId}-path`}
                placeholder="/v1/data (선택)"
                value={path}
                disabled={readOnly}
                data-error-field={API_PATH_FIELD}
                aria-invalid={pathError ? true : undefined}
                aria-describedby={pathError ? pathErrorId : undefined}
                onChange={(e) => update('path', e.target.value)}
                className="text-xs h-8"
              />
              <InlineFieldError id={pathErrorId} message={pathError} />
            </div>
          </div>
        )}

        {/* inline 모드: full URL 직접 입력 */}
        {apiConnectionId === null && (
          <div className="space-y-1.5">
            <Label htmlFor={`${baseId}-custom-url`} className="text-xs">URL *</Label>
            <Input
              id={`${baseId}-custom-url`}
              ref={customUrlRef}
              // 예시 플레이스홀더 — 까닭은 공용 상수 설명(WD-887)
              placeholder={API_FULL_URL_PLACEHOLDER}
              value={customUrl}
              disabled={readOnly}
              data-error-field={API_CUSTOM_URL_FIELD}
              aria-invalid={customUrlError ? true : undefined}
              aria-describedby={customUrlError ? customUrlErrorId : undefined}
              onChange={(e) => {
                setPreviewUrlError(undefined);
                update('customUrl', e.target.value);
              }}
              className="text-xs h-8"
            />
            <InlineFieldError id={customUrlErrorId} message={customUrlError} />
          </div>
        )}

        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1.5">
            <Label htmlFor={`${baseId}-method`} className="text-xs">HTTP 메서드</Label>
            <Select value={method} disabled={readOnly} onValueChange={(v) => update('method', v)}>
              <SelectTrigger id={`${baseId}-method`} size="sm" className="text-xs w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="GET">GET</SelectItem>
                <SelectItem value="POST">POST</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor={`${baseId}-data-path`} className="text-xs">데이터 경로</Label>
            <Input
              id={`${baseId}-data-path`}
              /* 아래 JSONPath 안내문은 데이터 경로 필드만 설명한다 (2열 그리드 밖에 있어 시각적으로만 떨어져 있음) */
              aria-describedby={`${baseId}-data-path-help`}
              placeholder="$.data.items"
              value={dataPath}
              disabled={readOnly}
              onChange={(e) => update('dataPath', e.target.value)}
              className="text-xs h-8"
            />
          </div>
        </div>
        <p id={`${baseId}-data-path-help`} className="text-xs text-muted-foreground">
          JSONPath 형식으로 데이터 배열의 경로를 지정하세요
        </p>
      </div>

      <Separator />

      {/* 인증 — inline 모드일 때만 표시 (saved 연결은 인증 정보 포함) */}
      {authMode === 'inline' && (
        <Section title="인증" icon={<Key className="h-3.5 w-3.5" />} defaultOpen={true}>
          <div className="space-y-2">
            <Select
              value={inlineAuth.authType}
              disabled={readOnly}
              onValueChange={(v) => {
                const patch: Partial<InlineAuth> = { authType: v };
                if (v === 'API_KEY' && !inlineAuth.placement) {
                  patch.placement = 'header';
                }
                handleAuthChange(patch);
              }}
            >
              <SelectTrigger size="sm" className="text-xs w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {/* 「없음」은 인라인 인증에만 있는 선택지라 따로 두고, 나머지는 API 연결 화면과 같은 공용 표를 쓴다(WD-681) */}
                <SelectItem value="NONE">없음</SelectItem>
                {AUTH_TYPE_OPTIONS.map((type) => (
                  <SelectItem key={type} value={type}>
                    {AUTH_TYPE_LABEL[type].full}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            {inlineAuth.authType === 'API_KEY' && (
              <>
                <Select
                  value={inlineAuth.placement ?? 'header'}
                  disabled={readOnly}
                  onValueChange={(v) => handleAuthChange({ placement: v })}
                >
                  <SelectTrigger size="sm" className="text-xs w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="header">Header</SelectItem>
                    <SelectItem value="query">Query Parameter</SelectItem>
                  </SelectContent>
                </Select>
                <Input
                  className="h-7 text-xs"
                  placeholder={inlineAuth.placement === 'query' ? 'api_key' : 'X-API-Key'}
                  value={
                    inlineAuth.placement === 'query'
                      ? (inlineAuth.paramName ?? '')
                      : (inlineAuth.headerName ?? '')
                  }
                  disabled={readOnly}
                  onChange={(e) =>
                    inlineAuth.placement === 'query'
                      ? handleAuthChange({ paramName: e.target.value })
                      : handleAuthChange({ headerName: e.target.value })
                  }
                />
                <Input
                  className="h-7 text-xs"
                  type="password"
                  placeholder="API 키를 입력하세요"
                  value={inlineAuth.apiKey ?? ''}
                  disabled={readOnly}
                  onChange={(e) => handleAuthChange({ apiKey: e.target.value })}
                />
              </>
            )}

            {inlineAuth.authType === 'BEARER' && (
              <Input
                className="h-7 text-xs"
                type="password"
                placeholder="토큰을 입력하세요"
                value={inlineAuth.token ?? ''}
                disabled={readOnly}
                onChange={(e) => handleAuthChange({ token: e.target.value })}
              />
            )}

            {/* (WD-681) Basic — 인라인 인증도 저장 연결과 같은 해석 경로라 Authorization: Basic 으로 나간다.
                URL 에 아이디:비밀번호@ 를 넣는 대신 이 두 칸을 쓴다. 라벨이 보이지 않는 좁은 칸이라 aria-label 로 이름을 준다. */}
            {inlineAuth.authType === 'BASIC' && (
              <>
                <Input
                  className="h-7 text-xs"
                  aria-label="아이디"
                  autoComplete="off"
                  placeholder="아이디를 입력하세요"
                  value={inlineAuth.username ?? ''}
                  disabled={readOnly}
                  onChange={(e) => handleAuthChange({ username: e.target.value })}
                />
                <Input
                  className="h-7 text-xs"
                  type="password"
                  aria-label="비밀번호"
                  autoComplete="new-password"
                  placeholder="비밀번호를 입력하세요"
                  value={inlineAuth.password ?? ''}
                  disabled={readOnly}
                  onChange={(e) => handleAuthChange({ password: e.target.value })}
                />
              </>
            )}
          </div>
        </Section>
      )}

      <Separator />

      {/* Headers */}
      <Section title="헤더" hasError={erroredSections.has('headers')} focusRequest={errorFocusRequest}>
        <KvEditor
          pairs={headerPairs}
          onChange={handleHeadersChange}
          readOnly={readOnly}
          keyPlaceholder="Content-Type"
          valuePlaceholder="application/json"
          rowField={(i) => kvRowField('headers', i)}
          fieldError={fieldError}
          errorIdPrefix={`${baseId}-header`}
        />
      </Section>

      <Separator />

      {/* Query Parameters */}
      <Section
        title="쿼리 파라미터"
        hasError={erroredSections.has('queryParams')}
        focusRequest={errorFocusRequest}
      >
        <KvEditor
          pairs={queryParamPairs}
          onChange={handleQueryParamsChange}
          readOnly={readOnly}
          keyPlaceholder="파라미터명"
          valuePlaceholder="값"
          rowField={(i) => kvRowField('queryParams', i)}
          fieldError={fieldError}
          errorIdPrefix={`${baseId}-query`}
        />
      </Section>

      <Separator />

      {/* Request Body - POST only */}
      {method === 'POST' && (
        <>
          <Section title="요청 바디">
            <Textarea
              className="text-xs font-mono"
              rows={4}
              placeholder='{"key": "value"}'
              value={body}
              disabled={readOnly}
              onChange={(e) => update('body', e.target.value)}
            />
          </Section>
          <Separator />
        </>
      )}

      {/* Field Mappings */}
      <Section
        title="필드 매핑"
        defaultOpen={true}
        hasError={erroredSections.has('fieldMappings')}
        focusRequest={errorFocusRequest}
      >
        <p className="text-xs text-muted-foreground">
          미리보기를 실행하면 소스 필드가 자동으로 채워집니다
        </p>
        <div className="space-y-1.5">
          {fieldMappings.length > 0 && (
            <div className="grid grid-cols-[1fr_1fr_auto] gap-1 text-xs text-muted-foreground px-0.5">
              <span>소스 필드</span>
              <span>대상 컬럼</span>
              <span></span>
            </div>
          )}
          {fieldMappings.map((mapping, i) => {
            // 행 오류 자리는 하나다 — 저장 시 검증 오류(빈 행 > 응답 필드 > 대상 컬럼, WD-154)가 있으면 그것을, 없으면 입력 중 길이 상한
            // 안내를 보인다. 출력 데이터셋이 없으면 대상 컬럼이 임시 데이터셋의 칼럼명이 된다 — 길이 상한은 columnNameLengthError 참고.
            const rowError = fieldError?.(mappingRowField(i));
            const sourceError = fieldError?.(mappingSourceField(i));
            const targetError =
              fieldError?.(mappingTargetField(i)) ?? columnNameLengthError(mapping.targetColumn);
            const rowMessage = rowError ?? sourceError ?? targetError;
            const rowErrorId = `${baseId}-mapping-error-${i}`;
            const sourceInvalid = !!(rowError || sourceError);
            const targetInvalid = !!(rowError || targetError);
            return (
              <div key={i} className="space-y-0.5">
                <div className="grid grid-cols-[1fr_1fr_auto] gap-1 items-center">
                  <Input
                    className="h-7 text-xs"
                    placeholder="sourceField"
                    aria-label={`응답 필드 ${i + 1}`}
                    /* 빈 행·응답 필드 오류는 행의 첫 칸(응답 필드)으로 포커스한다(WD-633) */
                    data-error-field={`${mappingRowField(i)} ${mappingSourceField(i)}`}
                    value={mapping.sourceField}
                    aria-invalid={sourceInvalid}
                    aria-describedby={sourceInvalid ? rowErrorId : undefined}
                    disabled={readOnly}
                    onChange={(e) => handleMappingChange(i, 'sourceField', e.target.value)}
                  />
                  <Input
                    className="h-7 text-xs"
                    placeholder="target_col"
                    aria-label={`대상 컬럼 ${i + 1}`}
                    data-error-field={mappingTargetField(i)}
                    value={mapping.targetColumn}
                    aria-invalid={targetInvalid}
                    aria-describedby={targetInvalid ? rowErrorId : undefined}
                    disabled={readOnly}
                    onChange={(e) => handleMappingChange(i, 'targetColumn', e.target.value)}
                  />
                  {!readOnly && (
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-7 w-7 shrink-0"
                      onClick={() => handleRemoveMapping(i)}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  )}
                </div>
                {/* 대상 컬럼 길이 상한은 입력 중 오류 — live(InlineFieldError 참고) */}
                <InlineFieldError id={rowErrorId} message={rowMessage} live />
              </div>
            );
          })}
          {!readOnly && (
            <Button
              variant="outline"
              size="sm"
              className="text-xs"
              onClick={handleAddMapping}
            >
              <Plus className="h-3 w-3" />
              매핑 추가
            </Button>
          )}
        </div>
      </Section>

      <Separator />

      {/* Pagination */}
      <Section
        title="페이지네이션"
        hasError={erroredSections.has('pagination')}
        focusRequest={errorFocusRequest}
      >
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <div className="flex-1 space-y-1">
              <Label htmlFor={`${baseId}-pagination-type`} className="text-xs">유형</Label>
              <Select
                value={pagination.type}
                disabled={readOnly}
                onValueChange={(v) =>
                  handlePaginationChange({ type: v as 'NONE' | 'OFFSET' })
                }
              >
                <SelectTrigger id={`${baseId}-pagination-type`} size="sm" className="text-xs w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="NONE">없음</SelectItem>
                  <SelectItem value="OFFSET">Offset</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {pagination.type === 'OFFSET' && !readOnly && (
              <div className="flex-1 space-y-1">
                <Label htmlFor={`${baseId}-pagination-preset`} className="text-xs">프리셋</Label>
                <Select
                  value=""
                  onValueChange={(v) => {
                    if (v === 'public') {
                      handlePaginationChange({ offsetParam: 'page', limitParam: 'perPage' });
                    } else if (v === 'standard') {
                      handlePaginationChange({ offsetParam: 'offset', limitParam: 'limit' });
                    }
                  }}
                >
                  <SelectTrigger id={`${baseId}-pagination-preset`} size="sm" className="text-xs w-full">
                    <SelectValue placeholder="프리셋 선택" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="public">공공데이터포털 (page/perPage)</SelectItem>
                    <SelectItem value="standard">표준 REST (offset/limit)</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            )}
          </div>

          {pagination.type === 'OFFSET' && (
            <div className="space-y-1.5">
              <div className="grid grid-cols-2 gap-1.5">
                <div className="space-y-1">
                  <Label htmlFor={`${baseId}-page-size`} className="text-xs">페이지 크기</Label>
                  <Input
                    id={`${baseId}-page-size`}
                    className="h-7 text-xs"
                    type="number"
                    min={API_PAGE_SIZE_RANGE.min}
                    max={API_PAGE_SIZE_RANGE.max}
                    step={1}
                    value={pagination.pageSize ?? 100}
                    disabled={readOnly}
                    data-error-field={API_PAGE_SIZE_FIELD}
                    aria-invalid={pageSizeError ? true : undefined}
                    aria-describedby={pageSizeError ? `${baseId}-page-size-error` : `${baseId}-page-size-hint`}
                    onChange={(e) =>
                      handlePaginationChange({ pageSize: Number(e.target.value) })
                    }
                  />
                  {/* 범위 밖 값은 고치지 않고 안내만 한다 — 외부 API limit 로 그대로 나가는 값이라 조용히 바꾸지 않는다(WD-362) */}
                  {/* 범위 오류는 입력 중 오류 — live(InlineFieldError 참고). 오류가 없을 때만 범위 안내를 보인다 */}
                  <InlineFieldError id={`${baseId}-page-size-error`} message={pageSizeError} live />
                  {!pageSizeError && (
                    <p id={`${baseId}-page-size-hint`} className="text-[11px] text-muted-foreground">
                      {API_PAGE_SIZE_RANGE.min}~{API_PAGE_SIZE_RANGE.max}건
                    </p>
                  )}
                </div>
                <div className="space-y-1">
                  <Label htmlFor={`${baseId}-total-path`} className="text-xs">총 건수 경로</Label>
                  <Input
                    id={`${baseId}-total-path`}
                    className="h-7 text-xs"
                    placeholder="$.meta.totalCount"
                    value={pagination.totalPath ?? ''}
                    disabled={readOnly}
                    onChange={(e) => handlePaginationChange({ totalPath: e.target.value })}
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor={`${baseId}-offset-param`} className="text-xs">Offset 파라미터</Label>
                  <Input
                    id={`${baseId}-offset-param`}
                    className="h-7 text-xs"
                    placeholder="offset"
                    value={pagination.offsetParam ?? 'offset'}
                    disabled={readOnly}
                    onChange={(e) => handlePaginationChange({ offsetParam: e.target.value })}
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor={`${baseId}-limit-param`} className="text-xs">Limit 파라미터</Label>
                  <Input
                    id={`${baseId}-limit-param`}
                    className="h-7 text-xs"
                    placeholder="limit"
                    value={pagination.limitParam ?? 'limit'}
                    disabled={readOnly}
                    onChange={(e) => handlePaginationChange({ limitParam: e.target.value })}
                  />
                </div>
              </div>
            </div>
          )}
        </div>
      </Section>

      <Separator />

      {/* Advanced Settings */}
      <Section
        title="고급 설정"
        icon={<Settings2 className="h-3.5 w-3.5" />}
        hasError={advancedHasIntegerDraft}
        revealSeq={integerDrafts?.revealSeq}
      >
        <div className="grid grid-cols-2 gap-1.5">
          <div className="space-y-1">
            <Label htmlFor={`${baseId}-max-retries`} className="text-xs">재시도 횟수</Label>
            <IntegerTextInput
              id={`${baseId}-max-retries`}
              className="h-7 text-xs"
              value={retry.maxRetries}
              disabled={readOnly}
              min={MAX_RETRIES_RANGE.min}
              max={MAX_RETRIES_RANGE.max}
              onValueChange={(n) => handleRetryChange({ maxRetries: n })}
              draftKey="maxRetries"
            />
            <p className="text-[11px] text-muted-foreground">{MAX_RETRIES_RANGE.min}~{MAX_RETRIES_RANGE.max}회</p>
          </div>
          <div className="space-y-1">
            <Label htmlFor={`${baseId}-timeout-ms`} className="text-xs">요청 타임아웃 (ms)</Label>
            <IntegerTextInput
              id={`${baseId}-timeout-ms`}
              className="h-7 text-xs"
              value={timeoutMs}
              disabled={readOnly}
              min={TIMEOUT_MS_RANGE.min}
              max={TIMEOUT_MS_RANGE.max}
              onValueChange={(n) => update('timeoutMs', n)}
              draftKey="timeoutMs"
            />
            <p className="text-[11px] text-muted-foreground">{TIMEOUT_MS_RANGE.min}~{TIMEOUT_MS_RANGE.max}ms</p>
          </div>
          <div className="space-y-1 col-span-2">
            <Label htmlFor={`${baseId}-max-duration-ms`} className="text-xs">최대 실행 시간 (ms)</Label>
            <IntegerTextInput
              id={`${baseId}-max-duration-ms`}
              className="h-7 text-xs"
              value={maxDurationMs}
              disabled={readOnly}
              min={MAX_DURATION_MS_RANGE.min}
              max={MAX_DURATION_MS_RANGE.max}
              onValueChange={(n) => update('maxDurationMs', n)}
              draftKey="maxDurationMs"
            />
            <p className="text-[11px] text-muted-foreground">{MAX_DURATION_MS_RANGE.min}~{MAX_DURATION_MS_RANGE.max}ms</p>
          </div>
        </div>
      </Section>

      <Separator />

      {/* Preview */}
      <div className="pb-2">
        <Button
          className="w-full"
          variant="secondary"
          disabled={readOnly || previewLoading}
          onClick={handlePreview}
        >
          <Play className="h-4 w-4" />
          {previewLoading ? '호출 중...' : '테스트 호출'}
        </Button>

        {previewResult && <ApiCallPreview result={previewResult} />}
      </div>
    </div>
  );
}
