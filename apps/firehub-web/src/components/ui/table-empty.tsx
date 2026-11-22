import { SearchX } from 'lucide-react';
import { type ReactNode, useEffect, useState } from 'react';

import { Button } from './button';
import { TableCell,TableRow } from './table';

/**
 * 라이브 리전 안 문구를 보조기술에 드러내기까지의 지연(ms) (WD-815).
 * setTimeout 0 은 Chromium 이 빈 status 영역을 접근성 트리에 반영하기 전에 돌 수 있어
 * 「내용과 함께 생성된 영역」으로 보여 낭독되지 않는다. react-aria 의 live announcer 등 관행처럼
 * 빈 영역이 먼저 보조기술에 전달될 시간을 준다. 화면에는 문구가 처음부터 보인다(지연은 낭독에만).
 */
export const ANNOUNCE_DELAY_MS = 100;

interface DelayedStatusProps {
  children: ReactNode;
  /** 바뀌면 문구가 같아도 새 노드로 갈아 끼워 다시 낭독한다 */
  announceKey?: string;
  /** 내용 노드(낭독되는 노드)에 붙일 클래스 */
  className?: string;
  /** 라이브 리전 역할 — 기본 status(정중한 안내), 실패처럼 바로 알려야 하면 alert (WD-930) */
  role?: 'status' | 'alert';
}

/**
 * 빈 상태 안내 문구를 role="status" 라이브 리전 안에 두어 스크린리더가 낭독하게 하는 공용 컴포넌트.
 * role="alert" 를 넘기면 같은 지연 낭독 방식으로 실패 안내를 aria-live="assertive" 로 알린다(WD-930).
 *
 * [WD-815] 내용과 함께 마운트된 status 영역은 낭독되지 않을 수 있다.
 * - 문구는 마운트 때부터 화면에 그리되 ANNOUNCE_DELAY_MS 동안은 aria-hidden 노드로 두고, 지연 뒤 key 가 다른
 *   숨기지 않은 노드로 갈아 끼운다. 보조기술에는 빈 영역에 문구 노드가 실제로 추가된 것으로 보여 낭독되고
 *   (aria-hidden 속성만 떼면 일부 화면 읽기 프로그램이 낭독하지 않는다), 화면에서는 한 커밋에 바뀌어 깜빡이지 않는다.
 * - status 는 분기 밖 같은 자리에 하나만 두어, 마운트된 동안 문구가 바뀌면 같은 영역의 변화로 낭독된다.
 */
export function DelayedStatus({ children, announceKey, className, role = 'status' }: DelayedStatusProps) {
  // 지연 뒤 숨긴 노드를 낭독되는 노드로 갈아 끼우기 위한 플래그
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setReady(true), ANNOUNCE_DELAY_MS);
    // 지연 전에 언마운트되면 setState 가 일어나지 않도록 정리
    return () => clearTimeout(t);
  }, []);

  return (
    <div role={role} aria-live={role === 'alert' ? 'assertive' : 'polite'} aria-atomic="true">
      {/* announceKey 가 바뀌어도 새 노드가 들어와 같은 문구를 다시 낭독한다 */}
      <div
        key={ready ? `announced:${announceKey ?? ''}` : 'pending'}
        className={className}
        aria-hidden={ready ? undefined : true}
      >
        {children}
      </div>
    </div>
  );
}

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
  /**
   * 바뀌면 문구가 같아도 다시 낭독한다 — 고정 message 를 쓰면서 0건인 채 검색어만 바뀌는 호출부가 검색어를 넘긴다.
   * (searchKeyword 를 쓰면 문구 자체가 바뀌어 낭독되므로 필요 없다)
   */
  announceKey?: string;
}

/**
 * 테이블 빈 상태 행.
 *
 * - searchKeyword가 있으면 검색 결과 0건 UI ("'<keyword>'에 대한 결과가 없습니다." + "검색 초기화" 버튼)
 * - 그 외에는 기본 메시지 (+ emptyAction CTA)
 *
 * [WD-815] 스크린리더가 "0건" 안내를 듣도록 안내 문구를 role="status" 라이브 리전 안에 둔다.
 * - 문구는 마운트 때부터 화면에 그리되 ANNOUNCE_DELAY_MS 동안은 aria-hidden 노드로 두고, 지연 뒤 key 가 다른
 *   숨기지 않은 노드로 갈아 끼운다. 보조기술에는 빈 영역에 문구 노드가 실제로 추가된 것으로 보여 낭독되고
 *   (aria-hidden 속성만 떼면 일부 화면 읽기 프로그램이 낭독하지 않는다), 화면에서는 한 커밋에 바뀌어 깜빡이지 않는다.
 * - status 는 분기 밖 같은 자리에 하나만 두어, 행이 마운트된 동안 문구가 바뀌면 같은 영역의 변화로 낭독된다.
 * - 버튼·emptyAction·아이콘은 status 밖이고, 문구는 DOM 에 한 벌만 있다(sr-only 중복 없음).
 */
export function TableEmptyRow({
  colSpan,
  message = '데이터가 없습니다.',
  searchKeyword,
  onResetSearch,
  emptyAction,
  announceKey,
}: TableEmptyRowProps) {
  const isSearchEmpty = !!searchKeyword;

  return (
    <TableRow>
      <TableCell colSpan={colSpan} className="text-center text-muted-foreground py-12">
        <div className="flex flex-col items-center gap-3">
          {isSearchEmpty && <SearchX className="h-8 w-8 text-muted-foreground/60" aria-hidden="true" />}
          <DelayedStatus announceKey={announceKey} className={isSearchEmpty ? 'space-y-1' : undefined}>
            {isSearchEmpty ? (
              <>
                <p className="text-sm">
                  <span className="font-medium text-foreground">'{searchKeyword}'</span>
                  <span>에 대한 결과가 없습니다.</span>
                </p>
                <p className="text-xs">다른 키워드로 검색해 보세요.</p>
              </>
            ) : (
              <p className="text-sm">{message}</p>
            )}
          </DelayedStatus>
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

interface TableErrorRowProps {
  colSpan: number;
  /** 조회 실패 시 노출할 메시지 (기본: "데이터를 불러오는 데 실패했습니다.") */
  message?: string;
}

/**
 * 테이블 조회 실패 행 (WD-930).
 *
 * 실패 문구를 role="alert" 라이브 리전 안에 두어 스크린리더 사용자가 조회 실패를 알게 한다(WCAG 4.1.3).
 * - alert 영역은 td 안쪽에 둔다 — td 에 role 을 덮어쓰면 표의 cell 역할이 깨지기 때문이다.
 * - 내용과 함께 마운트된 라이브 리전 낭독 누락(WD-815)을 피하려 TableEmptyRow 와 같은 DelayedStatus 를 쓴다.
 */
export function TableErrorRow({
  colSpan,
  message = '데이터를 불러오는 데 실패했습니다.',
}: TableErrorRowProps) {
  return (
    <TableRow>
      <TableCell colSpan={colSpan} className="text-center text-destructive">
        <DelayedStatus role="alert">{message}</DelayedStatus>
      </TableCell>
    </TableRow>
  );
}
