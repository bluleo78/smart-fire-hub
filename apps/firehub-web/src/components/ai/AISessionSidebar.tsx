import { MessageSquare,Plus, Trash2 } from 'lucide-react';

import { useAISessions, useDeleteAISession } from '../../hooks/queries/useAIChat';
import { cn } from '../../lib/utils';
import { Button } from '../ui/button';
import { ScrollArea } from '../ui/scroll-area';
import { useAI } from './AIProvider';

export function AISessionSidebar() {
  const { currentSessionId, startNewSession, loadSession } = useAI();
  const { data: sessions } = useAISessions();
  const deleteSession = useDeleteAISession();

  const handleDelete = (e: React.MouseEvent, id: number) => {
    e.stopPropagation();
    deleteSession.mutate(id);
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
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-6 w-6 shrink-0 opacity-0 group-hover:opacity-60 hover:!opacity-100"
                  onClick={(e) => handleDelete(e, session.id)}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
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
