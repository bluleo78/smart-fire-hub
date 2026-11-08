import { type ComponentProps, useState } from 'react';

import { useHorizontalOverflow } from '@/hooks/useHorizontalOverflow';
import { cn } from '@/lib/utils';

interface TruncatedTitleProps extends Omit<ComponentProps<'span'>, 'children' | 'title'> {
  /** 표시할 글자 — 잘렸을 때만 같은 값이 title 로 붙는다 */
  text: string;
}

/**
 * 한 줄 말줄임 + 실제로 잘렸을 때만 title (WD-656, WD-517 원칙)
 * - 왜: `TruncatedText`(표 칸)는 항상 title 을 두지만, breadcrumb·태그 칩처럼 대부분 짧은 값에 툴팁이 늘 뜨면 군더더기다.
 * - 잘림 판정은 `useHorizontalOverflow` 를 재사용한다. 글자가 바뀌어도 폭이 그대로면 ResizeObserver 가 다시 불리지 않으므로
 *   key 로 span 을 새로 달아 다시 판정한다.
 * - min-w-0: 부모가 flex 여도 줄어들어 말줄임이 걸리게 한다.
 */
export function TruncatedTitle({ text, className, ...rest }: TruncatedTitleProps) {
  const [el, setEl] = useState<HTMLSpanElement | null>(null);
  const { canScrollRight } = useHorizontalOverflow(el);
  return (
    <span
      key={text}
      ref={setEl}
      className={cn('min-w-0 truncate', className)}
      title={canScrollRight ? text : undefined}
      {...rest}
    >
      {text}
    </span>
  );
}
