import { useCallback, useEffect, useLayoutEffect, useRef } from 'react';

import { Button } from '../components/ui/button';
import { isForbiddenError } from '../lib/api-error';
import { cn } from '../lib/utils';

/**
 * 목록 「다시 시도」 버튼의 키보드 초점 관리 훅 (WD-957, WD-987 에서 공용화).
 *
 * 데이터 없이 실패한 목록에서 「다시 시도」를 누르면 쿼리가 pending 으로 돌아가 실패 행(과 버튼)이 사라져
 * 초점이 body 로 떨어진다(WCAG 2.4.3). 누르는 즉시 표로 초점을 옮겨 두고, 재조회가 끝나 다시 실패하면 버튼으로 되돌린다.
 * 누르지 않아도 저절로 다시 불러올 때(창 포커스 refetch·캐시 무효화·다시 마운트) 초점을 가진 버튼이 사라지면 같은 방식으로 표로 옮긴다.
 *
 * 사용: `<Table {...tableProps}>`(쪽 이동 placeholder 목록은 `<tbody {...bodyProps}>` 도) 와 `<TableErrorRow action={retryAction} />` 만 달면 된다.
 * 버튼 노출 여부(showRetry)·표 ref/mousedown/포커스 링 className·버튼 JSX 는 모두 이 훅이 만든다.
 * 표에 className 을 더 달려면 `<Table className=…>` 대신 `tableClassName` 으로 넘긴다 — 펼친 props 와 따로 주면 뒤에 쓴 쪽이 덮는다.
 */
export function useListRetryFocus({
  isPending: isPendingProp,
  isPlaceholderData = false,
  isError,
  isFetching,
  data,
  error,
  refetch,
  tableClassName,
}: {
  /** 쿼리가 pending(데이터 없이 조회 중) 인지 */
  isPending: boolean;
  /**
   * 쪽 이동 중 이전 쪽 행을 placeholder 로 유지하는 목록용 (WD-982). 기본 false.
   * 켜면 placeholder 를 진짜 data 가 아니므로 pending 으로 보고(아니면 다시 시도 뒤 재실패 때 초점이 버튼으로 못 돌아온다),
   * 표를 흐리게·누를 수 없게 하며(tableProps) 이전 행을 키보드로도 열 수 없게 한다(bodyProps → tbody inert).
   */
  isPlaceholderData?: boolean;
  /** 쿼리가 실패 상태인지 */
  isError: boolean;
  /** 재조회 중인지 — 버튼 중복 클릭 방지에 쓴다 */
  isFetching: boolean;
  /** 받아 둔 목록 데이터 — 있으면 재조회 실패여도 목록을 유지하므로 버튼을 두지 않는다 */
  data: unknown;
  /** 현재 쿼리 오류 — 403 판단과, 재조회가 다시 실패했을 때 effect 를 다시 돌리는 의존값 */
  error: unknown;
  /** 재조회 함수 */
  refetch: () => unknown;
  /** 표에 더할 className — 포커스 링 className 과 cn() 으로 합친다 */
  tableClassName?: string;
}) {
  // placeholder(이전 쪽 행)는 진짜 data 가 아니므로 pending 으로 본다
  const isPending = isPendingProp || isPlaceholderData;
  // 데이터 없는 실패일 때만 버튼을 둔다. 403 은 다시 해도 같으므로 두지 않는다 (WD-137 선례)
  const showRetry = isError && !data && !isForbiddenError(error);
  const tableRef = useRef<HTMLTableElement>(null);
  const retryButtonRef = useRef<HTMLButtonElement | null>(null);
  // 재조회 결과를 기다리는 중인지 표시 — 끝나서 다시 실패하면 버튼으로 초점을 되돌린다
  const refocusRetryRef = useRef(false);

  // 커밋된 isPending 의 최신값 — 버튼 ref 정리 뒤 마이크로태스크에서 「재조회가 시작돼 사라졌는지」를 가리는 데 쓴다
  const isPendingRef = useRef(isPending);
  useLayoutEffect(() => {
    isPendingRef.current = isPending;
  }, [isPending]);

  // 표로 초점을 옮긴다 (누를 때·초점 가진 버튼이 사라질 때 공통)
  const focusTable = useCallback(() => {
    const table = tableRef.current;
    if (!table) return;
    table.tabIndex = -1;
    // 스크롤 위치가 튀지 않게 포커스만 옮긴다
    table.focus({ preventScroll: true });
  }, []);

  const retryList = () => {
    // 재조회 결과를 기다린다는 표시 — 다시 실패하면 버튼으로 되돌린다
    refocusRetryRef.current = true;
    focusTable();
    refetch();
  };

  // 버튼 콜백 ref — 저절로 다시 불러와 실패 행이 사라질 때 버튼이 초점을 가졌으면 표로 옮긴다.
  // effect 는 DOM 에서 버튼이 빠진 뒤(초점이 이미 body)에 돌아 누가 초점을 가졌는지 알 수 없으므로, 노드를 떼기 직전에 불리는
  // ref 정리 함수에서 초점을 확인해 둔다. 다만 개발 모드 StrictMode 는 붙인 ref 를 한 번 떼었다 다시 붙여(노드는 그대로)
  // 정리 함수만으로는 진짜 제거와 구별되지 않는다 — 커밋이 끝난 뒤(마이크로태스크) 노드가 실제로 빠졌고 초점이 body 로
  // 떨어졌을 때만 옮긴다. 표가 같이 사라진 경우(페이지 이동)는 옮길 곳이 없으니 건드리지 않는다.
  // - 새 버튼으로 바뀌었으면(다시 마운트) 새 버튼에 초점을 준다.
  // - 재조회가 시작돼 사라졌으면 표로 옮기고 결과를 기다린다는 표시를 세운다(다시 실패하면 버튼으로).
  // - 재조회 없이 사라졌으면(데이터를 직접 채움·403 으로 바뀜) 표로만 옮긴다 — 표시를 세우면 이후 무관한 실패가 초점을 끌어간다.
  const retryButtonCallbackRef = useCallback(
    (node: HTMLButtonElement | null) => {
      retryButtonRef.current = node;
      if (!node) return;
      return () => {
        if (retryButtonRef.current === node) retryButtonRef.current = null;
        if (document.activeElement !== node) return;
        queueMicrotask(() => {
          // 진짜로 DOM 에서 빠졌고(StrictMode 재부착 아님), 초점이 body 로 떨어졌고, 옮길 표가 남아 있을 때만
          const focusLost = !document.activeElement || document.activeElement === document.body;
          const removedWhileFocused = !node.isConnected && focusLost && tableRef.current?.isConnected;
          if (!removedWhileFocused) return;
          const nextButton = retryButtonRef.current;
          if (nextButton?.isConnected) {
            nextButton.focus();
            return;
          }
          if (isPendingRef.current) refocusRetryRef.current = true;
          focusTable();
        });
      };
    },
    [focusTable],
  );

  // tabindex 는 blur 가 아니라, 포커스가 떠난 표를 마우스로 누를 때 뗀다 — blur 에 떼면 다른 창·대화상자에서 돌아올 자리가
  // 없어져 body 로 떨어지고, 늘 두면 표 빈 곳 클릭이 포커스를 가져간다.
  // 표 안 행의 삭제 대화상자 같은 포털 안 클릭은 합성 이벤트로 여기까지 버블링되므로, 실제 DOM 상 표 안에서 난 누름만 처리한다.
  const releaseTableFocusTarget = (e: React.MouseEvent<HTMLTableElement>) => {
    if (!e.currentTarget.contains(e.target as Node)) return;
    if (document.activeElement !== e.currentTarget) e.currentTarget.removeAttribute('tabindex');
  };

  useEffect(() => {
    // 재조회 중에는 기다리고, 끝난 첫 시점에 한 번만 판단한다(이후의 무관한 실패가 포커스를 옮기지 않게 표시를 먼저 끈다)
    if (isPending || !refocusRetryRef.current) return;
    refocusRetryRef.current = false;
    // 사용자가 그 사이 옮긴 포커스는 빼앗지 않는다 — 표에 그대로 있을 때만 버튼으로 돌린다
    if (showRetry && document.activeElement === tableRef.current) retryButtonRef.current?.focus();
  }, [isPending, showRetry, error]);

  // 표에 펼쳐 달 props — 초점 대상 ref, tabindex 해제 핸들러, 키보드 초점 링
  const tableProps = {
    ref: tableRef,
    onMouseDown: releaseTableFocusTarget,
    className: cn(
      'focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring',
      isPlaceholderData && 'pointer-events-none opacity-60',
      tableClassName,
    ),
    'aria-busy': isPlaceholderData || undefined,
  };

  // tbody 에 펼쳐 달 props — 이동 중 낡은 행을 키보드(Tab·Enter)로도 열 수 없게 한다. 포커스는 nav 버튼에 있어 빠지지 않는다.
  const bodyProps = { inert: isPlaceholderData };

  // TableErrorRow 의 action 에 넘길 「다시 시도」 버튼 (버튼이 없어야 할 때는 undefined)
  const retryAction = showRetry ? (
    <Button
      ref={retryButtonCallbackRef}
      size="sm"
      variant="outline"
      className="mt-3"
      disabled={isFetching}
      onClick={retryList}
    >
      다시 시도
    </Button>
  ) : undefined;

  return { showRetry, tableProps, bodyProps, retryAction };
}
