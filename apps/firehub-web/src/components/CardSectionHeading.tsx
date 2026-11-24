import type { ComponentProps } from 'react';

import { cn } from '@/lib/utils';

/**
 * 카드 섹션 제목(h2) 공용 컴포넌트.
 *
 * 공용 CardTitle(div) 대신 h2 로 그려 스크린리더 제목 목록(H 키)에 섹션이 잡히게 한다
 * (WD-1067·WD-1069, WCAG 1.3.1). 모양은 CardTitle 과 같게(클래스·data-slot 그대로, 크기 변화 없음) 두므로
 * CardTitle 스타일을 바꾸면 여기도 맞춘다 — components/ui 는 shadcn 생성물이라 손대지 않는다.
 * 아이콘·span 등 자식과 레이아웃용 className 은 그대로 받아 덧붙인다.
 */
export function CardSectionHeading({ className, ...props }: ComponentProps<'h2'>) {
  return <h2 data-slot="card-title" className={cn('leading-none font-semibold', className)} {...props} />;
}
