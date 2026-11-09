import type { ComponentProps } from 'react';

import { PendingButton } from './PendingButton';

type PendingSubmitButtonProps = Omit<ComponentProps<typeof PendingButton>, 'type'>;

/**
 * 제출 중에는 aria-disabled 로 잠그는 폼 제출 버튼(WD-572) — PendingButton 에 type="submit" 만 얹는다(WD-678).
 * `pending` 은 보통 formState.isSubmitting 이다.
 *
 * 단, 입력칸에서 Enter 로 하는 암묵적 제출은 버튼 click 을 거치지 않을 수 있으므로 폼 onSubmit 은 useGuardedSubmit 으로 만든다.
 */
export function PendingSubmitButton(props: PendingSubmitButtonProps) {
  return <PendingButton {...props} type="submit" />;
}
