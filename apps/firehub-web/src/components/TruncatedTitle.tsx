import { type ComponentProps, type FocusEvent, type PointerEvent, useLayoutEffect, useRef } from 'react';

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
 *   focus 는 요소가 포커스를 받을 수 있을 때만 의미가 있다. 일반 글자는 포커스 대상으로 만들지 않고,
 *   링크처럼 포커스되는 부모가 있으면 부모가 syncOverflowTitle 로 넘겨 준다.
 * - block: flex 가 아닌 부모 안에서도 폭이 생겨 말줄임이 걸린다(WD-663). min-w-0: flex 부모 안에서도 줄어든다.
 */
export function TruncatedTitle({ text, as: Tag = 'span', className, onPointerEnter, onFocus, ...rest }: TruncatedTitleProps) {
  const ref = useRef<HTMLSpanElement & HTMLHeadingElement>(null);

  // 글자가 바뀌면 이전 글자로 단 title 을 뗀다 — 다음 hover 때 새 글자로 다시 잰다
  useLayoutEffect(() => {
    ref.current?.removeAttribute('title');
  }, [text]);

  return (
    <Tag
      ref={ref}
      className={cn('block min-w-0 truncate', className)}
      onPointerEnter={(e: PointerEvent<HTMLSpanElement>) => {
        syncOverflowTitle(e.currentTarget);
        onPointerEnter?.(e);
      }}
      onFocus={(e: FocusEvent<HTMLSpanElement>) => {
        syncOverflowTitle(e.currentTarget);
        onFocus?.(e);
      }}
      {...rest}
    >
      {text}
    </Tag>
  );
}
