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
  /** 이 측정을 낸 items 배열 — 신원(===)으로 비교해 카테고리가 바뀐(수·이름·순서 무엇이든) 낡은 측정을 가린다. */
  items: ChipItem[];
  /** 칩별 너비(items 순서). */
  widths: number[];
  /** 「+N 더 보기」 버튼 너비. */
  buttonWidth: number;
  /** 짧은 「+N」 버튼 너비 — 아주 좁은 칸에서 「+N 더 보기」 전체 버튼 대신 쓴다 (WD-1151). */
  compactButtonWidth: number;
  /** 칩 묶음 컨테이너 너비. */
  containerWidth: number;
  /** 보이는 칩이 줄어드는 최소 너비 — min-w-8 은 rem 이라 루트 글자 크기를 따르므로 복제 칩의 계산값을 잰다 (WD-1151). */
  minChipWidth: number;
}

/**
 * 한 줄(containerWidth)에 들어가는 칩 수를 구한다.
 * 전부 들어가면 widths.length(더 보기 불필요), 아니면 「+N 더 보기」 버튼 자리까지 남기고 들어가는 최대 개수(0 일 수 있다).
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
  // 최소 개수 보장은 호출부(fitVisible)가 한다 — 여기서 올리면 「버튼과 최소 칩이 안 들어감」을 알 수 없다 (WD-1151).
  return k;
}

/** 칩 최소 너비를 재지 못했을 때 쓰는 값(px) — CATEGORY_CHIP_CLASS 의 min-w-8 를 기본 글자 크기(16px)로 본 값 (WD-880). */
const MIN_CHIP_WIDTH_FALLBACK = 32;

/**
 * 접힌 줄에 남길 칩 수와 버튼 모양을 정한다 (WD-1151).
 * 짧은 버튼(「+N」)은 「최소 칩(minCount 개)을 최소 너비(32px)까지 줄이고도 전체 버튼(「+N 더 보기」)이 안 들어갈 때」에만 쓴다.
 * 보이는 칩은 좁으면 말줄임으로 줄어 버튼 자리를 내므로, 자연 너비로 비교하면 중간 폭에서 괜히 짧아진다.
 * 개수는 두 경우 모두 Math.max(minCount, k) 로 최소 개수를 지킨다.
 * 한계: 최소 칩이 32px 씩으로도 짧은 버튼과 함께 못 들어가는 극단적으로 좁은 칸(고른 칩 있을 때 약 110px 미만)은 지원하지 않는다 —
 * 그때는 버튼이 칸 밖으로 잘릴 수 있다(설계상 비목표).
 */
function fitVisible(
  widths: number[],
  m: Measure,
  minCount: number,
): { count: number; compact: boolean } {
  const full = fitCount(widths, m.buttonWidth, m.containerWidth);
  // 전부 들어가면 버튼이 필요 없다
  if (full === widths.length) return { count: full, compact: false };
  // 최소 칩(각 최소 너비까지 줄임) + 칩 사이 간격 + 버튼 앞 간격(= minCount 개의 GAP) + 전체 버튼
  const shrunkMin =
    widths.slice(0, minCount).reduce((sum, w) => sum + Math.min(w, m.minChipWidth), 0) + GAP * minCount;
  if (shrunkMin + m.buttonWidth <= m.containerWidth) return { count: Math.max(minCount, full), compact: false };
  const short = fitCount(widths, m.compactButtonWidth, m.containerWidth);
  return { count: Math.max(minCount, short), compact: true };
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
      // 복제 행 끝 두 자식: 전체 버튼(「+N 더 보기」), 짧은 버튼(「+N」)
      const buttonEl = kids[kids.length - 2];
      const compactEl = kids[kids.length - 1];
      const next: Measure = {
        items,
        widths: kids.slice(0, -2).map((el) => el.getBoundingClientRect().width),
        buttonWidth: buttonEl.getBoundingClientRect().width,
        compactButtonWidth: compactEl.getBoundingClientRect().width,
        // 안쪽 여백을 뺀 실제 칩이 놓이는 너비에서 안전 여유를 더 뺀다
        containerWidth: group.clientWidth - CLIP_PAD * 2 - SAFETY,
        // 복제 칩은 실제 칩과 같은 클래스라 계산된 min-width(px)가 실제 칩의 최소 너비와 같다
        minChipWidth: parseFloat(getComputedStyle(kids[0]).minWidth) || MIN_CHIP_WIDTH_FALLBACK,
      };
      // 같은 값이면 같은 객체를 유지해 불필요한 리렌더를 막는다.
      setMeasure((prev) =>
        prev &&
        prev.buttonWidth === next.buttonWidth &&
        prev.compactButtonWidth === next.compactButtonWidth &&
        prev.containerWidth === next.containerWidth &&
        prev.minChipWidth === next.minChipWidth &&
        // items 신원이 다르면(카테고리가 바뀜) 너비가 같아도 새 상태로 — 아니면 낡은 items 를 가진 객체가 남아 측정이 영영 null 이 된다.
        prev.items === next.items &&
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
  // 카테고리 목록이 바뀌어 측정이 낡았으면(items 신원 불일치 — 수가 같고 이름·순서만 바뀐 경우 포함) 무시한다.
  // 어긋난 너비로 계산하면 NaN 이 되거나(수 불일치), 맞지 않는 칩을 숨겨 초점 판단을 그르친다(수 동일). 측정 레이아웃 이펙트가
  // 칠하기 전에 다시 재므로 이 임시 렌더는 화면에 보이지 않는다.
  const measure = measureState && measureState.items === items ? measureState : null;
  const { order, visibleCount, overflow, compact } = useMemo(() => {
    const selIdx = selectedId === undefined ? -1 : items.findIndex((it) => it.id === selectedId);
    if (!measure) {
      // WD-1138: 측정 전에도 고른 칩은 숨지 않게 「전체」 바로 뒤에 두고 둘만 보인다(이미 index 1 이면 순서는 그대로).
      if (selIdx > 0) return { order: promotedOrder(items.length, selIdx).map((i) => items[i]), visibleCount: 2, overflow: items.length > 2, compact: false };
      return { order: items, visibleCount: 1, overflow: items.length > 1, compact: false };
    }
    const natural = fitVisible(measure.widths, measure, 1);
    const hasOverflow = natural.count < items.length;
    // 고른 칩이 숨는 쪽이면 「전체」 바로 뒤로 옮기고 그 순서로 다시 센다.
    if (hasOverflow && selIdx >= natural.count) {
      const idxOrder = promotedOrder(items.length, selIdx);
      // 고른 칩은 반드시 보여야 하므로 아주 좁아도 「전체」+고른 칩 2개는 남긴다. 그래도 넘치면 접힌 줄은 줄바꿈하지 않고
      // 보이는 칩 이름을 말줄임해 버튼을 첫 줄에 남긴다(아래 collapsed 클래스·renderChip 의 shrinkable).
      const fit = fitVisible(idxOrder.map((i) => measure.widths[i]), measure, 2);
      // 최소 2개를 남긴 결과 모든 칩이 보여도(카테고리 하나를 고른 좁은 칸) 접힌 줄(줄바꿈 없음·말줄임)은 유지한다 —
      // 숨은 칩이 없으니 「+0」 버튼은 아래 렌더에서 빼고, 넘침(접힌 모양)만 남긴다.
      return { order: idxOrder.map((i) => items[i]), visibleCount: fit.count, overflow: true, compact: fit.compact };
    }
    // fitVisible 은 전부 들어가면 compact=false 라 넘침 여부를 다시 볼 필요가 없다.
    return { order: items, visibleCount: natural.count, overflow: hasOverflow, compact: natural.compact };
  }, [measure, items, selectedId]);

  // 넘침이 없어지면(창이 넓어짐·카테고리가 줄어듦) 펼침을 접힘으로 되돌린다 — 버튼이 없어 사용자가 접을 수 없는 상태로
  // 남았다가 다시 넘칠 때 여러 줄로 펼쳐진 채 나타나지 않게 한다(렌더 중 상태 조정 — 이펙트보다 한 번 덜 그린다).
  // 측정이 없거나 낡은 임시 렌더(카테고리 재조회 직후)의 넘침 값은 어림이라, 그때 접으면 사용자가 펼친 줄이 저절로 접힌다 — 측정이 맞을 때만 본다.
  if (measure && expanded && !overflow) setExpanded(false);
  const collapsed = !expanded && overflow;
  const hiddenCount = items.length - visibleCount;
  // 짧은 「+N」 버튼은 접힌 동안만 — 펼치면 「접기」 글자를 쓴다 (WD-1151).
  const showCompact = compact && !expanded;
  // WD-1138: 묶음과 「더 보기」 버튼을 aria-controls 로 잇기 위한 id.
  const groupId = useId();

  // WD-1138: 창 크기가 바뀌어 초점을 가진 요소가 사라지거나(「접기」 버튼) 숨으면(display:none 칩) 브라우저가 초점을 body 로 떨어뜨려
  // 키보드 사용자가 위치를 잃는다 — 묶음에 초점이 있었던 경우에 한해 초점을 되돌린다.
  // 의존성은 레이아웃을 바꾸는 값만 — 이 값들이 같으면 초점 요소가 새로 숨거나 사라질 일이 없다.
  useLayoutEffect(() => {
    if (!focusWithinRef.current) return;
    // 측정 전·낡은 측정(카테고리가 바뀐 직후 — 수가 같아도 items 신원이 달라 null)의 임시 렌더는 칩을 잠깐 숨기지만
    // 칠하기 전에 다시 재서 고쳐진다 — 그 사이에 초점을 옮기면 다시 보일 칩에서 초점을 빼앗으므로, 측정이 맞을 때만 판단한다.
    if (!measure) return;
    const group = groupRef.current;
    if (!group) return;
    const active = document.activeElement;
    // 안에 있는데 숨었거나(display:none), 묶음 밖이면 body 로 떨어졌을 때만(isFocusDropped — 본문 main 은 빼앗지 않음) 잃은 것이다.
    const lost = active && group.contains(active) ? isHidden(active) : isFocusDropped(active);
    if (lost) {
      // 첫 칩(「전체」)으로 옮긴다. preventScroll: 표를 내려 본 채 창 폭을 바꿔도 화면이 칩 줄로 튀지 않게 한다.
      group.querySelector<HTMLButtonElement>('button[aria-pressed]')?.focus({ preventScroll: true });
    }
    // 판단을 마치면 기억을 실제 초점 위치로 맞춘다 — 옮기지 못했거나 이미 밖(건너뛰기 링크로 main 등)이면 false 가 돼,
    // 나중 리렌더에서 사용자가 일부러 둔 초점을 「전체」로 끌어오지 않는다.
    focusWithinRef.current = group.contains(document.activeElement);
  }, [measure, collapsed, visibleCount, order, expanded]);

  // WD-1151: 말줄임으로 실제로 잘린 칩에만 title(전체 이름)을 단다. React prop 이 아니라 DOM 속성으로 다루는 이유: 잘림 여부는
  // 그린 뒤에야 알 수 있어(scrollWidth > clientWidth, 정수끼리 비교) 렌더 중에는 알 수 없다. 레이아웃이 바뀔 때마다 다시 맞춘다.
  useLayoutEffect(() => {
    const group = groupRef.current;
    if (!group) return;
    for (const btn of group.querySelectorAll<HTMLButtonElement>('button[data-chip-id]')) {
      // 말줄임 span 은 접힌 동안 보이는 칩에만 있다 — 없거나(펼침·숨김) 안 잘렸으면 title 을 뗀다.
      const span = btn.querySelector('span');
      // 잘림 판정은 공용 syncOverflowTitle(lib/overflow-title)과 같은 1px 허용 — 소수점 반올림으로 1px 넘는 것은 잘림으로 보지 않는다.
      // 그 도우미는 대상 요소 자신에 title 을 다는데, 여기서는 칩 버튼 전체(누를 자리)에 달아야 해 판정만 맞춘다.
      if (span && span.scrollWidth - 1 > span.clientWidth) btn.setAttribute('title', span.textContent ?? '');
      else btn.removeAttribute('title');
    }
  }, [measure, collapsed, visibleCount, order, expanded]);

  /**
   * 칩 하나 — i 는 지금 그리는 순서의 자리. 접힌 동안 visibleCount 뒤의 칩은 숨고(display:none), 보이는 칩은 아주 좁을 때
   * 최소 폭(min-w-8, WD-880)까지 줄고 이름을 말줄임해 「+N 더 보기」(shrink-0)가 잘리지 않게 한다 (WD-1138).
   * 실제로 말줄임된 칩의 title 은 위 레이아웃 이펙트가 DOM 속성으로 단다(잘림은 그린 뒤에야 알 수 있다).
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
          data-chip-id={item.id ?? 'all'}
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
        {/* 숨은 칩이 없으면(최소 칩만으로 다 보이는 좁은 칸) 펼칠 것이 없으니 「+0」 버튼을 그리지 않는다 — 펼친 뒤의 「접기」는 남긴다 */}
        {overflow && (expanded || hiddenCount > 0) && (
          <Button
            {...MORE_BUTTON_PROPS}
            aria-expanded={expanded}
            aria-controls={groupId}
            // 짧은 「+N」 은 글자만으로 뜻이 모호하므로 접근 이름은 「+N 더 보기」 로 유지한다(펼친 「접기」는 글자 그대로).
            aria-label={showCompact ? `+${hiddenCount} 더 보기` : undefined}
            onClick={() => setExpanded((v) => !v)}
          >
            {expanded ? '접기' : showCompact ? `+${hiddenCount}` : `+${hiddenCount} 더 보기`}
          </Button>
        )}
      </div>
      {/* 측정용 복제 행 — 보이지 않고 상호작용도 막는다(aria-hidden·inert). 끝 두 자식은 가장 넓은 「+N 더 보기」·「+N」 버튼. */}
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
          {/* 짧은 버튼 — 아주 좁은 칸에서 쓸 「+N」 의 너비를 잰다(글자 수가 가장 많은 +전체 개수 기준) */}
          <Button {...MORE_BUTTON_PROPS} tabIndex={-1}>
            {`+${items.length}`}
          </Button>
        </div>
      </div>
    </div>
  );
}
