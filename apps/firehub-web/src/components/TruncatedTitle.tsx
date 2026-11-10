import { type ComponentProps, type SyntheticEvent, useEffect, useLayoutEffect, useRef } from 'react';

import { syncOverflowTitle } from '@/lib/overflow-title';
import { cn } from '@/lib/utils';

interface TruncatedTitleProps extends Omit<ComponentProps<'span'>, 'children' | 'title' | 'ref'> {
  /** 표시할 글자 — 잘렸을 때만 같은 값이 title 로 붙는다 */
  text: string;
  /** 그릴 태그 — 페이지 제목처럼 heading 이어야 하는 곳은 'h1' (기본 span) */
  as?: 'span' | 'h1';
}

/**
 * 한 줄 말줄임 + 실제로 잘렸을 때만 title (WD-656, WD-517 원칙)
 * - 왜: `TruncatedText`(표 칸)는 항상 title 을 두지만, breadcrumb·태그 칩·툴바 제목처럼 대부분 짧은 값에 툴팁이 늘 뜨면 군더더기다.
 * - 잘림 판정은 pointerenter·focus 때만 한다(WD-663) — 인스턴스마다 관찰자를 붙이지 않고, 글자가 바뀔 때 다시 마운트하지도 않는다.
 * - focus 는 「글자를 감싼 가장 가까운 포커스 대상(링크·버튼·tabindex, 자기 자신 포함)」의 focusin 으로 잰다(WD-700).
 *   글자 span 은 포커스를 받지 않고 focus 는 안쪽으로 내려오지 않아, 예전 span onFocus 는 breadcrumb 가운데 링크처럼
 *   바깥 링크가 포커스를 받는 곳에서 불리지 않았다(WD-669 에서 링크 쪽 onFocus 를 뺀 뒤 Tab 포커스 title 이 사라짐).
 *   호출부마다 링크에 onFocus 를 달지 않아도 링크·버튼 안 어디서 쓰든 같게 동작한다.
 * - block: flex 가 아닌 부모 안에서도 폭이 생겨 말줄임이 걸린다(WD-663). min-w-0: flex 부모 안에서도 줄어든다.
 */
export function TruncatedTitle({ text, as: Tag = 'span', className, onPointerEnter, ...rest }: TruncatedTitleProps) {
  const ref = useRef<HTMLSpanElement & HTMLHeadingElement>(null);

  // 글자가 바뀌면 이전 글자로 단 title 을 정리한다.
  // - 포인터가 이미 위에 있으면(:hover) pointerenter 가 다시 오지 않으므로 지금 새 글자로 다시 잰다(WD-669).
  // - 아니면 떼기만 한다 — 다음 hover 때 새 글자로 다시 잰다.
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (el.matches(':hover')) syncOverflowTitle(el);
    else el.removeAttribute('title');
  }, [text]);

  // 감싼 포커스 대상(없으면 건너뜀)이 키보드·프로그램으로 포커스를 받으면 잰다 — 마운트 때 한 번만 찾는다.
  useEffect(() => {
    const el = ref.current;
    const host = el?.closest<HTMLElement>('a[href], button, [tabindex]');
    if (!el || !host) return;
    const measure = () => syncOverflowTitle(el);
    host.addEventListener('focusin', measure);
    return () => host.removeEventListener('focusin', measure);
  }, []);

  /** pointerenter — 잘림을 재서 title 을 맞춘 뒤 호출부 핸들러를 부른다 */
  const measureThen =
    <E extends SyntheticEvent<HTMLSpanElement>>(handler?: (e: E) => void) =>
    (e: E) => {
      syncOverflowTitle(e.currentTarget);
      handler?.(e);
    };

  return (
    <Tag
      ref={ref}
      className={cn('block min-w-0 truncate', className)}
      onPointerEnter={measureThen(onPointerEnter)}
      {...rest}
    >
      {text}
    </Tag>
  );
}
