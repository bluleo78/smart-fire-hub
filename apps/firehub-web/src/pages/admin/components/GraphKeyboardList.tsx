import { useRef, useState } from 'react';

export interface GraphKeyboardItem {
  /** 캔버스 노드 id와 동일한 안정 식별자 — 활성화 시 그대로 위임한다. */
  id: string;
  /** 스크린리더가 읽을 항목 라벨(이름·타입·관계 수 등 캔버스에서 시각으로만 알 수 있던 정보). */
  label: string;
}

interface Props {
  /** 목록 전체의 접근 가능한 이름 — 노드/관계 개수를 포함해 캔버스 요약을 대체한다. */
  label: string;
  items: GraphKeyboardItem[];
  /**
   * Enter/Space(버튼 기본 동작)로 항목을 활성화했을 때 호출 — 캔버스 tap 핸들러와 동일한 경로를 태운다.
   * 없으면 항목을 버튼이 아닌 정적 텍스트로 렌더해 "눌러도 아무 일 없는 버튼"을 만들지 않는다.
   */
  onActivate?: (id: string) => void;
  'data-testid'?: string;
}

/**
 * Cytoscape 캔버스의 텍스트 대체물(#326) — 캔버스는 <canvas>만 그려 키보드·스크린리더 접근 경로가 0이므로,
 * 동일 데이터(필터·검색 적용 후)를 DOM 리스트로 노출해 마우스 없이도 노드를 선택할 수 있게 한다.
 *
 * 설계:
 * - 평소에는 sr-only(시각적 숨김)로 스크린리더에만 노출하고, 포커스가 들어오면 오버레이로 드러낸다.
 *   보이지 않는 요소에 포커스가 머무는 상태(WCAG SC 2.4.7 위반, #327과 동일한 함정)를 피하기 위함.
 * - roving tabIndex — 노드가 수백 개여도 탭 스톱은 1개만 두고 ↑/↓·Home/End로 순회한다.
 *   (매 항목이 탭 스톱이면 키보드 사용자가 캔버스 뒤 UI로 넘어가지 못한다.)
 */
export default function GraphKeyboardList({ label, items, onActivate, 'data-testid': testId }: Props) {
  const [activeIndex, setActiveIndex] = useState(0); // roving tabIndex 대상
  const [focused, setFocused] = useState(false); // 포커스 진입 시 오버레이로 시각 노출
  const listRef = useRef<HTMLUListElement>(null);

  // 필터·검색으로 항목 수가 줄면 저장된 인덱스가 범위를 벗어나므로 렌더 시점에 보정한다
  // (effect에서 setState하면 연쇄 렌더가 되므로 파생값으로 계산).
  const rovingIndex = activeIndex < items.length ? activeIndex : 0;

  // 지정 인덱스로 roving 포커스 이동 — 상태와 실제 DOM 포커스를 함께 옮긴다.
  const focusItem = (index: number) => {
    setActiveIndex(index);
    listRef.current?.querySelectorAll<HTMLButtonElement>('[data-graph-item]')[index]?.focus();
  };

  // ↑/↓ 순회(양끝 순환) + Home/End. 페이지 스크롤을 막기 위해 preventDefault.
  const handleKeyDown = (e: React.KeyboardEvent<HTMLUListElement>) => {
    if (items.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      focusItem((rovingIndex + 1) % items.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      focusItem((rovingIndex - 1 + items.length) % items.length);
    } else if (e.key === 'Home') {
      e.preventDefault();
      focusItem(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      focusItem(items.length - 1);
    }
  };

  return (
    <div
      role="group"
      aria-label={label}
      data-testid={testId}
      // focusin/focusout(버블링)으로 컨테이너 내부 포커스 유무를 추적한다.
      // Tailwind focus-within 변이는 sr-only의 position:absolute를 되돌리는 순서가 불안정해 state로 제어.
      onFocus={() => setFocused(true)}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocused(false);
      }}
      className={
        focused
          ? 'absolute inset-y-2 left-2 z-10 flex w-72 flex-col rounded-md border bg-background p-2 shadow-lg'
          : 'sr-only'
      }
    >
      {/* 포커스 시 드러나는 시각 제목 — 접근 가능한 이름은 컨테이너 aria-label이 담당하므로 중복 낭독을 막는다. */}
      <p className="mb-1 shrink-0 px-1 text-xs font-medium text-muted-foreground" aria-hidden="true">
        {label}
      </p>
      {items.length === 0 ? (
        <p className="px-1 text-sm">표시할 항목이 없습니다.</p>
      ) : (
        <ul ref={listRef} className="min-h-0 flex-1 space-y-0.5 overflow-y-auto" onKeyDown={handleKeyDown}>
          {items.map((item, index) =>
            onActivate ? (
              <li key={item.id}>
                <button
                  type="button"
                  data-graph-item
                  // roving tabIndex: 활성 항목 1개만 탭 스톱으로 남긴다.
                  tabIndex={index === rovingIndex ? 0 : -1}
                  onFocus={() => setActiveIndex(index)}
                  onClick={() => onActivate(item.id)}
                  className="w-full rounded px-2 py-1.5 text-left text-sm hover:bg-muted focus-visible:bg-muted"
                >
                  {item.label}
                </button>
              </li>
            ) : (
              <li key={item.id} className="px-2 py-1.5 text-sm">
                {item.label}
              </li>
            ),
          )}
        </ul>
      )}
    </div>
  );
}
