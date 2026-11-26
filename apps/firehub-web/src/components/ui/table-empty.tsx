import { SearchX } from 'lucide-react';
import { type ReactNode, type RefObject, useEffect, useState } from 'react';

import { cn } from '@/lib/utils';

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

/**
 * 「검색 초기화」 버튼과 그 뒤 초점 대상은 함께 넘긴다 (WD-985 리뷰 후속).
 * 초기화로 검색어가 사라지면 버튼이 든 빈 행이 함께 사라져 초점이 body 로 떨어지므로,
 * onResetSearch 를 넘기는 자리(값이 조건부로 undefined 여도)는 resetFocusRef 를 반드시 함께 넘기게 타입으로 묶는다
 * — 템플릿을 따라 만든 새 화면이 ref 를 빠뜨려 같은 초점 유실을 되풀이하지 않게.
 */
export type SearchResetProps =
  | { onResetSearch?: undefined; resetFocusRef?: undefined }
  | {
      /** 검색어 초기화 콜백 — 제공 시 "검색 초기화" 버튼 노출 */
      onResetSearch: (() => void) | undefined;
      /** 「검색 초기화」 뒤 초점을 받을 검색 입력의 ref (WD-985). current 가 비어 있으면 초점이 body 로 떨어지니 꼭 붙인다. */
      resetFocusRef: RefObject<HTMLElement | null>;
    };

type TableEmptyRowProps = SearchResetProps & {
  colSpan: number;
  /** 데이터가 절대값 0건일 때 노출할 메시지 (기본: "데이터가 없습니다.") */
  message?: string;
  /** 검색어가 있을 때 우선적으로 노출할 검색어 — 있으면 검색 결과 빈 상태 UI 사용 */
  searchKeyword?: string;
  /** 진짜 데이터 0건 상태에서 노출할 추가 CTA (예: "새 데이터셋 만들기") */
  emptyAction?: ReactNode;
  /**
   * 바뀌면 문구가 같아도 다시 낭독한다 — 고정 message 를 쓰면서 0건인 채 검색어만 바뀌는 호출부가 검색어를 넘긴다.
   * (searchKeyword 를 쓰면 문구 자체가 바뀌어 낭독되므로 필요 없다)
   */
  announceKey?: string;
};

/**
 * 검색 0건 안내 — 아이콘 + 「'키워드'에 대한 결과가 없습니다.」 라이브 리전 + 「검색 초기화」 버튼 (WD-1127).
 *
 * 왜 꺼냈나: 그래프 탐색 등 표 밖의 검색 0건도 표와 같은 카피·낭독 방식·초점 규칙(WD-985)을 쓰게 하려고
 * TableEmptyRow 의 검색 분기를 그대로 옮겼다. 표 안 DOM·문구·낭독 동작은 이전과 같다.
 * - 아이콘·DelayedStatus·버튼을 Fragment 형제로 돌려준다 — 감싸는 레이아웃(flex 등)은 호출부가 맡는다.
 * - 버튼은 onResetSearch 가 있을 때만 나온다. 클릭하면 검색어를 먼저 지우고 resetFocusRef 요소로 초점을 옮긴다(WD-985).
 * - 긴 검색어 감김은 SearchNoResultText 의 [overflow-wrap:anywhere] 가 맡는다(이유는 그쪽 JSDoc).
 */
export function SearchNoResult({
  searchKeyword,
  onResetSearch,
  resetFocusRef,
  announceKey,
  iconClassName,
}: SearchResetProps & {
  searchKeyword: string;
  /** 바뀌면 문구가 같아도 다시 낭독한다 */
  announceKey?: string;
  /** 아이콘 클래스 덮어쓰기 (기본 h-8 w-8 text-muted-foreground/60) */
  iconClassName?: string;
}) {
  return (
    <>
      <SearchNoResultIcon className={iconClassName} />
      <DelayedStatus announceKey={announceKey} className="space-y-1">
        <SearchNoResultText searchKeyword={searchKeyword} />
      </DelayedStatus>
      {onResetSearch && <ResetButton label="검색 초기화" onReset={onResetSearch} focusRef={resetFocusRef} />}
    </>
  );
}

/** 검색 0건 아이콘 — SearchNoResult 와 TableEmptyRow 가 공유한다 (WD-1127) */
function SearchNoResultIcon({ className }: { className?: string }) {
  return <SearchX className={className ?? 'h-8 w-8 text-muted-foreground/60'} aria-hidden="true" />;
}

/** 검색 0건 문구 — DelayedStatus 안에 둘 내용. 공백 없는 긴 검색어는 [overflow-wrap:anywhere] 가 감는다: flex 열에서는 break-words 가 min-content 폭을 줄이지 못해 좁은 오버레이에서 넘치기 때문이다 (WD-1127) */
function SearchNoResultText({ searchKeyword }: { searchKeyword: string }) {
  return (
    <>
      <p className="text-sm [overflow-wrap:anywhere]">
        <span className="font-medium text-foreground">'{searchKeyword}'</span>
        <span>에 대한 결과가 없습니다.</span>
      </p>
      <p className="text-xs">다른 키워드로 검색해 보세요.</p>
    </>
  );
}

/** 초기화 버튼 — 먼저 되돌린 뒤, 그다음 초점을 검색 입력으로 옮긴다(WD-985). 검색 초기화·타입 필터 초기화(WD-1127)가 함께 쓴다 */
export function ResetButton({
  label,
  onReset,
  focusRef,
}: {
  label: string;
  onReset: () => void;
  focusRef?: RefObject<HTMLElement | null>;
}) {
  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      onClick={() => {
        onReset();
        focusRef?.current?.focus();
      }}
    >
      {label}
    </Button>
  );
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
 * - [WD-1012] 내용은 TableVisibleWidthCell 안에 두어 긴 검색어도 보이는 폭 안에서 감기고 「검색 초기화」가 잘리지 않는다(셀 좌우 여백 0).
 */
export function TableEmptyRow({
  colSpan,
  message = '데이터가 없습니다.',
  searchKeyword,
  onResetSearch,
  resetFocusRef,
  emptyAction,
  announceKey,
}: TableEmptyRowProps) {
  // 검색어가 있으면 검색 0건 UI, 없으면 기본 빈 상태
  const isSearchEmpty = !!searchKeyword;

  return (
    <TableRow>
      <TableVisibleWidthCell
        colSpan={colSpan}
        cellClassName="text-center text-muted-foreground py-12"
        className="flex flex-col items-center gap-3"
      >
        {/* 표 안 DOM 구조(아이콘·status·버튼 위치)는 그대로 둔다 — status 는 분기 밖 같은 자리라 검색어 유무가 바뀌어도 같은 요소다(WD-815).
            조각은 표 밖에서 쓰는 SearchNoResult 와 공유한다(WD-1127) */}
        {isSearchEmpty && <SearchNoResultIcon />}
        <DelayedStatus announceKey={announceKey} className={isSearchEmpty ? 'space-y-1' : undefined}>
          {isSearchEmpty ? <SearchNoResultText searchKeyword={searchKeyword} /> : <p className="text-sm">{message}</p>}
        </DelayedStatus>
        {isSearchEmpty && onResetSearch && <ResetButton label="검색 초기화" onReset={onResetSearch} focusRef={resetFocusRef} />}
        {!isSearchEmpty && emptyAction}
      </TableVisibleWidthCell>
    </TableRow>
  );
}

/**
 * 보이는 폭 셀 (WD-958, WD-1012) — 셀(px-0)과 그 안 내용 상자를 함께 그린다. 실패·빈 상태 행이 공유하는 내보내지 않는 내부 컴포넌트.
 *
 * 전제:
 * - 공용 `Table`(components/ui/table.tsx) 안에서만 쓴다 — 그 스크롤 상자가 이 상자(data-slot="table-visible-content")가
 *   있을 때만 크기 컨테이너가 되어 100cqw 가 보이는 폭이 된다. 맨 <table> 이나 공용 Table 밖에서 쓰면 100cqw 가 바깥 컨테이너·뷰포트 폭으로 풀려 폭이 어긋난다.
 * - 셀 좌우 여백은 0(px-0 — 이 컴포넌트가 붙인다)이고 안쪽 여백은 내용 상자가 맡는다 — 상자 폭이 셀 여백에 묶이지 않는다.
 *   반응형 변형(sm:px-6 등)은 덮지 못하므로 좌우 여백은 cellClassName 으로 주지 않는다.
 *
 * 클래스 이유:
 * - data-slot: table.tsx 의 has-[…] 가 이 이름으로 스크롤 상자를 크기 컨테이너로 켠다 — 바꾸면 함께.
 * - sticky left-0 w-[100cqw] max-w-full: 가로 스크롤돼도 보이는 폭 그대로 붙어 긴 문구는 늘어나지 않고 줄바꿈된다.
 * - px-10: 스크롤 페이드(w-10)·힌트 배지가 문구·버튼 양끝을 덮지 않게 양쪽 2.5rem 안쪽에만 둔다.
 * - whitespace-normal + overflow-wrap:anywhere: 공백 없는 긴 토큰(trace 등)도 감는다. mx-auto 는 쓰지 않는다(표가 더 넓으면 가운데가 보이는 영역 밖으로 밀림).
 */
function TableVisibleWidthCell({
  colSpan,
  cellClassName,
  className,
  children,
}: {
  colSpan: number;
  /** 셀(td)에 더할 클래스 — 정렬·세로 여백·글자색 등. 좌우 여백은 px-0 으로 덮인다(아래 전제) */
  cellClassName?: string;
  /** 내용 상자에 더할 클래스 — 안쪽 배치(flex 등) */
  className?: string;
  children: ReactNode;
}) {
  return (
    // px-0 를 마지막에 둬 cellClassName 의 px-* 를 덮는다 — 셀 여백과 상자 px-10 은 한 규칙이라 이 컴포넌트가 함께 맡는다
    <TableCell colSpan={colSpan} className={cn(cellClassName, 'px-0')}>
      <div
        data-slot="table-visible-content"
        className={cn('sticky left-0 w-[100cqw] max-w-full px-10 whitespace-normal [overflow-wrap:anywhere]', className)}
      >
        {children}
      </div>
    </TableCell>
  );
}

interface TableErrorRowProps {
  colSpan: number;
  /** 조회 실패 시 노출할 메시지 (기본: "데이터를 불러오는 데 실패했습니다.") */
  message?: string;
  /** 문구 글자 톤 — 기본 error(빨강), 권한 없음처럼 흐린 안내는 muted */
  tone?: 'error' | 'muted';
  /** 셀에 더할 클래스 — 세로 여백(py-*) 등. 좌우 여백은 전제 참고 */
  className?: string;
  /**
   * 문구 옆에 둘 동작 (예: "다시 시도" 버튼).
   * alert 밖, DelayedStatus 바로 뒤 형제로 둔다 — DelayedStatus 는 지연 동안 내용을 aria-hidden 으로 두고
   * 지연 뒤 노드를 갈아 끼우므로 안에 넣으면 버튼이 숨겨졌다 다시 만들어져 초점·낭독이 끊긴다.
   */
  action?: ReactNode;
}

/**
 * 테이블 조회 실패 행 (WD-930).
 *
 * 실패 문구를 role="alert"(muted 톤은 role="status", WD-990) 라이브 리전 안에 두어 스크린리더 사용자가 조회 실패를 알게 한다(WCAG 4.1.3).
 * - alert 영역은 td 안쪽에 둔다 — td 에 role 을 덮어쓰면 표의 cell 역할이 깨지기 때문이다.
 * - 톤 클래스는 문구 노드(DelayedStatus 의 className)에만 둔다 — td 에 두면 outline 버튼(color: inherit)인
 *   action 이 빨간/흐린 글자를 물려받아 예전 모양(버튼은 본문 글자색)이 바뀐다.
 * - 내용과 함께 마운트된 라이브 리전 낭독 누락(WD-815)을 피하려 TableEmptyRow 와 같은 DelayedStatus 를 쓴다.
 *
 * 전제 (WD-958): 공용 `Table` 안에서만 쓰고 셀 좌우 여백은 0(px-0, className 뒤에 둬 px-* 를 덮는다)이다 —
 * 자세한 이유는 TableVisibleWidthCell JSDoc. 반응형 변형(sm:px-6 등)은 덮지 못하므로 좌우 여백은 className 으로 주지 않는다.
 */
export function TableErrorRow({
  colSpan,
  message = '데이터를 불러오는 데 실패했습니다.',
  tone = 'error',
  className,
  action,
}: TableErrorRowProps) {
  // 흐린 권한 안내인지 — 낭독 방식(role)과 색을 함께 정한다 (WD-990)
  const muted = tone === 'muted';
  return (
    <TableRow>
      <TableVisibleWidthCell colSpan={colSpan} cellClassName={cn('text-center', className)}>
        {/* WD-990: 오류(error)만 즉시 끊어 읽는 alert·assertive. 권한 없음 같은 muted 안내는 장애가 아니라 정보라 status·polite 로 읽어 진행 중 낭독을 끊지 않는다 */}
        {/* WD-990 리뷰: tone 을 key 로 둬 톤이 바뀌면 라이브 리전을 새로 마운트한다 — role 만 바뀐 같은 노드는 새 긴급도로 다시 낭독되지 않을 수 있다 */}
        <DelayedStatus key={tone} role={muted ? 'status' : 'alert'} className={muted ? 'text-muted-foreground' : 'text-destructive'}>
          {message}
        </DelayedStatus>
        {action}
      </TableVisibleWidthCell>
    </TableRow>
  );
}
