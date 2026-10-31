import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { dataImportsApi } from '../../api/dataImports';
import { categoriesApi,datasetsApi } from '../../api/datasets';
import { fetchAllPages } from '../../lib/fetch-all-pages';
import type { ColumnMappingEntry, ImportMode,ImportResponse, ImportStartResponse } from '../../types/dataImport';
import type { ApiImportRequest,CloneDatasetRequest } from '../../types/dataset';
import { useMyPermissions } from './useMyPermissions';

/**
 * 데이터셋 상세 화면(헤더·각 탭·온톨로지 연결 카드)의 쓰기 진입점을 보일지 정하는 권한 묶음 (WD-139). 화면은 권한 코드를 직접 읽지 않고 이 훅만 쓴다.
 * 데이터셋 목록(추가·삭제)·카테고리 관리(생성·수정·삭제)·홈 퀵 액션도 같은 훅으로 판정한다(WD-144).
 *
 * 조회 권한만 있는 사용자에게 「복제」「수정」「행 추가」 등을 보여 주면 다이얼로그를 다 채운 뒤에야 403 을 받는다.
 * 서버 컨트롤러가 요구하는 권한 코드를 그대로 따라 진입점별로 나눈다 — 정의·메타 변경(dataset:write)과 행 적재·수정(data:import),
 * 행 삭제(data:delete), API 가져오기(파이프라인을 만들므로 pipeline:write)는 서로 다른 권한이라 하나로 뭉치면 일부 사용자가 잘못 막히거나 열린다.
 * 서버에 관리자 우회가 없으므로(ADMIN 역할이 권한을 가짐) 권한 코드만 본다. 권한을 불러오는 동안은 빈 집합이라 모두 false — 버튼이 깜빡이지 않는다.
 * isLoading 은 "권한이 없다"는 안내 문구를 로딩 중에 띄우지 않으려는 화면(매핑 탭·온톨로지 연결 카드)이 쓴다.
 * 최종 판정은 서버이고 여기는 노출 결정용이다.
 */
export function useDatasetPermissions() {
  const { permissions, isLoading } = useMyPermissions();
  return {
    /** 정보 수정·태그·복제·상태 변경·필드 정의·검색 설정·매핑·온톨로지 연결·문서 삭제·오브젝트 업로드 (dataset:write) */
    canWriteDataset: permissions.has('dataset:write'),
    /** 행 추가·행 수정·파일 임포트·SQL 실행·문서 업로드 (data:import) */
    canImportData: permissions.has('data:import'),
    /** 선택 행 삭제 (data:delete) — 데이터셋 자체 삭제(canDeleteDataset)와 다르다 */
    canDeleteData: permissions.has('data:delete'),
    /**
     * 데이터셋 자체 삭제·카테고리 삭제 (dataset:delete — DatasetController·DatasetCategoryController DELETE, WD-144).
     * 행 삭제(canDeleteData, data:delete)와 이름이 비슷하지만 권한 코드가 다르다. 생성·카테고리 생성/수정은 canWriteDataset.
     */
    canDeleteDataset: permissions.has('dataset:delete'),
    /** API 가져오기 — 미리보기·생성 모두 파이프라인 엔드포인트라 pipeline:write */
    canApiImport: permissions.has('pipeline:write'),
    /** 보안 등급 변경 (DatasetSecurityController PUT security-level) */
    canClassify: permissions.has('dataset:classify'),
    /** 허용 목록(접근 부여) 추가·삭제 (DatasetSecurityController access-grants) */
    canGrant: permissions.has('dataset:grant'),
    isLoading,
  };
}

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.getCategories().then(r => r.data),
  });
}

export function useDatasets(params: { categoryId?: number; storageType?: string; originType?: string; search?: string; page?: number; size?: number; favoriteOnly?: boolean; status?: string; securityLevelId?: number }) {
  return useQuery({
    queryKey: ['datasets', params],
    queryFn: () => datasetsApi.getDatasets(params).then(r => r.data),
  });
}

/** 서버가 목록 조회 size 를 100 으로 자르므로(DatasetController) 선택 목록은 이 크기로 페이지를 순회한다. */
const ALL_DATASETS_PAGE_SIZE = 100;

/**
 * 선택 목록(파이프라인 편집기 출력/입력, 트리거 감시 대상 등)용 전체 데이터셋 조회 (#732).
 *
 * 왜: `size: 1000` 으로 한 번에 받으려 해도 서버는 조용히 100 건으로 자르므로
 * 101 번째 이후 데이터셋이 목록에서 빠지고, 그 데이터셋을 이미 참조하는 스텝은 빈 칸으로 보였다.
 * 서버 상한(보호 장치)은 그대로 두고, `fetchAllPages` 로 `totalPages` 만큼 페이지를 순회해 전부 모은다.
 * queryKey 가 'datasets' 로 시작하므로 데이터셋 생성·삭제 시의 기존 무효화에 함께 걸린다.
 */
export function useAllDatasets() {
  return useQuery({
    queryKey: ['datasets', 'all'],
    queryFn: () =>
      fetchAllPages(
        (page, size) => datasetsApi.getDatasets({ page, size }).then((r) => r.data),
        ALL_DATASETS_PAGE_SIZE,
      ),
  });
}

export function useDataset(id: number) {
  return useQuery({
    queryKey: ['datasets', id],
    queryFn: () => datasetsApi.getDatasetById(id).then(r => r.data),
    enabled: !!id,
  });
}

export function useDatasetData(datasetId: number, params: {
  search?: string; size?: number; sortBy?: string; sortDir?: string;
  // Phase 1-2: spatial filter (optional)
  spatialColumn?: string;
  nearbyLon?: number; nearbyLat?: number; nearbyRadius?: number;
  bboxMinLon?: number; bboxMinLat?: number; bboxMaxLon?: number; bboxMaxLat?: number;
}) {
  const { search, size = 50, sortBy, sortDir, spatialColumn, nearbyLon, nearbyLat, nearbyRadius, bboxMinLon, bboxMinLat, bboxMaxLon, bboxMaxLat } = params;
  return useInfiniteQuery({
    queryKey: ['datasets', datasetId, 'data', { search, size, sortBy, sortDir, spatialColumn, nearbyLon, nearbyLat, nearbyRadius, bboxMinLon, bboxMinLat, bboxMaxLon, bboxMaxLat }],
    queryFn: ({ pageParam = 0 }) =>
      datasetsApi.getDatasetData(datasetId, {
        search,
        page: pageParam,
        size,
        sortBy,
        sortDir,
        includeTotalCount: pageParam === 0,
        spatialColumn,
        nearbyLon,
        nearbyLat,
        nearbyRadius,
        bboxMinLon,
        bboxMinLat,
        bboxMaxLon,
        bboxMaxLat,
      }).then(r => r.data),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => {
      if (lastPage.totalPages > 0 && lastPage.page < lastPage.totalPages - 1) {
        return lastPage.page + 1;
      }
      // When totalPages is -1 (not included), check if we got a full page
      if (lastPage.totalPages === -1 && lastPage.rows.length >= (size || 50)) {
        return lastPage.page + 1;
      }
      return undefined;
    },
    enabled: !!datasetId,
  });
}

export function useCreateDataset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: datasetsApi.createDataset,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets'] }),
  });
}

export function useUpdateDataset(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof datasetsApi.updateDataset>[1]) => datasetsApi.updateDataset(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', id] });
    },
  });
}

export function useDeleteDataset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: datasetsApi.deleteDataset,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets'] }),
  });
}

/**
 * 데이터셋 참조 집계(WD-143) — 삭제 확인 창 내용 안에서 쓰므로 창이 열려 있을 때만 마운트되어 조회한다.
 * gcTime 0: 창이 닫혀 언마운트되면 결과를 버린다 — 다시 열면 새로 조회한다(그 사이 생긴 파이프라인·위젯을 놓치지 않게).
 * retry false: 실패(시간 초과 포함)하면 바로 안내로 넘어간다(재시도 동안 삭제가 막혀 있지 않게).
 */
export function useDatasetReferences(id: number) {
  return useQuery({
    // ['datasets'] 아래에 두지 않는다 — 삭제 성공이 ['datasets'] 를 무효화하면 닫히는 중인 창이 지워진 데이터셋을 다시 조회해 404 안내가 비친다.
    queryKey: ['dataset-references', id],
    queryFn: ({ signal }) => datasetsApi.getReferences(id, signal).then((r) => r.data),
    gcTime: 0,
    retry: false,
  });
}

export function useAddColumn(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof datasetsApi.addColumn>[1]) => datasetsApi.addColumn(datasetId, data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] }),
  });
}

export function useUpdateColumn(datasetId: number, columnId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof datasetsApi.updateColumn>[2]) => datasetsApi.updateColumn(datasetId, columnId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

export function useReorderColumns(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (columnIds: number[]) => datasetsApi.reorderColumns(datasetId, columnIds),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] }),
  });
}

export function useDeleteDataRows(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (rowIds: number[]) => datasetsApi.deleteDataRows(datasetId, rowIds).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'data'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

export function useColumnStats(datasetId: number, enabled = true) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'stats'],
    queryFn: () => datasetsApi.getDatasetStats(datasetId).then(r => r.data),
    enabled: !!datasetId && enabled,
    staleTime: 5 * 60 * 1000,
  });
}

export function useDeleteColumn(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (columnId: number) => datasetsApi.deleteColumn(datasetId, columnId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

export function useImports(datasetId: number) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'imports'],
    queryFn: () => dataImportsApi.getImports(datasetId).then(r => r.data),
    enabled: !!datasetId,
    refetchInterval: (query) => {
      const data = query.state.data;
      const hasPending = data?.some((i: ImportResponse) => i.status === 'PENDING' || i.status === 'PROCESSING');
      return hasPending ? 3000 : false;
    },
  });
}

export function useImport(datasetId: number, importId: number) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'imports', importId],
    queryFn: () => dataImportsApi.getImportById(datasetId, importId).then(r => r.data),
    enabled: !!datasetId && !!importId,
  });
}

export function useUploadFile(datasetId: number) {
  const queryClient = useQueryClient();
  // sheetName: 엑셀에서 가져올 시트(WD-253) — 미지정이면 서버가 첫 번째 보이는 시트를 쓴다
  return useMutation<
    ImportStartResponse,
    unknown,
    { file: File; mappings?: ColumnMappingEntry[]; importMode?: ImportMode; sheetName?: string | null }
  >({
    mutationFn: ({ file, mappings, importMode, sheetName }) =>
      dataImportsApi.uploadFile(datasetId, file, mappings, importMode, sheetName).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'imports'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

/** 임포트 미리보기 — sheetName 을 주면 그 시트를, 없으면 서버 기본(첫 번째 보이는 시트)을 읽는다 (WD-253) */
export function usePreviewImport(datasetId: number) {
  return useMutation({
    mutationFn: ({ file, sheetName }: { file: File; sheetName?: string | null }) =>
      dataImportsApi.previewImport(datasetId, file, sheetName).then(r => r.data),
  });
}

/** 임포트 사전 검증 — 미리보기에서 고른 시트(sheetName)를 그대로 검사한다 (WD-253) */
export function useValidateImport(datasetId: number) {
  return useMutation({
    mutationFn: ({ file, mappings, sheetName }: { file: File; mappings: ColumnMappingEntry[]; sheetName?: string | null }) =>
      dataImportsApi.validateImport(datasetId, file, mappings, sheetName).then(r => r.data),
  });
}

export function useCreateCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: categoriesApi.createCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['categories'] }),
  });
}

export function useUpdateCategory(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof categoriesApi.updateCategory>[1]) => categoriesApi.updateCategory(id, data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['categories'] }),
  });
}

export function useDeleteCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: categoriesApi.deleteCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['categories'] }),
  });
}

/** 즐겨찾기 추가·해제 — 원하는 상태(favorite)를 보내고, 응답의 favorited 가 실제 결과다(WD-388). */
export function useSetFavorite() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, favorite }: { id: number; favorite: boolean }) =>
      datasetsApi.setFavorite(id, favorite).then(r => r.data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets'] }),
  });
}

export function useUpdateStatus(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof datasetsApi.updateStatus>[1]) => datasetsApi.updateStatus(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', id] });
    },
  });
}

export function useAddTag(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (tagName: string) => datasetsApi.addTag(datasetId, tagName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
    },
  });
}

export function useRemoveTag(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (tagName: string) => datasetsApi.removeTag(datasetId, tagName),
    // 실패해도 다시 읽는다 — 다른 탭에서 이미 지운 태그(404, WD-242)가 화면에 계속 남지 않게
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
    },
  });
}

export function useTags() {
  return useQuery({
    queryKey: ['tags'],
    queryFn: () => datasetsApi.getAllTags().then(r => r.data),
  });
}

// Phase 1: SQL Query hooks
export function useExecuteQuery(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ sql, maxRows }: { sql: string; maxRows?: number }) =>
      datasetsApi.executeQuery(datasetId, sql, maxRows).then(r => r.data),
    onSuccess: (data) => {
      // Always invalidate query history (all queries are logged)
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'queries'] });
      if (data.queryType !== 'SELECT') {
        queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'data'] });
        queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      }
    },
  });
}

export function useQueryHistory(datasetId: number, page = 0, size = 20) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'queries', { page, size }],
    queryFn: () => datasetsApi.getQueryHistory(datasetId, page, size).then(r => r.data),
    enabled: !!datasetId,
  });
}

// Phase 2: Manual Row Entry hooks
export function useAddRow(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Record<string, unknown>) =>
      datasetsApi.addRow(datasetId, data).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'data'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

export function useUpdateRow(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ rowId, data }: { rowId: number; data: Record<string, unknown> }) =>
      datasetsApi.updateRow(datasetId, rowId, data).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'data'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

// Phase 3: API Import hook
export function useCreateApiImport(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: ApiImportRequest) => datasetsApi.createApiImport(datasetId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      queryClient.invalidateQueries({ queryKey: ['pipelines'] });
    },
  });
}

// Phase 3: Clone Dataset hook
export function useCloneDataset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ datasetId, data }: { datasetId: number; data: CloneDatasetRequest }) =>
      datasetsApi.cloneDataset(datasetId, data).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
    },
  });
}
