import { type QueryClient, type QueryKey, useMutation, useMutationState, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import type { RefObject } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';

import { aiApi, AIChatHttpError, streamAIChat } from '../../api/ai';
import { client as apiClient } from '../../api/client';
import { uploadFiles } from '../../api/files';
import { buildScreenContext, type ScreenEntityKind, type ScreenNameLookup } from '../../components/ai/screen-context';
import { getInvalidationKeys } from '../../components/ai/widgets/invalidationMap';
import { buildNavigationContext } from '../../components/ai/widgets/routes';
import { isClientError, isForbiddenError, isNotFoundError } from '../../lib/api-error';
import type { AIAttachment, AIMessage, AISession, AIStreamEvent, CanvasLayout } from '../../types/ai';
import { dashboardDetailKey } from './useAnalytics';
import { datasetDetailKey } from './useDatasets';
import { pipelineDetailKey } from './usePipelines';

// ai_session.title 컬럼은 DB에서 VARCHAR(255) — 서버가 한 번 더 방어하지만(WD-58), 애초에
// 과도하게 긴 원문을 그대로 보내지 않도록 전송 전 여기서도 안전하게 잘라낸다.
const SESSION_TITLE_MAX_LENGTH = 200;

/** 새 세션 제목으로 쓸 문자열을 안전한 길이로 트렁케이트한다(말줄임표 포함). */
function buildSessionTitle(content: string): string {
  const trimmed = content.trim();
  if (trimmed.length <= SESSION_TITLE_MAX_LENGTH) return trimmed;
  return `${trimmed.slice(0, SESSION_TITLE_MAX_LENGTH)}…`;
}

/**
 * 화면 대상 종류별 상세 캐시 키 (WD-759).
 * 각 상세 훅이 쓰는 키 함수를 그대로 써서 어긋나지 않게 한다
 * (id 는 모두 parseRouteId 로 읽은 number — 상세 화면도 같은 방식으로 읽는다).
 */
const SCREEN_DETAIL_KEY: Record<ScreenEntityKind, (id: number) => QueryKey> = {
  dataset: datasetDetailKey,
  pipeline: pipelineDetailKey,
  dashboard: dashboardDetailKey,
};

/**
 * 화면 컨텍스트에 붙일 이름을 TanStack Query 캐시에서만 찾는 lookup 을 만든다 (WD-759).
 * 상세 화면이 이미 불러온 단건 데이터를 다시 쓰므로 새 네트워크 요청이 없다 — 캐시에 없으면 undefined(ID 만 전달).
 */
function createCachedScreenNameLookup(queryClient: QueryClient): ScreenNameLookup {
  // 세 상세 응답 모두 name 을 가지므로 name 만 읽는다
  return (kind, id) => queryClient.getQueryData<{ name?: string }>(SCREEN_DETAIL_KEY[kind](id))?.name;
}

export function useAISessions(page = 0, size = 20) {
  return useQuery({
    queryKey: ['ai-sessions', page, size],
    queryFn: () => aiApi.getSessions({ page, size }).then(r => r.data),
  });
}

/** 대화 삭제 mutation 의 키 — usePendingSessionDeletes 가 진행 중인 삭제를 찾는 데 쓴다 (WD-973) */
const DELETE_SESSION_MUTATION_KEY = ['ai-sessions', 'delete'] as const;

export function useDeleteAISession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationKey: DELETE_SESSION_MUTATION_KEY,
    // WD-989: 이미 다른 곳에서 지운 대화(404)는 원하던 결과(없음)와 같으므로 실패가 아니라 「지운 것」으로 처리한다.
    // 호출처의 mutateAsync().then 성공 경로(현재 대화 전환·초점 이동)를 그대로 타도록 throw 하지 않고 alreadyGone 만 알린다.
    // 404 외(500 등)는 지금처럼 그대로 throw 해 실패로 둔다.
    mutationFn: async (id: number): Promise<{ alreadyGone: boolean }> => {
      try {
        await aiApi.deleteSession(id);
        return { alreadyGone: false };
      } catch (err) {
        if (isNotFoundError(err)) return { alreadyGone: true };
        throw err;
      }
    },
    onSuccess: (data, id) => {
      // WD-973: 성공 즉시 캐시에서 그 줄을 뺀다. 재조회가 끝나기 전에는 pending 도 풀려 지운 줄이 다시 눌릴 수
      // 있고, 그러면 이미 지워진 id 로 DELETE 가 또 나가 실패 토스트가 뜬다. (query 만 건드리므로 mutationKey 와 무관)
      // WD-989: 404(이미 지움)도 같다.
      queryClient.setQueriesData<AISession[]>({ queryKey: ['ai-sessions'] }, (old) =>
        Array.isArray(old) ? old.filter((s) => s.id !== id) : old,
      );
      if (data.alreadyGone) toast.info('이미 삭제된 대화입니다.');
      else toast.success('대화가 삭제되었습니다.');
    },
    // 삭제 API 실패 시 사용자에게 오류 피드백 제공
    onError: () => {
      toast.error('대화 삭제에 실패했습니다.');
    },
    // WD-989: 성공·404·실패 모두 목록을 다시 받는다 — 실패해도 서버 상태와 어긋난 줄(이미 지워진 줄 등)을 바로잡는다.
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
    },
  });
}

/**
 * 삭제 요청이 진행 중인 세션 id 집합 (WD-973).
 * 같은 QueryClient 를 쓰는 모든 목록(패널·전체 화면)이 공유해, 응답 전에 같은 줄을 다시 지우지 못하게 한다.
 */
export function usePendingSessionDeletes(): Set<number> {
  const ids = useMutationState({
    filters: { mutationKey: DELETE_SESSION_MUTATION_KEY, status: 'pending' },
    select: (m) => m.state.variables as number,
  });
  return useMemo(() => new Set(ids), [ids]);
}

export function useAIChat(options?: {
  onCanvasWidget?: (widget: { id: string; toolName: string; input: Record<string, unknown>; canvas?: CanvasLayout }) => void;
}) {
  const queryClient = useQueryClient();
  const [messages, setMessages] = useState<AIMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamingMessage, setStreamingMessage] = useState<Partial<AIMessage> | null>(null);
  const [pendingUserMessage, setPendingUserMessage] = useState<string | null>(null);
  const [currentSessionId, setCurrentSessionIdState] = useState<string | null>(null);
  // WD-972: "지금 열린 대화" 를 동기로 읽는 ref. state 는 다음 렌더에야 반영되고 클로저는 낡아, 삭제 응답이
  // 늦게 올 때(그 사이 다른 대화로 옮겼거나 목록 컴포넌트가 사라졌을 때)의 판단에 쓸 수 없다.
  // 그래서 현재 대화를 바꾸는 모든 곳은 이 함수로 state 와 ref 를 함께 바꾼다.
  const currentSessionIdRef = useRef<string | null>(null);
  // WD-972: 기록 불러오기 요청 번호 — 마지막으로 요청한 loadSession(또는 새 대화 전환)만 결과를 반영하게 한다.
  // 같은 대화를 A→B→A 로 다시 열면 세션 id 비교만으로는 먼저 보낸 낡은 응답을 가려내지 못해서 번호로도 판단한다.
  const loadSeqRef = useRef(0);
  const setCurrentSessionId = useCallback((id: string | null) => {
    currentSessionIdRef.current = id;
    setCurrentSessionIdState(id);
  }, []);
  const [contextTokens, setContextTokens] = useState<number | null>(null);
  const [isCompacting, setIsCompacting] = useState(false);
  // 압축 시작 시각 — 대화 영역 진행 표시의 경과 시간 계산용. 미압축 시 null.
  const [compactionStartedAt, setCompactionStartedAt] = useState<number | null>(null);
  // SSE 콜백은 클로저라 state 를 읽으면 stale 이므로, 완료 시점 경과 계산은 ref 로 한다.
  const compactionStartedAtRef = useRef<number | null>(null);
  const [isThinking, setIsThinking] = useState(false);
  const abortControllerRef = useRef<AbortController | null>(null);
  const messagesCommittedRef = useRef(false);
  // 대화 이력 로딩 상태. sendMessage 가 클로저 없이 최신값을 읽도록 state 와 ref 를 함께 갱신한다 (WD-1004).
  const [isLoadingHistory, setIsLoadingHistoryState] = useState(false);
  const isLoadingHistoryRef = useRef(false);
  const setLoadingHistory = useCallback((v: boolean) => {
    isLoadingHistoryRef.current = v;
    setIsLoadingHistoryState(v);
  }, []);
  /**
   * 이력 로딩 중에 들어온 「AI 에게 묻기」 질문 보관함 (WD-1004).
   * 로딩 중 sendMessage 는 글을 버리므로 여기 두었다가, 그 로딩이 끝났을 때 아직 같은 대화가 열려 있으면 한 번 보낸다.
   * 대화를 바꾸는 모든 동작(새 대화·다른 대화 열기·삭제 후 새 대화)이 비우므로 엉뚱한 대화로 가지 않는다. 마지막 질문 하나만 남긴다.
   */
  const pendingAskRef = useRef<{ content: string; sessionId: string | null } | null>(null);
  const streamingContentRef = useRef<Partial<AIMessage> | null>(null);
  // 스트리밍 중단 시 메시지 유실 방지용 — messages[]에 커밋되기 전 userMessage 보관
  const pendingUserMessageObjRef = useRef<AIMessage | null>(null);
  // WD-57: 진행 중인 스트림의 세션 id. 새 세션은 init 이벤트에서야 알게 되는데 stopStreaming 은 그 직후에도
  // 불릴 수 있어 state(다음 렌더에 반영)가 아니라 ref 로 읽는다.
  const streamSessionIdRef = useRef<string | null>(null);
  // WD-57: 보낸 취소 요청. 다음 전송은 이것이 끝난 뒤에 나가야 취소가 새 요청의 실행을 잘못 멈추지 않는다.
  const pendingCancelRef = useRef<Promise<void> | null>(null);

  /**
   * 압축 진행 표시 해제.
   * 스트림이 어떤 경로로 끝나든(완료·오류·중단·스트림 조기 종료·새 턴 시작) 표시가 남으면
   * 대화 영역에 "요약하는 중"이 영원히 돌게 되므로 모든 종료 지점에서 호출한다.
   */
  const clearCompaction = useCallback(() => {
    setIsCompacting(false);
    compactionStartedAtRef.current = null;
    setCompactionStartedAt(null);
  }, []);

  const [isUploading, setIsUploading] = useState(false);

  // options.onCanvasWidget을 ref로 관리하여 sendMessage useCallback에서 stale closure 없이 최신 콜백 참조
  // useCallback 의존성에 options를 추가하면 매 렌더마다 sendMessage가 재생성되므로 ref 패턴 사용
  const onCanvasWidgetRef = useRef(options?.onCanvasWidget);
  useEffect(() => {
    onCanvasWidgetRef.current = options?.onCanvasWidget;
  });

  // 생성된 Blob URL을 추적하여 컴포넌트 언마운트 또는 세션 교체 시 일괄 해제 (메모리 누수 방지)
  const blobUrlsRef = useRef<string[]>([]);

  /** Blob URL을 생성하고 추적 목록에 등록한다. */
  const createTrackedBlobUrl = useCallback((blob: Blob) => {
    const url = URL.createObjectURL(blob);
    blobUrlsRef.current.push(url);
    return url;
  }, []);

  /** 추적 중인 모든 Blob URL을 해제한다. */
  const revokeAllBlobUrls = useCallback(() => {
    blobUrlsRef.current.forEach(url => URL.revokeObjectURL(url));
    blobUrlsRef.current = [];
  }, []);

  // 컴포넌트 언마운트 시 누적된 Blob URL 전체 해제
  useEffect(() => {
    return () => {
      revokeAllBlobUrls();
    };
  }, [revokeAllBlobUrls]);

  // 스트리밍/업로드 중 새로고침·탭 닫기·주소창 직접 이동 시 메시지가 경고 없이
  // 통째로 유실되는 것을 막는다 (WD-56). 패널이 상주하는 AIProvider 전역에서
  // 쓰이므로 인앱 SPA 라우팅(useUnsavedChangesGuard)과 달리 브라우저 레벨
  // 네비게이션만 막으면 충분하다.
  useEffect(() => {
    if (!isStreaming && !isUploading) return;
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [isStreaming, isUploading]);

  /**
   * 보관한 「AI 에게 물어보기」 질문을 버리고 사용자에게 알린다 (WD-1004).
   * 보관할 때 알림 패널은 이미 닫혀 있어 말없이 지우면 질문이 사라진 걸 알 수 없기 때문이다. 보관한 질문이 있을 때만 알린다.
   */
  const discardPendingAsk = useCallback((
    message = '대화가 바뀌어 「AI 에게 물어보기」 질문을 보내지 않았습니다.',
    options?: { silent?: boolean },
  ): boolean => {
    if (!pendingAskRef.current) return false;
    pendingAskRef.current = null;
    // silent(WD-1050): 호출부가 자기 안내 토스트로 합칠 때 알림 없이 비운다
    if (!options?.silent) toast.info(message);
    return true;
  }, []);

  /** 보냈으면 true(스트리밍 시작), 어떤 이유로든 보내지 못했으면 false — 호출한 입력창이 글을 돌려주는 데 쓴다 (WD-1004). */
  const sendMessage = useCallback(async (content: string, files?: File[]): Promise<boolean> => {
    // WD-1004: 이력 응답이 오기 전에 보내면 loadSession 의 setMessages(msgs) 가 방금 보낸 글·답을 덮어쓴다.
    // 그래서 로딩 중에는 보내지 않고 안내만 한다(입력창은 그대로 둔다).
    if (isLoadingHistoryRef.current) {
      toast.info('대화 이력을 불러오는 중입니다. 잠시 후 다시 보내 주세요.');
      return false;
    }
    messagesCommittedRef.current = false;

    // WD-57: 직전에 누른 "응답 중단"의 서버 취소가 아직 가는 중이면 먼저 도착시킨다. 순서가 뒤집히면
    // 늦게 도착한 취소가 이 새 요청의 실행을 멈출 수 있다. (실패해도 진행 — 서버 가드가 겹침을 막는다)
    if (pendingCancelRef.current) {
      await pendingCancelRef.current;
      pendingCancelRef.current = null;
      // WD-1004: 취소를 기다리는 사이 다른 대화를 열거나 새 대화로 바꿨으면, 여기서 보내면 옛 대화로 가거나
      // 새로 불러온 이력에 덮인다 — 보내지 않고 안내만 한다.
      if (isLoadingHistoryRef.current || currentSessionIdRef.current !== currentSessionId) {
        toast.info('대화가 바뀌어 보내지 않았습니다. 다시 보내 주세요.');
        return false;
      }
    }
    streamSessionIdRef.current = currentSessionId;

    // Upload files first if any
    let attachments: AIAttachment[] = [];
    let fileIds: number[] | null = null;

    if (files && files.length > 0) {
      setIsUploading(true);
      try {
        const uploaded = await uploadFiles(files);
        fileIds = uploaded.map(f => f.id);
        attachments = uploaded.map(f => ({
          id: f.id,
          name: f.originalName,
          mimeType: f.mimeType,
          fileSize: f.fileSize,
          category: f.fileCategory,
          previewUrl: f.mimeType.startsWith('image/')
            ? createTrackedBlobUrl(files.find(file => file.name === f.originalName) ?? files[0])
            : undefined,
        }));
      } catch {
        toast.error('파일 업로드에 실패했습니다.');
        setIsUploading(false);
        return false;
      } finally {
        setIsUploading(false);
      }
    }

    const userMessage: AIMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      content,
      timestamp: new Date().toISOString(),
      attachments: attachments.length > 0 ? attachments : undefined,
    };

    let userMessageCommitted = false;

    const assistantId = `assistant-${Date.now()}`;
    const assistantTimestamp = new Date().toISOString();

    pendingUserMessageObjRef.current = userMessage;
    setPendingUserMessage(content);
    setIsStreaming(true);
    setIsThinking(true);
    // 이전 턴이 비정상 종료해 압축 표시가 남았더라도 새 턴에서 스스로 회복한다
    clearCompaction();
    streamingContentRef.current = {
      id: assistantId,
      role: 'assistant',
      content: '',
      timestamp: assistantTimestamp,
    };
    setStreamingMessage(streamingContentRef.current);

    const commitTurn = () => {
      if (messagesCommittedRef.current) return;
      const current = streamingContentRef.current;
      if (!current?.content && (!current?.toolCalls || current.toolCalls.length === 0)) return;

      const turnMsg: AIMessage = {
        id: current.id || `assistant-turn-${Date.now()}`,
        role: 'assistant',
        content: current.content || '',
        toolCalls: current.toolCalls,
        contentBlocks: current.contentBlocks,
        timestamp: current.timestamp || new Date().toISOString(),
      };

      if (!userMessageCommitted) {
        userMessageCommitted = true;
        setMessages(prev => [...prev, userMessage, turnMsg]);
        setPendingUserMessage(null);
        pendingUserMessageObjRef.current = null;
      } else {
        setMessages(prev => [...prev, turnMsg]);
      }

      // Start new streaming message for next turn
      const newId = `assistant-${Date.now()}`;
      streamingContentRef.current = {
        id: newId,
        role: 'assistant',
        content: '',
        timestamp: new Date().toISOString(),
      };
      setStreamingMessage({ ...streamingContentRef.current });
      setIsThinking(true);
    };

    const commitMessages = () => {
      if (messagesCommittedRef.current) return;
      messagesCommittedRef.current = true;
      const current = streamingContentRef.current;
      const assistantMsg: AIMessage | null = (current?.content || (current?.toolCalls && current.toolCalls.length > 0)) ? {
        id: current.id || assistantId,
        role: 'assistant',
        content: current.content || '',
        toolCalls: current.toolCalls,
        contentBlocks: current.contentBlocks,
        timestamp: current.timestamp || assistantTimestamp,
      } : null;

      if (!userMessageCommitted) {
        setMessages(prev =>
          assistantMsg ? [...prev, userMessage, assistantMsg] : [...prev, userMessage]
        );
      } else if (assistantMsg) {
        setMessages(prev => [...prev, assistantMsg]);
      }

      setStreamingMessage(null);
      setPendingUserMessage(null);
      pendingUserMessageObjRef.current = null;
      setIsStreaming(false);
      setIsThinking(false);
      clearCompaction();
      streamingContentRef.current = null;
    };

    // 새 세션일 때만 네비게이션 컨텍스트 전달
    const navContext = !currentSessionId ? buildNavigationContext() : undefined;
    // 매 메시지마다 현재 화면 컨텍스트 전달 (useLocation 대신 직접 읽어 불필요한 리렌더 방지)
    // 상세 화면이면 캐시에 있는 이름을 붙여 에이전트가 질문과 화면 대상의 관련성을 판단하게 한다 (WD-759)
    const screen = buildScreenContext(window.location.pathname, createCachedScreenNameLookup(queryClient));

    abortControllerRef.current = streamAIChat(
      content,
      currentSessionId,
      fileIds,
      (event: AIStreamEvent) => {
        switch (event.type) {
          case 'init':
            if (event.sessionId) {
              streamSessionIdRef.current = event.sessionId;
              const isNewSession = !currentSessionId;
              setCurrentSessionId(event.sessionId);
              if (isNewSession) {
                const title = content ? buildSessionTitle(content) : '파일 첨부';
                aiApi.createSession({ sessionId: event.sessionId, title })
                  .then(() => queryClient.invalidateQueries({ queryKey: ['ai-sessions'] }))
                  .catch(err => {
                    // 세션 "제목 저장"만 실패하는 게 아니라 세션 row 자체가 생성되지 않을 수 있어
                    // (WD-58) 채팅 흐름은 유지하되 콘솔에 남겨 조기에 발견할 수 있게 한다.
                    console.warn('[AI] createSession 실패 — 세션이 영속화되지 않았을 수 있습니다', err);
                  });
              }
            }
            break;
          case 'text': {
            setIsThinking(false);
            if (streamingContentRef.current) {
              const blocks = streamingContentRef.current.contentBlocks || [];
              const lastBlock = blocks[blocks.length - 1];
              // 새 text 블록 생성 시 현재 content 길이를 시작 오프셋으로 기록
              const textStart = (streamingContentRef.current.content || '').length;
              streamingContentRef.current = {
                ...streamingContentRef.current,
                content: (streamingContentRef.current.content || '') + (event.content || ''),
                contentBlocks: lastBlock?.type === 'text'
                  ? blocks
                  : [...blocks, { type: 'text' as const, textStart }],
              };
              setStreamingMessage({ ...streamingContentRef.current });
            }
            break;
          }
          case 'tool_use': {
            setIsThinking(true);
            if (streamingContentRef.current) {
              const newToolCalls = [
                ...(streamingContentRef.current.toolCalls || []),
                { name: event.toolName || '', input: event.input || {} },
              ];
              streamingContentRef.current = {
                ...streamingContentRef.current,
                toolCalls: newToolCalls,
                contentBlocks: [
                  ...(streamingContentRef.current.contentBlocks || []),
                  { type: 'tool_use' as const, toolCallIndex: newToolCalls.length - 1 },
                ],
              };
              setStreamingMessage({ ...streamingContentRef.current });
            }
            break;
          }
          case 'tool_result':
            if (streamingContentRef.current) {
              // 이 clone이 undefined를 []로 바꾸는 유일한 지점이다.
              // 선행 tool_use 없이 tool_result가 오면 []가 상태에 남고,
              // 렌더 조건에서 length(0)가 그대로 화면에 찍히므로 비어 있으면 상태를 건드리지 않는다.
              const toolCalls = [...(streamingContentRef.current.toolCalls || [])];
              if (toolCalls.length > 0) {
                const lastTool = toolCalls[toolCalls.length - 1];
                lastTool.result = event.result;
                // isError 전달: MCP 도구 실패 시 UI에서 실패 상태를 표시하기 위해 보존
                if (event.isError !== undefined) {
                  lastTool.isError = event.isError;
                }

                // Canvas widget placement: fire callback at tool_result time (idempotent, no useEffect)
                // Fires for ALL tool results — AIProvider decides whether to place on canvas based on mode
                // onCanvasWidgetRef.current 사용: 최신 콜백을 참조 (stale closure 방지)
                if (onCanvasWidgetRef.current) {
                  const canvasLayout = lastTool.input?.canvas as CanvasLayout | undefined;
                  onCanvasWidgetRef.current({
                    id: lastTool.id || `tc-${lastTool.name}-${toolCalls.length}`,
                    toolName: lastTool.name,
                    input: lastTool.input || {},
                    canvas: canvasLayout,
                  });
                }

                // Auto-invalidate TanStack Query cache
                // 도구 입력도 넘긴다 — 입력의 작업 id 로 그 작업의 캐시만 고르는 항목이 있다(WD-667)
                const keys = getInvalidationKeys(lastTool.name, lastTool.input);
                for (const key of keys) {
                  queryClient.invalidateQueries({ queryKey: key });
                }

                streamingContentRef.current = { ...streamingContentRef.current, toolCalls };
                setStreamingMessage({ ...streamingContentRef.current });
              }
            }
            break;
          case 'turn':
            commitTurn();
            break;
          case 'compaction':
            if (event.status === 'started') {
              setIsCompacting(true);
              compactionStartedAtRef.current = Date.now();
              setCompactionStartedAt(compactionStartedAtRef.current);
            } else if (event.status === 'completed') {
              // After compaction, token count resets significantly
              if (typeof event.preTokens === 'number') {
                setContextTokens(null); // Will be updated on next done event
              }
              // 완료 메시지에 "무엇을 얼마나 오래" 요약했는지 남긴다 —
              // 진행 표시가 사라진 뒤에도 95초의 공백이 무엇이었는지 사후에 알 수 있어야 한다.
              const startedAt = compactionStartedAtRef.current;
              const elapsedSec = startedAt != null ? Math.floor((Date.now() - startedAt) / 1000) : null;
              const detail = [
                typeof event.preTokens === 'number' ? `${event.preTokens.toLocaleString()} 토큰` : null,
                elapsedSec !== null ? `${elapsedSec}초 소요` : null,
              ].filter(Boolean).join(', ');
              clearCompaction();
              setMessages(prev => [...prev, {
                id: `system-compaction-${Date.now()}`,
                role: 'system' as const,
                content: detail
                  ? `컨텍스트가 길어져 자동으로 요약되었습니다. (${detail})`
                  : '컨텍스트가 길어져 자동으로 요약되었습니다.',
                timestamp: new Date().toISOString(),
              }]);
            }
            break;
          case 'done':
            if (typeof event.inputTokens === 'number') {
              setContextTokens(event.inputTokens);
            }
            clearCompaction();
            commitMessages();
            break;
          case 'error': {
            console.error('AI stream error:', event.message);
            if (typeof event.inputTokens === 'number') {
              setContextTokens(event.inputTokens);
            }
            // WD-57: 같은 세션의 이전 응답이 아직 처리 중이라 서버가 거절했다. 이 메시지는 처리되지 않았으므로
            // 보낸 대화로 남기지 않고(새로고침하면 없는 턴이 된다) 안내만 띄운다.
            if (event.code === 'session_busy') {
              messagesCommittedRef.current = true;
              toast.error(event.message || '이전 응답을 처리 중입니다. 잠시 후 다시 시도해 주세요.');
              pendingUserMessageObjRef.current = null;
            } else if (!messagesCommittedRef.current) {
              messagesCommittedRef.current = true;
              const isMaxTurns = event.message === 'max_turns_exceeded';
              const errorMsg = isMaxTurns
                ? '대화 턴 수가 초과되었습니다. 이어서 대화하시려면 메시지를 보내주세요.'
                : (event.message || '알 수 없는 오류가 발생했습니다');
              setMessages(prev => [...prev, userMessage, {
                id: `error-${Date.now()}`,
                role: 'assistant' as const,
                content: isMaxTurns ? errorMsg : `오류: ${errorMsg}`,
                timestamp: new Date().toISOString(),
              }]);
            }
            setIsStreaming(false);
            setIsThinking(false);
            // 압축 도중 오류로 끊기면 진행 표시가 영구히 남으므로 함께 해제
            clearCompaction();
            setStreamingMessage(null);
            setPendingUserMessage(null);
            streamingContentRef.current = null;
            break;
          }
        }
      },
      (error) => {
        console.error('AI stream error:', error);
        // fetch() 자체가 실패했거나(오프라인/DNS/연결 실패 등) HTTP 오류로 거절돼 SSE 스트림이 열리지 못한 경우.
        // 서버가 보낸 'error' 이벤트 분기(위 case 'error')와 동일하게, 낙관적으로만 표시되던
        // userMessage를 messages에 커밋하고 에러 안내를 함께 추가해 메시지가 소리소문 없이
        // 사라지지 않도록 한다.
        // 서버가 HTTP 오류로 거절한 경우(WD-550)엔 네트워크 문구 대신 서버가 준 문구(검증 실패 등)를 보여 준다.
        // 문구가 없으면 상태 코드라도 알려 네트워크 장애로 오인하지 않게 한다.
        const errorContent = error instanceof AIChatHttpError
          ? `오류: ${error.serverMessage ?? `요청을 처리하지 못했습니다 (HTTP ${error.status})`}`
          : '오류: 네트워크 연결에 실패했습니다. 다시 시도해주세요.';
        if (!messagesCommittedRef.current) {
          messagesCommittedRef.current = true;
          setMessages(prev => [...prev, userMessage, {
            id: `error-${Date.now()}`,
            role: 'assistant' as const,
            content: errorContent,
            timestamp: new Date().toISOString(),
          }]);
        }
        setIsStreaming(false);
        setIsThinking(false);
        clearCompaction();
        setStreamingMessage(null);
        setPendingUserMessage(null);
        streamingContentRef.current = null;
      },
      () => {
        // Stream ended - commit if not already done
        commitMessages();
      },
      navContext,
      screen,
    );
    return true;
  }, [currentSessionId, queryClient, createTrackedBlobUrl, clearCompaction, setCurrentSessionId]);

  const stopStreaming = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
      // WD-57: 브라우저 연결만 끊으면 서버는 작업을 계속한다(의도된 설계) — 서버 실행도 명시적으로 취소한다.
      // 실패해도 화면의 중단은 그대로 둔다. 새 세션의 init 전이면 세션 id 를 몰라 취소할 수 없다.
      const sessionId = streamSessionIdRef.current;
      if (sessionId) {
        pendingCancelRef.current = aiApi.cancelChat(sessionId).then(
          () => undefined,
          (err) => console.warn('[AI] 서버 응답 취소 실패 — 화면에서만 중단됩니다', err),
        );
      }
    }
    // abort 직후 플래그를 세워 onEnd 콜백의 이중 커밋 방지
    messagesCommittedRef.current = true;
    setIsStreaming(false);
    setIsThinking(false);
    // 사용자가 중단하면 압축 진행 표시도 즉시 걷는다
    clearCompaction();

    // pendingUserMessage가 아직 messages[]에 커밋되지 않은 상태라면 보존
    const pendingMsg = pendingUserMessageObjRef.current;
    if (pendingMsg) {
      const partial = streamingContentRef.current;
      setMessages(prev => {
        const msgs: AIMessage[] = [...prev, pendingMsg];
        // content가 없어도 toolCalls가 있으면 어시스턴트 메시지 보존 (commitMessages와 동일 조건)
        if (partial?.content || (partial?.toolCalls && partial.toolCalls.length > 0)) {
          msgs.push({
            id: partial.id || `assistant-${Date.now()}`,
            role: 'assistant',
            content: partial.content || '',
            toolCalls: partial.toolCalls,
            contentBlocks: partial.contentBlocks,
            timestamp: partial.timestamp || new Date().toISOString(),
          });
        }
        return msgs;
      });
      pendingUserMessageObjRef.current = null;
    }

    setStreamingMessage(null);
    setPendingUserMessage(null);
  }, [clearCompaction]);

  const startNewSession = useCallback(() => {
    // 스트리밍 중 새 세션 시작 시 기존 SSE 스트림을 취소하여 상태 오염 방지
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setMessages([]);
    setCurrentSessionId(null);
    setStreamingMessage(null);
    setPendingUserMessage(null);
    pendingUserMessageObjRef.current = null;
    setIsStreaming(false);
    setIsThinking(false);
    // 진행 중이던 기록 요청을 낡은 것으로 만든다(번호 증가) — 그 응답은 버려진다
    loadSeqRef.current += 1;
    // 다른 대화로 가는 것이므로 이전 대화용으로 보관한 질문은 버리고 알린다 (WD-1004)
    discardPendingAsk();
    // 로딩 중이던 기록 요청은 loadSession 이 버리고(로딩 표시도 건드리지 않음) 여기서 로딩 표시를 끈다 (WD-972)
    setLoadingHistory(false);
    setContextTokens(null);
    clearCompaction();
  }, [clearCompaction, setCurrentSessionId, setLoadingHistory, discardPendingAsk]);


  const loadSession = useCallback(async (sessionId: string) => {
    // 스트리밍 중 다른 세션으로 전환 시 기존 SSE 스트림을 취소하여 새 세션 상태 오염 방지
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    // 이 요청의 번호 — 응답 때 최신 번호와 다르면 낡은 응답이다
    const seq = ++loadSeqRef.current;
    // 새 로딩이 시작되면 이전 로딩 때 보관한 질문은 버리고 알린다. 단 같은 대화를 다시 여는 것이면 그 대화용 질문이므로 유지한다 (WD-1004)
    if (pendingAskRef.current?.sessionId !== sessionId) discardPendingAsk();
    const isStale = () => loadSeqRef.current !== seq || currentSessionIdRef.current !== sessionId;
    setLoadingHistory(true);
    setCurrentSessionId(sessionId);
    setMessages([]);
    setStreamingMessage(null);
    setPendingUserMessage(null);
    pendingUserMessageObjRef.current = null;
    setIsStreaming(false);
    setIsThinking(false);
    setContextTokens(null);
    clearCompaction();
    try {
      const response = await aiApi.getSessionMessages(sessionId);
      // 히스토리의 이미지 첨부에 인증된 blob URL 생성 (img 태그는 JWT 헤더를 보낼 수 없으므로)
      const msgs = response.data as AIMessage[];
      // WD-972: 기록을 기다리는 사이 다른 대화로 옮겼거나(지운 뒤 새 대화 포함) 새 요청이 있으면 늦게 온 기록을 버린다
      if (isStale()) return;
      // 글 이력은 바로 보여 주고 로딩을 푼다 — 이미지 내려받기가 보내기를 막지 않게 한다 (WD-1004)
      setMessages(msgs);
      setLoadingHistory(false);
      // 이미지 blob 은 배경에서 받아, 도착할 때마다 함수형 갱신으로 해당 첨부에만 previewUrl 을 넣는다.
      // (setMessages(msgs) 로 덮으면 그 사이 사용자가 보낸 새 글이 사라지므로 덮지 않는다)
      for (const msg of msgs) {
        if (!msg.attachments?.length) continue;
        for (const att of msg.attachments) {
          if (!att.mimeType.startsWith('image/')) continue;
          void apiClient.get(`/files/${att.id}/content`, { responseType: 'blob' })
            .then((res) => {
              // 그 사이 다른 대화로 옮겼으면 버린다
              if (isStale()) return;
              const url = createTrackedBlobUrl(res.data as Blob);
              setMessages((prev) => prev.map((m) => (
                m.id !== msg.id ? m : {
                  ...m,
                  attachments: m.attachments?.map((a) => (a.id === att.id ? { ...a, previewUrl: url } : a)),
                }
              )));
            }).catch(() => {});
        }
      }
    } catch (error) {
      if (isStale()) return;
      // 세션 히스토리 로드 실패 시 사용자에게 에러 피드백 제공
      console.error('Failed to load session history:', error);
      if (isClientError(error)) {
        // WD-1050: 보관 질문은 알림 없이 비우고 안내 토스트 하나로 합친다(기본 discardPendingAsk 는 토스트를 또 띄우므로 silent).
        const hadPending = discardPendingAsk(undefined, { silent: true });
        const gone = isNotFoundError(error) || (axios.isAxiosError(error) && error.response?.status === 410);
        // 권한 없음·없어진 대화는 다시 해도 같은 결과라 그 대화 선택을 풀고 새 대화로 시작한다.
        const unselect = gone || isForbiddenError(error);
        const base = hadPending
          ? '대화 이력을 불러오지 못해 「AI 에게 물어보기」 질문을 보내지 않았습니다.'
          : '대화 이력을 불러오지 못했습니다.';
        toast.error(unselect ? `${base} 새 대화로 시작합니다.` : base);
        if (unselect) {
          // startNewSession 이 스트림 ref·로딩·압축까지 함께 정리한다(보관 질문은 위에서 이미 비웠다).
          startNewSession();
          // 404·410 은 그 대화가 없어진 것이므로 목록을 다시 받게 한다
          if (gone) void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
        } else {
          // 그 밖의 4xx(400·409 등)는 대화를 유지한다
          setMessages([]);
        }
      } else {
        // 5xx·네트워크 오류는 일시 장애일 수 있어 대화를 유지하고 보관 질문도 지금처럼 보낸다
        toast.error('대화 이력을 불러오지 못했습니다.');
        setMessages([]); // 빈 상태 명시적 처리
      }
    } finally {
      // 버려진(다른 대화로 옮긴) 응답은 로딩 표시를 끄지 않는다 — 옮긴 대화가 아직 로딩 중일 때 빈 상태가 깜빡이지 않게 (WD-972)
      if (!isStale()) setLoadingHistory(false);
    }
  }, [createTrackedBlobUrl, clearCompaction, setCurrentSessionId, setLoadingHistory, discardPendingAsk, startNewSession, queryClient]);

  /**
   * 「AI 에게 묻기」용 전송 (WD-1004). 이력 로딩 중이 아니면 바로 보내고, 로딩 중이면 지금 불러오는 대화에 묶어 보관한다.
   * 보관한 질문은 아래 effect 가 로딩이 끝났을 때(성공·실패 모두) 그 대화가 아직 열려 있을 때만 보낸다.
   */
  const sendAfterHistoryLoaded = useCallback((content: string) => {
    if (!isLoadingHistoryRef.current) {
      void sendMessage(content);
      return;
    }
    // 이미 보관한 질문이 있으면 새 질문이 그것을 대신한다 — 말없이 바뀌지 않게 알린다
    discardPendingAsk('앞서 보관한 「AI 에게 물어보기」 질문 대신 새 질문을 보냅니다.');
    pendingAskRef.current = { content, sessionId: currentSessionIdRef.current };
  }, [sendMessage, discardPendingAsk]);

  // 이력 로딩이 끝나면 보관한 질문을 보낸다. effect 에서 보내는 이유: 렌더가 끝난 뒤의 sendMessage 가 새로 열린 대화 id 를 알고 있다.
  // 보낸 대화가 아직 그 대화일 때만 보내고, 어떤 경우든 비워 두 번 보내지 않는다.
  useEffect(() => {
    if (isLoadingHistory) return;
    const pending = pendingAskRef.current;
    if (!pending) return;
    if (pending.sessionId !== currentSessionIdRef.current) {
      // 로딩이 끝났는데 다른 대화가 열려 있으면 버리고 알린다 (WD-1004)
      discardPendingAsk();
      return;
    }
    pendingAskRef.current = null;
    void sendMessage(pending.content);
  }, [isLoadingHistory, sendMessage, discardPendingAsk]);

  /**
   * 대화 삭제가 성공한 뒤 호출한다 (WD-972). 지운 것이 ref 기준 지금 열린 대화일 때만 새 대화로 바꾸고 true 를 돌려준다.
   * 스트리밍 중이면 브라우저 연결만 끊지 않고 stopStreaming 으로 서버 실행까지 취소한다(WD-57).
   */
  const handleSessionDeleted = useCallback((sessionId: string) => {
    if (sessionId !== currentSessionIdRef.current) return false;
    // 스트리밍 중이 아니면 stopStreaming 이 아무것도 취소하지 않는다(내부에서 같은 조건을 본다)
    stopStreaming();
    startNewSession();
    return true;
  }, [stopStreaming, startNewSession]);

  return {
    messages,
    isStreaming,
    isThinking,
    isUploading,
    isLoadingHistory,
    isCompacting,
    compactionStartedAt,
    streamingMessage,
    pendingUserMessage,
    currentSessionId,
    // 동기로 바뀌는 현재 대화 ref(읽기 전용) — 제공자가 초안을 지금 대화에 묶을 때 쓴다(WD-1050)
    currentSessionIdRef: currentSessionIdRef as RefObject<string | null>,
    contextTokens,
    sendMessage,
    sendAfterHistoryLoaded,
    stopStreaming,
    startNewSession,
    loadSession,
    handleSessionDeleted,
  };
}
