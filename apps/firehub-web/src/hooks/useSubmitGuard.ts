import type { FormEvent } from 'react';
import { useRef } from 'react';
import type { FieldValues, UseFormHandleSubmit } from 'react-hook-form';

/**
 * 잠금을 거친 제출의 결과(WD-688).
 * ignored: true — 다른 제출이 진행 중이라 이 호출은 무시됐다(그 제출이 끝날 때 끝난다). false — 이 호출이 실제로 제출했다.
 */
export type SubmitOutcome = { ignored: boolean };

// 두 결과 — 호출마다 새로 만들지 않고 얼린 하나씩을 나눠 준다(받는 쪽이 고칠 수 없게)
const IGNORED: SubmitOutcome = Object.freeze({ ignored: true });
const SUBMITTED: SubmitOutcome = Object.freeze({ ignored: false });
// 진행 중 제출이 성공하든 실패하든 무시한 호출은 IGNORED 로 끝난다 — 실패는 그 제출을 시작한 호출자만 받는다
const resolveIgnored = () => IGNORED;

/**
 * 폼 제출 잠금(WD-572). 폼에서는 보통 useGuardedSubmit 을 쓴다.
 *
 * 왜: 제출 버튼을 `disabled` 로 잠그면 Enter·클릭으로 제출한 순간 포커스가 body 로 떨어진다(키보드·스크린리더 사용자가
 * 폼 밖으로 밀려남). 그래서 버튼은 `aria-disabled` 로만 잠그고(PendingSubmitButton), 브라우저가 더 이상 막아 주지 않는
 * 중복 제출(잠긴 버튼 클릭, 입력칸 Enter 연타)은 여기서 막는다 — react-hook-form 의 handleSubmit 은 진행 중 재제출을 막지 않는다.
 * 폼이 아닌 버튼 onClick 제출에도 쓴다(DashboardListPage, WD-688) — 이벤트 없이 부르면 된다.
 *
 * - 진행 중 여부는 ref 로 동기 판정한다 — isSubmitting 은 다시 그려진 뒤에야 바뀌어 같은 틱의 연타를 놓칠 수 있다.
 *   상태가 ref 에만 있어 어느 렌더에서 만든 함수든 같은 잠금을 본다(메모이즈 불필요).
 * - 무시하는 제출도 preventDefault 한다 — 하지 않으면 브라우저 기본 제출(페이지 새로고침)이 일어난다.
 * - 무시하는 제출은 진행 중인 제출이 끝날 때(성공·실패 모두) `{ ignored: true }` 로 끝난다(WD-683, WD-678 정렬) —
 *   편집 충돌 창처럼 콜백 Promise 가 끝날 때까지 잠그는 호출자가, 무시된 클릭 때문에 실제 요청보다 먼저 잠금을 풀지 않게.
 *   진행 중 제출의 거부는 그 제출을 시작한 호출자가 받으므로 여기서는 삼킨다(같은 오류가 두 번 드러나지 않게).
 * - 실제 제출은 성공하면 `{ ignored: false }` 로 끝나고 실패하면 그 오류로 거부된다 — 「내 제출이 처리됐다」와
 *   「무시됐고 남의 제출이 끝났다」를 호출자가 가를 수 있다(WD-688).
 *   지금 호출자는 모두 결과를 보지 않고 끝나기만 기다린다(form onSubmit, 충돌 창). 결과는 무시됐을 때 토스트·이동 등을
 *   달리 하려는 앞으로의 호출자를 위한 것이며, void 를 돌려주는 핸들러 자리에도 그대로 넘길 수 있다.
 */
export function useSubmitGuard() {
  // 진행 중인 제출의 Promise — null 이면 잠금이 풀린 상태
  const inFlight = useRef<Promise<SubmitOutcome> | null>(null);

  return (submit: (event?: FormEvent<HTMLFormElement>) => Promise<void>) =>
    (event?: FormEvent<HTMLFormElement>): Promise<SubmitOutcome> => {
      if (inFlight.current) {
        // 첫 await 전에(동기로) 기본 동작을 막고, 진행 중 제출이 끝날 때 「무시됨」으로 끝난다
        event?.preventDefault();
        return inFlight.current.then(resolveIgnored, resolveIgnored);
      }
      // submit 이 첫 await 전에 동기로 끝나거나(throw) 하면 finally 가 먼저 돈다 — 그때는 잠금을 걸지 않는다(영구 잠김 방지)
      let settled = false;
      const running = (async (): Promise<SubmitOutcome> => {
        try {
          await submit(event);
          return SUBMITTED;
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
 * 돌려준 함수의 결과는 useSubmitGuard 와 같다 — 다른 경로의 제출이 진행 중이라 무시됐으면 `{ ignored: true }`(WD-688).
 * (검증 실패도 「무시」가 아니라 실제로 처리한 제출이므로 `{ ignored: false }` 다.)
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
