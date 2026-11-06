import { type ComponentProps, type MouseEvent, useRef, useState } from 'react';

import { AlertDialogAction } from './alert-dialog';

/**
 * 확인 창이 한 번 열린 동안 onClick 을 한 번만 부르는 AlertDialogAction (WD-507 → WD-522 에서 공용으로 뺌).
 *
 * 왜: Radix 는 확인 클릭 때 창 닫기를 시작하지만 닫힘 애니메이션 동안 버튼이 남아 있어, 더블클릭의 두 번째 클릭이
 * onClick 을 다시 불러 요청이 두 번 나갔다(삭제 204 뒤 404 → 성공·"찾을 수 없습니다" 토스트가 함께 뜸).
 * 호출부의 처리는 비동기라 거기서 막기 어려워, 확인 버튼 자체에서 막는다.
 *
 * AlertDialogContent 안에서 렌더되므로 창이 열릴 때마다 새로 마운트된다 — 가드(ref·state)가 매번 초기화되어
 * 취소 뒤 다시 열면 정상적으로 확인할 수 있다. 열린 채 대상만 바뀌는 사용처는 key 로 새로 시작한다.
 *
 * 쓰지 말아야 할 곳: onClick 에서 preventDefault 로 창을 열어 둔 채 실패 뒤 다시 누르게 하는 확인 버튼 —
 * 두 번째 클릭이 막힌다. 그런 곳은 진행 중 상태(pending)로 disabled 를 직접 준다.
 */
export function OnceAlertDialogAction({ onClick, disabled, ...props }: ComponentProps<typeof AlertDialogAction>) {
  // ref: 같은 이벤트 루프 안에서 연달아 오는 클릭도 즉시 막는다(state 는 다음 렌더까지 반영되지 않는다).
  const confirmedRef = useRef(false);
  // state: 첫 클릭 뒤 버튼을 disabled 로 보여 더 누를 수 없음을 알린다.
  const [confirmed, setConfirmed] = useState(false);

  const handleClick = (e: MouseEvent<HTMLButtonElement>) => {
    if (confirmedRef.current) {
      // 이미 확인했다 — 다시 부르지 않는다. 닫기도 이미 시작됐으므로 기본 동작(닫기)도 건너뛴다.
      e.preventDefault();
      return;
    }
    confirmedRef.current = true;
    setConfirmed(true);
    try {
      onClick?.(e);
    } catch (err) {
      // onClick 이 동기로 던지면 Radix 닫기가 실행되지 않아 창이 열린 채 남는다 — 버튼이 영영 꺼지지 않게 가드를 풀고 다시 던진다.
      confirmedRef.current = false;
      setConfirmed(false);
      throw err;
    }
  };

  return <AlertDialogAction {...props} onClick={handleClick} disabled={disabled || confirmed} />;
}
