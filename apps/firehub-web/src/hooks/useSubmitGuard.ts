import type { FormEvent } from 'react';
import { useRef } from 'react';

/**
 * 폼 제출 잠금(WD-572).
 *
 * 왜: 제출 버튼을 `disabled` 로 잠그면 Enter·클릭으로 제출한 순간 포커스가 있던 버튼이 비활성화되어 브라우저가
 * 포커스를 body 로 돌린다(키보드·스크린리더 사용자가 폼 밖으로 밀려남). 그래서 버튼은 `aria-disabled` 로만 잠그고
 * (PendingSubmitButton), 그러면 더 이상 브라우저가 막아 주지 않는 중복 제출(잠긴 버튼 클릭, 입력칸 Enter 연타)을
 * 여기서 막는다. react-hook-form 의 handleSubmit 은 진행 중 재제출을 막지 않는다.
 *
 * - 진행 여부는 ref 로 동기 판정한다 — isSubmitting 은 다시 그려진 뒤에야 바뀌어 같은 틱의 연타를 놓칠 수 있다.
 * - 무시하는 제출도 preventDefault 한다 — 하지 않으면 브라우저 기본 제출(페이지 새로고침)이 일어난다.
 *
 * - 반환 함수는 렌더마다 새로 만들어도 된다 — 상태는 ref 에만 있어 어느 렌더의 함수든 같은 잠금을 본다(메모이즈 불필요).
 *
 * 사용: `<form onSubmit={guard(handleSubmit(onSubmit))}>`
 */
export function useSubmitGuard() {
  const inFlight = useRef(false);

  return (submit: (event?: FormEvent<HTMLFormElement>) => Promise<void>) =>
    async (event?: FormEvent<HTMLFormElement>) => {
      if (inFlight.current) {
        event?.preventDefault();
        return;
      }
      inFlight.current = true;
      try {
        await submit(event);
      } finally {
        inFlight.current = false;
      }
    };
}
