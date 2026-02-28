import { ChevronDown, MessageSquare,Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';

import { useAISessions, useDeleteAISession } from '../../hooks/queries/useAIChat';
import { cn } from '../../lib/utils';
import type { AISession } from '../../types/ai';
import { Button } from '../ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu';

interface SessionSwitcherProps {
  currentSessionId?: string | null;
  onNewSession: () => void;
  onSelectSession?: (session: AISession) => void;
}

export function SessionSwitcher({ currentSessionId, onNewSession, onSelectSession }: SessionSwitcherProps) {
  const [open, setOpen] = useState(false);
  const { data: sessions } = useAISessions();
  const deleteSession = useDeleteAISession();

  const handleDelete = (e: React.MouseEvent, id: number) => {
    e.stopPropagation();
    deleteSession.mutate(id);
  };

  const currentSession = sessions?.find((s) => s.sessionId === currentSessionId);
  const triggerLabel = currentSession
    ? (currentSession.title || `대화 #${currentSession.id}`)
    : '새 대화';

  return (
    <div className="flex items-center justify-between gap-1">
      {sessions && sessions.length > 0 ? (
        <DropdownMenu open={open} onOpenChange={setOpen}>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="sm" className="h-7 gap-1 text-xs max-w-[180px]">
              <MessageSquare className="h-3 w-3 shrink-0" />
              <span className="truncate">{triggerLabel}</span>
              <ChevronDown className="h-3 w-3 shrink-0" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-56 max-h-64 overflow-y-auto">
            {sessions.map((session) => (
              <DropdownMenuItem
                key={session.id}
                className={cn(
                  'flex items-center justify-between gap-2',
                  session.sessionId === currentSessionId && 'bg-accent'
                )}
                onClick={() => {
                  onSelectSession?.(session);
                  setOpen(false);
                }}
              >
                <span className="truncate text-xs">
                  {session.title || `대화 #${session.id}`}
                </span>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-5 w-5 shrink-0 opacity-50 hover:opacity-100"
                  onClick={(e) => handleDelete(e, session.id)}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem className="text-xs text-muted-foreground justify-center" disabled>
              최근 대화 목록
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      ) : (
        <span className="text-xs text-muted-foreground">대화 이력 없음</span>
      )}
      <Button variant="ghost" size="sm" className="h-7 gap-1 text-xs" onClick={onNewSession}>
        <Plus className="h-3 w-3" />
        새 대화
      </Button>
    </div>
  );
}
