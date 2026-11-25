import { type CompositionEvent, type KeyboardEvent, useRef } from 'react';

/** Enter 키인지 — IME 가 key 를 'Process' 로 바꿔 보내도(Windows Chrome 등) 물리 키(code)는 Enter 다. */
function isEnterKey(e: KeyboardEvent<HTMLInputElement>) {
  return e.key === 'Enter' || e.code === 'Enter' || e.code === 'NumpadEnter';
}

/** IME 가 처리 중인 키 이벤트인지 — 브라우저는 이런 키를 key 'Process' 또는 keyCode 229 로 보낸다. */
function isImeProcessing(e: KeyboardEvent<HTMLInputElement>) {
  return e.key === 'Process' || e.keyCode === 229;
}

/** 새 누름으로 보지 않는 보조키 — 확정 Enter 거르기를 끝내지 않는다. */
const MODIFIER_KEYS = new Set(['Shift', 'Control', 'Alt', 'Meta', 'CapsLock']);

/**
 * 한글 IME 조합 중 Enter 가 「조합 확정 + 실행」 을 한 번에 하도록 돕는 입력칸용 훅 (WD-1159).
 *
 * 브라우저마다 확정 Enter 의 순서가 다르다.
 * - Chrome: 조합 중 keydown(Enter, isComposing=true) → compositionend. 이 Enter 는 확정일 뿐이라 compositionend 에서 실행한다.
 * - Safari: compositionend → keydown(Enter, keyCode 229, isComposing=false). 조합이 이미 끝났으므로 이 keydown 에서 실행한다.
 * 어느 쪽이든 한 번만 실행해야 하므로, 조합 중 Enter 는 「대기」 로 기억했다가 compositionend 에서 실행하고,
 * 같은 확정에 이어 오는 Enter(macOS Chrome 에서 뒤따르는 keyCode 13 keydown — 다른 task 로 올 수 있다)는
 * 그 Enter 의 keyup 이 올 때까지 거른다. IME 가 그 keyup 을 Process·keyCode 229 로 보내거나 keyup 이 오지 않을 수 있어,
 * 다른 키를 누르거나 새 조합이 시작될 때도 거르기를 끝낸다. 누르고 있어 생기는 자동 반복 Enter 는 실행하지 않는다.
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
      if (!isEnterKey(e)) {
        // 다른 키를 눌렀다 — 확정 Enter 의 keyup 을 못 받았어도 그 누름은 끝났으므로 거르기를 끝낸다.
        // 보조키·IME 처리 키는 뒤따르는 Enter 보다 먼저 올 수 있어(빠른 타이핑) 끝내지 않는다 — 끝내면 검색이 두 번 나간다.
        if (!MODIFIER_KEYS.has(e.key) && !isImeProcessing(e)) skipNextEnterRef.current = false;
        return;
      }
      // Enter 를 누르고 있어 생기는 자동 반복 — 같은 누름이라 다시 실행하지 않는다.
      if (e.repeat) return;
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
      // 새 조합이 시작됐다 — 앞 확정 Enter 의 거르기는 더 이상 이 누름의 것이 아니다.
      skipNextEnterRef.current = false;
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
      // IME 는 그 keyup 을 key 'Process'·keyCode 229 로 보내기도 한다. 다만 아직 떼지 않은 다른 글자 키의 keyup 도
      // Process/229 로 올 수 있어, 물리 키가 Enter 이거나 알 수 없을 때만 확정 Enter 를 뗀 것으로 본다.
      if (isEnterKey(e) || (isImeProcessing(e) && !e.code)) skipNextEnterRef.current = false;
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
