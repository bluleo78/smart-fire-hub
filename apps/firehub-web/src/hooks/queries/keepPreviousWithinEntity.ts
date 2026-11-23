/**
 * 같은 엔티티 안에서 페이지를 넘길 때만 이전 데이터를 유지하는 placeholderData 콜백을 만든다 (WD-535·WD-884).
 *
 * 왜: keepPreviousData 는 다른 엔티티(다른 데이터셋·파이프라인)로 옮겨 가도 이전 엔티티의 행을 그대로 보여 준다.
 * 새 응답이 오기 전 B 화면에 A 의 이력·링크·취소 버튼이 보이면 잘못된 id 로 동작할 수 있다.
 *
 * 계약: 쿼리 키의 `keyIndex` 번째 원소가 엔티티 id 여야 한다. 기본은 두 번째 원소(`queryKey[1]`)
 * (예: `['datasets', datasetId, 'imports', ...]`, `['pipelines', pipelineId, 'executions', ...]`).
 * id 가 세 번째에 있는 키는 `keyIndex` 를 넘긴다 (예: `['proactive', 'executions', jobId, 'list', ...]` → 2, WD-965).
 */
export function keepPreviousWithinEntity<T>(entityId: number, keyIndex = 1) {
  return (previousData: T | undefined, previousQuery: { queryKey: readonly unknown[] } | undefined): T | undefined =>
    previousQuery?.queryKey[keyIndex] === entityId ? previousData : undefined;
}
