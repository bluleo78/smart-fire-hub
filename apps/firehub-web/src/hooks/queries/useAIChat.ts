import { useState, useCallback, useRef } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { aiApi, streamAIChat } from '../../api/ai';
import type { AIMessage, AIStreamEvent } from '../../types/ai';

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
  const abortControllerRef = useRef<AbortController | null>(null);
  const messagesCommittedRef = useRef(false);
  const streamingContentRef = useRef<Partial<AIMessage> | null>(null);

  const sendMessage = useCallback((content: string) => {
    messagesCommittedRef.current = false;
    const userMessage: AIMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      content,
      timestamp: new Date().toISOString(),
    };

    const assistantId = `assistant-${Date.now()}`;
    const assistantTimestamp = new Date().toISOString();

    setPendingUserMessage(content);
    setIsStreaming(true);
    streamingContentRef.current = {
      id: assistantId,
      role: 'assistant',
      content: '',
      timestamp: assistantTimestamp,
    };
    setStreamingMessage(streamingContentRef.current);

    const commitMessages = () => {
      if (messagesCommittedRef.current) return;
      messagesCommittedRef.current = true;
      const current = streamingContentRef.current;
      const assistantMsg: AIMessage | null = current?.content ? {
        id: current.id || assistantId,
        role: 'assistant',
        content: current.content,
        toolCalls: current.toolCalls,
        timestamp: current.timestamp || assistantTimestamp,
      } : null;
      setMessages(prev =>
        assistantMsg ? [...prev, userMessage, assistantMsg] : [...prev, userMessage]
      );
      setStreamingMessage(null);
      setPendingUserMessage(null);
      setIsStreaming(false);
      streamingContentRef.current = null;
    };

    abortControllerRef.current = streamAIChat(
      content,
      currentSessionId,
      (event: AIStreamEvent) => {
        switch (event.type) {
          case 'init':
            if (event.sessionId) {
              setCurrentSessionId(event.sessionId);
              // 새 세션일 때만 DB 저장 (기존 세션 재개 시 currentSessionId가 이미 있으므로 skip)
              if (!currentSessionId) {
                aiApi.createSession({ sessionId: event.sessionId, title: content })
                  .then(() => queryClient.invalidateQueries({ queryKey: ['ai-sessions'] }))
                  .catch(() => {}); // 실패해도 채팅 흐름 유지
              }
            }
            break;
          case 'text':
            if (streamingContentRef.current) {
              streamingContentRef.current = {
                ...streamingContentRef.current,
                content: (streamingContentRef.current.content || '') + (event.content || ''),
              };
            }
            setStreamingMessage(prev => ({
              ...prev,
              content: (prev?.content || '') + (event.content || ''),
            }));
            break;
          case 'tool_use':
            if (streamingContentRef.current) {
              streamingContentRef.current = {
                ...streamingContentRef.current,
                toolCalls: [
                  ...(streamingContentRef.current.toolCalls || []),
                  { name: event.toolName || '', input: event.input || {} },
                ],
              };
            }
            setStreamingMessage(prev => ({
              ...prev,
              toolCalls: [
                ...(prev?.toolCalls || []),
                { name: event.toolName || '', input: event.input || {} },
              ],
            }));
            break;
          case 'tool_result':
            if (streamingContentRef.current) {
              const toolCalls = [...(streamingContentRef.current.toolCalls || [])];
              if (toolCalls.length > 0) {
                toolCalls[toolCalls.length - 1].result = event.result;
              }
              streamingContentRef.current = { ...streamingContentRef.current, toolCalls };
            }
            setStreamingMessage(prev => {
              const toolCalls = [...(prev?.toolCalls || [])];
              if (toolCalls.length > 0) {
                toolCalls[toolCalls.length - 1].result = event.result;
              }
              return { ...prev, toolCalls };
            });
            break;
          case 'done':
            commitMessages();
            break;
          case 'error': {
            console.error('AI stream error:', event.message);
            if (!messagesCommittedRef.current) {
              messagesCommittedRef.current = true;
              const errorMsg = event.message || '알 수 없는 오류가 발생했습니다';
              setMessages(prev => [...prev, userMessage, {
                id: `error-${Date.now()}`,
                role: 'assistant' as const,
                content: `오류: ${errorMsg}`,
                timestamp: new Date().toISOString(),
              }]);
            }
            setIsStreaming(false);
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
        setStreamingMessage(null);
        setPendingUserMessage(null);
        streamingContentRef.current = null;
      },
      () => {
        // Stream ended - commit if not already done
        commitMessages();
      }
    );
  }, [currentSessionId, queryClient]);

  const stopStreaming = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setIsStreaming(false);
    setStreamingMessage(null);
    setPendingUserMessage(null);
  }, []);

  const startNewSession = useCallback(() => {
    setMessages([]);
    setCurrentSessionId(null);
    setStreamingMessage(null);
    setPendingUserMessage(null);
    setIsStreaming(false);
  }, []);

  const [isLoadingHistory, setIsLoadingHistory] = useState(false);

  const loadSession = useCallback(async (sessionId: string) => {
    setIsLoadingHistory(true);
    setCurrentSessionId(sessionId);
    setMessages([]);
    setStreamingMessage(null);
    setPendingUserMessage(null);
    setIsStreaming(false);
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
    isLoadingHistory,
    streamingMessage,
    pendingUserMessage,
    currentSessionId,
    sendMessage,
    stopStreaming,
    startNewSession,
    loadSession,
  };
}
