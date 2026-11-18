import { ArrowLeft, Copy, HelpCircle } from 'lucide-react';
import { useEffect, useEffectEvent, useId, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { StatusBadge } from '@/components/api-connection/StatusBadge';
import { DetailPageTitle } from '@/components/PageTitleSync';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { FormField } from '@/components/ui/form-field';
import {
  type ExtraHeaderRow,
  extraHeaderRowErrors,
  extraHeaderRowsFromConfig,
  extraHeadersToConfig,
  focusFirstExtraHeaderError,
  withoutChangedRowErrors,
  withoutExtraHeaders,
} from '@/lib/api-connection-extra-headers';
import {
  AUTH_FIELD_KEYS,
  AUTH_TYPE_LABEL,
  AUTH_TYPE_OPTIONS,
  authNameError,
  authTypeBadgeVariant,
  authTypeLabel,
  baseUrlError,
  basicUsernameError,
  connectionNameError,
  type FieldErrors,
  focusFirstError,
  withoutKeys,
} from '@/lib/api-connection-form';
import { missingSecretMessage } from '@/lib/api-connection-secret';
import { handleApiError } from '@/lib/api-error';
import { formatDate } from '@/lib/formatters';
import { parseRouteId } from '@/lib/route-id';
import { MAX_DESCRIPTION_LENGTH } from '@/lib/validations/description';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '../../components/ui/tooltip';
import {
  useApiConnection,
  useDeleteApiConnection,
  useTestApiConnection,
  useUpdateApiConnection,
} from '../../hooks/queries/useApiConnections';
import { useMyPermissions } from '../../hooks/queries/useMyPermissions';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import type {
  ApiConnectionAuthType,
  ApiConnectionResponse,
  StoredApiConnectionAuthType,
  TestConnectionResponse,
} from '../../types/api-connection';
import type { UpdateApiConnectionRequest } from '../../types/api-connection';
import { BasicAuthFields } from './components/BasicAuthFields';
import { ExtraHeadersFields } from './components/ExtraHeadersFields';

/** 기본 정보 카드에서 인라인 오류를 보일 수 있는 칸 (WD-561) — 선언 순서가 화면 순서(첫 오류 포커스 기준)다. */
const INFO_FIELD_ORDER = ['name', 'baseUrl'] as const;
type InfoFieldKey = (typeof INFO_FIELD_ORDER)[number];
/** 인증 편집 칸 (WD-561) — 유형·위치에 따라 보이는 칸만 검사한다. 순서·key 는 생성 대화상자와 같은 공용 상수다(WD-666). */
type AuthFieldKey = (typeof AUTH_FIELD_KEYS)[number];

/**
 * 인증 칸 기준 스냅샷(WD-763 리뷰·WD-777) — 인증 편집의 기준이다. 늘 마지막으로 받은 서버 응답으로 만든다.
 * 편집 중 「고쳤는지」(미저장 경고·재조회 때 칸별 병합)와 비밀 유지 판단(가린 키·비밀번호)을 이 값으로 본다.
 * 재조회 때마다 새 응답으로 바꿔(칸별 병합과 함께) 옛 응답의 가린 키를 보내지 않는다(WD-777 결함 2).
 */
interface AuthSeed {
  authType: StoredApiConnectionAuthType;
  placement: string;
  headerName: string;
  paramName: string;
  username: string;
  /** 기준 추가 헤더 행 — 고쳤는지는 행 내용(행 수·행별 이름·값)으로 비교한다(rowsEqual·EXTRA_ROW_CONTENT_KEYS, WD-777) */
  extraHeaderRows: ExtraHeaderRow[];
  /** 키 칸을 비워 두면 유지할 저장 키(가린 값) — API_KEY 로 저장됐고 가린 키가 있을 때만(WD-480·WD-666) */
  maskedKey?: string;
  /** 비밀번호 칸을 비워 두면 유지할 저장 비밀번호(가린 값) — BASIC 으로 저장됐고 가린 비밀번호가 있을 때만(WD-681) */
  maskedPassword?: string;
}

/** 인증 기준 스냅샷과 그것을 만든 연결 응답(WD-777) — 편집 중 새 응답이 왔는지(참조가 from 과 다른지) 알려고 함께 둔다. */
interface AuthSnapshot {
  seed: AuthSeed;
  from: ApiConnectionResponse;
}

/**
 * 조회한 연결로 인증 칸 스냅샷을 만든다(WD-763 리뷰).
 * 메인 인증 칸은 추가 헤더 키(extraHeader:…)를 뺀 값으로 채운다(WD-753) — 추가 헤더는 행(extraHeaderRows)으로 따로 둔다.
 * 추가 헤더 행은 인자 응답에서 바로 만든다(WD-777) — 다른 응답에서 만든 행이 섞이지 않게 한다.
 */
function authSeedFrom(connection: ApiConnectionResponse): AuthSeed {
  const mc = withoutExtraHeaders(connection.maskedAuthConfig);
  return {
    authType: connection.authType,
    placement: mc.placement ?? 'header',
    headerName: mc.headerName ?? '',
    paramName: mc.paramName ?? '',
    // (WD-681) 아이디는 비밀이 아니라 가리지 않고 내려온다 — 그대로 채운다
    username: mc.username ?? '',
    extraHeaderRows: extraHeaderRowsFromConfig(connection.maskedAuthConfig),
    maskedKey: connection.authType === 'API_KEY' ? mc.apiKey || undefined : undefined,
    maskedPassword: connection.authType === 'BASIC' ? mc.password || undefined : undefined,
  };
}

/**
 * 재조회 병합의 칸 하나(WD-777) — 3-way: 기준이 없거나(첫 응답) 지금 값이 이전 기준과 같으면(고치지 않음) 새 값으로 바꾼다.
 * 값이 실제로 달라질 때만 set 하고 true 를 돌려줘, 부르는 쪽이 그 칸 오류만 지울 수 있게 한다(오류 key 가 없는 칸은 반환값을 무시한다).
 */
function mergeField<T>(cur: T, prev: T | undefined, next: T, set: (value: T) => void): boolean {
  if (prev !== undefined && cur !== prev) return false;
  if (cur === next) return false;
  set(next);
  return true;
}

/** 추가 헤더 행 「내용」 비교 키 — 고쳤는지(미저장 경고·재조회 때 덩어리를 지킬지)를 본다(WD-777). */
const EXTRA_ROW_CONTENT_KEYS = ['name', 'value'] as const;
/**
 * 추가 헤더 행 「저장값까지」 비교 키(WD-777) — 고치지 않은 행을 새 행으로 바꿀지 볼 때 쓴다.
 * 이름·값이 같아도 서버의 가린 값이 바뀌었으면 새 행으로 바꿔야 저장 때 새 가린 값을 보낸다.
 */
const EXTRA_ROW_STORED_KEYS = ['name', 'value', 'storedMasked', 'storedName'] as const;

/**
 * 추가 헤더 행 목록이 같은지(WD-777) — 행 수와 행별 keys 값이 같으면 같다(화면 안 id 는 무시, 빈 행도 한 행으로 센다).
 * 내용 키로 「건드렸는지」와 「고쳤는지」를 한 번에 본다 — 빈 행을 더하면 고친 것(미저장 경고), 더했다 지우면 처음과 같아 고치지 않은 것이다.
 * 예전엔 배열 참조로 건드림을 봐서, 빈 행을 더했다 지워도 계속 「건드림」이라 재조회 값을 받지 못하고 덮어썼다(WD-777 결함 3).
 */
function rowsEqual(
  a: ExtraHeaderRow[],
  b: ExtraHeaderRow[],
  keys: readonly (keyof ExtraHeaderRow)[],
): boolean {
  return a.length === b.length && a.every((row, i) => keys.every((key) => row[key] === b[i][key]));
}

/** 고친 추가 헤더 덩어리를 새 기준에 맞춘 결과(WD-788) — rows 는 바꿀 것이 없으면 입력과 같은 배열 참조다. */
interface SyncedStoredRows {
  rows: ExtraHeaderRow[];
  /** 새 기준에 없어(다른 곳에서 지워져) 빼거나 새 행으로 바꾼 저장 행의 원래 이름 — 토스트 안내용 */
  removedNames: string[];
}

/**
 * 고친 추가 헤더 덩어리 안의 저장 행(storedName 있음)을 새 기준에 맞춘다(WD-777 리뷰·WD-788).
 * 손대지 않은 저장 행 = 이전 기준에 원래 이름·이름·값이 같은 행이 있는 행이다.
 * - 새 기준에 원래 이름이 같은 행이 있으면: 값을 비워 둔 행(손대지 않은 행·이름 공백만 바꾼 행)은 가린 값을 새 값으로 바꾼다(옛 가린 값을 보내 서버가 400 으로 거절하지 않게).
 * - 새 기준에 그 이름이 없으면(다른 곳에서 지워짐, WD-788):
 *   - 손대지 않은 행은 목록에서 뺀다 — 지워진 헤더를 다시 보내지 않게 한다.
 *   - 손댄 행은 storedName·storedMasked 를 지워 새 행으로 바꾼다 — 사용자가 쓴 이름·값은 지키고, 지워진 헤더의 가린 값은 절대 보내지 않는다
 *     (예전엔 이름 앞뒤 공백만 바꾼 행이 값을 비워도 옛 가린 값을 보내 서버가 거절했다). 값이 비면 저장 때 행 검증이 「값 필수」로 잡는다.
 *   행 id 는 그대로라 남은 행의 오류는 둔다(뺀 행의 오류는 부르는 쪽이 지운다).
 */
function syncStoredRows(
  rows: ExtraHeaderRow[],
  prevRows: ExtraHeaderRow[],
  nextRows: ExtraHeaderRow[],
): SyncedStoredRows {
  let changed = false;
  const removedNames: string[] = [];
  const synced: ExtraHeaderRow[] = [];
  for (const row of rows) {
    if (row.storedName === undefined) {
      synced.push(row);
      continue;
    }
    const fresh = nextRows.find((n) => n.storedName === row.storedName);
    if (!fresh) {
      // 서버에서 지워진 헤더(WD-788) — 손대지 않았으면 빼고, 손댔으면 저장값 없는 새 행으로 바꾼다
      const untouched = prevRows.some(
        (p) => p.storedName === row.storedName && p.name === row.name && p.value === row.value,
      );
      changed = true;
      removedNames.push(row.storedName);
      if (!untouched) synced.push({ id: row.id, name: row.name, value: row.value });
      continue;
    }
    // 값을 비워 둔 행은 저장 때 가린 값을 그대로 보낼 수 있다(이름 앞뒤 공백만 바꾼 행 포함 — keptStoredMasked) — 그래서 손댔는지와
    // 상관없이 값이 비었으면 가린 값을 새 기준으로 맞춘다(WD-788 코드 리뷰). 값을 쓴 행은 그 값을 보내므로 가린 값과 무관하다.
    if (row.value === '' && fresh.storedMasked !== row.storedMasked) {
      changed = true;
      synced.push({ ...row, storedMasked: fresh.storedMasked });
      continue;
    }
    synced.push(row);
  }
  return { rows: changed ? synced : rows, removedNames };
}

/**
 * API 연결 상세 페이지 — 주소 id 를 읽어 본문을 그 id 로 key 해 그린다(WD-763 리뷰, UserDetailPage·ReportTemplateDetailPage 와 같은 방식).
 * 라우트 요소는 :id 로 key 되지 않아, 앱 안 링크로 다른 연결로 옮기면 같은 컴포넌트가 남은 채 id 만 바뀐다.
 * 그러면 인증 편집 상태·입력한 키·토큰·비밀번호·최근 테스트 결과가 새 연결 화면에 남는다 — key 로 다시 만들어 모든 상태를 새로 시작한다.
 */
export default function ApiConnectionDetailPage() {
  const { id } = useParams<{ id: string }>();
  // 숫자가 아닌 id(WD-434)는 null — 본문이 조회 없이 「불러오기 실패」로 안내한다(:id 라우트라 id 가 없는 경우는 없다)
  return <ApiConnectionDetailView key={id} connectionId={parseRouteId(id) ?? null} />;
}

/** API 연결 상세 본문 — 기본 정보/인증/연결 상태 확인/삭제. connectionId 가 null 이면 잘못된 주소 id 다. */
function ApiConnectionDetailView({ connectionId }: { connectionId: number | null }) {
  const navigate = useNavigate();
  // 숫자가 아닌 id(WD-434)는 조회가 꺼져 로딩·오류·데이터가 모두 없는 빈 화면이 됐다 — 없는 id 와 같게 안내한다
  const isInvalidId = connectionId == null;
  const { data: connection, isLoading, isError } = useApiConnection(connectionId);
  const updateMutation = useUpdateApiConnection();
  const deleteMutation = useDeleteApiConnection();
  const testMutation = useTestApiConnection();
  // 지금 확인(POST /{id}/test)·기본 정보 저장·인증 정보 변경 = apiconnection:write, 삭제 = apiconnection:delete (WD-121).
  // 권한이 없으면 버튼은 숨기고 입력은 잠근다 — 입력이 열려 있으면 고친 값이 저장할 수 없는 「미저장 변경」 경고로 남는다.
  // 라우트 가드가 권한 조회를 끝낸 뒤에만 이 화면을 그린다. 서버에 관리자 우회가 없으므로 권한 코드만 본다.
  const { permissions } = useMyPermissions();
  const canWrite = permissions.has('apiconnection:write');
  const canDelete = permissions.has('apiconnection:delete');
  // 마지막 "지금 확인" 응답을 저장 — 상태코드/응답 본문/헤더/요청 URL 노출 (#76)
  const [lastTestResult, setLastTestResult] = useState<TestConnectionResponse | null>(null);

  // 접근성: 라벨↔입력 연결용 id 접두사 (#432).
  // 하드코딩 대신 useId() 를 써서 같은 폼이 여러 번 렌더돼도 id 충돌이 없게 한다.
  // 조기 return(로딩/미존재) 이 아래에 있으므로 반드시 훅 구간 최상단에서 호출한다.
  const baseId = useId();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  // 신규 필드: baseUrl, healthCheckPath
  const [baseUrl, setBaseUrl] = useState('');
  const [healthCheckPath, setHealthCheckPath] = useState('');

  // Auth edit state
  const [isEditingAuth, setIsEditingAuth] = useState(false);
  // 조회한 방식(옛 OAUTH2 포함)을 그대로 들고 시작하므로 조회 값 범위로 둔다
  const [authType, setAuthType] = useState<StoredApiConnectionAuthType>('API_KEY');
  const [placement, setPlacement] = useState('header');
  const [headerName, setHeaderName] = useState('');
  const [paramName, setParamName] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [token, setToken] = useState('');
  // (WD-681) Basic 인증 — 아이디는 조회값(가리지 않음)으로 채우고, 비밀번호는 다른 비밀처럼 비워 둔 채 시작한다
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  // (WD-753) 추가 인증 헤더 행 — 저장된 헤더는 이름과 가린 값(storedMasked)으로 시작하고 값 칸은 비운다
  const [extraHeaders, setExtraHeaders] = useState<ExtraHeaderRow[]>([]);
  const [extraHeaderErrors, setExtraHeaderErrors] = useState<Record<string, string>>({});

  // (WD-561) 칸별 검증 오류 — 예전엔 토스트로만 알려 어느 칸이 틀렸는지 알기 어려웠다.
  // 기본 정보와 인증은 저장 버튼이 따로라 오류도 따로 둔다(한쪽 저장이 다른 쪽 오류를 건드리지 않게).
  const [infoErrors, setInfoErrors] = useState<FieldErrors<InfoFieldKey>>({});
  const [authErrors, setAuthErrors] = useState<FieldErrors<AuthFieldKey>>({});

  /** 칸 key → 입력 id. 첫 오류 칸 포커스용(FormField htmlFor 와 같은 값). */
  const infoFieldId: Record<InfoFieldKey, string> = {
    name: `${baseId}-name`,
    baseUrl: `${baseId}-base-url`,
  };
  const authFieldId: Record<AuthFieldKey, string> = {
    authName: `${baseId}-auth-name`,
    apiKey: `${baseId}-api-key`,
    token: `${baseId}-token`,
    username: `${baseId}-username`,
    password: `${baseId}-password`,
  };

  /**
   * 인증 기준 스냅샷과 그 응답(WD-763 리뷰·WD-777) — 편집을 열 때(applyAuthSeed)와 편집 중 재조회 때(mergeAuthFields) 새 응답으로 바꾼다.
   * 렌더 중 비교(고쳤는지·비밀 유지 판단)에 쓰므로 ref 가 아닌 state 로 둔다(react-hooks/refs 룰, 기본 정보의 initialInfo 와 같다).
   * 기준과 그 응답을 한 state 로 묶어, 예전 authSeededFromRef(응답)와 authSeed(기준)가 따로 어긋날 틈을 없앤다.
   */
  const [authSnapshot, setAuthSnapshot] = useState<AuthSnapshot | null>(null);
  const authSeed = authSnapshot?.seed ?? null;

  // 키·비밀번호 칸을 비워 두면 유지할 저장 가린 값 — 필수 표시·유지 안내·저장 판단이 이 값 하나를 본다(WD-480·WD-666·WD-681, 칸을 채운 스냅샷 기준)
  const storedMaskedKey = authSeed?.maskedKey;
  const apiKeyHintId = `${baseId}-api-key-keep-hint`;
  const storedMaskedPassword = authSeed?.maskedPassword;
  const passwordHintId = `${baseId}-password-keep-hint`;

  /**
   * 저장된 추가 헤더 행(WD-753 리뷰) — 조회한 가린 config 가 바뀔 때만 한 번 만든다(읽기 보기용).
   * 예전엔 렌더마다 다시 만들어 행 id 순번을 계속 올렸다(렌더 중 부수효과). 인증 칸 스냅샷은 응답에서 따로 만든다(authSeedFrom, WD-777).
   */
  const storedExtraHeaderRows = useMemo(
    () => extraHeaderRowsFromConfig(connection?.maskedAuthConfig),
    [connection?.maskedAuthConfig],
  );

  /**
   * 인증 칸(인증 방식·키 위치·헤더/파라미터 이름·Basic 아이디·추가 헤더 행)과 그 스냅샷을 연결 응답으로 모두 채우고 인라인 오류를 비운다
   * (WD-753 리뷰·WD-763). 편집을 열 때(startAuthEdit) 쓴다 — 편집 중 재조회는 칸별 병합(mergeAuthFields)을 쓰고, 닫힌 동안은 읽기 보기가 connection 을 바로 그려 채우지 않는다.
   */
  const applyAuthSeed = (conn: ApiConnectionResponse) => {
    const seed = authSeedFrom(conn);
    setAuthSnapshot({ seed, from: conn });
    fillMainAuthFields(seed);
    setExtraHeaders(seed.extraHeaderRows);
    // 인증 칸을 새 값으로 다시 채우면 이전 값에 대한 인라인 오류도 비운다(WD-666) — 지난 오류 문구가 남지 않게 한다.
    setAuthErrors({});
    setExtraHeaderErrors({});
  };

  /**
   * 메인 인증 칸(방식·키 위치·헤더/파라미터 이름·Basic 아이디)을 스냅샷 값으로 채운다 — 편집을 열 때와, 재조회로 인증 방식이 바뀔 때(WD-788)
   * 함께 쓴다. 칸 목록을 한 곳에 둬 새 인증 칸을 더할 때 한쪽만 빠지지 않게 한다. 추가 헤더·비밀 칸은 부르는 쪽이 정한다.
   */
  function fillMainAuthFields(seed: AuthSeed) {
    setAuthType(seed.authType);
    setPlacement(seed.placement);
    setHeaderName(seed.headerName);
    setParamName(seed.paramName);
    setUsername(seed.username);
  }

  /** 입력한 비밀(키·토큰·비밀번호)을 비운다 — 편집을 닫을 때와 재조회로 인증 방식이 바뀔 때(WD-788) 쓴다. */
  function clearSecretInputs() {
    setApiKey('');
    setToken('');
    setPassword('');
  }

  /** 인증 편집을 연다 — 지금 연결 값으로 칸을 채운다(WD-763 리뷰 — 취소·저장으로 닫았다 다시 열어도 저장값으로 시작한다). */
  const startAuthEdit = () => {
    if (!connection) return;
    applyAuthSeed(connection);
    setIsEditingAuth(true);
  };

  /**
   * 인증 편집을 닫는다 — 입력한 키·토큰·비밀번호를 비운다(남은 비밀이 다음 편집에 실리지 않게).
   * 다른 칸과 오류는 다음에 열 때(startAuthEdit) 저장값으로 다시 채운다.
   */
  const closeAuthEdit = () => {
    setIsEditingAuth(false);
    clearSecretInputs();
  };

  /** 추가 헤더 행을 고친다(WD-753) — 고치거나 지운 행의 오류만 지운다. */
  const handleExtraHeadersChange = (next: ExtraHeaderRow[]) => {
    setExtraHeaderErrors((prev) => withoutChangedRowErrors(prev, extraHeaders, next));
    setExtraHeaders(next);
  };

  // 서버에서 로드된 기본 정보 초기값 스냅샷 — 이탈 가드용 dirty 비교 기준 (#551)
  // render 중 비교에 사용하므로 ref가 아닌 state로 관리한다 (react-hooks/refs 룰).
  const [initialInfo, setInitialInfo] = useState<{
    name: string;
    description: string;
    baseUrl: string;
    healthCheckPath: string;
  } | null>(null);

  /** 404 등 오류 응답 시 목록으로 리다이렉트 — UserDetailPage와 동일 패턴 */
  useEffect(() => {
    if (!isError && !isInvalidId) return;
    // 숫자가 아닌 id 는 마운트 즉시 알린다 — 개발 모드 StrictMode 의 이중 실행에도 토스트가 하나만 남게 id 를 준다
    toast.error('연결 정보를 불러오는 데 실패했습니다.', { id: 'api-connection-not-found' });
    navigate('/admin/api-connections');
  }, [isError, isInvalidId, navigate]);

  /**
   * 연결 응답을 기본 정보 칸에 칸별로 병합한다(WD-777) — 효과 안에서 부르는 effect event 라 늘 지금 칸 값·기준(initialInfo)을 읽는다.
   * - 처음 받은 응답(기준 없음)이면 모두 채운다.
   * - 재조회면 칸마다: 지금 값 == 이전 기준 값(고치지 않음)이면 새 값으로 바꾸고, 다르면(고침) 지금 값을 둔다.
   *   예전엔 재조회마다 모두 서버 값으로 덮어, 저장 안 한 설명이 인증 저장 뒤 사라졌다(WD-777 결함 1).
   * - 기준은 새 응답 값으로 바꾼다 — 고친 칸은 여전히 「고침」이라 미저장 경고가 남는다(#551).
   * - 새 값으로 바꾼 칸의 인라인 오류만 지운다(WD-666·WD-777 결함 5) — 지킨 칸은 여전히 틀릴 수 있어 오류를 둔다.
   */
  const mergeInfoFields = useEffectEvent((conn: ApiConnectionResponse) => {
    const next = {
      name: conn.name,
      description: conn.description ?? '',
      baseUrl: conn.baseUrl,
      healthCheckPath: conn.healthCheckPath ?? '',
    };
    // 기준이 없으면(첫 응답) 모든 칸을 채운다 — mergeField 가 prev 없음을 「새 값으로 채움」으로 본다
    const prev = initialInfo ?? undefined;
    // 새 값으로 바꾼(값이 실제로 달라진) 칸 — 그 칸 오류만 지운다
    const replaced: InfoFieldKey[] = [];
    if (mergeField(name, prev?.name, next.name, setName)) replaced.push('name');
    mergeField(description, prev?.description, next.description, setDescription);
    if (mergeField(baseUrl, prev?.baseUrl, next.baseUrl, setBaseUrl)) replaced.push('baseUrl');
    mergeField(healthCheckPath, prev?.healthCheckPath, next.healthCheckPath, setHealthCheckPath);
    if (replaced.length > 0) setInfoErrors((errs) => withoutKeys(errs, ...replaced));
    // dirty 비교 기준 스냅샷 갱신 (#551) — 저장 없이 사이드바 이동 시 유실 경고용
    setInitialInfo(next);
  });

  /**
   * 연결 응답이 새로 오면(첫 조회·재조회) 기본 정보를 칸별로 병합한다(WD-777). connection 참조는 TanStack Query 가 같은 응답이면 공유하므로
   * 실제로는 첫 조회·재조회 때만 실행된다. 인증 칸은 여기서 채우지 않는다(WD-763) — 편집을 열 때와 편집 중 재조회 때(아래 효과)만 채운다.
   */
  useEffect(() => {
    if (!connection) return;
    mergeInfoFields(connection);
  }, [connection]);

  // 기본 정보 폼이 로드된 초기값과 달라졌는지 여부 (#551)
  const isInfoDirty =
    initialInfo !== null &&
    (name !== initialInfo.name ||
      description !== initialInfo.description ||
      baseUrl !== initialInfo.baseUrl ||
      healthCheckPath !== initialInfo.healthCheckPath);
  /**
   * 방식에 딸린 메인 인증 칸(위치·헤더/파라미터 이름·Basic 아이디)이 기준과 다르거나 새 비밀을 입력했는지(WD-788).
   * 미저장 경고(isAuthDirty)와 재조회로 방식이 바뀔 때의 안내(mergeAuthFields)가 같은 칸 목록으로 보게 한 곳에 둔다.
   */
  function mainAuthEdited(seed: AuthSeed): boolean {
    return (
      placement !== seed.placement ||
      headerName !== seed.headerName ||
      paramName !== seed.paramName ||
      username !== seed.username ||
      apiKey.trim() !== '' ||
      token.trim() !== '' ||
      password !== ''
    );
  }

  /** 편집 중일 때의 스냅샷 — 닫혀 있으면 null 이라 아래 판정이 모두 거짓이 된다 */
  const editingSeed = isEditingAuth ? authSeed : null;
  /**
   * 인증 편집에서 고친 것이 있는지 — 미저장 경고(#551) 판단에 쓴다. 기준 스냅샷(늘 마지막 응답)과 비교한다(WD-763 리뷰·WD-777).
   * - 메인 인증 칸(방식·위치·헤더/파라미터 이름)이나 Basic 아이디가 기준과 다르다.
   * - 새 키·토큰·비밀번호를 입력했다(#551·WD-681).
   * - 추가 헤더 행 내용(행 수·이름·값, 빈 행 포함)이 기준과 다르다(WD-753·WD-777).
   */
  const isAuthDirty =
    editingSeed !== null &&
    (authType !== editingSeed.authType ||
      mainAuthEdited(editingSeed) ||
      !rowsEqual(extraHeaders, editingSeed.extraHeaderRows, EXTRA_ROW_CONTENT_KEYS));

  /**
   * 편집 중 새 연결 응답을 인증 칸에 칸별로 병합한다(WD-777) — 효과 안에서 부르는 effect event 라 늘 지금 칸 값·스냅샷을 읽는다.
   * 같은 응답(스냅샷의 from 과 같은 참조)이면 아무것도 하지 않는다 — 편집을 연 직후(startAuthEdit 가 이미 채움)도 여기에 걸린다.
   * - 인증 방식·키 위치·헤더 이름·파라미터 이름·Basic 아이디: 칸마다 지금 값 == 이전 기준 값이면 새 값, 다르면(고침) 지금 값을 둔다.
   * - 추가 헤더 행: 한 덩어리로 — 지금 행 내용이 이전 기준과 같으면 새 행으로 바꾼다(행에 서버 쪽 안정 id 가 없어 행끼리 짝지을 수 없다).
   * - 비밀 칸(키·토큰·비밀번호)은 채우지 않는다 — 입력한 비밀은 서버 값과 비교할 수 없다. 비워 두면 유지할 가린 값은 새 기준의 값이다(결함 2).
   * - 기준은 새 응답으로 바꾼다 — 고친 칸은 여전히 「고침」이라 미저장 경고가 남는다.
   * - 새 값으로 바꾼 칸의 오류만 지운다(결함 5): 헤더/파라미터 이름·키 위치 → authName, 아이디 → username, 추가 헤더를 바꿨을 때만 행 오류 전체.
   * - 사용자는 방식을 고치지 않았는데 재조회로 방식이 바뀌면(WD-788), 방식에 딸린 칸은 칸별 병합하지 않고 모두 새 기준 값으로 바꾼다(아래 주석).
   */
  const mergeAuthFields = useEffectEvent((conn: ApiConnectionResponse) => {
    if (!authSnapshot || authSnapshot.from === conn) return;
    const prev = authSnapshot.seed;
    const next = authSeedFrom(conn);
    // 새 값으로 바꾼(값이 실제로 달라진) 칸의 오류 key
    const cleared: AuthFieldKey[] = [];
    if (authType === prev.authType && next.authType !== prev.authType) {
      // (WD-788) 다른 곳에서 인증 방식이 바뀌었다 — 옛 방식에서 고친 칸(헤더 이름 등)을 지키면 새 방식 화면에서 숨은 채 남아,
      // 저장할 수 없는 미저장 경고가 계속 뜨고 옛 방식의 키를 새 방식에 보낼 수 있다. 그래서 딸린 칸(위치·헤더/파라미터 이름·아이디)은
      // 새 기준 값으로, 입력한 비밀(키·토큰·비밀번호)은 비운다. 추가 헤더는 방식과 무관해 아래 규칙 그대로 둔다.
      const editedDependent = mainAuthEdited(prev);
      fillMainAuthFields(next);
      clearSecretInputs();
      // 칸이 모두 새 방식 값으로 바뀌어 지난 인증 칸 오류는 어느 칸에도 맞지 않는다
      cleared.push(...AUTH_FIELD_KEYS);
      // 고친 것을 말없이 버리지 않게 알린다 — StrictMode 이중 실행에도 하나만 남게 id 를 준다
      if (editedDependent) {
        toast.info(
          `다른 곳에서 인증 방식이 ${authTypeLabel(next.authType, 'full')}으로 바뀌어, 고친 인증 칸을 새 값으로 바꿨습니다.`,
          { id: 'api-connection-auth-type-changed' },
        );
      }
    } else {
      mergeField(authType, prev.authType, next.authType, setAuthType);
      // 위치가 바뀌면 이름 칸이 헤더 이름 ↔ 파라미터 이름으로 바뀌어, 지난 이름 칸 오류가 다른 칸에 붙지 않게 지운다
      if (mergeField(placement, prev.placement, next.placement, setPlacement)) cleared.push('authName');
      if (mergeField(headerName, prev.headerName, next.headerName, setHeaderName)) cleared.push('authName');
      if (mergeField(paramName, prev.paramName, next.paramName, setParamName)) cleared.push('authName');
      if (mergeField(username, prev.username, next.username, setUsername)) cleared.push('username');
    }
    // 추가 헤더는 덩어리로 — 고치지 않았고 새 행이 (가린 값까지) 다를 때만 바꾸고 행 오류를 비운다(행 id 가 바뀌어 지난 오류가 남지 않게)
    if (rowsEqual(extraHeaders, prev.extraHeaderRows, EXTRA_ROW_CONTENT_KEYS)) {
      if (!rowsEqual(extraHeaders, next.extraHeaderRows, EXTRA_ROW_STORED_KEYS)) {
        setExtraHeaders(next.extraHeaderRows);
        setExtraHeaderErrors({});
      }
    } else {
      // 고친 덩어리는 지키되, 그 안의 손대지 않은 저장 행(이전 기준 행과 이름·값이 같은 행)의 가린 값은 새 기준의 같은 이름 행 값으로 맞춘다
      // (WD-777 리뷰) — 메인 키처럼 「기준은 늘 최신」이라, 옛 응답의 가린 값을 보내 서버가 400 으로 거절하는 일을 막는다.
      // 값을 쓴 행은 그 값을 보내므로 가린 값을 건드리지 않는다(값을 비운 행은 손댔어도 맞춘다 — syncStoredRows). 새 기준에 그 이름이 없으면(다른 곳에서 지워짐) 손대지 않은 행은 빼고
      // 손댄 행은 새 행으로 바꾼다(WD-788). 남은 행은 id 가 그대로라 행 오류도 두고, 뺀 행의 오류만 지운다.
      const synced = syncStoredRows(extraHeaders, prev.extraHeaderRows, next.extraHeaderRows);
      if (synced.rows !== extraHeaders) {
        setExtraHeaderErrors((errs) => withoutChangedRowErrors(errs, extraHeaders, synced.rows));
        setExtraHeaders(synced.rows);
      }
      if (synced.removedNames.length > 0) {
        toast.info(`다른 곳에서 추가 헤더(${synced.removedNames.join(', ')})가 지워져 목록을 맞췄습니다.`, {
          id: 'api-connection-extra-headers-removed',
        });
      }
    }
    if (cleared.length > 0) setAuthErrors((errs) => withoutKeys(errs, ...cleared));
    setAuthSnapshot({ seed: next, from: conn });
  });

  /**
   * 편집 중 연결이 다시 불러와지면 인증 칸을 칸별로 병합한다(WD-763·WD-777).
   * 연결은 기본 정보 저장·인증 저장 뒤 무효화(invalidateQueries — 저장 응답이 끝나도 재조회는 기다리지 않는다)와, 마운트 때 staleTime(30초)이
   * 지난 캐시로 그린 뒤의 배경 재조회로 다시 불러와진다(창 포커스 재조회는 main.tsx 에서 꺼 두었다).
   * - 닫혀 있으면 채우지 않는다 — 읽기 보기는 connection 을 바로 그리고, 열 때(startAuthEdit) 그때의 연결로 채운다.
   * - 의존성은 연결 응답과 편집 여부뿐이다 — 타이핑(고친 칸을 기준 값으로 되돌림 등)으로 다시 채우지 않는다(WD-777 결함 4).
   * 다른 연결로 옮기면 페이지가 그 id 로 다시 마운트돼(바깥 ApiConnectionDetailPage 의 key) 편집이 닫히고 입력한 비밀도 사라진다.
   */
  useEffect(() => {
    if (!connection || !isEditingAuth) return;
    mergeAuthFields(connection);
  }, [connection, isEditingAuth]);

  const { dialog: unsavedChangesDialog, requestNavigate } = useUnsavedChangesGuard(
    isInfoDirty || isAuthDirty,
  );

  /**
   * 기본 정보(이름/설명/baseUrl/healthCheckPath) 저장.
   * (WD-561) 이름·Base URL 을 한꺼번에 검사해 칸마다 인라인 오류를 달고, 첫 오류 칸으로 포커스를 옮긴다.
   */
  const handleSaveInfo = async () => {
    // 숫자만으로 된 주소 id(connectionId)로만 저장한다(WD-628) — 화면도 그 id 로 조회했다
    if (connectionId == null) return;
    const nextErrors: FieldErrors<InfoFieldKey> = {
      name: connectionNameError(name),
      // Base URL은 API 연결의 필수 값 — 빈 값으로 저장하면 백엔드가 null 처리해 무음으로 무시하므로 클라이언트에서 차단 (#124)
      // 편집 화면은 예전처럼 빈 값만 막는다(형식 검사는 서버 검증에 맡긴다).
      baseUrl: baseUrlError(baseUrl, false),
    };
    setInfoErrors(nextErrors);
    if (focusFirstError(INFO_FIELD_ORDER, nextErrors, infoFieldId)) return;
    try {
      await updateMutation.mutateAsync({
        id: connectionId,
        data: {
          name: name.trim(),
          description: description.trim() || undefined,
          baseUrl: baseUrl.trim(),
          // 빈 문자열도 그대로 전달해야 backend 가 명시적 clear 로 인식한다 (#115)
          healthCheckPath: healthCheckPath.trim(),
        },
      });
      toast.success('연결 정보가 업데이트되었습니다.');
      // 저장 성공 시 dirty 기준 스냅샷을 현재 입력값으로 갱신 (#551)
      // trim() 전 원본 상태값을 그대로 기준으로 삼아야 이후 dirty 비교가 실제 입력창 값과 어긋나지 않는다.
      setInitialInfo({ name, description, baseUrl, healthCheckPath });
    } catch (error) {
      handleApiError(error, '연결 정보 업데이트에 실패했습니다.');
    }
  };

  /**
   * 인증 설정 저장.
   * (WD-561) 지금 보이는 인증 칸을 한꺼번에 검사해 칸마다 인라인 오류를 달고, 첫 오류 칸으로 포커스를 옮긴다.
   */
  const handleSaveAuth = async () => {
    if (connectionId == null) return;

    const nextErrors: FieldErrors<AuthFieldKey> = {};
    const authConfig: Record<string, string> = { authType };
    if (authType === 'API_KEY') {
      authConfig.placement = placement;
      // 키 칸을 비워 두면 「저장된 키 유지」(WD-480): 조회로 받은 가린 값(****XXXX)을 그대로 보내면
      // 서버(resolveMaskedSecrets, WD-179)가 저장 원문을 유지한다. 유지 가능 여부는 필수 표시·안내 문구와 같은
      // storedMaskedKey 하나로 정한다(WD-666) — 인증 방식을 바꾸거나 저장된 키가 없으면 새 키를 반드시 받아야 한다.
      // 빈 키 판정은 생성·저장 전 테스트·서버와 같은 공용 헬퍼로 한다(WD-639).
      const missingKey = missingSecretMessage('API_KEY', apiKey);
      const keyValue = missingKey ? storedMaskedKey : apiKey;
      nextErrors.authName = authNameError(placement, placement === 'header' ? headerName : paramName);
      // 유지할 저장 키도 없을 때만 키 칸 오류다
      if (!keyValue && missingKey) nextErrors.apiKey = missingKey;
      if (placement === 'header') authConfig.headerName = headerName;
      else authConfig.paramName = paramName;
      if (keyValue) authConfig.apiKey = keyValue;
    } else if (authType === 'BASIC') {
      // (WD-681) 비밀번호 칸을 비워 두면 키 칸과 같이 조회로 받은 가린 값을 그대로 보내 저장된 비밀번호를 유지한다.
      // 유지할 비밀번호가 없을 때(다른 방식에서 바꾸는 중 등)만 비밀번호 칸 오류다. 아이디는 서버처럼 그대로 보낸다.
      const missingPassword = missingSecretMessage('BASIC', password);
      const passwordValue = missingPassword ? storedMaskedPassword : password;
      nextErrors.username = basicUsernameError(username);
      if (!passwordValue && missingPassword) nextErrors.password = missingPassword;
      authConfig.username = username;
      if (passwordValue) authConfig.password = passwordValue;
    } else {
      // 접두어만 있는 토큰(「Bearer 」)도 빈 토큰이다 — 생성·저장 전 테스트·서버와 같은 규칙·문구(WD-639).
      const missingToken = missingSecretMessage('BEARER', token);
      if (missingToken) nextErrors.token = missingToken;
      authConfig.token = token;
    }

    // (WD-753) 추가 헤더 행도 검사한다 — 메인 인증 칸 다음 화면 순서라 메인 칸 오류가 먼저 포커스를 받는다
    const nextExtraErrors = extraHeaderRowErrors(extraHeaders);
    setAuthErrors(nextErrors);
    setExtraHeaderErrors(nextExtraErrors);
    if (focusFirstError(AUTH_FIELD_KEYS, nextErrors, authFieldId)) return;
    if (focusFirstExtraHeaderError(extraHeaders, nextExtraErrors)) return;

    // 서버는 authConfig 를 통째로 바꿔 저장하므로 추가 헤더 조각을 반드시 함께 싣는다 — 빼면 저장된 추가 헤더가 지워진다.
    // 값을 비운 저장 행은 가린 값을 보내 서버가 저장 원문을 유지한다(방식을 바꾸는 저장도 같다).
    const data: UpdateApiConnectionRequest = {
      authType,
      authConfig: { ...authConfig, ...extraHeadersToConfig(extraHeaders) },
    };
    try {
      await updateMutation.mutateAsync({ id: connectionId, data });
      toast.success('인증 정보가 업데이트되었습니다.');
      closeAuthEdit();
    } catch (error) {
      handleApiError(error, '인증 정보 업데이트에 실패했습니다.');
    }
  };

  /** 연결 즉시 테스트 */
  const handleTest = async () => {
    if (!connection) return;
    try {
      const result = await testMutation.mutateAsync(connection.id);
      // 응답 본문/헤더 등 디버깅 정보를 카드로 노출 (#76)
      setLastTestResult(result);
      if (result.ok) {
        toast.success(`연결 정상 (HTTP ${result.status ?? '-'}, ${result.latencyMs}ms)`);
      } else {
        toast.error(`연결 이상: ${result.errorMessage ?? '알 수 없는 오류'}`);
      }
    } catch (error) {
      handleApiError(error, '연결 테스트에 실패했습니다.');
    }
  };

  /**
   * 응답 본문을 Content-Type 또는 첫 글자 기반으로 JSON pretty-print.
   * 파싱 실패 시 원본을 그대로 반환 (HTML 에러 페이지 등).
   */
  const formatResponseBody = (body: string | null, contentType: string | null): string => {
    if (!body) return '';
    const looksJson =
      (contentType ?? '').toLowerCase().includes('json') ||
      body.trimStart().startsWith('{') ||
      body.trimStart().startsWith('[');
    if (!looksJson) return body;
    try {
      return JSON.stringify(JSON.parse(body), null, 2);
    } catch {
      return body;
    }
  };

  /** 클립보드 복사 — 비동기 실패 시 토스트 안내 */
  const handleCopy = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(`${label} 복사됨`);
    } catch {
      toast.error('복사에 실패했습니다.');
    }
  };

  const handleDelete = async () => {
    if (connectionId == null || !connection) return;
    try {
      await deleteMutation.mutateAsync(connectionId);
      toast.success(`"${connection.name}" 연결이 삭제되었습니다.`);
      navigate('/admin/api-connections');
    } catch (error) {
      handleApiError(error, 'API 연결 삭제에 실패했습니다.');
    }
  };

  if (isLoading) {
    return (
      <div className="mx-auto max-w-2xl space-y-6">
        <Skeleton className="h-8 w-48" />
        <Card>
          <CardContent className="space-y-4 pt-6">
            <Skeleton className="h-4 w-64" />
            <Skeleton className="h-4 w-48" />
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!connection) return null;

  // 읽기 보기의 메인 인증 칸은 추가 헤더 키를 뺀 값, 추가 헤더는 이름·가린 값 행(storedExtraHeaderRows, 훅 구간의 useMemo)으로 따로 보인다(WD-753)
  const mc = withoutExtraHeaders(connection.maskedAuthConfig);

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      {/* 탭 제목에 서버에서 불러온 저장된 이름을 붙인다 (WD-534) */}
      <DetailPageTitle name={connection.name} />
      {/* 미저장 변경사항 이탈 가드 다이얼로그 (#551) */}
      {unsavedChangesDialog}
      <div className="flex items-center gap-4">
        {/* API 연결 목록으로 돌아가는 뒤로가기 버튼 — 접근성 보강 (#102)
            (#634) navigate() 직접 호출 대신 requestNavigate 사용 — dirty 상태면 이탈 가드 다이얼로그를 띄운다 */}
        <Button variant="ghost" size="icon" onClick={() => requestNavigate('/admin/api-connections')} aria-label="목록으로 돌아가기" title="목록으로 돌아가기">
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">API 연결 상세</h1>
        {/* 배지 문구는 목록과 같은 공용 표(WD-681) — 모르는 값은 서버 값을 그대로 보인다 */}
        <Badge variant={authTypeBadgeVariant(connection.authType)}>
          {authTypeLabel(connection.authType, 'badge')}
        </Badge>
      </div>

      {/* 연결 상태 카드: lastStatus/latency/error 표시 + 즉시 테스트 버튼 */}
      <Card>
        <CardHeader>
          <CardTitle>연결 상태</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center gap-3">
            <StatusBadge
              status={connection.lastStatus}
              checkedAt={connection.lastCheckedAt}
              healthCheckPath={connection.healthCheckPath}
            />
            {connection.lastLatencyMs !== null && (
              <p className="text-sm text-muted-foreground">지연: {connection.lastLatencyMs}ms</p>
            )}
          </div>
          {connection.lastErrorMessage && (
            <p className="text-sm text-destructive">{connection.lastErrorMessage}</p>
          )}
          {canWrite && (
            <Button
              variant="outline"
              size="sm"
              onClick={handleTest}
              disabled={testMutation.isPending}
            >
              {testMutation.isPending ? '확인 중...' : '지금 확인'}
            </Button>
          )}
        </CardContent>
      </Card>

      {/* 최근 테스트 결과 카드 (#76): 응답 상태코드/본문/헤더/요청 URL을 함께 노출하여 디버깅 지원 */}
      {lastTestResult && (
        <Card data-testid="test-result-card">
          <CardHeader>
            <CardTitle>최근 테스트 결과</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge
                variant={lastTestResult.ok ? 'default' : 'destructive'}
                data-testid="test-result-status"
              >
                {lastTestResult.ok ? '성공' : '실패'}
              </Badge>
              <span className="text-sm font-mono">
                HTTP {lastTestResult.status ?? '-'}
              </span>
              <span className="text-sm text-muted-foreground">
                · {lastTestResult.latencyMs}ms
              </span>
            </div>

            {lastTestResult.errorMessage && (
              <p className="text-sm text-destructive">{lastTestResult.errorMessage}</p>
            )}

            {/* 요청 URL */}
            {lastTestResult.requestUrl && (
              <div className="space-y-1">
                <div className="flex items-center justify-between">
                  {/* 대응하는 입력 컨트롤이 없는 표시 전용 제목이라 label 이 아닌 span 으로 둔다 (#432).
                      Label 기본 스타일(leading-none/font-medium)을 직접 보충해 시각 결과를 유지. */}
                  <span className="text-xs leading-none font-medium text-muted-foreground">요청 URL</span>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 px-2"
                    onClick={() => handleCopy(lastTestResult.requestUrl ?? '', '요청 URL')}
                    aria-label="요청 URL 복사"
                  >
                    <Copy className="h-3 w-3" />
                  </Button>
                </div>
                <p className="text-xs font-mono break-all rounded bg-muted px-2 py-1">
                  {lastTestResult.requestUrl}
                </p>
              </div>
            )}

            {/* 응답 헤더 */}
            {Object.keys(lastTestResult.responseHeaders ?? {}).length > 0 && (
              <details className="space-y-2" data-testid="test-result-headers">
                <summary className="cursor-pointer text-sm font-medium">
                  응답 헤더 ({Object.keys(lastTestResult.responseHeaders).length})
                </summary>
                <div className="overflow-x-auto rounded border">
                  <table className="w-full text-xs">
                    <tbody>
                      {Object.entries(lastTestResult.responseHeaders).map(([k, v]) => (
                        <tr key={k} className="row-divider">
                          <td className="px-2 py-1 font-mono text-muted-foreground whitespace-nowrap align-top">
                            {k}
                          </td>
                          <td className="px-2 py-1 font-mono break-all">{v}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            )}

            {/* 응답 본문 */}
            {lastTestResult.responseBodyPreview !== null &&
              lastTestResult.responseBodyPreview !== undefined && (
                <div className="space-y-1" data-testid="test-result-body">
                  <div className="flex items-center justify-between">
                    {/* 위와 동일 — <pre> 출력의 제목일 뿐 입력 컨트롤이 없다 (#432) */}
                    <span className="text-xs leading-none font-medium text-muted-foreground">응답 본문</span>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-6 px-2"
                      onClick={() =>
                        handleCopy(lastTestResult.responseBodyPreview ?? '', '응답 본문')
                      }
                      aria-label="응답 본문 복사"
                    >
                      <Copy className="h-3 w-3" />
                    </Button>
                  </div>
                  <pre className="max-h-[400px] overflow-auto rounded bg-muted px-3 py-2 text-xs font-mono whitespace-pre-wrap break-all">
                    {formatResponseBody(
                      lastTestResult.responseBodyPreview,
                      lastTestResult.responseContentType,
                    ) || '(빈 응답)'}
                  </pre>
                </div>
              )}
          </CardContent>
        </Card>
      )}

      {/* Basic Info */}
      <Card>
        <CardHeader>
          <CardTitle>기본 정보</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* (WD-561) 필수 칸은 FormField 로 감싸 * 표시·인라인 오류·aria 연결을 한 곳에서 처리한다 */}
          <FormField label="연결 이름" htmlFor={infoFieldId.name} error={infoErrors.name} required>
            <Input
              id={infoFieldId.name}
              value={name}
              onChange={(e) => {
                setName(e.target.value);
                setInfoErrors((prev) => withoutKeys(prev, 'name'));
              }}
              disabled={!canWrite}
            />
          </FormField>
          <div className="space-y-2">
            {/* 필수 칸은 FormField 의 *, 선택 칸은 라벨의 「(선택)」 — 생성 대화상자와 같은 표시(WD-666) */}
            <Label htmlFor={`${baseId}-description`}>설명 (선택)</Label>
            <Input id={`${baseId}-description`} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="연결에 대한 설명" disabled={!canWrite} maxLength={MAX_DESCRIPTION_LENGTH} />
          </div>
          {/* Base URL: 외부 API 기본 주소 */}
          <FormField label="Base URL" htmlFor={infoFieldId.baseUrl} error={infoErrors.baseUrl} required>
            <Input
              id={infoFieldId.baseUrl}
              value={baseUrl}
              onChange={(e) => {
                setBaseUrl(e.target.value);
                setInfoErrors((prev) => withoutKeys(prev, 'baseUrl'));
              }}
              placeholder="https://api.example.com"
              disabled={!canWrite}
            />
          </FormField>
          {/* 헬스체크 경로 */}
          <div className="space-y-2">
            <Label htmlFor={`${baseId}-health-path`}>헬스체크 경로 (선택)</Label>
            <Input
              id={`${baseId}-health-path`}
              value={healthCheckPath}
              onChange={(e) => setHealthCheckPath(e.target.value)}
              placeholder="/health"
              disabled={!canWrite}
              /* 아래 도움말 문구를 스크린리더가 함께 읽도록 연결 (#432) */
              aria-describedby={`${baseId}-health-path-help`}
            />
            <p id={`${baseId}-health-path-help`} className="text-xs text-muted-foreground">
              10분마다 자동 상태 점검. 비워두면 점검 안 함.
            </p>
          </div>
          <div className="grid grid-cols-2 gap-4 text-sm text-muted-foreground">
            <div>생성일: {formatDate(connection.createdAt)}</div>
            <div>수정일: {formatDate(connection.updatedAt)}</div>
          </div>
          {canWrite && (
            <Button onClick={handleSaveInfo} disabled={updateMutation.isPending}>
              {updateMutation.isPending ? '저장 중...' : '저장'}
            </Button>
          )}
        </CardContent>
      </Card>

      <Separator />

      {/* Auth Config */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>인증 설정</CardTitle>
            {canWrite && !isEditingAuth && (
              <Button variant="outline" size="sm" onClick={startAuthEdit}>
                인증 정보 변경
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          {!isEditingAuth ? (
            /* 읽기 전용 마스킹 뷰 */
            <div className="space-y-3">
              <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                <span className="text-muted-foreground">인증 유형</span>
                <span>{authTypeLabel(connection.authType, 'full')}</span>
              </div>
              {connection.authType === 'API_KEY' && (
                <>
                  <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                    <span className="text-muted-foreground">위치</span>
                    <span>{mc.placement === 'query' ? 'Query Parameter' : 'Header'}</span>
                  </div>
                  <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                    <span className="text-muted-foreground">{mc.placement === 'query' ? '파라미터 이름' : '헤더 이름'}</span>
                    <span>{mc.placement === 'query' ? mc.paramName : mc.headerName}</span>
                  </div>
                  <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                    <span className="text-muted-foreground flex items-center gap-1">
                      키 값
                      {/* (#92) 마스킹 정책 안내 — 평문 노출은 보안상 차단되며 변경 시 재입력 필요. */}
                      <TooltipProvider>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <button
                              type="button"
                              aria-label="키 값 마스킹 정책 안내"
                              data-testid="masked-key-help"
                              className="text-muted-foreground hover:text-foreground"
                            >
                              <HelpCircle className="h-3.5 w-3.5" />
                            </button>
                          </TooltipTrigger>
                          <TooltipContent className="max-w-[260px] text-xs">
                            보안 정책상 마지막 4자리만 표시됩니다. 전체 키 확인이 필요하면 "인증 정보 변경"으로 재입력하세요.
                          </TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                    </span>
                    <span className="font-mono">{mc.apiKey ?? '****'}</span>
                  </div>
                </>
              )}
              {connection.authType === 'BEARER' && (
                <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                  <span className="text-muted-foreground flex items-center gap-1">
                    토큰
                    {/* (#92) 마스킹 정책 안내 — Bearer 토큰도 동일 정책 적용. */}
                    <TooltipProvider>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <button
                            type="button"
                            aria-label="토큰 마스킹 정책 안내"
                            data-testid="masked-token-help"
                            className="text-muted-foreground hover:text-foreground"
                          >
                            <HelpCircle className="h-3.5 w-3.5" />
                          </button>
                        </TooltipTrigger>
                        <TooltipContent className="max-w-[260px] text-xs">
                          보안 정책상 마지막 4자리만 표시됩니다. 전체 토큰 확인이 필요하면 "인증 정보 변경"으로 재입력하세요.
                        </TooltipContent>
                      </Tooltip>
                    </TooltipProvider>
                  </span>
                  <span className="font-mono">{mc.token ?? '****'}</span>
                </div>
              )}
              {/* (WD-681) Basic — 아이디는 그대로, 비밀번호는 서버가 가린 값(다른 비밀과 같은 정책) */}
              {connection.authType === 'BASIC' && (
                <>
                  <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                    <span className="text-muted-foreground">아이디</span>
                    <span className="font-mono break-all">{mc.username ?? '-'}</span>
                  </div>
                  <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                    <span className="text-muted-foreground">비밀번호</span>
                    <span className="font-mono">{mc.password ?? '****'}</span>
                  </div>
                </>
              )}
              {/* (WD-753) 추가 인증 헤더 — 이름과 서버가 가린 값만 보인다(원문은 화면에 오지 않는다) */}
              {storedExtraHeaderRows.length > 0 && (
                <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                  <span className="text-muted-foreground">추가 헤더</span>
                  <ul className="space-y-1">
                    {storedExtraHeaderRows.map((header) => (
                      <li key={header.name} className="font-mono break-all">
                        {`${header.name}: ${header.storedMasked}`}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          ) : (
            /* 편집 모드 */
            <div className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor={`${baseId}-auth-type`}>인증 유형</Label>
                {/* 유형·위치를 바꾸면 인증 칸 오류를 비운다 — 숨겨진 칸 오류가 남아 사라진 칸으로 포커스가 가지 않게(WD-561) */}
                <Select
                  value={authType}
                  onValueChange={(v) => {
                    setAuthType(v as ApiConnectionAuthType);
                    setAuthErrors({});
                  }}
                >
                  {/* shadcn Select 는 트리거가 실제 포커스 대상이라 id 를 트리거에 건다 */}
                  <SelectTrigger id={`${baseId}-auth-type`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {/* 선택지·이름은 만들기 대화상자와 같은 공용 표(WD-681) */}
                    {AUTH_TYPE_OPTIONS.map((type) => (
                      <SelectItem key={type} value={type}>
                        {AUTH_TYPE_LABEL[type].full}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              {authType === 'API_KEY' && (
                <>
                  <div className="space-y-2">
                    <Label htmlFor={`${baseId}-placement`}>위치</Label>
                    <Select
                      value={placement}
                      onValueChange={(v) => {
                        setPlacement(v);
                        setAuthErrors({});
                      }}
                    >
                      <SelectTrigger id={`${baseId}-placement`}>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="header">Header</SelectItem>
                        <SelectItem value="query">Query Parameter</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  {/* 라벨 텍스트만 placement 에 따라 바뀌고 입력은 하나뿐이라 id 는 고정한다 */}
                  <FormField
                    label={placement === 'query' ? '파라미터 이름' : '헤더 이름'}
                    htmlFor={authFieldId.authName}
                    error={authErrors.authName}
                    required
                  >
                    <Input
                      id={authFieldId.authName}
                      placeholder={placement === 'query' ? 'api_key' : 'Authorization'}
                      value={placement === 'query' ? paramName : headerName}
                      onChange={(e) => {
                        if (placement === 'query') setParamName(e.target.value);
                        else setHeaderName(e.target.value);
                        setAuthErrors((prev) => withoutKeys(prev, 'authName'));
                      }}
                    />
                  </FormField>
                  {/* 유지할 저장 키가 있으면 비워 둬도 되므로 * 없이 안내 문구를 보이고, 없으면 필수(*)다(WD-480·WD-666 — 저장 판단과 같은 조건) */}
                  <FormField
                    label="키 값"
                    htmlFor={authFieldId.apiKey}
                    error={authErrors.apiKey}
                    required={!storedMaskedKey}
                  >
                    <Input
                      id={authFieldId.apiKey}
                      type="password"
                      placeholder="새 API 키를 입력하세요"
                      // 유지 안내 문구를 스크린리더가 함께 읽도록 잇는다(WD-666). 오류가 생기면 FormField 가 이 id 뒤에 오류 id 를 붙인다.
                      aria-describedby={storedMaskedKey ? apiKeyHintId : undefined}
                      value={apiKey}
                      onChange={(e) => {
                        setApiKey(e.target.value);
                        setAuthErrors((prev) => withoutKeys(prev, 'apiKey'));
                      }}
                    />
                    {/* 저장된 키가 있으면 비워 둬도 된다는 걸 알려 준다 — 이름·위치만 고칠 때 키 재입력 불필요(WD-480) */}
                    {storedMaskedKey && (
                      <p id={apiKeyHintId} className="text-xs text-muted-foreground">
                        비워 두면 저장된 키를 그대로 사용합니다.
                      </p>
                    )}
                  </FormField>
                </>
              )}

              {authType === 'BEARER' && (
                <FormField label="Bearer Token" htmlFor={authFieldId.token} error={authErrors.token} required>
                  <Input
                    id={authFieldId.token}
                    type="password"
                    placeholder="새 토큰을 입력하세요"
                    value={token}
                    onChange={(e) => {
                      setToken(e.target.value);
                      setAuthErrors((prev) => withoutKeys(prev, 'token'));
                    }}
                  />
                </FormField>
              )}

              {/* (WD-681) Basic — 아이디·비밀번호. 비밀번호는 키 칸처럼 저장된 값이 있으면 비워 둬도 된다 */}
              {authType === 'BASIC' && (
                <BasicAuthFields
                  usernameId={authFieldId.username}
                  passwordId={authFieldId.password}
                  username={username}
                  password={password}
                  usernameError={authErrors.username}
                  passwordError={authErrors.password}
                  onUsernameChange={(value) => {
                    setUsername(value);
                    setAuthErrors((prev) => withoutKeys(prev, 'username'));
                  }}
                  onPasswordChange={(value) => {
                    setPassword(value);
                    setAuthErrors((prev) => withoutKeys(prev, 'password'));
                  }}
                  passwordPlaceholder="새 비밀번호를 입력하세요"
                  keepPasswordHintId={storedMaskedPassword ? passwordHintId : undefined}
                />
              )}

              {/* (WD-753) 추가 인증 헤더 — 방식과 무관하게 메인 인증 칸 아래에 둔다. 값을 비운 저장 행은 저장값을 유지한다 */}
              <ExtraHeadersFields
                rows={extraHeaders}
                onChange={handleExtraHeadersChange}
                errors={extraHeaderErrors}
              />

              <div className="flex gap-2">
                <Button onClick={handleSaveAuth} disabled={updateMutation.isPending}>
                  {updateMutation.isPending ? '저장 중...' : '인증 저장'}
                </Button>
                <Button variant="outline" onClick={closeAuthEdit}>
                  취소
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Delete — apiconnection:delete 가 있을 때만(WD-121) */}
      {canDelete && (
        <>
          <Separator />
          <Card>
            <CardContent className="pt-6">
              <DeleteConfirmDialog
                entityName="API 연결"
                itemName={connection.name}
                // (#647) 이름이 같은 다른 연결과 혼동해 잘못 삭제하지 않도록 ID/Base URL을 함께 표시
                extraDetail={`ID: ${connection.id} · ${connection.baseUrl}`}
                onConfirm={handleDelete}
                trigger={
                  <Button variant="destructive">
                    이 연결 삭제
                  </Button>
                }
              />
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
