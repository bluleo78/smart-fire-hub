import { useEffect, useRef } from 'react';

import { Button } from '../components/ui/button';
import { isForbiddenError } from '../lib/api-error';

/**
 * 목록 「다시 시도」 버튼의 키보드 초점 관리 훅 (WD-957, WD-987 에서 공용화).
 *
 * 데이터 없이 실패한 목록에서 「다시 시도」를 누르면 쿼리가 pending 으로 돌아가 실패 행(과 버튼)이 사라져
 * 초점이 body 로 떨어진다(WCAG 2.4.3). 누르는 즉시 표로 초점을 옮겨 두고, 재조회가 끝나 다시 실패하면 버튼으로 되돌린다.
 *
 * 사용: `<Table {...tableProps}>` 와 `<TableErrorRow action={retryAction} />` 만 달면 된다.
 * 버튼 노출 여부(showRetry)·표 ref/mousedown/포커스 링 className·버튼 JSX 는 모두 이 훅이 만든다.
 */
export function useListRetryFocus({
  isPending,
  isError,
  isFetching,
  data,
  error,
  refetch,
}: {
  /** 쿼리가 pending(데이터 없이 조회 중) 인지 */
  isPending: boolean;
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
}) {
  // 데이터 없는 실패일 때만 버튼을 둔다. 403 은 다시 해도 같으므로 두지 않는다 (WD-137 선례)
  const showRetry = isError && !data && !isForbiddenError(error);
  const tableRef = useRef<HTMLTableElement>(null);
  const retryButtonRef = useRef<HTMLButtonElement>(null);
  // 「다시 시도」를 눌러 재조회 결과를 기다리는 중인지 표시
  const refocusRetryRef = useRef(false);

  const retryList = () => {
    refocusRetryRef.current = true;
    const table = tableRef.current;
    if (table) {
      table.tabIndex = -1;
      // 스크롤 위치가 튀지 않게 포커스만 옮긴다
      table.focus({ preventScroll: true });
    }
    refetch();
  };

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
    className: 'focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring',
  };

  // TableErrorRow 의 action 에 넘길 「다시 시도」 버튼 (버튼이 없어야 할 때는 undefined)
  const retryAction = showRetry ? (
    <Button
      ref={retryButtonRef}
      size="sm"
      variant="outline"
      className="mt-3"
      disabled={isFetching}
      onClick={retryList}
    >
      다시 시도
    </Button>
  ) : undefined;

  return { showRetry, tableProps, retryAction };
}
