import { BarChart3, Download, Eye, History, Hourglass, Info, Plus, Star, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { PageHeading } from '../../components/PageHeading';
import { TruncatedLink } from '../../components/TruncatedLink';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { ExportBlockedTooltip } from '../../components/ui/ExportBlockedTooltip';
import { SearchInput } from '../../components/ui/search-input';
import { SecurityLevelBadge } from '../../components/ui/SecurityLevelBadge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { SimplePagination } from '../../components/ui/simple-pagination';
import {
  SortableHeader,
  type SortDirection,
  Table,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { TableEmptyRow, TableErrorRow } from '../../components/ui/table-empty';
import { TableSkeletonRows } from '../../components/ui/table-skeleton';
import { TruncatedText } from '../../components/ui/truncated-text';
import {
  useCategories,
  useDatasetPermissions,
  useDatasets,
  useDeleteDataset,
  useSetFavorite,
} from '../../hooks/queries/useDatasets';
import { useSecurityLevels } from '../../hooks/queries/useSecurityLevels';
import { useLastPageInfo } from '../../hooks/useLastPageInfo';
import { useListRetryFocus } from '../../hooks/useListRetryFocus';
import { offlineListMessage, useOfflinePaused } from '../../hooks/useOnlineStatus';
import { useRecentDatasets } from '../../hooks/useRecentDatasets';
import { handleApiError, listLoadErrorProps } from '../../lib/api-error';
import {
  describeOriginType,
  describeStorageType,
  formatDateOnly,
  formatDateTimeMinute,
  formatRelativeTime,
  STORAGE_TYPE_OPTIONS,
  type TypeLabelInfo,
} from '../../lib/formatters';
import { getPageAfterDelete, parseNonNegativeInt } from '../../lib/pagination';
import { cn, iGa } from '../../lib/utils';
import type { CategoryResponse, DatasetResponse } from '../../types/dataset';
import { DatasetCategoryChips } from './components/DatasetCategoryChips';
import { DatasetDeleteConfirmDialog } from './components/DatasetDeleteConfirmDialog';
import { DatasetPreviewSheet } from './components/DatasetPreviewSheet';
import { DatasetTypeModal, type DatasetTypeSelection } from './components/DatasetTypeModal';
import { ExportDialog } from './components/ExportDialog';
import { DATASET_SEARCH_DEBOUNCE_MS } from './dataset-list-search';

type SortKey = 'name' | 'createdAt';

/** 출처 필터가 골라져 있을 때 「임시 데이터셋 보기」를 비활성으로 두는 이유(WD-754·WD-776) */
const ORIGIN_FILTER_HINT = '출처 필터를 고르면 그 출처의 데이터셋만 보여 이 옵션은 쓰이지 않습니다';

/** 이름 옆 상태 배지 — 상태별 문구·색 (NONE 은 배지 없음) */
const STATUS_BADGES: Partial<Record<DatasetResponse['status'], { label: string; variant: 'success' | 'destructive' }>> = {
  CERTIFIED: { label: '✓ Certified', variant: 'success' },
  DEPRECATED: { label: 'Deprecated', variant: 'destructive' },
};

/**
 * 유형 칸 배지 (WD-650)
 * - 알려진 유형은 짧은 한글 라벨(「테이블」·「원본」 등)이라 잘리지 않게 그대로 둔다(WD-517: 짧은 값은 자르지 않는다).
 * - 알 수 없는 유형(서버 enum 원본)만 줄어들며 TruncatedText 로 말줄임·title(WD-658).
 */
function DatasetTypeBadge({ info: { label, known }, className }: { info: TypeLabelInfo; className: string }) {
  return (
    <Badge className={cn('border-0', !known && 'min-w-0 shrink', className)}>
      {known ? label : <TruncatedText value={label} className="min-w-0" />}
    </Badge>
  );
}

/** 카테고리 응답 전 빈 목록 — 매 렌더 새 배열이 되지 않게 모듈 상수로 둔다. */
const EMPTY_CATEGORIES: CategoryResponse[] = [];

/**
 * 목록 필터의 URL 키(검색어 q 제외) — 「조건에 맞는 데이터셋이 없습니다」 판정과 「필터 초기화」가 함께 쓴다 (WD-879).
 * 정렬(sort/order)·페이지 크기(size)·임시 보기(showTemp)는 보기 설정이라 넣지 않는다.
 */
const DATASET_FILTER_PARAM_KEYS = ['categoryId', 'storageType', 'originType', 'status', 'securityLevel', 'favorite'] as const;

/** 정렬 키 파싱 — 잘못된 값(손으로 고친 주소)은 정렬 없음(null). 렌더 값과 toggleSort 가 같은 규칙을 쓴다. */
function parseSortKey(raw: string | null): SortKey | null {
  return raw === 'name' || raw === 'createdAt' ? raw : null;
}

/**
 * WD-1062: search 문자열에서 정렬 보기 설정(sort·order)을 뺀 문자열을 돌려준다.
 * 왜: 정렬은 클라이언트 보기 설정이라 page 를 초기화하지 않으므로, 보정 시점의 주소 비교(addressChangedSince)에서는 무시해야 한다.
 */
function withoutSortKeys(search: string): string {
  const params = new URLSearchParams(search);
  params.delete('sort');
  params.delete('order');
  return params.toString();
}

/** 분류 id 파싱 — 주소 값이 없으면 undefined(전체). 눌림 표시와 토글이 같은 비교(숫자)를 쓰게 한다(?categoryId=03 도 3). */
function parseCategoryId(raw: string | null): number | undefined {
  return raw ? Number(raw) : undefined;
}

/** 정렬 방향 파싱 — 잘못된 값은 none */
function parseSortOrder(raw: string | null): SortDirection {
  return raw === 'asc' || raw === 'desc' ? raw : 'none';
}

/**
 * URL 쿼리 파라미터를 단일 진실 소스(single source of truth)로 사용한다 (refs #94).
 * - 새로고침/공유 시 필터·검색·정렬 상태가 복원된다.
 * - 빈 값(default)일 때는 URL에서 제거하여 깔끔한 URL 유지.
 * - history 오염 방지를 위해 replace=true 로 갱신.
 */
export default function DatasetListPage() {
  // WD-985: 「검색 초기화」 뒤 초점이 body 로 떨어지지 않게 이 화면의 검색 입력으로 보낸다
  const searchInputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // URL → 상태 파싱 (매 렌더 시 동기화)
  const categoryId = parseCategoryId(searchParams.get('categoryId'));
  const storageType = searchParams.get('storageType') || '';
  const originType = searchParams.get('originType') || '';
  const search = searchParams.get('q') || '';
  // 손으로 고친·낡은 주소(?page=abc, ?page=-1)는 첫 페이지로 읽는다 — 그대로 보내면 서버 400 이 빈 목록처럼 보였다(WD-146)
  const page = parseNonNegativeInt(searchParams.get('page'), 0);
  const favoriteOnly = searchParams.get('favorite') === 'true';
  /**
   * 보기 옵션 「임시 데이터셋 보기」(WD-754) — URL ?showTemp=1.
   * 서버 목록은 파이프라인이 스텝마다 만드는 임시(TEMP) 데이터셋을 기본으로 숨긴다(includeTemp=false).
   * 켜면 includeTemp=true 로 함께 받는다. 거르는 일은 서버가 하므로 건수·페이지도 서버 응답 그대로 쓴다.
   */
  const showTemp = searchParams.get('showTemp') === '1';
  /**
   * 출처 필터가 무엇이든 골라져 있으면 서버가 그 출처만 주고 includeTemp 를 무시한다 — 보기 옵션이 목록을 바꾸지 못하므로 비활성으로 둔다
   * (WD-754 는 「임시」만 막았는데, 「원본」에서도 눌러도 아무 일이 없는 토글이었다 — WD-776). 아래 두 값도 이것으로 유도한다:
   * showTempEffective = 보기 옵션이 실제로 목록에 반영되는가(요청 includeTemp 와 같은 값), tempShown = 지금 목록에 임시가 보이는가
   * (출처 「임시」이거나 반영된 보기 옵션 — 토글의 눌림 표시를 실제 목록과 맞춰, 출처 「원본」 + 주소 showTemp=1 이어도 눌림으로 보이지 않게).
   */
  const tempToggleBlocked = originType !== '';
  const showTempEffective = showTemp && !tempToggleBlocked;
  const tempShown = originType === 'TEMP' || showTempEffective;
  const statusFilter = searchParams.get('status') || '';
  // 보안 등급 필터 — 서버 쿼리(페이지 내 정렬 한계 회피, 목업 s2). URL 값은 등급 id.
  const securityLevelParam = searchParams.get('securityLevel');
  const securityLevelId = securityLevelParam ? Number(securityLevelParam) : undefined;
  /** 페이지당 표시 건수: 사용자가 selector 로 변경 가능 (기본 10) */
  const size = parseNonNegativeInt(searchParams.get('size'), 10) || 10;
  const sortKey = parseSortKey(searchParams.get('sort'));
  const sortOrder = parseSortOrder(searchParams.get('order'));

  /**
   * 마지막으로 쓴 주소 매개변수 (WD-950, 리뷰 반영).
   *
   * 왜: react-router 의 setSearchParams 함수형 갱신은 prev 로 렌더 시점 searchParams 스냅샷을 준다.
   * 화면 반영 전에 patchParams 가 여러 번 불리면(정렬 직후 필터, 토글 두 번 등) 뒤 호출이 앞 호출을 모른 채
   * 낡은 값 위에 써서 앞 변경이 사라진다. 그래서 내가 써 넣었지만 아직 렌더에 오지 않은 주소 문자열을
   * 순서대로 pendingWritesRef 에 쌓고, 다음 patch 의 기준(getLatestParams)은 그 마지막 항목에서 유도한다
   * (없으면 지금 searchParams) — 기준을 따로 저장하지 않으므로 두 값을 맞춰 쓸 일이 없다.
   *
   * 커밋 뒤 동기화(useLayoutEffect — 렌더 중 ref 를 읽고 쓰지 않는다):
   * - 커밋된 location.search 가 pending 에 있으면 내가 쓴 주소가 도착한 것 — 그 항목까지 뺀다.
   * - pending 에 없는데 location.key 가 바뀌었으면 외부 이동(사이드바 링크·뒤로가기) — pending 을 비운다.
   * - location.key·렌더된 주소는 항상 커밋된 값으로 기록한다.
   * layout effect 인 이유: 버려지는 렌더(transition 중 끼는 옛 location 의 급한 렌더)가 ref 를 건드리지 않게 하고,
   * 그래도 paint·클릭 이벤트보다 먼저 끝나 낡은 값을 쓸 틈이 없게 하려는 것.
   *
   * 실제 주소 대조(WD-1061): 외부 이동(사이드바 링크)은 history 를 즉시 쓰지만 커밋·layout effect 는 transition 렌더 뒤라,
   * 그 사이 클릭은 낡은 기준(이미 지운 favorite·sort)을 읽어 되살린다. 그래서 getLatestParams 가 window.location 을 직접
   * 대조해, 같은 경로인데 기준(마지막 pending 또는 렌더된 주소)과 실제 주소가 다르면 외부 이동으로 보고 pending 을 비운 뒤
   * 실제 주소를 기준으로 쓴다(경로가 다르면 다른 페이지로 가는 중이라 기존 기준 유지).
   *
   * 정렬(#358) 연속 클릭 전이 유실 방지와 뒤로가기 뒤 URL 기준 정렬도 이 구조가 맡는다
   * (toggleSort 와 토글 버튼이 모두 getLatestParams 의 최신 값을 읽는다).
   */
  const location = useLocation();
  const pendingWritesRef = useRef<string[]>([]);
  const lastLocationKeyRef = useRef(location.key);
  // 렌더에 온 주소를 ref 로도 들어 patchParams 가 렌더마다 새로 만들어지지 않게 한다(의존 배열에 searchParams 를 넣지 않으려고)
  const renderedParamsRef = useRef(searchParams);
  // 렌더(커밋)된 경로 — getLatestParams 가 실제 주소의 경로와 비교해 같은 페이지 안 외부 이동만 가른다(WD-1061)
  const renderedPathnameRef = useRef(location.pathname);
  // 커밋이 확실해진 뒤(useLayoutEffect)에만 대기 목록·기준값을 갱신한다 — 렌더 중 ref 읽기·쓰기를 하지 않는다.
  // 왜: react-router 7 은 탐색을 startTransition 으로 적용해, 새 location 의 렌더가 도는 동안 옛 location 의 급한 렌더가 끼고
  // 버려질 수 있다. 렌더 중에 하면 버려진 렌더가 대기 항목을 빼거나 기준값을 옛 주소로 되돌려 그 사이 클릭이 정렬을 다시 지운다.
  // layout effect 는 실제로 커밋된 주소에서만 돌고, 클릭(이벤트)보다 먼저 끝나므로 낡은 값을 쓸 틈이 없다.
  useLayoutEffect(() => {
    if (pendingWritesRef.current.length > 0) {
      // 가장 최근에 쓴 같은 주소를 찾는다 — 같은 주소를 여러 번 쓴 경우([A,X,A])에도 커밋된 주소는 그 마지막 쓰기까지 반영된 것이라,
      // 첫 항목(indexOf)만 빼면 [X,A] 가 남아 낡은 항목이 기준값이 된다.
      const arrivedIndex = pendingWritesRef.current.lastIndexOf(searchParams.toString());
      if (arrivedIndex >= 0) {
        pendingWritesRef.current = pendingWritesRef.current.slice(arrivedIndex + 1);
      } else if (location.key !== lastLocationKeyRef.current) {
        pendingWritesRef.current = [];
      }
    }
    lastLocationKeyRef.current = location.key;
    renderedParamsRef.current = searchParams;
    renderedPathnameRef.current = location.pathname;
  }, [searchParams, location.key, location.pathname]);

  /**
   * 아직 렌더에 오지 않은 변경까지 포함한 최신 주소 — 마지막 pending, 없으면 렌더에 온 주소.
   * 실제 주소와 어긋나면(커밋 전 외부 이동) pending 을 비우는 부수 효과가 있으므로 렌더 중에는 부르지 않는다.
   * 호출처는 이벤트 처리기(필터·정렬 클릭)와 effect·비동기 뒤 호출(페이지 범위 보정 effect, handleDelete 의 await 뒤 page 보정)이다
   * — 뒤의 둘은 낡은 값으로 계산한 page 를 떠난 주소에 쓰지 않도록 addressChangedSince 로 최신 주소와 대조한다(WD-1062).
   */
  const getLatestParams = useCallback(() => {
    const pending = pendingWritesRef.current;
    // 기준 — 마지막 pending, 없으면 렌더에 온 주소
    const base = pending.length > 0 ? new URLSearchParams(pending[pending.length - 1]) : renderedParamsRef.current;
    // WD-1061: 실제 주소(window.location)가 기준과 다르면 아직 커밋 전인 외부 이동이 이미 주소를 바꾼 것 — 실제 주소 위에 쓴다
    // 가정: BrowserRouter·basename 없음(App.tsx) — history 를 동기로 쓰고 window.location.pathname 이 라우터 경로와 같다.
    // basename·다른 라우터를 쓰게 되면 경로 비교가 늘 어긋나 이 대조가 꺼지므로 함께 고쳐야 한다.
    const live = new URLSearchParams(window.location.search);
    if (
      window.location.pathname === renderedPathnameRef.current &&
      live.toString() !== base.toString()
    ) {
      pendingWritesRef.current = [];
      return live;
    }
    return base;
  }, []);

  /**
   * WD-1062: snapshot(과거 시점의 search 문자열) 이후 주소가 바뀌었는지 — 범위 보정 effect 와 handleDelete 의 await 뒤 보정이 함께 쓴다.
   * 왜: 두 보정은 옛 값으로 page 를 계산하므로, (1) 다른 화면으로 떠났거나(경로 변경) (2) 조회 조건(필터·검색·page)이 바뀌었으면 쓰지 않아야 한다.
   * 정렬(sort·order)은 클라이언트 보기 설정이라 page 를 초기화하지 않으므로 비교에서 뺀다 — 정렬만 바꾼 경우엔 보정이 여전히 유효하다.
   */
  const addressChangedSince = useCallback(
    (snapshot: string) => {
      if (window.location.pathname !== renderedPathnameRef.current) return true;
      return withoutSortKeys(getLatestParams().toString()) !== withoutSortKeys(snapshot);
    },
    [getLatestParams],
  );

  /** 아직 렌더에 오지 않은 변경까지 포함한 최신 주소 값 — 토글이 렌더 값 대신 이걸로 다음 값을 정한다. */
  const readLatestParam = (key: string) => getLatestParams().get(key);

  /**
   * URL 파라미터 패치 헬퍼 — 빈 값/기본값은 키 자체를 제거하여 URL 청결성 유지.
   * value 가 null/undefined/'' 이면 키 삭제, 그렇지 않으면 set.
   */
  const patchParams = useCallback(
    (patch: Record<string, string | number | boolean | null | undefined>) => {
      // WD-1065: 다른 페이지로 가는 중(행 클릭 → 상세)이면 목록 주소를 다시 쓰지 않는다 — 쓰면 이미 시작된 이동을 덮어써 취소된다.
      // 렌더된 경로와 실제 경로가 다르면 커밋 전 이동 중이라는 뜻이다(대조는 WD-1061 이 만든 renderedPathnameRef).
      if (window.location.pathname !== renderedPathnameRef.current) return;
      const next = new URLSearchParams(getLatestParams());
      for (const [k, v] of Object.entries(patch)) {
        if (v === null || v === undefined || v === '' || v === false) {
          next.delete(k);
        } else {
          next.set(k, String(v));
        }
      }
      // 바뀐 게 없으면(이미 같은 주소) 대기 목록에도 쌓지 않고 쓰지도 않는다 — 같은 주소가 겹쳐 쌓이거나 불필요한 탐색이 나가는 것을 공용 쪽에서 막는다
      if (next.toString() === getLatestParams().toString()) return;
      pendingWritesRef.current.push(next.toString());
      setSearchParams(next, { replace: true });
    },
    [getLatestParams, setSearchParams],
  );

  /**
   * 「최신 값과 비교해 켜고 끄기」 — 최신 주소의 key 값이 onValue 면 끄고(null), 아니면 켠다(onValue).
   * 반환값은 patchParams 에 그대로 넣을 값이다(각 버튼의 page 초기화 같은 부수 효과는 호출 쪽이 정한다).
   */
  const toggledValue = (key: string, onValue: string) => (readLatestParam(key) === onValue ? null : onValue);

  const toggleSort = (key: SortKey) => {
    // 렌더 값이 아니라 최신 주소의 sort·order 로 다음 전이를 계산한다(sortKey/sortOrder 와 같은 파서 — 잘못된 값은 none).
    const curKey = parseSortKey(readLatestParam('sort'));
    const curOrder = parseSortOrder(readLatestParam('order'));
    // 같은 컬럼 재클릭 — asc → desc → none(원본 순서) 순환. 다른 컬럼이면 asc 부터.
    let next: { key: SortKey | null; order: SortDirection };
    if (curKey !== key) next = { key, order: 'asc' };
    else if (curOrder === 'asc') next = { key, order: 'desc' };
    else if (curOrder === 'desc') next = { key: null, order: 'none' };
    else next = { key, order: 'asc' };

    patchParams({ sort: next.key, order: next.order === 'none' ? null : next.order });
  };

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewDatasetId, setPreviewDatasetId] = useState<number | null>(null);
  const [previewDatasetName, setPreviewDatasetName] = useState('');

  // 내보내기 다이얼로그 상태 (refs #95) — 호버 액션 '내보내기' 버튼에서 트리거
  const [exportOpen, setExportOpen] = useState(false);
  const [exportDatasetId, setExportDatasetId] = useState<number | null>(null);
  const [exportDatasetName, setExportDatasetName] = useState('');

  // 데이터셋 생성 유형 선택 모달 상태 — 저장 방식/출처를 먼저 고른 뒤 생성 폼으로 이동
  const [typeModalOpen, setTypeModalOpen] = useState(false);

  const { data: categoriesData } = useCategories();
  // 목록 훅과 쪽 수 기억(useLastPageInfo)이 같은 조회 조건 객체를 쓴다
  const datasetParams = {
    categoryId,
    storageType: storageType || undefined,
    originType: originType || undefined,
    search: search || undefined,
    page,
    size,
    favoriteOnly: favoriteOnly || undefined,
    status: statusFilter || undefined,
    securityLevelId,
    // 꺼져 있으면 싣지 않고 서버 기본(임시 숨김)에 맡긴다. 출처 필터가 있으면 서버가 그쪽을 우선해 무시하므로
    // 싣지 않는다 — 같은 결과에 캐시 키만 달라져 요청이 한 번 더 나가지 않게(WD-754)
    includeTemp: showTempEffective || undefined,
  };
  const { data: datasetsData, isLoading, isPending, isPaused, isError, isPlaceholderData, error: datasetsError, refetch: refetchDatasets, isFetching } = useDatasets(datasetParams);

  // 오프라인 멈춤 판정(공용 useOfflinePaused, WD-966·WD-982·WD-971): 받은 목록 없음 또는 쪽 이동 placeholder + paused 가 실제 오프라인이면 offline(안내),
  // 데이터 없이 온라인인데 paused(탭 숨김으로 재시도 멈춤)면 waiting(스켈레톤). offline 이면 흐림·inert 없이 안내를 보인다
  const { waiting, offline } = useOfflinePaused({ data: datasetsData, isPaused, isPlaceholderData });

  // 「다시 시도」 포커스(WD-957, 공용 훅 WD-987): 실패한 목록에서 누르면 표로 초점을 옮겼다가 다시 실패하면 버튼으로 되돌린다
  const { tableProps, bodyProps, retryAction } = useListRetryFocus({
    isPending,
    // WD-982: 쪽 이동 중 이전 쪽 행 처리(pending 판정·흐림·inert)는 훅이 맡는다
    // 오프라인 쪽 이동 대기는 흐림·inert 없이 안내를 보인다(WD-982, useOfflinePaused)
    isPlaceholderData: isPlaceholderData && !offline,
    isError,
    isFetching,
    data: datasetsData,
    error: datasetsError,
    refetch: refetchDatasets,
  });

  const { data: levels } = useSecurityLevels();
  const deleteDataset = useDeleteDataset();
  const setFavorite = useSetFavorite();
  // 생성(POST, dataset:write)·삭제(DELETE, dataset:delete) 진입점 노출 — 조회 전용 사용자가 누르고 나서야 403 을 받지 않게 한다(WD-144).
  // 권한 조회 중에는 둘 다 false 라 버튼을 그리지 않는다(상세 화면 WD-139 와 같다).
  const { canWriteDataset, canDeleteDataset } = useDatasetPermissions();
  const { recents, removeRecent, syncRecents } = useRecentDatasets();

  const categories = categoriesData ?? EMPTY_CATEGORIES;
  const rawDatasets = datasetsData?.content || [];
  /**
   * 정렬이 적용된 데이터셋 목록 (현재 페이지 내).
   * - 원본 배열을 변형하지 않기 위해 slice() 후 정렬.
   * - 이름은 한국어 자모 정렬을 위해 localeCompare('ko'), 날짜는 ISO 문자열 비교.
   */
  const datasets = useMemo(() => {
    if (!sortKey || sortOrder === 'none') return rawDatasets;
    const sorted = rawDatasets.slice();
    sorted.sort((a, b) => {
      let cmp = 0;
      if (sortKey === 'name') cmp = a.name.localeCompare(b.name, 'ko');
      else if (sortKey === 'createdAt') cmp = a.createdAt.localeCompare(b.createdAt);
      return sortOrder === 'asc' ? cmp : -cmp;
    });
    return sorted;
  }, [rawDatasets, sortKey, sortOrder]);
  // 목록을 받으면 「최근 접근」에 저장된 옛 이름·테이블명을 서버의 현재 값으로 고친다 (WD-390)
  useEffect(() => {
    if (datasetsData?.content) syncRecents(datasetsData.content);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- syncRecents 는 렌더마다 새로 만들어진다. 목록 응답이 바뀔 때만 맞춘다
  }, [datasetsData]);
  const { totalPages, totalElements } = useLastPageInfo(datasetsData, datasetParams);

  /**
   * 범위 밖 페이지 — 북마크한 뒷 페이지 주소를 데이터가 줄어든 뒤 열면 서버는 빈 페이지를 준다.
   * 「데이터셋이 없습니다」로 오해하지 않게 마지막 페이지로 옮긴다(WD-146). 옮기는 동안은 빈 안내 대신 로딩 행을 보인다.
   */
  // WD-982: placeholder(쪽 이동 중 이전 쪽 data)의 totalPages 는 대상 쪽의 것이 아니므로 범위 밖 판정을 하지 않는다 — 실제 응답이 와야 판정한다
  const pageOutOfRange = !!datasetsData && !isPlaceholderData && page > 0 && page >= Math.max(datasetsData.totalPages, 1);
  useEffect(() => {
    if (!pageOutOfRange || !datasetsData) return;
    // WD-1062: 이 effect 는 옛 응답의 totalPages 로 값을 계산한다. 그 사이 사용자가 다른 주소로 이미 떠났다면(렌더된 주소 ≠ 실제 최신 주소)
    // 그 값을 새 주소에 덮어쓰지 않는다 — 새 주소의 응답이 오면 다시 판정한다.
    if (addressChangedSince(searchParams.toString())) return;
    const lastPage = Math.max(datasetsData.totalPages - 1, 0);
    // 이미 목표 쪽이면 patchParams 가 쓸 것이 없다고 보고 그냥 돌아온다
    patchParams({ page: lastPage === 0 ? null : lastPage });
  }, [pageOutOfRange, datasetsData, patchParams, addressChangedSince, searchParams]);

  // 검색어를 뺀 여섯 필터(카테고리·저장방식·출처·상태·보안등급·즐겨찾기) 중 하나라도 걸려 있는지 (WD-879).
  // 0건 빈 상태가 「데이터셋이 없습니다」(데이터 자체가 없음)인지 「조건에 맞는 데이터셋이 없습니다」(필터로 걸러짐)인지 가르는 기준이다.
  // 숫자로 바꾼 값이 아니라 주소 값이 있는지로 본다 — 손으로 고친 ?categoryId=abc 도 요청에는 실리므로, 숫자 변환(NaN)으로
  // 「필터 없음」이 되면 초기화 버튼 없이 첫 사용 문구가 나와 잘못된 값을 지울 길이 없다.
  // 활성 판정과 초기화가 같은 키 목록을 써서, 필터를 더할 때 한쪽만 고쳐 어긋나지 않게 한다.
  // 다만 즐겨찾기는 요청(favoriteOnly)이 'true' 일 때만 거르므로 같은 기준으로 본다 — ?favorite=false 링크로 들어오면
  // 즐겨찾기 버튼은 꺼져 보이는데 「조건에 맞는…」이 나오고 「새 데이터셋 만들기」·최근 데이터셋이 사라지던 어긋남을 막는다 (WD-879 리뷰 후속).
  const filtersActive = DATASET_FILTER_PARAM_KEYS.some((key) =>
    key === 'favorite' ? favoriteOnly : !!searchParams.get(key),
  );
  const noFiltersActive = !search && !filtersActive;

  // 여섯 필터와 page 만 지운다 — 정렬·페이지 크기·임시 보기·검색어는 필터가 아니라 보기 설정이라 남긴다 (WD-879).
  const resetFilters = () => {
    patchParams({ ...Object.fromEntries(DATASET_FILTER_PARAM_KEYS.map((key) => [key, null])), page: null });
  };

  const handleDelete = async (id: number, name: string) => {
    try {
      // 삭제 응답을 기다리는 사이 사용자가 필터·정렬을 바꿀 수 있어, 시작 시점의 주소를 기억해 둔다(WD-1062).
      // 왜: 아래 nextPage 가 렌더된 page·rawDatasets 로 계산되므로 기준 주소도 같은 렌더의 searchParams 여야 한다(getLatestParams 는 이미 다른 주소일 수 있다)
      const paramsAtStart = searchParams.toString();
      await deleteDataset.mutateAsync(id);
      // 지운 데이터셋 카드가 「최근 접근」에 남지 않게 뺀다 (WD-390)
      removeRecent(id);
      // 현재 페이지의 마지막 항목을 삭제한 경우, 페이지를 앞으로 보정해
      // "검색 결과 없음" 오표시(#549)를 방지한다. page 는 URL 쿼리 파라미터라
      // setPage 대신 patchParams 로 갱신한다 (0 페이지는 파라미터 제거로 표현).
      const nextPage = getPageAfterDelete(rawDatasets.length, page);
      // 왜: nextPage 는 클릭 시점의 page·행 수로 계산한 값이다. 그 사이 주소가 바뀌었다면(다른 필터 → page 초기화 등)
      // 이미 새 조건의 첫 쪽을 보고 있으므로 낡은 page 를 되쓰면 새 주소를 덮어쓴다 — 주소가 그대로일 때만 보정한다.
      if (nextPage !== page && !addressChangedSince(paramsAtStart)) {
        patchParams({ page: nextPage === 0 ? null : nextPage });
      }
      toast.success(`데이터셋 "${name}"${iGa(name)} 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '데이터셋 삭제에 실패했습니다.');
    }
  };

  const handleToggleFavorite = async (e: React.MouseEvent, id: number, name: string, isFavorite: boolean) => {
    e.stopPropagation();
    try {
      // 화면에 보이는 반대 상태를 원하는 상태로 보내고, 안내는 서버가 돌려준 실제 결과로 고른다(WD-388)
      const { favorited } = await setFavorite.mutateAsync({ id, favorite: !isFavorite });
      toast.success(favorited ? `"${name}" 즐겨찾기 추가` : `"${name}" 즐겨찾기 해제`);
    } catch {
      toast.error('즐겨찾기 변경에 실패했습니다.');
    }
  };


  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <PageHeading>데이터셋 관리</PageHeading>
        {canWriteDataset && (
          <Button onClick={() => setTypeModalOpen(true)}>
            <Plus className="h-4 w-4" />
            새 데이터셋
          </Button>
        )}
      </div>

      {/* Recent Datasets */}
      {noFiltersActive && recents.length > 0 && (
        <section aria-label="최근 접근" className="space-y-2">
          <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
            <History className="h-4 w-4" />
            <span>최근 접근</span>
          </div>
          <div className="flex overflow-x-auto gap-3 pb-2">
            {/* 키보드 접근성(WD-396): 카드는 상세 링크다 — Tab 으로 닿고 Enter 로 열린다(예전엔 div onClick 이라 마우스로만 열렸다).
                WD-755: hover 는 단색 — design-tokens §1-7 */}
            {recents.map((recent) => (
              <Link
                key={recent.id}
                to={`/data/datasets/${recent.id}`}
                className="block p-3 min-w-[200px] max-w-[250px] cursor-pointer hover:bg-accent transition-colors flex-shrink-0 rounded-lg border bg-card shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <p className="font-medium truncate">{recent.name}</p>
                <p className="text-xs text-muted-foreground font-mono truncate mt-0.5">
                  {recent.tableName}
                </p>
                <p className="text-xs text-muted-foreground mt-1">
                  {formatRelativeTime(recent.accessedAt)}
                </p>
              </Link>
            ))}
          </div>
        </section>
      )}

      {/* Filters */}
      <div className="space-y-3">
        <div className="flex items-center gap-3 flex-wrap">
          <SearchInput
            placeholder="데이터셋 검색..."
            inputRef={searchInputRef}
            // 입력이 멈춘 뒤 한 번만 검색한다(WD-513) — 글자마다 목록 요청·페이지 초기화가 나가지 않게
            debounceMs={DATASET_SEARCH_DEBOUNCE_MS}
            value={search}
            onChange={(value) => {
              patchParams({ q: value, page: null });
            }}
          />
          {/* 저장 방식 필터 */}
          <Select
            value={storageType || '__all__'}
            onValueChange={(v) => patchParams({ storageType: v === '__all__' ? null : v, page: null })}
          >
            {/* 값을 고른 뒤에도 무엇을 거르는 상자인지 읽히게 접근 이름을 붙인다 (WD-398) */}
            <SelectTrigger className="w-[140px]" aria-label="저장 방식 필터">
              <SelectValue placeholder="전체 저장방식" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all__">전체 저장방식</SelectItem>
              {/* 선택지는 저장 방식 라벨 맵에서 만든다(WD-760) — 하드코딩하면 새 저장 방식(파일)이 빠져 거를 수 없었다 */}
              {STORAGE_TYPE_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {/* 출처 필터 */}
          <Select
            value={originType || '__all__'}
            onValueChange={(v) => patchParams({ originType: v === '__all__' ? null : v, page: null })}
          >
            <SelectTrigger className="w-[140px]" aria-label="출처 필터">
              <SelectValue placeholder="전체 출처" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all__">전체 출처</SelectItem>
              <SelectItem value="SOURCE">원본</SelectItem>
              <SelectItem value="DERIVED">파생</SelectItem>
              <SelectItem value="TEMP">임시</SelectItem>
            </SelectContent>
          </Select>

          {/* Status filter */}
          <Select
            value={statusFilter || '__all__'}
            onValueChange={(value) => {
              patchParams({ status: value === '__all__' ? null : value, page: null });
            }}
          >
            <SelectTrigger className="w-[140px]" aria-label="상태 필터">
              <SelectValue placeholder="전체 상태" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all__">전체 상태</SelectItem>
              <SelectItem value="CERTIFIED">인증됨</SelectItem>
              <SelectItem value="DEPRECATED">사용 중단</SelectItem>
              <SelectItem value="NONE">상태 없음</SelectItem>
            </SelectContent>
          </Select>

          {/* Security level filter — 서버 쿼리 파라미터 securityLevelId 로 나간다 */}
          <Select
            value={securityLevelParam || '__all__'}
            onValueChange={(value) => {
              patchParams({ securityLevel: value === '__all__' ? null : value, page: null });
            }}
          >
            <SelectTrigger className="w-[140px]" aria-label="보안 등급 필터">
              {/* 다른 필터처럼 「전체 ○○」 문구 (WD-1119) */}
              <SelectValue placeholder="전체 보안 등급" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all__">전체 보안 등급</SelectItem>
              {(levels ?? []).map((l) => (
                <SelectItem key={l.id} value={String(l.id)}>
                  {l.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {/* Favorite toggle — 켜짐 상태를 aria-pressed 로 알린다(스크린리더가 토글 버튼으로 읽고, 테스트가 화면 반영을 기다릴 기준이 된다) */}
          <Button
            variant={favoriteOnly ? 'default' : 'outline'}
            size="sm"
            aria-pressed={favoriteOnly}
            onClick={() => {
              patchParams({ favorite: toggledValue('favorite', 'true'), page: null });
            }}
            className="gap-1.5"
          >
            <Star className={`h-4 w-4 ${favoriteOnly ? 'fill-current' : ''}`} />
            즐겨찾기
          </Button>

          {/* 보기 옵션 「임시 데이터셋 보기」(WD-754) — 즐겨찾기 토글과 같은 모양·aria-pressed.
              출처 필터가 골라져 있으면(WD-776 — 「임시」뿐 아니라 무엇이든) 서버가 그 출처만 주므로 비활성으로 두고,
              공용 비활성 사유 래퍼로 포커스·호버 때 이유를 툴팁으로 알린다. */}
          <ExportBlockedTooltip
            blocked={tempToggleBlocked}
            message={ORIGIN_FILTER_HINT}
            label="임시 데이터셋 보기"
          >
            <Button
              variant={tempShown ? 'default' : 'outline'}
              size="sm"
              aria-pressed={tempShown}
              onClick={() => {
                patchParams({ showTemp: toggledValue('showTemp', '1'), page: null });
              }}
              className="gap-1.5"
            >
              <Hourglass className="h-4 w-4" />
              임시 데이터셋 보기
            </Button>
          </ExportBlockedTooltip>
        </div>

        {/* Category Chip Filter — 한 줄로 접고 「+N 더 보기」 로 펼친다(WD-1073). 칩 접근성은 컴포넌트가 맡는다(WD-396). */}
        <DatasetCategoryChips
          categories={categories}
          selectedId={categoryId}
          onSelect={(id) => {
            // 「전체」(null)는 필터를 지우고, 칩은 눌림 표시와 같은 파싱(parseCategoryId)으로 최신 주소와 비교해 끈다 —
            // ?categoryId=03 처럼 문자열이 달라도 같은 분류면 꺼진다
            const toggled = parseCategoryId(readLatestParam('categoryId')) === id ? null : id;
            patchParams({ categoryId: id === null ? null : toggled, page: null });
          }}
        />
      </div>

      {/* 클라이언트 사이드 정렬 범위 안내 — 정렬 활성 시에만 표시하여 혼동 방지 (이슈 #173) */}
      {sortKey && (
        <div className="flex items-center gap-1.5 text-sm text-muted-foreground">
          <Info className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          <span>현재 페이지 내 정렬이 적용됩니다. 전체 정렬은 페이지를 이동하면 초기화됩니다.</span>
        </div>
      )}

      {/* WD-634·WD-658: --k = 헤더 선언 폭 합 + 선언 폭 없는 칸(이름) 1rem 여백. 남는 폭 R = 100cqw − --k 은 태그가 0.35 몫, 나머지는 이름이 쓴다. */}
      <div className="@container [--k:calc(2rem_+_6.25rem_+_5rem_+_7rem_+_6rem_+_6.75rem_+_3.75rem_+_1rem)] rounded-md border">
        <Table
          aria-label="데이터셋 목록"
          {...tableProps}
        >
          <TableHeader>
            <TableRow>
              <TableHead className="w-[2rem]" />
              <SortableHeader
                direction={sortKey === 'name' ? sortOrder : 'none'}
                onSort={() => toggleSort('name')}
                title="현재 페이지 내에서만 정렬됩니다"
              >
                이름
              </SortableHeader>
              <TableHead className="w-[6.25rem]">보안 등급</TableHead>
              <TableHead className="w-[5rem]">태그</TableHead>
              <TableHead className="w-[7rem]">유형</TableHead>
              <TableHead className="w-[6rem]">카테고리</TableHead>
              <SortableHeader
                className="w-[6.75rem]"
                direction={sortKey === 'createdAt' ? sortOrder : 'none'}
                onSort={() => toggleSort('createdAt')}
                title="현재 페이지 내에서만 정렬됩니다"
              >
                생성일
              </SortableHeader>
              <TableHead className="w-[3.75rem]" />
            </TableRow>
          </TableHeader>
          <tbody {...bodyProps}>
            {isLoading || pageOutOfRange || waiting ? (
              <TableSkeletonRows columns={8} rows={5} />
            ) : offline ? (
              // WD-982: placeholder 갈래는 실제 오프라인일 때만 — 온라인인데 다음 쪽 재시도가 탭 숨김으로 멈춘 paused 에서는 이전 행을 그대로 둔다.
              // WD-982: 오프라인에서 쪽을 옮기면 placeholder 로 datasetsData 가 차 있어도 새 쪽은 못 받는 중이므로 흐린 이전 행 대신 안내를 보인다(페이지네이션은 표 밖이라 남는다).
              // 데이터 없는 paused 쿼리 중 실제 오프라인만 여기로 온다 — 온라인인데 paused(탭 숨김으로 재시도 멈춤)면 위 스켈레톤 분기가 받는다.
              // 데이터 없는 paused 쿼리(오프라인)는 isLoading 도 isError 도 아니라 빈 분기(「데이터셋이 없습니다」)로 떨어지므로 오류·빈 분기보다 먼저 잡는다(WD-966).
              // 실패가 아닌 중립 대기 상태라 alert(assertive) 대신 TableEmptyRow 의 role=status(polite)로 알린다.
              // 검색어·emptyAction 은 넘기지 않아 안내 문구만 보이고 버튼은 없다 — 연결되면 자동으로 다시 불러오므로 눌러서 할 일이 없다. 이전 데이터가 있으면 목록을 유지한다.
              <TableEmptyRow colSpan={8} message={offlineListMessage('데이터셋')} />
            ) : isError && !datasetsData ? (
              // 조회 실패를 「데이터셋이 없습니다」로 그리지 않는다 — 서버·권한 오류를 데이터가 없다고 오해하게 된다(WD-146).
              // 이전 데이터가 있는 재조회 실패는 위 분기가 그 데이터를 그대로 보인다.
              <TableErrorRow
                colSpan={8}
                className="py-12 font-medium"
                // 문구·tone(403 은 muted 권한 안내)은 다른 목록과 같은 공용 규칙으로 정한다(WD-990).
                {...listLoadErrorProps(datasetsError, '데이터셋')}
                action={retryAction}
              />
            ) : datasets.length > 0 ? (
              datasets.map((dataset) => (
                <TableRow
                  key={dataset.id}
                  className="row-hover cursor-pointer hover:bg-muted/50 transition-colors group relative"
                  onClick={() => navigate(`/data/datasets/${dataset.id}`)}
                >
                  {/* Favorite star */}
                  <TableCell onClick={(e) => e.stopPropagation()} className="pr-0">
                    <button
                      type="button"
                      // WD-880: 최소 클릭 대상 32×32 — p-2(아이콘 16 + 16 = 32)로 상자를 넓히고 -m-1 로 레이아웃 차지는 기존(24)과 같게 유지해 행 높이가 변하지 않는다
                      className="-m-1 p-2 rounded hover:bg-muted transition-colors"
                      aria-label={dataset.isFavorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
                      onClick={(e) => handleToggleFavorite(e, dataset.id, dataset.name, dataset.isFavorite)}
                    >
                      <Star
                        className={`h-4 w-4 transition-colors ${
                          dataset.isFavorite
                            ? 'fill-yellow-400 text-yellow-400'
                            : 'text-muted-foreground hover:text-yellow-400'
                        }`}
                      />
                    </button>
                  </TableCell>

                  {/* 이름 칸 — WD-658: 안쪽 w-0 min-w-full 로 고유 폭 0(td max-width 는 자동 레이아웃에서 정의되지 않음), 남는 폭을 다 쓰고 넘치면 말줄임. 22rem 상한 */}
                  <TableCell className="font-medium">
                    <div className="w-0 min-w-full">
                      <div className="flex items-center gap-2 flex-wrap max-w-[22rem]">
                        <TruncatedLink to={`/data/datasets/${dataset.id}`} value={dataset.name} className="min-w-0" />
                        {STATUS_BADGES[dataset.status] && (
                          // 고정 문구라 title 없이 칸 폭에서만 말줄임 — 좁은 칸에서 옆 칸으로 넘치지 않게(WD-658)
                          <Badge variant={STATUS_BADGES[dataset.status]!.variant} className="block max-w-full min-w-0 truncate text-xs">
                            {STATUS_BADGES[dataset.status]!.label}
                          </Badge>
                        )}
                      </div>
                    </div>
                  </TableCell>

                  {/* Security level column — 등급이 비어 오면(생성 직후 등) 빈 칸.
                      WD-650: 등급 이름은 관리자가 정해 길이 제한이 없다 — 선언 폭(6.25rem, --k 에 포함) − 칸 여백으로 배지 상한을 두고 말줄임(전체는 title) */}
                  <TableCell>
                    {dataset.securityLevel && (
                      <SecurityLevelBadge level={dataset.securityLevel} truncate className="max-w-[5.25rem]" />
                    )}
                  </TableCell>

                  {/* 태그 칸 — WD-658: 상한 = 바닥 4rem(--k 안) + R × 0.35(최대 12rem). w-max 로 한 줄 폭(상한까지)을 받아 세로로 쌓이지 않는다. data-long-value-cap 은 E2E 표식 */}
                  <TableCell>
                    <div
                      data-long-value-cap
                      className="flex w-max max-w-[max(4rem,min(12rem,calc(4rem_+_(100cqw_-_var(--k))_*_0.35)))] items-center gap-1 flex-wrap"
                    >
                      {(dataset.tags || []).slice(0, 3).map((tag) => (
                        <Badge key={tag} variant="secondary" className="max-w-full text-xs">
                          <TruncatedText value={tag} className="min-w-0" />
                        </Badge>
                      ))}
                      {(dataset.tags || []).length > 3 && (
                        <Badge variant="secondary" className="text-xs">
                          +{dataset.tags.length - 3}
                        </Badge>
                      )}
                    </div>
                  </TableCell>

                  {/* 저장 방식 + 출처를 두 배지로 분리 표시.
                      WD-650: 선언 폭(7rem, --k 에 포함) − 칸 여백으로 상한을 둔다. 가장 넓은 「테이블」+「원본」(약 88px)은 그대로 들어가고,
                      알 수 없는 유형(원본 enum)만 이 안에서 말줄임된다. */}
                  <TableCell>
                    <div className="flex max-w-[6rem] gap-1">
                      <DatasetTypeBadge
                        info={describeStorageType(dataset.storageType)}
                        className="bg-secondary text-secondary-foreground"
                      />
                      <DatasetTypeBadge info={describeOriginType(dataset.originType)} className="bg-primary/10 text-primary" />
                    </div>
                  </TableCell>
                  {/* 카테고리 — WD-634: 선언 폭(6rem, --k 에 포함) 안에서 말줄임 */}
                  <TableCell>
                    <TruncatedText value={dataset.category?.name} className="max-w-[5rem]" />
                  </TableCell>
                  <TableCell className="tabular-nums" title={formatDateTimeMinute(dataset.createdAt)}>
                    {formatDateOnly(dataset.createdAt)}
                  </TableCell>
                  <TableCell className="relative">
                    {/* 행 작업 버튼 — 마우스를 올리거나 키보드 포커스가 안에 들어오면 보인다(WD-412: Tab 으로 와도 보이지 않던 문제) */}
                    {/* WD-880: 아래 미리보기·프로파일·내보내기 버튼은 최소 클릭 대상 32×32(size="icon-sm"). 오버레이는 top-1/2 -translate-y-1/2 로 세로 가운데라 커져도 행 안에 든다 */}
                    <div className="absolute right-10 top-1/2 -translate-y-1/2 flex gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity bg-background/90 backdrop-blur-sm rounded-md p-1 shadow-sm z-10">
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        title="미리보기"
                        aria-label="미리보기"
                        onClick={(e) => {
                          e.stopPropagation();
                          setPreviewDatasetId(dataset.id);
                          setPreviewDatasetName(dataset.name);
                          setPreviewOpen(true);
                        }}
                      >
                        <Eye size={14} />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        title="프로파일"
                        aria-label="프로파일"
                        onClick={(e) => {
                          e.stopPropagation();
                          navigate(`/data/datasets/${dataset.id}?tab=data`);
                        }}
                      >
                        <BarChart3 size={14} />
                      </Button>
                      {/* 보조 다운로드 아이콘은 조회자 기준 내보내기 불가면 숨긴다(S4, 스펙 §5-4) — true 일 때만(fail-closed) */}
                      {dataset.exportAllowed === true && (
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          title="내보내기"
                          aria-label="내보내기"
                          onClick={(e) => {
                            // refs #95: navigate 가 아닌 ExportDialog 오픈 — 라벨/아이콘과 동작 일치
                            e.stopPropagation();
                            setExportDatasetId(dataset.id);
                            setExportDatasetName(dataset.name);
                            setExportOpen(true);
                          }}
                        >
                          <Download size={14} />
                        </Button>
                      )}
                    </div>

                    {/* Delete button — 창을 열면 이 데이터셋을 쓰는 곳(파이프라인·대시보드 등)을 먼저 보여 준다(WD-143).
                        삭제 권한(dataset:delete)이 있을 때만 보인다(WD-144). */}
                    {canDeleteDataset && (
                      <DatasetDeleteConfirmDialog
                        datasetId={dataset.id}
                        itemName={dataset.name}
                        onConfirm={() => handleDelete(dataset.id, dataset.name)}
                        trigger={
                          <Button
                            variant="ghost"
                            size="sm"
                            aria-label="삭제"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        }
                      />
                    )}
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={8}
                resetFocusRef={searchInputRef}
                // 임시가 숨겨진 상태면 0건이어도 임시 데이터셋은 있을 수 있다 — 어디서 보는지 함께 알린다(WD-754).
                // 출처 필터가 골라져 있으면 토글이 비활성이라 「켜면 보입니다」는 따를 수 없는 안내다 — 빼고 그대로 둔다(WD-776).
                // 필터로 걸러져 0건이면 「조건에 맞는」 문구를 쓴다 — 데이터가 없는 게 아니라 조건에 맞는 게 없다는 뜻이다(WD-879).
                // 검색어가 있으면 TableEmptyRow 가 검색 결과 안내를 대신 보이므로 이 문구는 쓰이지 않는다.
                message={
                  (filtersActive ? '조건에 맞는 데이터셋이 없습니다.' : '데이터셋이 없습니다.') +
                  (showTemp || tempToggleBlocked ? '' : ' 임시 데이터셋은 「임시 데이터셋 보기」를 켜면 보입니다.')
                }
                searchKeyword={search || undefined}
                onResetSearch={search ? () => { patchParams({ q: null, page: null }); } : undefined}
                // 만들 권한이 없으면 안내 문구만 둔다 — "데이터셋이 없습니다." 는 버튼 없이도 그대로 읽힌다(WD-144)
                emptyAction={
                  // 검색어가 있으면 TableEmptyRow 가 검색 안내만 보이고 emptyAction 은 쓰지 않는다
                  filtersActive ? (
                    // 필터 때문에 비었으면 만들기 대신 필터를 풀어 다시 보게 한다(WD-879)
                    <Button size="sm" variant="outline" onClick={resetFilters}>
                      필터 초기화
                    </Button>
                  ) : noFiltersActive && canWriteDataset ? (
                    <Button size="sm" variant="outline" onClick={() => setTypeModalOpen(true)}>
                      <Plus className="h-4 w-4" />
                      새 데이터셋 만들기
                    </Button>
                  ) : undefined
                }
              />
            )}
          </tbody>
        </Table>
      </div>

      <SimplePagination
        page={page}
        totalPages={totalPages}
        onPageChange={(p) => patchParams({ page: p === 0 ? null : p })}
        totalElements={totalElements}
        pageSize={size}
        onPageSizeChange={(newSize) => {
          patchParams({ size: newSize === 10 ? null : newSize, page: null });
        }}
      />

      {/* Dataset Preview Dialog */}
      {previewDatasetId !== null && (
        <DatasetPreviewSheet
          datasetId={previewDatasetId}
          datasetName={previewDatasetName}
          open={previewOpen}
          onOpenChange={(open) => {
            setPreviewOpen(open);
            if (!open) setPreviewDatasetId(null);
          }}
        />
      )}

      {/* Export Dialog (refs #95) — 호버 액션 '내보내기' 버튼에서 트리거 */}
      {exportDatasetId !== null && (
        <ExportDialog
          datasetId={exportDatasetId}
          datasetName={exportDatasetName}
          open={exportOpen}
          onOpenChange={(open) => {
            setExportOpen(open);
            if (!open) setExportDatasetId(null);
          }}
        />
      )}

      {/* 데이터셋 생성 유형 선택 모달 — 선택 완료 시 URL 쿼리로 유형을 전달하며 생성 폼으로 이동 */}
      <DatasetTypeModal
        open={typeModalOpen}
        onOpenChange={setTypeModalOpen}
        onSelect={(s: DatasetTypeSelection) =>
          navigate(`/data/datasets/new?storageType=${s.storageType}&originType=${s.originType}`)
        }
      />
    </div>
  );
}
