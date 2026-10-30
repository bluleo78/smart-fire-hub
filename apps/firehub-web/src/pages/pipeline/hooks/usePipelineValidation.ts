import type { QueryClient } from '@tanstack/react-query';
import { useQueryClient } from '@tanstack/react-query';
import type { Dispatch } from 'react';
import { useCallback } from 'react';
import { toast } from 'sonner';

import { datasetsApi } from '@/api/datasets';
import { iGa } from '@/lib/utils';
import { editorPipelineSchema } from '@/lib/validations/pipeline';
import type { DatasetResponse } from '@/types/dataset';

import type { EditorAction, PipelineEditorState, ValidationError } from './pipelineEditorReducer';
import {
  API_PAGE_SIZE_FIELD,
  apiPageSizeError,
  validateAiOutputColumns,
  validateApiFieldMappings,
  validatePythonOutputColumns,
} from './stepColumnValidation';

/**
 * API_CALL 스텝의 지정 출력 데이터셋 컬럼 이름을 가져온다(WD-154 — 대상 컬럼 존재 검사용).
 * 알 수 없으면 null 을 돌려 존재 검사를 건너뛴다 — 최종 판정은 서버가 한다:
 * - 편집기 데이터셋 목록(조회자가 볼 수 있는 전체)이 로드됐는데 그 id 가 없으면 볼 수 없는 출력이다. 상세를 조회하지 않는다(어차피 404).
 * - 조회 실패(권한·네트워크)도 저장을 막지 않는다.
 * 쿼리 키는 useDataset 과 같아 스텝 설정 패널이 이미 불러온 상세를 그대로 쓴다.
 */
async function loadOutputColumns(queryClient: QueryClient, datasetId: number): Promise<string[] | null> {
  const visible = queryClient.getQueryData<DatasetResponse[]>(['datasets', 'all']);
  if (visible && !visible.some((d) => d.id === datasetId)) return null;
  try {
    const detail = await queryClient.fetchQuery({
      queryKey: ['datasets', datasetId],
      queryFn: () => datasetsApi.getDatasetById(datasetId).then((r) => r.data),
      staleTime: 30_000,
      retry: false,
    });
    return detail.columns.map((c) => c.columnName);
  } catch {
    return null;
  }
}

/**
 * 편집기 저장 전 검증. 출력 데이터셋 컬럼을 조회해야 하는 검사(WD-154)가 있어 비동기다 — usePipelineSave 가 await 한다.
 * 오류가 있으면 SET_VALIDATION_ERRORS 로 스텝별 오류(DAG 노드 배지·스텝 설정 패널 인라인 문구)를 채우고 false 를 돌려준다.
 */
export function usePipelineValidation(
  state: PipelineEditorState,
  dispatch: Dispatch<EditorAction>,
): () => Promise<boolean> {
  const queryClient = useQueryClient();
  return useCallback(async (): Promise<boolean> => {
    const errors: ValidationError[] = [];

    if (!state.name.trim()) {
      toast.error('파이프라인 이름을 입력하세요.');
      return false;
    }

    if (state.steps.length === 0) {
      toast.error('최소 1개의 스텝을 정의하세요.');
      return false;
    }

    const nameCount = new Map<string, string[]>();
    for (const step of state.steps) {
      const result = editorPipelineSchema.shape.steps.element.safeParse({
        name: step.name,
        description: step.description,
        scriptType: step.scriptType,
        scriptContent: step.scriptContent,
        outputDatasetId: step.outputDatasetId,
        inputDatasetIds: step.inputDatasetIds,
        loadStrategy: step.loadStrategy,
        apiConfig: step.apiConfig,
        apiConnectionId: step.apiConnectionId,
      });

      // API_CALL 스텝 URL 빈값 검증
      // apiConnectionId가 null(직접 입력 모드)이면 customUrl, 아니면 path를 사용한다.
      // 빈값이면 서버로 요청하지 않고 클라이언트에서 즉시 차단한다 (이슈 #44).
      if (step.scriptType === 'API_CALL') {
        const cfg = step.apiConfig as Record<string, unknown> | undefined;
        const connectionId = step.apiConnectionId ?? null;
        const urlValue =
          connectionId !== null
            ? (cfg?.['path'] as string | undefined) ?? ''
            : (cfg?.['customUrl'] as string | undefined) ?? '';
        if (!urlValue.trim()) {
          errors.push({
            stepTempId: step.tempId,
            field: connectionId !== null ? 'apiConfig.path' : 'apiConfig.customUrl',
            message: 'URL을 입력하세요',
          });
        }

        // 응답 필드 매핑 행 검증(WD-154) — 빈 행·한쪽만 채운 행·대상 컬럼 형식/예약어/중복·출력 데이터셋에 없는 컬럼.
        // 빈 행을 조용히 버리지 않는다(채우려던 행일 수 있다) — 행 아래에 안내하고 저장을 막는다.
        const mappings = cfg?.['fieldMappings'];
        const outputColumns =
          step.outputDatasetId != null && Array.isArray(mappings) && mappings.length > 0
            ? await loadOutputColumns(queryClient, step.outputDatasetId)
            : null;
        for (const e of validateApiFieldMappings(mappings, step.outputDatasetId == null, outputColumns)) {
          errors.push({ stepTempId: step.tempId, ...e });
        }

        // 페이지 크기(WD-362) — 0 이하·소수·상한 초과는 실행기가 외부 API 를 끝없이(최대 1만 번) 다시 호출하게 하므로 저장을 막는다
        const pageSizeMessage = apiPageSizeError(cfg?.['pagination']);
        if (pageSizeMessage) {
          errors.push({ stepTempId: step.tempId, field: API_PAGE_SIZE_FIELD, message: pageSizeMessage });
        }
      }

      // AI_CLASSIFY specific validation
      if (step.scriptType === 'AI_CLASSIFY') {
        const cfg = step.aiConfig;
        if (!cfg || !cfg.prompt || !(cfg.prompt as string).trim()) {
          errors.push({ stepTempId: step.tempId, field: 'aiConfig.prompt', message: '프롬프트를 입력하세요' });
        }
        if (!cfg || !cfg.outputColumns || (cfg.outputColumns as unknown[]).length === 0) {
          errors.push({ stepTempId: step.tempId, field: 'aiConfig.outputColumns', message: '출력 컬럼을 최소 1개 정의하세요' });
        }
        // 출력 컬럼 이름 검증(WD-154) — 빈 이름·형식·중복(자동 생성 출력이면 예약어도). 실행 시점 영어 원문 오류 대신 행 아래에 안내한다.
        for (const e of validateAiOutputColumns(cfg?.outputColumns, step.outputDatasetId == null)) {
          errors.push({ stepTempId: step.tempId, ...e });
        }
      }

      // PYTHON 스텝 출력 컬럼 이름 검증(WD-352) — 컬럼 정의는 출력을 자동 생성할 때만 쓰이고 편집기도 그때만 보이므로 그때만 검사한다.
      // 이 이름으로 TEMP 를 만드니 빈 이름·형식·길이·중복·예약어를 행 아래에 안내하고 저장을 막는다. 예전에는 빈 이름만 막아
      // 나머지는 실행 시점에 영어 원문 오류(`Invalid name: my col. …`)로 실패했다.
      if (step.scriptType === 'PYTHON' && step.outputDatasetId == null) {
        for (const e of validatePythonOutputColumns(step.pythonConfig?.outputColumns)) {
          errors.push({ stepTempId: step.tempId, ...e });
        }
      }

      if (!result.success) {
        for (const issue of result.error.issues) {
          errors.push({
            stepTempId: step.tempId,
            field: issue.path.join('.'),
            message: issue.message,
          });
        }
      }

      const trimmed = step.name.trim();
      if (trimmed) {
        const existing = nameCount.get(trimmed) ?? [];
        existing.push(step.tempId);
        nameCount.set(trimmed, existing);
      }
    }

    for (const [name, tempIds] of nameCount) {
      if (tempIds.length > 1) {
        for (const tempId of tempIds) {
          errors.push({
            stepTempId: tempId,
            field: 'name',
            message: `스텝 이름 "${name}"${iGa(name)} 중복됩니다`,
          });
        }
      }
    }

    if (errors.length > 0) {
      dispatch({ type: 'SET_VALIDATION_ERRORS', payload: errors });
      toast.error('입력 오류를 확인하세요.');
      return false;
    }

    return true;
  }, [state, dispatch, queryClient]);
}
