/**
 * useLoadMoreAnnounce — 목록 「더 보기」·「다시 시도」 응답 뒤 알림·초점 이동 공용 훅 (WD-965 리뷰 후속, WD-1032).
 *
 * 왜: 목록 끝에 닿으면 「더 보기」가, 다시 시도가 성공하면 실패 블록이 사라져 키보드 초점이 body 로 빠지고,
 *     새 행이 붙은 것도 보조기술에 알려지지 않았다. 실행 이력·리포트 목록이 같은 규칙을 쓰도록 한곳에 둔다.
 */
import { useEffect, useRef } from 'react';

import { isFocusLost } from '@/lib/focus-lost';

interface UseLoadMoreAnnounceOptions<T> {
  /** 현재 보이는 행 목록 */
  items: T[];
  /**
   * 행의 고유 id — 누른 때 마지막 행을 기억해 새 행 위치를 가린다.
   * getId·findRowTarget 은 렌더마다 새로 만들지 않는 함수를 넘긴다 — 의존성이라 바뀌면 effect 가 다시 돈다.
   */
  getId: (item: T) => number;
  /** 지금 요청한 limit — 행 수가 limit 에 못 미치면 마지막이다 */
  limit: number;
  /** useLoadMoreLimit 의 failed — 「더 보기」 조회 실패 */
  failed: boolean;
  /** useLoadMoreLimit 의 pending — 새 limit 요청 진행(재시도 포함) */
  pending: boolean;
  /** 조회 성공 여부 */
  isSuccess: boolean;
  /** 새 limit 의 자리표시 데이터로 보이는 중 */
  isPlaceholderData: boolean;
  /** 재조회가 아직 끝나지 않음(isFetching || isPaused) */
  refetchPending: boolean;
  /** limit 을 늘리는 실제 동작 */
  loadMore: () => void;
  /** 알림 문구의 명사 ('실행 이력' | '리포트') */
  noun: string;
  /** 새 행이 없을 때 문구 — 조사가 명사마다 달라 문구째 받는다 */
  noneMessage: string;
  /** 첫 새 행의 초점 대상 — tbody 안에서 id 로 찾는다 */
  findRowTarget: (tbody: HTMLElement, id: number) => HTMLElement | null;
}

export function useLoadMoreAnnounce<T>({
  items,
  getId,
  limit,
  failed,
  pending,
  isSuccess,
  isPlaceholderData,
  refetchPending,
  loadMore,
  noun,
  noneMessage,
  findRowTarget,
}: UseLoadMoreAnnounceOptions<T>) {
  // 표를 감싸는 자리 — 첫 새 행·마지막 행을 찾는 범위
  const tableWrapRef = useRef<HTMLDivElement>(null);
  // 목록 아래 「더 보기」·실패 블록을 감싸는 자리 — 누른 때 초점이 여기 있었는지 보고, 응답 뒤 남은 「더 보기」를 찾는다
  const bottomRef = useRef<HTMLDivElement>(null);
  /**
   * 누른 때의 기다림 — null 이면 기다리는 「더 보기」가 없다.
   * - lastId: 누른 때 마지막 행의 id. 새 행은 그 행 뒤의 행이다 — 폴링으로 맨 위에 새 행이 끼어
   *   위치가 밀려도 id 로 가려 개수·첫 새 행이 맞게.
   * - from: 누른 때 행 수(마지막 행 id 를 못 찾을 때의 대비).
   * - hadFocus: 누른 때 초점이 아래 버튼 자리에 있었는지 — 마우스로 눌러 초점이 body 인 경우(Safari 등)에는 옮기지 않는다.
   */
  const pendingLoadRef = useRef<{ lastId: number | undefined; from: number; hadFocus: boolean } | null>(null);
  // 알림 문구는 상태가 아니라 status 영역 DOM 에 직접 쓴다 — 응답 뒤 effect 에서 setState 하면 렌더가 한 번 더 돈다.
  const statusRef = useRef<HTMLParagraphElement>(null);
  const statusFrameRef = useRef<number | null>(null);

  // 「더 보기」 응답의 끝 — 실패 확정(재시도 중이 아님), 또는 자리표시·재시도·백그라운드 재조회가 모두 끝난 성공 (WD-1019)
  const loadFailed = failed && !pending;
  const loadSettled = isSuccess && !isPlaceholderData && !pending && !refetchPending;

  /** 걸려 있는(아직 안 돈) 알림 프레임을 취소한다 — 지난 문구가 새 알림을 덮거나 화면을 떠난 뒤 쓰이지 않게 (WD-1019) */
  const cancelStatusFrame = () => {
    if (statusFrameRef.current !== null) {
      cancelAnimationFrame(statusFrameRef.current);
      statusFrameRef.current = null;
    }
  };

  // 화면을 떠나면 아직 쓰지 않은 알림을 버린다
  useEffect(() => cancelStatusFrame, []);

  /** 「더 보기」·「다시 시도」 공용 — 지금 자리를 기억하고 지난 알림을 비운 뒤 limit 을 늘린다(같은 문구도 다시 읽히게). */
  const handleLoadMore = () => {
    const last = items.at(-1);
    pendingLoadRef.current = {
      lastId: last === undefined ? undefined : getId(last),
      from: items.length,
      hadFocus: !!bottomRef.current?.contains(document.activeElement),
    };
    // 앞 응답의 알림 프레임이 아직 안 돌았으면 취소한다 — 그대로 두면 새 응답 전에 지난 문구가 다시 쓰인다 (WD-1019)
    cancelStatusFrame();
    if (statusRef.current) statusRef.current.textContent = '';
    loadMore();
  };

  /**
   * 응답이 오면 새로 받은 행 수를 status 영역으로 알리고, 버튼이 사라져 초점을 잃었으면 옮긴다.
   * 초점은 같은 자리의 「더 보기」 → 첫 새 행 → (새 행이 없으면) 마지막 행 순으로 옮긴다 — 읽던 자리에서 이어 가게.
   * 알림은 초점을 옮긴 다음 프레임에 쓴다 — 같은 때 쓰면 새 초점의 낭독에 polite 알림이 묻힐 수 있다.
   * 실패면 LoadMoreFailure 가 alert 로 알리고, 누른 때 초점이 버튼 자리에 있었으면 그 블록의 「다시 시도」로 초점을 옮긴다 — 버튼이 블록으로 바뀌며 초점이 body 로 빠지기 때문(WD-1036).
   */
  useEffect(() => {
    const load = pendingLoadRef.current;
    if (load === null) return;
    // 실패면 기다림만 접는다 — 남겨 두면 나중의 성공(폴링 등)이 사용자 동작 없이 지난 개수를 알리고 초점을 옮긴다 (WD-1019)
    if (loadFailed) {
      pendingLoadRef.current = null;
      // 키보드로 눌렀다면(초점이 버튼 자리에 있었다면) 실패 블록의 「다시 시도」로 초점을 옮긴다 — 마우스로 눌러 초점이 없던 경우는 옮기지 않는다 (WD-1036)
      if (load.hadFocus && isFocusLost()) {
        bottomRef.current?.querySelector<HTMLElement>('button')?.focus();
      }
      return;
    }
    // 낡은 캐시로 먼저 보인 새 limit 은 백그라운드 재조회가 끝나야 최종 행이다 — 그때 알리고 초점을 정한다 (WD-1019)
    if (!loadSettled) return;
    pendingLoadRef.current = null;
    const lastIndex = items.findIndex((item) => getId(item) === load.lastId);
    const firstNew = lastIndex >= 0 ? lastIndex + 1 : load.from;
    const added = items.length - firstNew;
    const hasMore = items.length >= limit;
    const message =
      added <= 0
        ? noneMessage
        : `${noun} ${added}건을 더 불러왔습니다.${hasMore ? '' : ` 마지막 ${noun}입니다.`}`;
    if (load.hadFocus && isFocusLost()) {
      const tbody = tableWrapRef.current?.querySelector('tbody');
      const firstNewItem = items[firstNew];
      const target =
        bottomRef.current?.querySelector<HTMLElement>('button') ??
        (firstNewItem !== undefined && tbody ? findRowTarget(tbody, getId(firstNewItem)) : null) ??
        tbody?.querySelector<HTMLElement>('tr:last-child a');
      target?.focus();
    }
    // 걸려 있는 지난 프레임이 있으면 먼저 취소한다 — 새 알림을 지난 알림이 덮지 않게 (WD-1019)
    cancelStatusFrame();
    statusFrameRef.current = requestAnimationFrame(() => {
      statusFrameRef.current = null;
      if (statusRef.current) statusRef.current.textContent = message;
    });
    // pendingLoadRef 가 null 이면 effect 는 바로 끝난다 — 의존성이 바뀌어 다시 돌아도 알림을 두 번 하지 않는다.
  }, [items, loadFailed, loadSettled, limit, getId, findRowTarget, noun, noneMessage]);

  return { handleLoadMore, tableWrapRef, bottomRef, statusRef };
}
