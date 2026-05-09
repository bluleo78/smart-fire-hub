import { MessageSquare, Plus, Trash2 } from 'lucide-react';

import { useAISessions, useDeleteAISession } from '../../hooks/queries/useAIChat';
import { cn } from '../../lib/utils';
import { Button } from '../ui/button';
import { DeleteConfirmDialog } from '../ui/delete-confirm-dialog';
import { ScrollArea } from '../ui/scroll-area';
import { useAI } from './AIProvider';

export function AISessionSidebar() {
  const { currentSessionId, startNewSession, loadSession } = useAI();
  const { data: sessions } = useAISessions();
  const deleteSession = useDeleteAISession();

  /**
   * 세션 삭제 실행 — DeleteConfirmDialog 확인 후 호출됨 (#210)
   * 현재 활성 세션을 삭제하는 경우 startNewSession()으로 채팅 UI 초기화
   */
  const handleDeleteConfirmed = (sessionId: string, id: number) => {
    const isActive = sessionId === currentSessionId;
    deleteSession.mutate(id, {
      onSuccess: () => {
        if (isActive) startNewSession();
      },
    });
  };

  return (
    <div className="flex flex-col h-full w-64 border-r bg-muted/30">
      {/* New Chat Button */}
      <div className="p-3 border-b">
        <Button
          variant="outline"
          className="w-full justify-start gap-2 h-9 text-sm"
          onClick={startNewSession}
        >
          <Plus className="h-4 w-4" />
          새 대화
        </Button>
      </div>

      {/* Session List */}
      <ScrollArea className="flex-1">
        <div className="p-2 space-y-0.5">
          {sessions && sessions.length > 0 ? (
            sessions.map((session) => (
              <div
                key={session.id}
                className={cn(
                  'group flex items-center gap-2 rounded-md px-2.5 py-2 text-sm cursor-pointer transition-colors',
                  session.sessionId === currentSessionId
                    ? 'bg-accent text-accent-foreground'
                    : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'
                )}
                onClick={() => loadSession(session.sessionId)}
              >
                <MessageSquare className="h-3.5 w-3.5 shrink-0" />
                <span className="flex-1 truncate text-xs">
                  {session.title || `대화 #${session.id}`}
                </span>
                {/* 삭제 전 확인 다이얼로그 — 즉시 삭제 방지 (#210) */}
                <DeleteConfirmDialog
                  entityName="대화"
                  itemName={session.title || `대화 #${session.id}`}
                  onConfirm={() => handleDeleteConfirmed(session.sessionId, session.id)}
                  trigger={
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-6 w-6 shrink-0 opacity-0 group-hover:opacity-60 hover:!opacity-100"
                      onClick={(e) => e.stopPropagation()}
                      aria-label={`${session.title || `대화 #${session.id}`} 삭제`}
                    >
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  }
                />
              </div>
            ))
          ) : (
            <p className="px-3 py-6 text-xs text-muted-foreground text-center">
              대화 이력이 없습니다
            </p>
          )}
        </div>
      </ScrollArea>
    </div>
  );
}
