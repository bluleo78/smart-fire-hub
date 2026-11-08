import type { ComponentProps, MouseEvent } from 'react';

import { cn } from '@/lib/utils';

import { Button } from './ui/button';

type PendingSubmitButtonProps = Omit<ComponentProps<typeof Button>, 'type'> & {
  /** 제출(요청) 진행 중 여부 — 보통 formState.isSubmitting */
  pending: boolean;
};

/**
 * 제출 중에는 `disabled` 대신 `aria-disabled` 로 잠그는 폼 제출 버튼(WD-572, WD-511 선례).
 *
 * 왜: `disabled` 는 포커스를 빼앗아 키보드로 제출한 사용자의 포커스가 body 로 떨어지고 실패 뒤에도 돌아오지 않는다.
 * `aria-disabled` 는 포커스를 지키면서 스크린리더에 「사용 불가」를 알린다.
 *
 * 스스로도 안전하게 잠긴다(WD-572 리뷰):
 * - 진행 중 클릭은 preventDefault 로 폼 제출을 막는다 — 포커스된 버튼의 Enter·Space 도 click 으로 들어오므로 함께 막힌다.
 * - `aria-disabled:pointer-events-none` 로 진행 중에는 마우스 클릭·hover 스타일이 먹지 않는다(disabled 가 아니라 포커스는 남는다).
 * - 기본 Button 스타일은 `disabled:` 만 흐리게 하므로 같은 모양을 `aria-disabled:` 로 맞춘다(ui/button 은 생성 파일이라 직접 수정하지 않음).
 *
 * 단, 입력칸에서 Enter 로 하는 암묵적 제출은 버튼 click 을 거치지 않을 수 있으므로 폼 onSubmit 에는 여전히 useSubmitGuard 를 감싸야 한다.
 */
export function PendingSubmitButton({ pending, className, children, onClick, ...props }: PendingSubmitButtonProps) {
  const handleClick = (event: MouseEvent<HTMLButtonElement>) => {
    // 진행 중에는 제출(기본 동작)과 호출자 onClick 을 모두 막는다 — 호출자가 useSubmitGuard 를 빠뜨려도 중복 제출되지 않게
    if (pending) {
      event.preventDefault();
      return;
    }
    onClick?.(event);
  };

  return (
    <Button
      type="submit"
      aria-disabled={pending || undefined}
      className={cn('aria-disabled:pointer-events-none aria-disabled:cursor-not-allowed aria-disabled:opacity-50', className)}
      onClick={handleClick}
      {...props}
    >
      {children}
    </Button>
  );
}
