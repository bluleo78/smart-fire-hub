import type { ComponentProps, MouseEvent } from 'react';

import { cn } from '@/lib/utils';

import { Button } from './ui/button';

// 기본 Button 스타일은 `disabled:` 만 흐리게 하므로 같은 모양을 `aria-disabled:` 로 맞춘다(ui/button 은 생성 파일이라 직접 수정하지 않음).
// pointer-events-none 으로 진행 중 마우스 클릭·hover 도 먹지 않는다(disabled 가 아니라 포커스는 남는다).
const ARIA_DISABLED_CLASS = 'aria-disabled:pointer-events-none aria-disabled:cursor-not-allowed aria-disabled:opacity-50';

/**
 * `disabled` 는 진행 중이 아닌 다른 이유(예: 입력 중복으로 제출 불가 — 포커스가 버튼에 있지 않은 상태)에만 쓴다.
 * 진행 중 잠금은 반드시 `pending` 으로 한다 — `disabled` 로 잠그면 누른 버튼의 포커스가 body 로 떨어진다(WD-637·WD-678).
 */
export type PendingButtonProps = ComponentProps<typeof Button> & {
  /** 요청 진행 중 여부 — 참이면 버튼을 aria-disabled 로 잠근다 */
  pending: boolean;
};

/**
 * 진행 중에는 `disabled` 대신 `aria-disabled` 로 잠그는 버튼(WD-678, PendingSubmitButton 에서 분리).
 *
 * 왜: `disabled` 는 포커스를 빼앗아 키보드로 누른 사용자의 포커스가 body 로 떨어지고 실패 뒤에도 돌아오지 않는다.
 * `aria-disabled` 는 포커스를 지키면서 스크린리더에 「사용 불가」를 알린다.
 *
 * 진행 중 클릭은 preventDefault 하고 호출자 onClick 을 부르지 않는다(WD-572) — 포커스된 버튼의 Enter·Space 도 click 으로 들어오므로 함께 막힌다.
 * 기본 type 은 "button" 이다 — 폼 밖(다이얼로그 등)에서 써도 실수로 폼을 제출하지 않게. 폼 제출은 PendingSubmitButton 을 쓴다.
 */
export function PendingButton({ pending, className, children, onClick, type = 'button', ...props }: PendingButtonProps) {
  const handleClick = (event: MouseEvent<HTMLButtonElement>) => {
    if (pending) {
      event.preventDefault();
      return;
    }
    onClick?.(event);
  };

  return (
    // 호출자 props 를 먼저 펼치고 잠금 속성을 뒤에 둔다 — 호출자가 aria-disabled·onClick 을 덮어써 잠금을 우회하지 못하게
    <Button
      {...props}
      type={type}
      aria-disabled={pending || undefined}
      className={cn(ARIA_DISABLED_CLASS, className)}
      onClick={handleClick}
    >
      {children}
    </Button>
  );
}
