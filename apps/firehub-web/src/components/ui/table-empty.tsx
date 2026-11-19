import { SearchX } from 'lucide-react';
import { type ReactNode, useEffect, useState } from 'react';

import { Button } from './button';
import { TableCell,TableRow } from './table';

/**
 * 라이브 리전에 문구를 채우기까지의 지연(ms) (WD-815).
 * setTimeout 0 은 Chromium 이 빈 status 영역을 접근성 트리에 반영하기 전에 돌 수 있어
 * 「내용과 함께 생성된 영역」으로 보여 낭독되지 않는다. react-aria 의 live announcer 등 관행처럼
 * 빈 영역이 먼저 보조기술에 전달될 시간을 준다.
 */
export const ANNOUNCE_DELAY_MS = 100;

interface TableEmptyRowProps {
  colSpan: number;
  /** 데이터가 절대값 0건일 때 노출할 메시지 (기본: "데이터가 없습니다.") */
  message?: string;
  /** 검색어가 있을 때 우선적으로 노출할 검색어 — 있으면 검색 결과 빈 상태 UI 사용 */
  searchKeyword?: string;
  /** 검색어 초기화 콜백 — 제공 시 "검색 초기화" 버튼 노출 */
  onResetSearch?: () => void;
  /** 진짜 데이터 0건 상태에서 노출할 추가 CTA (예: "새 데이터셋 만들기") */
  emptyAction?: ReactNode;
}

/**
 * 테이블 빈 상태 행.
 *
 * - searchKeyword가 있으면 검색 결과 0건 UI ("'<keyword>'에 대한 결과가 없습니다." + "검색 초기화" 버튼)
 * - 그 외에는 기본 메시지 (+ emptyAction CTA)
 *
 * [WD-815] 스크린리더가 "0건" 안내를 듣도록 안내 문구 컨테이너를 role="status" 라이브 리전으로 두고,
 * 빈 채 마운트한 뒤 ANNOUNCE_DELAY_MS 뒤에 문구를 채운다(이유는 상수 주석).
 * 버튼·emptyAction·아이콘은 status 밖에 두어 마운트 시점부터 보이고 낭독 대상에도 섞이지 않게 한다.
 * 문구는 DOM 에 한 벌만 존재한다(sr-only 중복 없음).
 */
export function TableEmptyRow({
  colSpan,
  message = '데이터가 없습니다.',
  searchKeyword,
  onResetSearch,
  emptyAction,
}: TableEmptyRowProps) {
  const isSearchEmpty = !!searchKeyword;

  // 라이브 리전을 빈 채로 먼저 마운트하고 잠시 뒤 문구를 채우기 위한 플래그 (WD-815)
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setReady(true), ANNOUNCE_DELAY_MS);
    // 지연 전에 언마운트되면 setState 가 일어나지 않도록 정리
    return () => clearTimeout(t);
  }, []);

  return (
    <TableRow>
      <TableCell colSpan={colSpan} className="text-center text-muted-foreground py-12">
        {/* 검색 0건 ↔ 기본 빈 상태가 바뀌어도 status div 가 같은 자식 순번에 하나만 있도록
            분기 밖으로 끌어올렸다 — 이 행이 마운트된 동안에는 같은 요소가 재사용돼 문구 변경이 라이브 리전 변경으로 낭독된다.
            호출부가 로딩 중 스켈레톤으로 바꿔 행을 다시 마운트하면 새 영역이 생기고 위 지연 뒤 채우기로 낭독된다. */}
        <div className="flex flex-col items-center gap-3">
          {isSearchEmpty && <SearchX className="h-8 w-8 text-muted-foreground/60" aria-hidden="true" />}
          <div
            role="status"
            aria-live="polite"
            aria-atomic="true"
            className={isSearchEmpty ? 'space-y-1 min-h-10' : 'min-h-5'}
          >
            {ready &&
              (isSearchEmpty ? (
                <>
                  <p className="text-sm">
                    <span className="font-medium text-foreground">'{searchKeyword}'</span>
                    <span>에 대한 결과가 없습니다.</span>
                  </p>
                  <p className="text-xs">다른 키워드로 검색해 보세요.</p>
                </>
              ) : (
                <p className="text-sm">{message}</p>
              ))}
          </div>
          {isSearchEmpty && onResetSearch && (
            <Button variant="outline" size="sm" onClick={onResetSearch}>
              검색 초기화
            </Button>
          )}
          {!isSearchEmpty && emptyAction}
        </div>
      </TableCell>
    </TableRow>
  );
}
