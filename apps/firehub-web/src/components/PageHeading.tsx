import type { ComponentProps } from 'react';

import { cn } from '@/lib/utils';

/**
 * 페이지 제목(h1) 공용 컴포넌트 (WD-910).
 *
 * 앱 표준 제목 타이포그래피(28px / 줄 높이 36px / 600 / tracking-tight, WD-813)를 한 곳에 둔다.
 * 같은 클래스 문자열이 여러 페이지에 복사돼 있어 한 곳만 바뀌고 나머지가 어긋나는 일이 반복됐기 때문이다
 * (WD-895 알림 채널 제목이 표준에서 벗어났던 사례 등). 각 페이지는 이 컴포넌트를 쓰고, 레이아웃용 클래스
 * (`min-w-0`, `wrap-anywhere`, `break-words` 등)만 `className` 으로 덧붙인다.
 * 표준을 `cn`(tailwind-merge)의 뒤에 두어, 같은 그룹의 타이포 클래스(`text-<크기>`·`leading-*`·`font-*`·
 * `tracking-*`)가 넘어와도 표준이 이긴다. 크기가 달라야 하는 제목은 이 컴포넌트를 쓰지 않는다.
 *
 * 탭 제목(document.title)을 정하는 `PageTitleSync` 의 `PageTitle*` 계열과는 무관하다 — 이쪽은 화면에 그려지는 h1 만 다룬다.
 */
export function PageHeading({ className, ...rest }: ComponentProps<'h1'>) {
  return (
    <h1
      {...rest}
      className={cn(className, 'text-[28px] leading-[36px] font-semibold tracking-tight')}
    />
  );
}
