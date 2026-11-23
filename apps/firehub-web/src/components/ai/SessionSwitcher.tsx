import { ChevronDown, MessageSquare,Plus, Trash2 } from 'lucide-react';
import { type FocusEvent, useEffect, useRef, useState } from 'react';

import { useAISessions, useDeleteAISession, usePendingSessionDeletes } from '../../hooks/queries/useAIChat';
import { isFocusLost } from '../../lib/focus-lost';
import { cn } from '../../lib/utils';
import type { AISession } from '../../types/ai';
import { PendingButton } from '../PendingButton';
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
  /** 대화 삭제가 성공한 뒤 부른다 — 지운 대화가 열려 있었는지는 받는 쪽이 최신 상태로 판단한다 (WD-972) */
  onSessionDeleted: (sessionId: string) => void;
  onSelectSession?: (session: AISession) => void;
}

export function SessionSwitcher({ currentSessionId, onNewSession, onSessionDeleted, onSelectSession }: SessionSwitcherProps) {
  const [open, setOpen] = useState(false);
  const { data: sessions } = useAISessions();
  const deleteSession = useDeleteAISession();
  // WD-973: 삭제 요청이 진행 중인 줄 — 그 줄 휴지통을 다시 열지 못하게 한다
  const deleting = usePendingSessionDeletes();
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
  // 목록 안에서 마지막으로 초점을 받은 줄 버튼(제목·휴지통) — 그 노드가 DOM 에서 빠졌으면 초점 쥔 줄이 사라진 것이다(WD-973 리뷰 후속 ③).
  // 목록 상자 자신은 담지 않는다: 줄이 빠지면 Radix FocusScope 가 초점을 목록 상자로 옮기는데, 그 focus 가 이 값을 덮으면
  // 효과가 「사라진 줄」을 못 본다. 목록 밖(여는 버튼·포털의 확인 창)으로 간 초점은 null 로 지워 낡은 노드가 남지 않게 한다.
  const lastFocused = useRef<HTMLElement | null>(null);
  // lastFocused 가 속한 줄의 id — 그 노드가 사라진 뒤에도 어느 줄이었는지 알기 위해 초점 때 함께 적는다
  const lastFocusedId = useRef<number | undefined>(undefined);
  // 직전 렌더의 목록 — 초점 쥔 줄이 재조회로 사라졌을 때 그 줄의 이웃(다음, 없으면 이전)을 옛 순서에서 찾는다 (WD-989·WD-1024)
  const prevSessions = useRef<AISession[] | undefined>(undefined);

  /**
   * 지운 줄이 sessions 에서 사라진 렌더 뒤, 초점을 잃었을 때만 이웃 줄로 옮긴다 (WD-951).
   * 목록 갱신은 삭제 응답보다 먼저 올 수도 뒤에 올 수도 있어 응답이 아니라 줄이 사라진 시점에 하고,
   * 그 사이 사용자가 Tab·Esc·클릭으로 옮긴 초점은 빼앗지 않는다. 줄이 사라지면 Radix FocusScope 가 초점을
   * 목록 상자로 되돌리므로 body·목록 상자를 "잃음"으로 본다.
   */
  useEffect(() => {
    if (!sessions) return;
    const prev = prevSessions.current;
    prevSessions.current = sessions;
    // 이번 갱신에서 사라진 줄들만 모음에서 꺼낸다(아직 남은 줄은 다음 갱신까지 기억)
    const gone: (number | undefined)[] = [];
    pendingDeletes.current.forEach((req) => {
      if (sessions.some((s) => s.id === req.id)) return;
      pendingDeletes.current.delete(req);
      gone.push(req.neighborId);
    });
    // 지운 줄이 사라지지 않았어도 초점 쥔 줄이 사라졌으면 옮긴다(WD-973 리뷰 후속 ③) — 성공(204)이 캐시에서 지운 줄을 먼저 빼
    // 초점이 이웃으로 간 뒤, 뒤따른 재조회가 그 이웃마저 없는 목록을 가져오면 지운 줄의 기억은 이미 꺼낸 뒤라 초점을 잃는다.
    const focusedRowGone = lastFocused.current !== null && !lastFocused.current.isConnected;
    // 사라진 초점 줄의 id — 노드가 이미 DOM 에서 빠져 줄을 역조회할 수 없으므로 초점 때 함께 적어 둔 값을 쓴다(lastFocusedId)
    const goneRowId = focusedRowGone ? lastFocusedId.current : undefined;
    // 한 번 본 사라진 노드는 지운다 — 다음 갱신에서 다시 「사라짐」으로 세지 않게
    if (focusedRowGone) lastFocused.current = null;
    if ((gone.length === 0 && !focusedRowGone) || !hadFocus.current) return;
    const active = document.activeElement;
    // body·본문 main(건너뛰기 뒤, WD-980)·목록 상자를 잃음으로 본다
    const lost = isFocusLost(active) || active === contentRef.current;
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
    // 기억한 삭제(gone)가 없는 재조회로 사라진 줄은 첫 줄이 아니라 옛 목록에서 그 줄의 다음 → 이전 이웃으로 (WD-989·WD-1024)
    let near: HTMLButtonElement | undefined;
    if (gone.length === 0 && goneRowId !== undefined && prev) {
      const at = prev.findIndex((s) => s.id === goneRowId);
      if (at >= 0) {
        const candidates = [...prev.slice(at + 1), ...prev.slice(0, at).reverse()];
        near = candidates.map((s) => titleRefs.current.get(s.id)).find(Boolean);
      }
    }
    (neighbor || near || sessions.map((s) => titleRefs.current.get(s.id)).find(Boolean))?.focus();
  }, [sessions]);

  // 대화가 모두 사라지면 목록을 닫아 둔다 — open 이 true 로 남으면 이후 새 대화가 생길 때 목록이 저절로 열린다.
  // 렌더 중 자기 상태 조정이라 효과보다 한 렌더 빠르고 깜빡임이 없다.
  if (open && sessions && sessions.length === 0) setOpen(false);

  const currentSession = sessions?.find((s) => s.sessionId === currentSessionId);
  const triggerLabel = currentSession ? sessionLabel(currentSession) : '대화 선택';

  return (
    <div
      className="flex items-center justify-between gap-1"
      onFocus={(e) => {
        hadFocus.current = true;
        if (e.target === contentRef.current) return;
        lastFocused.current = contentRef.current?.contains(e.target) ? e.target : null;
        const rowId = lastFocused.current?.closest('li')?.dataset.sessionId;
        lastFocusedId.current = rowId === undefined ? undefined : Number(rowId);
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
            <Button variant="ghost" size="sm" className="gap-1 text-xs max-w-[180px]">
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
                    data-session-id={session.id}
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
                        // 성공하면 지운 것이 지금 열린 대화인지의 판단·새 대화 전환은 useAIChat(onSessionDeleted)이 한다 (WD-972).
                        // 성공/실패를 나눠 받아 onOk 의 오류가 실패 처리(기억에서 빼기)로 가지 않게 한다. 실패하면 현재 대화는 그대로다.
                        deleteSession
                          .mutateAsync(session.id)
                          .then(
                            () => {
                              // 성공 뒤 처리의 오류는 여기서만 잡는다 — 끝에 .catch 를 달면 아래 거절 처리기의 오류까지 「삭제 성공 뒤」 로 잘못 남는다
                              try {
                                onSessionDeleted(session.sessionId);
                              } catch (error) {
                                console.error('Failed after AI session delete:', error);
                              }
                            },
                            (error) => {
                              // 삭제 실패 안내는 훅의 onError 가 띄운다 — 원인만 남기고 기억에서 뺀다
                              // 정리를 먼저 — 로그가 던져도 기억에서는 빠지게
                              pendingDeletes.current.delete(req);
                              console.error('Failed to delete AI session:', error);
                            },
                          );
                      }}
                      trigger={
                        <PendingButton
                          variant="ghost"
                          size="icon-xs"
                          // WCAG 2.5.8 최소 24×24(icon-xs) — 줄 32px 안 세로 가운데(top-1), 오른쪽 4px(right-1).
                          // 제목의 pr-9(36px)가 휴지통 폭+여백(28px)보다 커 글자를 가리지 않는다.
                          // WD-989: 줄은 현재 줄이면 늘, 아니면 가리킴·초점 때 bg-accent 라 ghost 의 hover:bg-accent 가 줄 색과 같아
                          // 휴지통 가리킴·초점이 안 보인다 — 모든 줄에서 줄과 다른 background 로.
                          // 어두운 테마에서는 ghost 의 dark:hover:bg-accent/50 이 더 구체적이라 이기므로 dark:hover 짝을 함께 둔다(ghost 에 dark focus-visible 배경은 없다).
                          className="group/trash absolute right-1 top-1 aria-disabled:pointer-events-auto hover:bg-background dark:hover:bg-background focus-visible:bg-background"
                          // WD-973: 삭제 응답 전에는 다시 못 연다. PendingButton 은 disabled 대신 aria-disabled 로 잠가 초점을 지키고
                          // (WD-951 실패 뒤 이 버튼 초점) 클릭을 preventDefault 해 Radix 트리거가 확인 창을 열지 않는다(Enter·Space 도 click 경로).
                          // 공용 잠금 모양의 pointer-events-none 은 되돌린다 — 줄 위에 겹친 버튼이라 클릭이 아래 제목(대화 열기)으로 새고 초점도 잃는다.
                          pending={deleting.has(session.id)}
                          // 어느 대화의 삭제인지 이름에 담는다(전체 화면 목록 #346 과 같게). 제목 버튼의 형제라
                          // #514 처럼 제목이 두 번 읽히지 않는다.
                          aria-label={`${label} 삭제`}
                          onFocus={scrollIntoViewOnKeyboardFocus}
                        >
                          {/* 흐린 opacity-50 에 muted 색까지 겹치면 현재 줄(bg-accent) 위 대비가 3:1 아래로 떨어진다 —
                              muted 색 하나만 두고 가리킬 때 진하게(WD-951 리뷰 후속, WCAG 1.4.11).
                              size-4 를 밝혀 둔다 — 없으면 icon-xs 가 아이콘을 12px 로 줄여 WD-951 의 16px 보다 작아진다.
                              삭제 중(aria-disabled)의 opacity-50 은 예외 — 비활성 컨트롤은 1.4.11 대상이 아니고 눌러도 안 됨을 보인다(WD-973) */}
                          <Trash2 className="size-4 text-muted-foreground group-hover/trash:text-foreground group-focus-visible/trash:text-foreground" />
                        </PendingButton>
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
      <Button ref={newSessionRef} variant="ghost" size="sm" className="gap-1 text-xs" onClick={onNewSession}>
        <Plus className="h-3 w-3" />
        새 대화
      </Button>
    </div>
  );
}
