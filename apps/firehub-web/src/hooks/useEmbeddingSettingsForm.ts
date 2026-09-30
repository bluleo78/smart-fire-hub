import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import {
  embeddingApi,
  type EmbeddingConfigRequest,
  type EmbeddingConfigView,
  type EmbeddingImpact,
  type EmbeddingProviderType,
  type EmbeddingStatus,
} from '../api/embedding';
import { extractApiError } from '../lib/api-error';
import { useEmbeddingStatus, useReindexAllEmbeddings } from './queries/useEmbedding';
import { useEmbeddingConfig, useSaveEmbeddingConfig } from './queries/useEmbeddingSettings';

/** 임베딩 provider 폼 값. */
export interface EmbeddingForm {
  provider: EmbeddingProviderType;
  model: string;
  baseUrl: string;
  apiKey: string;
}

// 미설정 테넌트의 시작 폼. 기본값을 "적용 중인 값"처럼 보이지 않게 모델·주소는 비워 둔다.
const EMPTY: EmbeddingForm = { provider: 'OLLAMA', model: '', baseUrl: '', apiKey: '' };

// 저장 요청 뒤 입력이 바뀌어 저장을 멈췄을 때의 안내(#716).
const STALE_SAVE_MESSAGE = '저장 중 입력이 바뀌어 저장하지 않았습니다. 다시 저장하세요.';

export type EmbeddingTestState =
  | { ok: true; dimension: number }
  | { ok: false; message: string }
  | null;

/** 저장 확인 창에 필요한 값 — 클라이언트가 먼저 잰 차원과 영향도(서버 PUT 은 다시 probe 한다). */
export interface EmbeddingPendingSave {
  request: EmbeddingConfigRequest;
  dimension: number;
  impact: EmbeddingImpact;
}

/** 임베딩 탭이 그리는 데 필요한 전부. */
export interface EmbeddingSettingsFormState {
  isLoading: boolean;
  /** 설정 조회 자체가 실패했는가 — 탭은 편집 가능한 빈 폼 대신 재시도 화면을 그린다. */
  isError: boolean;
  retryLoad: () => void;
  config: EmbeddingConfigView | undefined;
  status: EmbeddingStatus | undefined;
  form: EmbeddingForm;
  hasChanges: boolean;
  testState: EmbeddingTestState;
  pending: EmbeddingPendingSave | null;
  busy: boolean;
  isSaving: boolean;
  isReindexing: boolean;
  setField: (patch: Partial<EmbeddingForm>) => void;
  handleTest: () => Promise<void>;
  handleSave: () => Promise<void>;
  confirmPendingSave: () => void;
  cancelPendingSave: () => void;
  handleReindexAll: () => void;
}

/**
 * 임베딩 설정 탭의 폼 상태 기계(#713 리뷰 fix round 1) — SMTP/AI 분류 탭과 같은 패턴으로
 * <b>인스턴스를 `SettingsPage` 가 소유한다</b>. Radix `TabsContent` 는 비활성 탭을 언마운트하므로,
 * 탭 컴포넌트가 이 상태를 직접 소유하면 다른 탭에 다녀오는 순간 입력한 모델·키가 경고 없이
 * 사라진다(이슈 #86 의 재발) — `useSmtpSettingsForm` 도 같은 이유로 페이지에 산다.
 *
 * <b>조회 실패는 종단 상태다.</b> `isError` 를 삼키고 빈 폼을 그리면 "미설정"으로 오인해 저장으로
 * 기존 설정을 덮어쓸 수 있다 — 탭은 이 값을 보고 편집 폼 대신 재시도 화면을 그려야 한다.
 */
export function useEmbeddingSettingsForm(): EmbeddingSettingsFormState {
  const { data: config, isLoading, isError, refetch } = useEmbeddingConfig();
  const save = useSaveEmbeddingConfig();
  const { data: status } = useEmbeddingStatus();
  const reindex = useReindexAllEmbeddings();

  const [form, setForm] = useState<EmbeddingForm>(EMPTY);
  // dirty 판정 기준 — 서버에서 마지막으로 확정된(또는 방금 저장에 성공한) 값.
  const [original, setOriginal] = useState<EmbeddingForm>(EMPTY);
  const [testState, setTestState] = useState<EmbeddingTestState>(null);
  const [pending, setPending] = useState<EmbeddingPendingSave | null>(null);
  const [busy, setBusy] = useState(false);

  // 서버 설정 → 폼. 키는 값으로 내려오지 않으므로 항상 빈 칸에서 시작한다(비우면 유지).
  // 최초 성공 로드 때 한 번만 시드한다(useSmtpSettingsForm 의 didInitialLoad 와 같은 규칙) — config 가 바뀔
  // 때마다 시드하면 백그라운드 재조회(창 포커스·무효화)가 편집 중인 입력과 dirty 를 조용히 덮어쓴다. 저장 성공
  // 뒤의 폼·기준값은 commitSave 가 직접 갱신한다. 데이터가 있을 때만 표식을 세우므로 조회 실패 → 재시도로
  // 처음 성공한 로드도 시드된다.
  const didInitialLoad = useRef(false);
  useEffect(() => {
    if (!config || didInitialLoad.current) return;
    didInitialLoad.current = true;
    const seeded: EmbeddingForm = config.configured
      ? {
          provider: config.provider ?? 'OLLAMA',
          model: config.model ?? '',
          baseUrl: config.baseUrl ?? '',
          apiKey: '',
        }
      : EMPTY;
    setForm(seeded);
    setOriginal(seeded);
  }, [config]);

  // 폼 값 세대(#716) — 입력이 바뀔 때마다 올린다. 연결 테스트·저장은 시작할 때의 세대를 기억했다가 응답이
  // 도착했을 때 세대가 달라졌으면(=요청 이후 사용자가 입력을 고쳤으면) 그 응답을 지금 폼의 결과로 쓰지 않는다.
  // setField 가 이미 도착한 결과만 지울 수 있고 진행 중 요청의 늦은 응답은 막지 못하기 때문이다.
  const formSeqRef = useRef(0);

  // 입력이 바뀌면 직전 연결 테스트 결과는 더 이상 이 폼 값의 결과가 아니다 — 지워서 "연결 성공 · N차원"이
  // 바뀐 주소·모델에 대한 것처럼 보이지 않게 한다. 세대도 올려 진행 중 요청의 늦은 응답이 다시 채우지 못하게 한다.
  const setField = useCallback((patch: Partial<EmbeddingForm>) => {
    formSeqRef.current += 1;
    setForm((f) => ({ ...f, ...patch }));
    setTestState(null);
  }, []);

  const hasChanges =
    form.provider !== original.provider ||
    form.model !== original.model ||
    form.baseUrl !== original.baseUrl ||
    form.apiKey !== original.apiKey;

  // 빈 키는 보내지 않는다 — 서버 계약상 "생략 = 기존 키 유지"다.
  const buildRequest = (): EmbeddingConfigRequest => ({
    provider: form.provider,
    model: form.model.trim(),
    baseUrl: form.baseUrl.trim(),
    ...(form.provider === 'OPENAI' && form.apiKey ? { apiKey: form.apiKey } : {}),
  });

  const handleTest = async () => {
    const seq = formSeqRef.current;
    setBusy(true);
    try {
      const { data } = await embeddingApi.testConfig(buildRequest());
      // 요청 뒤 입력이 바뀌었으면 이 결과는 옛 값의 것이다 — 결과 줄에 붙이지 않는다(#716).
      if (seq !== formSeqRef.current) return;
      setTestState({ ok: true, dimension: data.dimension });
    } catch (e) {
      if (seq !== formSeqRef.current) return;
      setTestState({ ok: false, message: extractApiError(e, '연결 테스트에 실패했습니다.') });
    } finally {
      setBusy(false);
    }
  };

  /**
   * 저장 PUT 을 보내고 dirty 기준값을 저장된 값으로 옮긴다.
   * @param seq 저장을 시작할 때의 폼 세대. PUT 이 도는 동안 사용자가 입력을 고쳤다면(세대 불일치) 기준값만
   *            옮기고 폼은 덮어쓰지 않는다 — 고친 입력이 조용히 사라지지 않고 미저장 편집으로 남게 한다(#716).
   */
  const commitSave = async (request: EmbeddingConfigRequest, seq: number) => {
    await save.mutateAsync(request);
    toast.success('임베딩 설정을 저장했습니다');
    setPending(null);
    // 저장 성공 즉시 dirty 를 해제한다(재조회를 기다리지 않는다 — 재조회가 실패해도 이 사실은 참이다).
    const saved: EmbeddingForm = {
      provider: request.provider,
      model: request.model,
      baseUrl: request.baseUrl,
      apiKey: '',
    };
    if (seq === formSeqRef.current) setForm(saved);
    setOriginal(saved);
  };

  const handleSave = async () => {
    const request = buildRequest();
    const seq = formSeqRef.current;
    // 요청 뒤 입력이 바뀌었는가 — 그렇다면 이 저장은 지금 화면의 값에 대한 것이 아니다(#716).
    const isStale = () => seq !== formSeqRef.current;
    setBusy(true);
    try {
      const probe = await embeddingApi.testConfig(request).then(
        (r) => r.data,
        (e: unknown) => {
          // 저장 흐름의 probe 실패도 연결 테스트 실패다 — 결과 줄을 실패로 바꿔 직전 "연결 성공"이 남지 않게 한다.
          // 단, 입력이 이미 바뀌었으면 옛 값의 실패를 새 입력의 결과처럼 붙이지 않는다.
          if (!isStale()) {
            setTestState({ ok: false, message: extractApiError(e, '연결 테스트에 실패했습니다.') });
          }
          throw e;
        },
      );
      // probe 가 도는 동안 입력이 바뀌었으면 옛 값으로 저장하지 않고 멈춘다 — 사용자가 고친 값이 요청 당시 값으로
      // 덮어써지거나, 화면과 다른 값이 저장되는 것을 막는다. 조용한 무동작이 되지 않게 이유를 알린다.
      if (isStale()) {
        toast.info(STALE_SAVE_MESSAGE);
        return;
      }
      setTestState({ ok: true, dimension: probe.dimension });
      const { data: impact } = await embeddingApi.getImpact({
        model: request.model,
        dimension: probe.dimension,
      });
      if (isStale()) {
        toast.info(STALE_SAVE_MESSAGE);
        return;
      }
      if (impact.chunks + impact.datasets + impact.rowSearchIndexes > 0) {
        setPending({ request, dimension: probe.dimension, impact });
      } else {
        await commitSave(request, seq);
      }
    } catch (e) {
      toast.error(extractApiError(e, '임베딩 설정을 저장하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const confirmPendingSave = () => {
    if (!pending) return;
    // 확인 창이 열려 있는 동안은 모달이 입력을 막으므로 지금 세대가 곧 확인한 값의 세대다.
    commitSave(pending.request, formSeqRef.current).catch((e) =>
      toast.error(extractApiError(e, '임베딩 설정을 저장하지 못했습니다.')),
    );
  };

  const cancelPendingSave = () => setPending(null);

  const handleReindexAll = () => reindex.mutate();

  return {
    isLoading,
    isError,
    retryLoad: () => void refetch(),
    config,
    status,
    form,
    hasChanges,
    testState,
    pending,
    busy,
    isSaving: save.isPending,
    isReindexing: reindex.isPending,
    setField,
    handleTest,
    handleSave,
    confirmPendingSave,
    cancelPendingSave,
    handleReindexAll,
  };
}
