import type { FormEvent } from 'react';
import { useRef } from 'react';
import type { FieldValues, UseFormHandleSubmit } from 'react-hook-form';

/**
 * 폼 제출 잠금(WD-572). 폼에서는 보통 useGuardedSubmit 을 쓴다.
 *
 * 왜: 제출 버튼을 `disabled` 로 잠그면 Enter·클릭으로 제출한 순간 포커스가 body 로 떨어진다(키보드·스크린리더 사용자가
 * 폼 밖으로 밀려남). 그래서 버튼은 `aria-disabled` 로만 잠그고(PendingSubmitButton), 브라우저가 더 이상 막아 주지 않는
 * 중복 제출(잠긴 버튼 클릭, 입력칸 Enter 연타)은 여기서 막는다 — react-hook-form 의 handleSubmit 은 진행 중 재제출을 막지 않는다.
 *
 * - 진행 여부는 ref 로 동기 판정한다 — isSubmitting 은 다시 그려진 뒤에야 바뀌어 같은 틱의 연타를 놓칠 수 있다.
 *   상태가 ref 에만 있어 어느 렌더에서 만든 함수든 같은 잠금을 본다(메모이즈 불필요).
 * - 무시하는 제출도 preventDefault 한다 — 하지 않으면 브라우저 기본 제출(페이지 새로고침)이 일어난다.
 * - 무시하는 제출은 진행 중인 제출이 끝날 때(성공·실패 모두) 끝나는 Promise 를 돌려준다(WD-683, WD-678 정렬) —
 *   편집 충돌 창처럼 콜백 Promise 가 끝날 때까지 잠그는 호출자가, 무시된 클릭 때문에 실제 요청보다 먼저 잠금을 풀지 않게.
 *   진행 중 제출의 거부는 그 제출을 시작한 호출자가 받으므로 여기서는 삼킨다(같은 오류가 두 번 드러나지 않게).
 */
export function useSubmitGuard() {
  // 진행 중인 제출의 Promise — null 이면 잠금이 풀린 상태
  const inFlight = useRef<Promise<void> | null>(null);

  return (submit: (event?: FormEvent<HTMLFormElement>) => Promise<void>) =>
    (event?: FormEvent<HTMLFormElement>): Promise<void> => {
      if (inFlight.current) {
        // 첫 await 전에(동기로) 기본 동작을 막고, 진행 중 제출이 끝날 때 끝난다
        event?.preventDefault();
        return inFlight.current.then(
          () => undefined,
          () => undefined,
        );
      }
      // submit 이 첫 await 전에 동기로 끝나거나(throw) 하면 finally 가 먼저 돈다 — 그때는 잠금을 걸지 않는다(영구 잠김 방지)
      let settled = false;
      const running = (async () => {
        try {
          await submit(event);
        } finally {
          settled = true;
          inFlight.current = null;
        }
      })();
      if (!settled) inFlight.current = running;
      return running;
    };
}

/** useSubmitGuard 가 돌려주는 잠금 함수 */
export type SubmitGuard = ReturnType<typeof useSubmitGuard>;

/** 검증을 통과한 폼 값만 받는 처리 함수 */
type ValidSubmit<T> = (data: T) => unknown;

/**
 * 주어진 잠금으로 react-hook-form 제출 핸들러를 감싼다(WD-683) — 한 폼의 여러 제출 경로가 같은 잠금을 나눠 쓸 때 쓴다.
 * 검증 실패로 onValid 가 안 불려도 handleSubmit 이 끝나면 잠금이 풀린다.
 */
export function guardFormSubmit<TFieldValues extends FieldValues, TTransformedValues = TFieldValues>(
  guard: SubmitGuard,
  handleSubmit: UseFormHandleSubmit<TFieldValues, TTransformedValues>,
  onValid: ValidSubmit<TTransformedValues>,
) {
  // 제출 이벤트는 두 번째 인자로 넘기지 않는다 — 값만 받는 처리 함수의 다른 매개변수로 잘못 읽히지 않게
  return guard(handleSubmit((data) => onValid(data)));
}

/**
 * 잠금이 걸린 폼 onSubmit 핸들러를 만든다(WD-637, WD-683). 버튼은 PendingSubmitButton 에 `pending={formState.isSubmitting}`.
 * 훅이므로 조기 return 보다 앞에서, onValid 는 인자로 바로 읽으므로 그 정의 뒤에서 부른다.
 *
 * 사용: `const submit = useGuardedSubmit(form.handleSubmit, onSubmit);` → `<form onSubmit={submit}>`
 */
export function useGuardedSubmit<TFieldValues extends FieldValues, TTransformedValues = TFieldValues>(
  handleSubmit: UseFormHandleSubmit<TFieldValues, TTransformedValues>,
  onValid: ValidSubmit<TTransformedValues>,
) {
  return guardFormSubmit(useSubmitGuard(), handleSubmit, onValid);
}
