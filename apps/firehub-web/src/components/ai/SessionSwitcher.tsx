import { ChevronDown, MessageSquare,Plus, Trash2 } from 'lucide-react';
import { type FocusEvent, useEffect, useRef, useState } from 'react';

import { useAISessions, useDeleteAISession } from '../../hooks/queries/useAIChat';
import { cn } from '../../lib/utils';
import type { AISession } from '../../types/ai';
import { Button } from '../ui/button';
import { DeleteConfirmDialog } from '../ui/delete-confirm-dialog';
import { Popover, PopoverContent, PopoverTrigger } from '../ui/popover';

/** 줄·여는 버튼에 보이는 대화 이름 — 제목이 없으면 번호로 */
const sessionLabel = (session: AISession) => session.title || `대화 #${session.id}`;

/**
 * 키보드로 초점이 온 버튼을 스크롤 상자 안에 보이게 한다 (WD-951) — Radix 의 Tab 순환은 preventScroll 로 옮긴다.
 * 마우스 초점(:focus-visible 아님)에서는 움직이지 않는다 — 누르는 사이 버튼이 밀리면 클릭이 빗나간다.
 * lib 의 scrollIntoViewNearest 는 쓰지 않는다 — 열리는 zoom 애니메이션 중의 축소된 좌표로 계산해 덜 스크롤된다.
 */
function scrollIntoViewOnKeyboardFocus(e: FocusEvent<HTMLElement>) {
  if (e.currentTarget.matches(':focus-visible')) {
    e.currentTarget.scrollIntoView({ block: 'nearest' });
  }
}

interface SessionSwitcherProps {
  currentSessionId?: string | null;
  onNewSession: () => void;
  onSelectSession?: (session: AISession) => void;
}

export function SessionSwitcher({ currentSessionId, onNewSession, onSelectSession }: SessionSwitcherProps) {
  const [open, setOpen] = useState(false);
  const { data: sessions } = useAISessions();
  const deleteSession = useDeleteAISession();
  // 보낸 삭제 요청마다 지운 줄(id)과 그 줄이 사라지면 초점을 옮겨 갈 이웃 줄 (WD-951). 삭제가 겹칠 수 있어 모음이다.
  // 요청 단위라 실패한 요청만 빠지고, 같은 줄에 겹친 요청이 하나라도 남아 있으면 그 줄은 계속 기억된다.
  const pendingDeletes = useRef(new Set<{ id: number; neighborId: number | undefined }>());
  // 이 컴포넌트 안의 줄 제목 버튼과 목록 상자·새 대화 버튼 — 패널이 최대 세 곳에 그려지므로 전역 id 대신 ref 로 찾는다
  const titleRefs = useRef(new Map<number, HTMLButtonElement>());
  const contentRef = useRef<HTMLDivElement>(null);
  const newSessionRef = useRef<HTMLButtonElement>(null);
  // 초점이 이 컴포넌트(포털로 그려진 목록·확인 창 포함 — React 이벤트는 포털을 지나 올라온다) 안에 있었는지.
  // 초점 쥔 노드가 DOM 에서 빠질 때는 blur 가 오지 않으므로, 줄이 사라져 잃은 초점과 사용자가 치운 초점을 가른다.
  const hadFocus = useRef(false);

  /**
   * 지운 줄이 sessions 에서 사라진 렌더 뒤, 초점을 잃었을 때만 이웃 줄로 옮긴다 (WD-951).
   * 목록 갱신은 삭제 응답보다 먼저 올 수도 뒤에 올 수도 있어 응답이 아니라 줄이 사라진 시점에 하고,
   * 그 사이 사용자가 Tab·Esc·클릭으로 옮긴 초점은 빼앗지 않는다. 줄이 사라지면 Radix FocusScope 가 초점을
   * 목록 상자로 되돌리므로 body·목록 상자를 "잃음"으로 본다.
   */
  useEffect(() => {
    if (!sessions) return;
    // 이번 갱신에서 사라진 줄들만 모음에서 꺼낸다(아직 남은 줄은 다음 갱신까지 기억)
    const gone: (number | undefined)[] = [];
    pendingDeletes.current.forEach((req) => {
      if (sessions.some((s) => s.id === req.id)) return;
      pendingDeletes.current.delete(req);
      gone.push(req.neighborId);
    });
    if (gone.length === 0 || !hadFocus.current) return;
    const active = document.activeElement;
    const lost = !active || active === document.body || active === contentRef.current;
    if (!lost) return;
    if (sessions.length === 0) {
      // 남은 대화가 없다 — 목록은 아래에서 닫히므로 「새 대화」로 옮긴다
      newSessionRef.current?.focus();
      return;
    }
    // 이웃이 그새 사라졌어도 남은 줄이 있으면 목록 순서의 첫 남은 줄로 — 대화가 남았는데 목록을 닫지 않는다.
    // Map 의 넣은 순서(ref 가 붙는 순서)에 기대지 않고 sessions 순서로 고른다(WD-951 리뷰 후속).
    // 목록이 닫혀 있으면 줄 버튼이 없어 옮길 곳이 없고, 대화가 남았으니 「새 대화」로 끌어가지도 않는다.
    const neighbor = gone.map((id) => id !== undefined && titleRefs.current.get(id)).find(Boolean);
    (neighbor || sessions.map((s) => titleRefs.current.get(s.id)).find(Boolean))?.focus();
  }, [sessions]);

  // 대화가 모두 사라지면 목록을 닫아 둔다 — open 이 true 로 남으면 이후 새 대화가 생길 때 목록이 저절로 열린다.
  // 렌더 중 자기 상태 조정이라 효과보다 한 렌더 빠르고 깜빡임이 없다.
  if (open && sessions && sessions.length === 0) setOpen(false);

  const currentSession = sessions?.find((s) => s.sessionId === currentSessionId);
  const triggerLabel = currentSession ? sessionLabel(currentSession) : '대화 선택';

  return (
    <div
      className="flex items-center justify-between gap-1"
      onFocus={() => {
        hadFocus.current = true;
      }}
      onBlur={(e) => {
        // 창만 바꾼 blur 는 activeElement 가 그대로라 초점을 치운 것으로 보지 않는다
        hadFocus.current = document.activeElement === e.target;
      }}
    >
      {sessions && sessions.length > 0 ? (
        // modal Popover — 기존 DropdownMenu 처럼 바깥을 비활성화하고 초점을 목록 안에 가둔다.
        // 메뉴(menu role)는 초점을 항목으로만 옮기고 Enter·Space 를 항목 선택으로 먹어 안에 든 삭제 버튼에
        // 키보드로 닿지 못했다(WD-951). 펼침 목록은 줄마다 제목 버튼·삭제 버튼이 형제라 Tab 으로 둘 다 닿는다.
        <Popover modal open={open} onOpenChange={setOpen}>
          <PopoverTrigger asChild>
            <Button variant="ghost" size="sm" className="h-7 gap-1 text-xs max-w-[180px]">
              <MessageSquare className="h-3 w-3 shrink-0" />
              <span className="truncate">{triggerLabel}</span>
              <ChevronDown className="h-3 w-3 shrink-0" />
            </Button>
          </PopoverTrigger>
          {/* PopoverContent 기본값(w-72 p-4)은 기존 메뉴 모양(w-56 p-1)으로 덮어쓴다 */}
          <PopoverContent
            align="start"
            aria-label="대화 선택"
            ref={contentRef}
            className="w-56 p-1 max-h-64 overflow-y-auto"
          >
            {/* Safari 는 list-style: none 인 ul 의 목록 의미를 버리므로 role 을 명시한다 */}
            <ul role="list" aria-label="최근 대화">
              {sessions.map((session, index) => {
                const label = sessionLabel(session);
                const isCurrent = session.sessionId === currentSessionId;
                return (
                  <li
                    key={session.id}
                    className={cn(
                      // 초점 줄 배경은 :focus-visible 일 때만 — 마우스로 열면 Popover 가 첫 제목에 초점을 주는데
                      // focus-within 은 그 줄을 현재 줄처럼 칠해 "현재 대화"가 둘로 보였다
                      'relative flex h-8 items-center rounded-sm hover:bg-accent has-[:focus-visible]:bg-accent',
                      isCurrent && 'bg-accent'
                    )}
                  >
                    {/* 제목 버튼이 줄 전체를 채워 줄 어디를 눌러도 열린다(휴지통 제외). 휴지통은 줄 위에 절대 위치로
                        얹고 그 자리만큼 오른쪽 안쪽 여백(pr-9)을 둬, 틈·여백도 클릭 영역이면서 글자가 휴지통에 가리지 않는다 */}
                    <button
                      type="button"
                      className="h-full min-w-0 flex-1 cursor-pointer truncate rounded-sm pl-2 pr-9 text-left text-xs focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring"
                      aria-current={isCurrent ? 'true' : undefined}
                      ref={(el) => {
                        if (el) titleRefs.current.set(session.id, el);
                        else titleRefs.current.delete(session.id);
                      }}
                      onFocus={scrollIntoViewOnKeyboardFocus}
                      onClick={() => {
                        onSelectSession?.(session);
                        setOpen(false);
                      }}
                    >
                      {label}
                    </button>
                    {/* 삭제 전 확인 다이얼로그 — 즉시 삭제 방지 (#15). 제목 버튼의 형제라 키보드로 닿는다 */}
                    <DeleteConfirmDialog
                      entityName="대화"
                      itemName={label}
                      onConfirm={() => {
                        // 이웃은 다음 줄, 없으면 이전 줄 — 누른 시점의 목록으로 정한다
                        const neighborId = (sessions[index + 1] ?? sessions[index - 1])?.id;
                        // 보내기 전에 기억한다 — 응답보다 목록 갱신이 먼저 올 수 있다. 실패한 요청은 기억에서 뺀다(리뷰 후속):
                        // 남겨 두면 나중에 다른 까닭으로 그 줄이 사라질 때 초점을 엉뚱하게 옮긴다. 호출별 mutate 콜백은
                        // 겹친 호출 중 마지막 것만 불리므로 promise 로 받는다. 오류 안내는 훅의 onError 가 띄운다.
                        const req = { id: session.id, neighborId };
                        pendingDeletes.current.add(req);
                        deleteSession.mutateAsync(session.id).catch(() => pendingDeletes.current.delete(req));
                      }}
                      trigger={
                        <Button
                          variant="ghost"
                          size="icon"
                          className="group/trash absolute right-2 top-1.5 h-5 w-5"
                          // 어느 대화의 삭제인지 이름에 담는다(전체 화면 목록 #346 과 같게). 제목 버튼의 형제라
                          // #514 처럼 제목이 두 번 읽히지 않는다.
                          aria-label={`${label} 삭제`}
                          onFocus={scrollIntoViewOnKeyboardFocus}
                        >
                          {/* 흐린 opacity-50 에 muted 색까지 겹치면 현재 줄(bg-accent) 위 대비가 3:1 아래로 떨어진다 —
                              muted 색 하나만 두고 가리킬 때 진하게(WD-951 리뷰 후속, WCAG 1.4.11) */}
                          <Trash2 className="h-3 w-3 text-muted-foreground group-hover/trash:text-foreground group-focus-visible/trash:text-foreground" />
                        </Button>
                      }
                    />
                  </li>
                );
              })}
            </ul>
            <div role="separator" className="-mx-1 my-1 h-px bg-border" />
            {/* 누를 것이 아니므로 버튼·비활성 항목이 아닌 글 — 투명도 없이 muted 색(대비) */}
            <p className="px-2 py-1.5 text-center text-xs text-muted-foreground">최근 대화 목록</p>
          </PopoverContent>
        </Popover>
      ) : (
        <span className="text-xs text-muted-foreground">대화 이력 없음</span>
      )}
      <Button ref={newSessionRef} variant="ghost" size="sm" className="h-7 gap-1 text-xs" onClick={onNewSession}>
        <Plus className="h-3 w-3" />
        새 대화
      </Button>
    </div>
  );
}
