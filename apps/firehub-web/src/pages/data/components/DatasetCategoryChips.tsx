import { useId, useLayoutEffect, useMemo, useRef, useState } from 'react';

import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { isFocusDropped } from '../../../lib/focus-lost';
import { cn } from '../../../lib/utils';
import type { CategoryResponse } from '../../../types/dataset';

/** 칩 공통 크기·상호작용 클래스 — WD-880: 최소 클릭 대상 32×32(h-8·min-w-8). 칩 한 줄 높이(h-8 = 32px)가 접힌 묶음 높이의 기준이다. */
const CATEGORY_CHIP_CLASS = 'h-8 min-w-8 cursor-pointer hover:opacity-80 transition-opacity';
/**
 * 접힌 묶음의 클립 상자 안쪽 여백(p-1 = 4px) — 포커스 링(ring 3px, box-shadow)이 overflow-hidden 에 잘리지 않게 칩 둘레에 둔다.
 * 같은 크기의 음수 마진(-m-1)으로 레이아웃 높이는 칩 한 줄(32px) 그대로라 표가 밀리지 않는다.
 */
const CLIP_PAD = 4;
/** 소수점 너비 오차로 「+N 더 보기」가 줄바꿈돼 잘리는 일을 막는 안전 여유(px). */
const SAFETY = 1;
/** 「+N 더 보기」·「접기」 버튼 공통 속성 — 실제 행과 측정용 복제 행이 같은 값을 써 잰 너비가 어긋나지 않게 한 곳에 둔다. */
const MORE_BUTTON_PROPS = { type: 'button', variant: 'ghost', size: 'sm', className: 'h-8 shrink-0' } as const;
/** flex gap-2 (8px) — 너비 계산에 쓰는 칩 사이 간격. */
const GAP = 8;

interface ChipItem {
  /** null 이면 「전체」 칩. */
  id: number | null;
  label: string;
}

interface Measure {
  /** 칩별 너비(items 순서). */
  widths: number[];
  /** 「+N 더 보기」 버튼 너비. */
  buttonWidth: number;
  /** 칩 묶음 컨테이너 너비. */
  containerWidth: number;
}

/**
 * 한 줄(containerWidth)에 들어가는 칩 수를 구한다.
 * 전부 들어가면 widths.length(더 보기 불필요), 아니면 「+N 더 보기」 버튼 자리까지 남기고 들어가는 최대 개수(최소 1: 「전체」).
 */
function fitCount(widths: number[], buttonWidth: number, containerWidth: number): number {
  const total = widths.reduce((s, w) => s + w, 0) + GAP * Math.max(0, widths.length - 1);
  if (total <= containerWidth) return widths.length;
  let used = buttonWidth; // 버튼은 마지막 칩 뒤 같은 줄에 놓인다
  let k = 0;
  for (const w of widths) {
    if (used + w + GAP > containerWidth) break;
    used += w + GAP;
    k++;
  }
  return Math.max(1, k);
}

/** 고른 칩(selIdx)을 「전체」 바로 뒤로 옮긴 index 순서 — 측정 전·측정 뒤 앞으로 당기기가 같은 순서를 쓰게 한 곳에 둔다 (WD-1138). */
function promotedOrder(length: number, selIdx: number): number[] {
  return [0, selIdx, ...Array.from({ length }, (_, i) => i).filter((i) => i !== 0 && i !== selIdx)];
}

/** 레이아웃 상자가 없는(display:none 등으로 숨은) 요소인지 (WD-1138). */
function isHidden(el: Element): boolean {
  return el.getClientRects().length === 0;
}

/**
 * 데이터셋 목록 카테고리 칩 묶음 — 접힌 동안 늘 한 줄(32px)로 고정하고, 넘치는 칩은 「+N 더 보기」 로 펼친다 (WD-1073).
 *
 * 왜: 카테고리가 많거나 응답이 늦게 오면 칩이 여러 줄로 늘어 아래 표를 밀어내(CLS) 버렸다. 접힌 높이를 한 줄로 고정하면
 *     응답 전(「전체」 하나)과 후의 높이가 같아 표가 밀리지 않는다.
 * 어떻게: 화면에 안 보이는 측정용 복제 행(aria-hidden·inert·visibility:hidden)에 모든 칩과 버튼을 그려 너비를 재고,
 *     컨테이너 너비(ResizeObserver)와 비교해 한 줄에 들어가는 개수만 보이게 한다. 나머지는 display:none(hidden)이라 탭 순서에서도 빠진다.
 *     복제 행으로 재므로 실제 행을 잠깐 펼쳐 재는 깜빡임이 없다.
 * 고른 칩이 숨는 쪽이면 접힌 동안 「전체」 바로 뒤로 옮겨 보이게 한다. 토글 판단은 호출부(페이지)가 한다.
 */
export function DatasetCategoryChips({
  categories,
  selectedId,
  onSelect,
}: {
  categories: CategoryResponse[];
  selectedId: number | undefined;
  /** null 이면 「전체」, 아니면 눌린 카테고리 id. */
  onSelect: (id: number | null) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [measureState, setMeasure] = useState<Measure | null>(null);
  const groupRef = useRef<HTMLDivElement>(null);
  const ghostRef = useRef<HTMLDivElement>(null);
  // WD-1138: 초점이 이 묶음 안에 있었는지 기억한다. 사용자가 Tab·클릭으로 직접 나간 경우와, 창 크기 변화로 초점 요소가
  // 사라져 body 로 떨어진 경우를 구분해 후자일 때만 「전체」로 초점을 옮기기 위함이다.
  const focusWithinRef = useRef(false);

  const items = useMemo<ChipItem[]>(
    () => [{ id: null, label: '전체' }, ...categories.map((c) => ({ id: c.id, label: c.name }))],
    [categories],
  );

  // 측정 — 복제 행의 칩·버튼 너비와 컨테이너 너비를 재고, 컨테이너·복제 행 크기가 바뀔 때(창 폭·폰트 로드) 다시 잰다.
  useLayoutEffect(() => {
    const group = groupRef.current;
    const ghost = ghostRef.current;
    if (!group || !ghost) return;
    const read = () => {
      const kids = Array.from(ghost.children) as HTMLElement[];
      const buttonEl = kids[kids.length - 1];
      const next: Measure = {
        widths: kids.slice(0, -1).map((el) => el.getBoundingClientRect().width),
        buttonWidth: buttonEl.getBoundingClientRect().width,
        // 안쪽 여백을 뺀 실제 칩이 놓이는 너비에서 안전 여유를 더 뺀다
        containerWidth: group.clientWidth - CLIP_PAD * 2 - SAFETY,
      };
      // 같은 값이면 같은 객체를 유지해 불필요한 리렌더를 막는다.
      setMeasure((prev) =>
        prev &&
        prev.buttonWidth === next.buttonWidth &&
        prev.containerWidth === next.containerWidth &&
        prev.widths.length === next.widths.length &&
        prev.widths.every((w, i) => w === next.widths[i])
          ? prev
          : next,
      );
    };
    read();
    const ro = new ResizeObserver(read);
    ro.observe(group);
    ro.observe(ghost);
    return () => ro.disconnect();
  }, [items]);

  // 접힌 동안 보일 순서와 개수 — 측정 전에는 「전체」(+고른 칩)만 보이게 해 높이를 한 줄로 유지한다.
  // 카테고리 목록이 바뀌어 측정이 낡았으면(칩 수 불일치) 무시한다 — 어긋난 너비로 계산하면 NaN 이 된다.
  const measure = measureState && measureState.widths.length === items.length ? measureState : null;
  const { order, visibleCount, overflow } = useMemo(() => {
    const selIdx = selectedId === undefined ? -1 : items.findIndex((it) => it.id === selectedId);
    if (!measure) {
      // WD-1138: 측정 전에도 고른 칩은 숨지 않게 「전체」 바로 뒤에 두고 둘만 보인다(이미 index 1 이면 순서는 그대로).
      if (selIdx > 0) return { order: promotedOrder(items.length, selIdx).map((i) => items[i]), visibleCount: 2, overflow: items.length > 2 };
      return { order: items, visibleCount: 1, overflow: items.length > 1 };
    }
    const natural = fitCount(measure.widths, measure.buttonWidth, measure.containerWidth);
    const hasOverflow = natural < items.length;
    // 고른 칩이 숨는 쪽이면 「전체」 바로 뒤로 옮기고 그 순서로 다시 센다.
    if (hasOverflow && selIdx >= natural) {
      const idxOrder = promotedOrder(items.length, selIdx);
      // 고른 칩은 반드시 보여야 하므로 아주 좁아도 「전체」+고른 칩 2개는 남긴다. 그래도 넘치면 접힌 줄은 줄바꿈하지 않고
      // 보이는 칩 이름을 말줄임해 버튼을 첫 줄에 남긴다(아래 collapsed 클래스·renderChip 의 shrinkable).
      const k = Math.max(2, fitCount(idxOrder.map((i) => measure.widths[i]), measure.buttonWidth, measure.containerWidth));
      return { order: idxOrder.map((i) => items[i]), visibleCount: k, overflow: true };
    }
    return { order: items, visibleCount: natural, overflow: hasOverflow };
  }, [measure, items, selectedId]);

  // 넘침이 없어지면(창이 넓어짐·카테고리가 줄어듦) 펼침을 접힘으로 되돌린다 — 버튼이 없어 사용자가 접을 수 없는 상태로
  // 남았다가 다시 넘칠 때 여러 줄로 펼쳐진 채 나타나지 않게 한다(렌더 중 상태 조정 — 이펙트보다 한 번 덜 그린다).
  if (expanded && !overflow) setExpanded(false);
  const collapsed = !expanded && overflow;
  const hiddenCount = items.length - visibleCount;
  // WD-1138: 묶음과 「더 보기」 버튼을 aria-controls 로 잇기 위한 id.
  const groupId = useId();

  // WD-1138: 창 크기가 바뀌어 초점을 가진 요소가 사라지거나(「접기」 버튼) 숨으면(display:none 칩) 브라우저가 초점을 body 로 떨어뜨려
  // 키보드 사용자가 위치를 잃는다 — 묶음에 초점이 있었던 경우에 한해 첫 칩(「전체」)으로 옮긴다. 매 커밋마다 확인한다(의존성 없음).
  useLayoutEffect(() => {
    if (!focusWithinRef.current) return;
    // 측정 전·낡은 측정(카테고리 수가 바뀐 직후)의 임시 렌더는 칩을 잠깐 숨기지만 칠하기 전에 다시 재서 고쳐진다 —
    // 그 사이에 초점을 옮기면 다시 보일 칩에서 초점을 빼앗으므로, 측정이 맞을 때만 판단한다.
    if (!measure) return;
    const group = groupRef.current;
    if (!group) return;
    const active = document.activeElement;
    // 안에 있는데 숨었거나(display:none), 묶음 밖이면 body 로 떨어졌을 때만(isFocusDropped — 본문 main 은 빼앗지 않음) 잃은 것이다.
    const lost = active && group.contains(active) ? isHidden(active) : isFocusDropped(active);
    // preventScroll: 표를 내려 본 채 창 폭을 바꿔도 화면이 칩 줄로 튀지 않게 한다(focusWhenLost 와 같은 방식).
    if (lost) group.querySelector<HTMLButtonElement>('button[aria-pressed]')?.focus({ preventScroll: true });
    // 판단을 마치면 기억을 실제 초점 위치로 맞춘다 — 옮기지 못했거나 이미 밖(건너뛰기 링크로 main 등)이면 false 가 돼,
    // 나중 리렌더에서 사용자가 일부러 둔 초점을 「전체」로 끌어오지 않는다.
    focusWithinRef.current = group.contains(document.activeElement);
  });

  /**
   * 칩 하나 — i 는 지금 그리는 순서의 자리. 접힌 동안 visibleCount 뒤의 칩은 숨고(display:none), 보이는 칩은 아주 좁을 때
   * 최소 폭(min-w-8, WD-880)까지 줄고 이름을 말줄임해 「+N 더 보기」(shrink-0)가 잘리지 않게 한다 (WD-1138).
   * 줄어들 수 있는 칩은 말줄임돼도 마우스로 전체 이름을 볼 수 있게 title 을 단다(「전체」는 두 글자라 달지 않는다).
   */
  const renderChip = (item: ChipItem, i: number) => {
    const hidden = collapsed && i >= visibleCount;
    const shrinkable = collapsed && !hidden;
    const pressed = item.id === null ? selectedId === undefined : selectedId === item.id;
    return (
      <Badge
        key={item.id ?? 'all'}
        asChild
        variant={pressed ? 'default' : 'outline'}
        // shrinkable: Badge 기본 shrink-0 을 풀어 CATEGORY_CHIP_CLASS 의 min-w-8(32px)까지 줄게 하고, 이름은 안쪽 span 의 truncate 로 말줄임한다
        className={cn(CATEGORY_CHIP_CLASS, hidden && 'hidden', shrinkable && 'shrink')}
      >
        <button
          type="button"
          aria-pressed={pressed}
          title={shrinkable && item.id !== null ? item.label : undefined}
          // 앞으로 당겨졌던 칩을 다시 눌러 끄면 숨는데, 초점 이동은 위 WD-1138 레이아웃 이펙트가 맡는다.
          onClick={() => onSelect(item.id)}
        >
          {shrinkable ? <span className="truncate">{item.label}</span> : item.label}
        </button>
      </Badge>
    );
  };

  return (
    <div className="relative">
      {/* 칩 묶음 — 칩은 button 이라 Tab·Enter 로 거른다(WD-396). 고른 칩은 aria-pressed 로 알리고, 묶음 이름 「카테고리」로 무엇을 거르는지 읽힌다. */}
      <div
        ref={groupRef}
        id={groupId}
        role="group"
        aria-label="카테고리"
        onFocus={() => {
          focusWithinRef.current = true;
        }}
        onBlur={(e) => {
          // 묶음 안 다른 칩으로 옮겨 가는 blur 는 무시한다.
          if (e.relatedTarget instanceof Node && e.currentTarget.contains(e.relatedTarget)) return;
          const left = e.target as HTMLElement;
          // 요소가 사라지거나 숨어서 생긴 blur(창 크기 변화)는 사용자가 나간 게 아니므로 기억을 유지한다.
          if (!left.isConnected || isHidden(left)) return;
          focusWithinRef.current = false;
        }}
        // 접힌 동안은 줄바꿈하지 않는다(flex-nowrap) — 숨은 칩은 display:none 이라 자리를 차지하지 않고, 넘치면 보이는 칩이 줄어
        // 「+N 더 보기」가 늘 첫 줄에 남는다. 펼치면 여러 줄로 감싼다.
        className={cn('flex gap-2 -m-1 p-1', collapsed ? 'flex-nowrap h-10 overflow-hidden' : 'flex-wrap')}
      >
        {/* 앞으로 당기기는 접힌 동안만 — 펼친 뒤에는 원래 순서로 둬 칩이 뛰지 않게 한다 */}
        {(collapsed ? order : items).map(renderChip)}
        {overflow && (
          <Button {...MORE_BUTTON_PROPS} aria-expanded={expanded} aria-controls={groupId} onClick={() => setExpanded((v) => !v)}>
            {expanded ? '접기' : `+${hiddenCount} 더 보기`}
          </Button>
        )}
      </div>
      {/* 측정용 복제 행 — 보이지 않고 상호작용도 막는다(aria-hidden·inert). 마지막 자식은 가장 넓은 「+N 더 보기」 버튼. */}
      {/* 복제 행은 폭이 수천 px 이라 자체 클립 상자(h-0 overflow-hidden)로 감싼다 — 안 그러면 보이지 않아도 스크롤 영역을 넓혀 목록에 가로 스크롤이 생긴다.
          overflow-hidden 을 relative 래퍼에 두지 않는 이유: 묶음의 -m-1 이 래퍼 밖으로 4px 나가 포커스 링이 다시 잘린다. */}
      <div
        aria-hidden="true"
        inert
        className="pointer-events-none absolute inset-x-0 top-0 h-0 overflow-hidden"
      >
        <div ref={ghostRef} className="invisible flex w-max gap-2">
          {items.map((item) => (
            // 실제 행과 같은 요소(button)·같은 클래스로 그려 너비가 어긋나지 않게 한다(inert 라 포커스·클릭 불가)
            <Badge key={item.id ?? 'all'} asChild variant="outline" className={CATEGORY_CHIP_CLASS}>
              <button type="button" tabIndex={-1}>
                {item.label}
              </button>
            </Badge>
          ))}
          <Button {...MORE_BUTTON_PROPS} tabIndex={-1}>
            {`+${items.length} 더 보기`}
          </Button>
        </div>
      </div>
    </div>
  );
}
