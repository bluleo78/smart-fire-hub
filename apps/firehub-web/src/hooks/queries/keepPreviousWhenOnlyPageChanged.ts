import { hashKey } from '@tanstack/react-query';

/**
 * 조회 조건에서 page 를 뺀 해시 — 필터·size 가 같은지 비교하는 기준 (WD-932).
 * TanStack Query 의 hashKey 로 비교한다 — 객체 키를 정렬해 직렬화하므로 키 순서에 좌우되지 않고,
 * undefined 값은 빠져 {a: undefined} 와 {} 가 같게 취급된다(queryKey 해시와 같은 기준).
 */
export function hashWithoutPage(o: Record<string, unknown>): string {
  return hashKey([{ ...o, page: undefined }]);
}

/**
 * page 만 바뀔 때만 이전 페이지 데이터를 유지하는 placeholderData 콜백을 만든다 (WD-932).
 *
 * 왜: 페이지 이동 중 data 가 undefined 가 되면 화면이 SimplePagination 을 언마운트했다 다시 그려,
 * 방향 버튼(처음·이전·다음·마지막)에 있던 키보드 포커스가 body 로 떨어진다. 이전 페이지 데이터를 유지해 nav 를 그대로 둔다.
 * 단 필터나 size 가 바뀌면 이전 데이터를 보여 주지 않는다 —
 * 다른 조건의 결과가 새 조건의 결과처럼 잠깐이라도 보이면 안 되기 때문이다(keepPreviousWithinEntity 와 같은 꼴).
 * 현재 조건 쪽 해시는 팩토리를 만들 때 한 번만 계산하고, 콜백은 이전 쿼리 쪽만 계산한다.
 * 계약: 쿼리 키의 마지막 원소가 조회 조건 객체다 (`[이름…, params]`).
 */
export function keepPreviousWhenOnlyPageChanged<T>(
  currentParams: Record<string, unknown>,
) {
  const currentHash = hashWithoutPage(currentParams);
  return (
    previousData: T | undefined,
    previousQuery: { queryKey: readonly unknown[] } | undefined,
  ): T | undefined => {
    const prev = previousQuery?.queryKey.at(-1);
    if (!prev || typeof prev !== 'object') return undefined;
    return hashWithoutPage(prev as Record<string, unknown>) === currentHash ? previousData : undefined;
  };
}
