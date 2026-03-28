import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useRef,useState } from 'react';
import { toast } from 'sonner';

import { aiApi, streamAIChat } from '../../api/ai';
import { getInvalidationKeys } from '../../components/ai/widgets/invalidationMap';
import { uploadFiles } from '../../api/files';
import type { AIAttachment, AIMessage, AIStreamEvent } from '../../types/ai';

export function useAISessions(page = 0, size = 20) {
  return useQuery({
    queryKey: ['ai-sessions', page, size],
    queryFn: () => aiApi.getSessions({ page, size }).then(r => r.data),
  });
}

export function useDeleteAISession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: aiApi.deleteSession,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
    },
  });
}

export function useAIChat() {
  const queryClient = useQueryClient();
  const [messages, setMessages] = useState<AIMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamingMessage, setStreamingMessage] = useState<Partial<AIMessage> | null>(null);
  const [pendingUserMessage, setPendingUserMessage] = useState<string | null>(null);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [contextTokens, setContextTokens] = useState<number | null>(null);
  const [isCompacting, setIsCompacting] = useState(false);
  const [isThinking, setIsThinking] = useState(false);
  const abortControllerRef = useRef<AbortController | null>(null);
  const messagesCommittedRef = useRef(false);
  const streamingContentRef = useRef<Partial<AIMessage> | null>(null);

  const [isUploading, setIsUploading] = useState(false);

  const sendMessage = useCallback(async (content: string, files?: File[]) => {
    messagesCommittedRef.current = false;

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
            ? URL.createObjectURL(files.find(file => file.name === f.originalName) ?? files[0])
            : undefined,
        }));
      } catch {
        toast.error('파일 업로드에 실패했습니다.');
        setIsUploading(false);
        return;
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

    setPendingUserMessage(content);
    setIsStreaming(true);
    setIsThinking(true);
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
      setStreamingMessage(streamingContentRef.current);
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
      setIsStreaming(false);
      setIsThinking(false);
      streamingContentRef.current = null;
    };

    abortControllerRef.current = streamAIChat(
      content,
      currentSessionId,
      fileIds,
      (event: AIStreamEvent) => {
        switch (event.type) {
          case 'init':
            if (event.sessionId) {
              const isNewSession = !currentSessionId;
              setCurrentSessionId(event.sessionId);
              if (isNewSession) {
                aiApi.createSession({ sessionId: event.sessionId, title: content || '파일 첨부' })
                  .then(() => queryClient.invalidateQueries({ queryKey: ['ai-sessions'] }))
                  .catch(() => {}); // 실패해도 채팅 흐름 유지
              }
            }
            break;
          case 'text': {
            setIsThinking(false);
            if (streamingContentRef.current) {
              const blocks = streamingContentRef.current.contentBlocks || [];
              const lastBlock = blocks[blocks.length - 1];
              streamingContentRef.current = {
                ...streamingContentRef.current,
                content: (streamingContentRef.current.content || '') + (event.content || ''),
                contentBlocks: lastBlock?.type === 'text'
                  ? blocks
                  : [...blocks, { type: 'text' }],
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
              const toolCalls = [...(streamingContentRef.current.toolCalls || [])];
              if (toolCalls.length > 0) {
                const lastTool = toolCalls[toolCalls.length - 1];
                lastTool.result = event.result;

                // Auto-invalidate TanStack Query cache
                const keys = getInvalidationKeys(lastTool.name);
                for (const key of keys) {
                  queryClient.invalidateQueries({ queryKey: key });
                }
              }
              streamingContentRef.current = { ...streamingContentRef.current, toolCalls };
              setStreamingMessage({ ...streamingContentRef.current });
            }
            break;
          case 'turn':
            commitTurn();
            break;
          case 'compaction':
            if (event.status === 'started') {
              setIsCompacting(true);
            } else if (event.status === 'completed') {
              setIsCompacting(false);
              // After compaction, token count resets significantly
              if (typeof event.preTokens === 'number') {
                setContextTokens(null); // Will be updated on next done event
              }
              setMessages(prev => [...prev, {
                id: `system-compaction-${Date.now()}`,
                role: 'system' as const,
                content: '컨텍스트가 길어져 자동으로 요약되었습니다.',
                timestamp: new Date().toISOString(),
              }]);
            }
            break;
          case 'done':
            if (typeof event.inputTokens === 'number') {
              setContextTokens(event.inputTokens);
            }
            setIsCompacting(false);
            commitMessages();
            break;
          case 'error': {
            console.error('AI stream error:', event.message);
            if (typeof event.inputTokens === 'number') {
              setContextTokens(event.inputTokens);
            }
            if (!messagesCommittedRef.current) {
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
            setStreamingMessage(null);
            setPendingUserMessage(null);
            streamingContentRef.current = null;
            break;
          }
        }
      },
      (error) => {
        console.error('AI stream error:', error);
        setIsStreaming(false);
        setIsThinking(false);
        setStreamingMessage(null);
        setPendingUserMessage(null);
        streamingContentRef.current = null;
      },
      () => {
        // Stream ended - commit if not already done
        commitMessages();
      }
    );
  }, [currentSessionId, queryClient]); // eslint-disable-line react-hooks/exhaustive-deps

  const stopStreaming = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setIsStreaming(false);
    setIsThinking(false);
    setStreamingMessage(null);
    setPendingUserMessage(null);
  }, []);

  const startNewSession = useCallback(() => {
    setMessages([]);
    setCurrentSessionId(null);
    setStreamingMessage(null);
    setPendingUserMessage(null);
    setIsStreaming(false);
    setIsThinking(false);
    setContextTokens(null);
    setIsCompacting(false);
  }, []);

  const [isLoadingHistory, setIsLoadingHistory] = useState(false);

  const loadSession = useCallback(async (sessionId: string) => {
    setIsLoadingHistory(true);
    setCurrentSessionId(sessionId);
    setMessages([]);
    setStreamingMessage(null);
    setPendingUserMessage(null);
    setIsStreaming(false);
    setIsThinking(false);
    setContextTokens(null);
    setIsCompacting(false);
    try {
      const response = await aiApi.getSessionMessages(sessionId);
      setMessages(response.data);
    } catch (error) {
      console.error('Failed to load session history:', error);
    } finally {
      setIsLoadingHistory(false);
    }
  }, []);

  return {
    messages,
    isStreaming,
    isThinking,
    isUploading,
    isLoadingHistory,
    isCompacting,
    streamingMessage,
    pendingUserMessage,
    currentSessionId,
    contextTokens,
    sendMessage,
    stopStreaming,
    startNewSession,
    loadSession,
  };
}
