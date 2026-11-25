import { type CompositionEvent, type KeyboardEvent, useRef } from 'react';

/**
 * 한글 IME 조합 중 Enter 가 「조합 확정 + 실행」 을 한 번에 하도록 돕는 입력칸용 훅 (WD-1159).
 *
 * 브라우저마다 확정 Enter 의 순서가 다르다.
 * - Chrome: 조합 중 keydown(Enter, isComposing=true) → compositionend. 이 Enter 는 확정일 뿐이라 compositionend 에서 실행한다.
 * - Safari: compositionend → keydown(Enter, keyCode 229, isComposing=false). 조합이 이미 끝났으므로 이 keydown 에서 실행한다.
 * 어느 쪽이든 한 번만 실행해야 하므로, 조합 중 Enter 는 「대기」 로 기억했다가 compositionend 에서 실행하고,
 * 같은 확정에 이어 오는 Enter(macOS Chrome 에서 뒤따르는 keyCode 13 keydown — 다른 task 로 올 수 있다)는
 * 그 Enter 의 keyup 이 올 때까지 거른다.
 * 상태 없이 ref 만 써서 불필요한 리렌더를 만들지 않는다.
 *
 * @param onEnter 실행할 동작. 입력칸의 현재 값을 인자로 받는다(상태가 아직 갱신 전일 수 있어 DOM 값을 넘긴다).
 */
export function useImeSafeEnter(onEnter: (value: string) => void) {
  // 조합 중 여부
  const isComposingRef = useRef(false);
  // 조합 중 Enter 를 받아 compositionend 에서 실행하길 기다리는 중인지
  const pendingRef = useRef(false);
  // 방금 compositionend 에서 실행했으므로 같은 누름의 keyup 전까지 오는 Enter 는 거른다
  const skipNextEnterRef = useRef(false);

  return {
    onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
      if (e.key !== 'Enter') return;
      // 공용 isImeComposing(useOverlayEscape)은 keyCode 229 도 조합으로 보는데, Safari 는 compositionend 뒤 Enter 를 keyCode 229·isComposing=false 로 보내므로 쓰면 검색이 영영 실행되지 않는다.
      if (isComposingRef.current || e.nativeEvent.isComposing) {
        // 조합 확정용 Enter — 아직 실행하지 않고 compositionend 를 기다린다.
        pendingRef.current = true;
        return;
      }
      if (skipNextEnterRef.current) {
        // compositionend 에서 이미 실행한 확정의 뒤따르는 Enter — 두 번 실행하지 않는다.
        skipNextEnterRef.current = false;
        return;
      }
      onEnter(e.currentTarget.value);
    },
    onCompositionStart() {
      isComposingRef.current = true;
      // compositionend 없이 끝난 이전 조합의 낡은 대기가 다음 조합 끝에 실행되지 않도록 초기화한다.
      pendingRef.current = false;
    },
    onCompositionEnd(e: CompositionEvent<HTMLInputElement>) {
      isComposingRef.current = false;
      if (!pendingRef.current) return;
      pendingRef.current = false;
      onEnter(e.currentTarget.value);
      // 뒤따르는 Enter keydown 은 다음 task 로 올 수 있어 시간(setTimeout 0)이 아니라 이 누름의 keyup 으로 표시를 지운다.
      skipNextEnterRef.current = true;
    },
    onKeyUp(e: KeyboardEvent<HTMLInputElement>) {
      // 확정 Enter 를 뗐다 — 이후의 Enter 는 새 누름이므로 다시 실행한다.
      if (e.key === 'Enter') skipNextEnterRef.current = false;
    },
    onBlur() {
      // 조합 중 포커스를 잃으면 compositionend 가 오지 않을 수 있다 — 대기 중이던 실행은 취소한다.
      isComposingRef.current = false;
      pendingRef.current = false;
      skipNextEnterRef.current = false;
    },
    isComposingRef,
  };
}
