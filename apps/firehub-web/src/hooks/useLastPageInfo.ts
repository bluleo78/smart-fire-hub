import { useState } from 'react';

import { hashWithoutPage } from './queries/keepPreviousWhenOnlyPageChanged';

/** 쪽 정보(총 쪽 수·총 건수) */
interface PageInfo {
  totalPages: number;
  totalElements: number;
}

/**
 * 마지막으로 받은 쪽 정보(totalPages·totalElements)를 기억해 돌려준다 (WD-982).
 *
 * 왜: 다음 쪽 조회가 실패하면 data 가 undefined 가 돼 totalPages 가 0 으로 떨어지고,
 * 쪽 이동(SimplePagination)이 사라져 사용자가 이전 쪽으로 돌아갈 수 없다. 마지막 성공 값을 남겨 이동을 유지한다.
 * totalElements 도 함께 기억하는 이유: SimplePagination 은 totalElements 유무로 showCount 갈래(다른 감싸는 구조)가
 * 바뀌어 nav 가 다시 마운트되고, 「다음 페이지」에 둔 초점이 BODY 로 떨어진다. 실패 때도 같은 값을 넘겨 구조를 유지한다.
 * 단 조회 조건(params 에서 page 를 뺀 해시)이 바뀌면 기억을 비운다 — 옛 조건의 쪽 정보가 새 조건 실패 화면에 남으면 안 된다.
 * params 는 화면이 목록 훅에 넘기는 바로 그 객체를 넘긴다(키 기준이 placeholderData 와 같아진다).
 * effect 없이 렌더 중 setState 로 처리한다(react-hooks/set-state-in-effect 규칙, PipelineListPage 와 같은 꼴).
 */
export function useLastPageInfo(
  data: PageInfo | undefined,
  params: Record<string, unknown>,
): { totalPages: number; totalElements: number | undefined } {
  const resetKey = hashWithoutPage(params);
  const [memo, setMemo] = useState<{ key: string; value: PageInfo | undefined }>({ key: resetKey, value: undefined });

  // 조건이 바뀐 렌더에는 기억을 비우고, 데이터가 오면 그 값을 기억한다.
  let current = memo;
  if (memo.key !== resetKey) current = { key: resetKey, value: undefined };
  if (data !== undefined && (current.value?.totalPages !== data.totalPages || current.value?.totalElements !== data.totalElements)) {
    current = { key: resetKey, value: { totalPages: data.totalPages, totalElements: data.totalElements } };
  }
  if (current !== memo) setMemo(current);

  return { totalPages: current.value?.totalPages ?? 0, totalElements: current.value?.totalElements };
}
