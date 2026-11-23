import { MessageSquare, Plus, Trash2 } from 'lucide-react';

import { useAISessions, useDeleteAISession } from '../../hooks/queries/useAIChat';
import { cn } from '../../lib/utils';
import { Button } from '../ui/button';
import { DeleteConfirmDialog } from '../ui/delete-confirm-dialog';
import { ScrollArea } from '../ui/scroll-area';
import { useAI } from './AIProvider';

export function AISessionSidebar() {
  const { currentSessionId, startNewSession, onSessionDeleted, loadSession } = useAI();
  const { data: sessions } = useAISessions();
  const deleteSession = useDeleteAISession();

  /**
   * 세션 삭제 실행 — DeleteConfirmDialog 확인 후 호출됨 (#210)
   * 성공하면 지운 것이 지금 열린 대화인지의 판단·새 대화 전환은 useAIChat(onSessionDeleted)이 최신 상태로 한다 (WD-972).
   * 호출별 mutate 콜백은 겹친 삭제 중 마지막 것만 불려 promise 로 받는다. 실패 안내는 훅의 onError 가 띄운다.
   */
  const handleDeleteConfirmed = (sessionId: string, id: number) => {
    deleteSession
      .mutateAsync(id)
      .then(
        () => onSessionDeleted(sessionId),
        // 요청 실패 — 안내는 훅의 onError 가 띄운다. 화면 동작은 그대로 두고 원인만 남긴다
        (error) => console.error('Failed to delete AI session:', error),
      )
      // 삭제는 성공했고 뒤따른 처리(onSessionDeleted)에서 난 오류 — 요청 실패와 구분해 남긴다
      .catch((error) => console.error('Failed after AI session delete:', error));
  };

  // WD-755: 세션 목록 면은 알파 없는 단색 bg-muted(/30 은 대화 영역과 갈리지 않는다) — 현재 세션(bg-accent)은 muted 보다 한 단계 위라 갈린다
  return (
    <div className="flex flex-col h-full w-64 border-r bg-muted">
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
      {/*
        대화 목록은 role=list / 각 행은 role=listitem으로 구조를 노출한다 (#346).
        행 자체를 클릭 핸들러 달린 div로 두면 키보드로 대화를 열 수 없으므로,
        제목을 네이티브 <button>으로 만들어 Enter/Space를 기본 동작으로 얻는다.
        삭제 버튼은 그 형제로 두어 인터랙티브 요소 중첩을 피한다.
      */}
      <ScrollArea className="flex-1">
        {/*
          role=list는 목록이 실제로 있을 때만 부여한다. 빈 상태 문구(<p>)를 감싸면
          list가 listitem이 아닌 자식을 갖게 되어 aria-required-children 위반이 된다.
        */}
        <div
          className="p-2 space-y-0.5"
          role={sessions && sessions.length > 0 ? 'list' : undefined}
          aria-label={sessions && sessions.length > 0 ? '대화 이력' : undefined}
        >
          {sessions && sessions.length > 0 ? (
            sessions.map((session) => {
              const label = session.title || `대화 #${session.id}`;
              const isCurrent = session.sessionId === currentSessionId;
              return (
                <div
                  key={session.id}
                  role="listitem"
                  className={cn(
                    'group flex items-center gap-2 rounded-md px-2.5 py-2 text-sm transition-colors',
                    isCurrent
                      ? 'bg-accent text-accent-foreground'
                      : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'
                  )}
                >
                  <button
                    type="button"
                    className="flex flex-1 min-w-0 items-center gap-2 text-left cursor-pointer rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
                    onClick={() => loadSession(session.sessionId)}
                    aria-current={isCurrent ? 'true' : undefined}
                  >
                    <MessageSquare className="h-3.5 w-3.5 shrink-0" />
                    <span className="flex-1 truncate text-xs">{label}</span>
                  </button>
                  {/* 삭제 전 확인 다이얼로그 — 즉시 삭제 방지 (#210) */}
                  <DeleteConfirmDialog
                    entityName="대화"
                    itemName={label}
                    onConfirm={() => handleDeleteConfirmed(session.sessionId, session.id)}
                    trigger={
                      <Button
                        variant="ghost"
                        size="icon"
                        // 키보드 포커스 시에도 보이게 한다 (#346). focus-within까지 거는 이유:
                        // 다이얼로그가 마우스로 닫히며 트리거로 포커스를 되돌릴 때(#337)는
                        // :focus-visible이 걸리지 않아 focus-visible만으로는 다시 투명해진다.
                        className="h-6 w-6 shrink-0 opacity-0 group-hover:opacity-60 group-focus-within:opacity-60 hover:!opacity-100 focus-visible:!opacity-100"
                        aria-label={`${label} 삭제`}
                      >
                        <Trash2 className="h-3 w-3" />
                      </Button>
                    }
                  />
                </div>
              );
            })
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
