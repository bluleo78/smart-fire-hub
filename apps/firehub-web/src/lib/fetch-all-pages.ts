import type { PageResponse } from '../types/common';

/** 무한 순회 방지용 기본 페이지 상한 — 서버 상한 크기 × 100 페이지까지만 모은다. */
const DEFAULT_MAX_PAGES = 100;

/**
 * 페이지 목록 API 의 전 페이지를 받아 하나의 배열로 합친다 (#732, #737).
 *
 * 왜: 선택 목록(드롭다운·콤보박스)은 "전부"가 필요한데, 서버는 목록 조회 size 에 상한을 둔다
 * (초과 시 조용히 자르거나 400). 큰 size 한 번으로 받으려 하면 101번째 이후가 조용히 빠지거나
 * 목록이 통째로 비었다. 서버 상한(보호 장치)은 그대로 두고, 상한 이하 크기로 `totalPages` 만큼 순회한다.
 *
 * @param fetchPage 0-based 페이지 번호와 크기로 한 페이지를 조회하는 함수
 * @param pageSize 서버 상한 이하의 페이지 크기
 * @param maxPages 안전 상한(기본 100 페이지)
 */
export async function fetchAllPages<T extends { id: number }>(
  fetchPage: (page: number, size: number) => Promise<PageResponse<T>>,
  pageSize: number,
  maxPages: number = DEFAULT_MAX_PAGES,
): Promise<T[]> {
  const first = await fetchPage(0, pageSize);
  const content = [...first.content];
  const lastPage = Math.min(first.totalPages, maxPages);
  // 두 번째 페이지부터는 서로 독립이므로 병렬로 받아 원래 순서대로 이어 붙인다
  const rest = await Promise.all(
    Array.from({ length: Math.max(0, lastPage - 1) }, (_, i) =>
      fetchPage(i + 1, pageSize).then((p) => p.content),
    ),
  );
  for (const pageContent of rest) content.push(...pageContent);
  // 페이지 사이에 생성·삭제가 끼면 경계에서 같은 항목이 두 번 올 수 있어 id 로 중복 제거
  const seen = new Set<number>();
  return content.filter((item) => (seen.has(item.id) ? false : (seen.add(item.id), true)));
}
