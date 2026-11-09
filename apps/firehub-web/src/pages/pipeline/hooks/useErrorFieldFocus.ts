import { useEffect, useEffectEvent, useState } from 'react';

/** 칸이 나타나기를 기다리는 최대 시간 — 지나면 포커스하지 않고 요청만 지운다(WD-633) */
export const ERROR_FIELD_FOCUS_TIMEOUT_MS = 5000;

/**
 * 저장 실패 후 루트 안의 `data-error-field~=<focusField>` 칸에 포커스하고 onDone 으로 요청을 지운다(WD-633).
 * 칸이 아직 없으면(지연 로드·데이터 로드) 추가되는 노드를 지켜보고, 사용자가 다른 조작(포인터·키)을 하거나 제한 시간이 지나면 포기한다.
 *
 * @returns 루트 요소에 달 콜백 ref
 */
export function useErrorFieldFocus<T extends HTMLElement>(
  focusField: string | null | undefined,
  onDone: () => void,
) {
  const [root, setRoot] = useState<T | null>(null);
  // effect 를 다시 돌리지 않고 최신 콜백을 부른다 — 호출부가 안정적인 함수를 넘길 필요가 없다
  const done = useEffectEvent(onDone);

  useEffect(() => {
    if (!focusField || !root) return;
    const selector = `[data-error-field~="${CSS.escape(focusField)}"]`;
    const focus = (el: HTMLElement) => {
      el.focus();
      done();
    };
    const existing = root.querySelector<HTMLElement>(selector);
    if (existing) {
      focus(existing);
      return;
    }

    // 새로 붙은 노드(또는 그 안)에서만 찾는다
    const observer = new MutationObserver((records) => {
      for (const record of records) {
        for (const node of record.addedNodes) {
          if (!(node instanceof HTMLElement)) continue;
          const el = node.matches(selector) ? node : node.querySelector<HTMLElement>(selector);
          if (el) {
            stop();
            focus(el);
            return;
          }
        }
      }
    });
    // 포기 — 나중에 칸이 생겨도 사용자가 하던 일을 방해하지 않게 포커스는 옮기지 않는다
    const abandon = () => {
      stop();
      done();
    };
    const timer = window.setTimeout(abandon, ERROR_FIELD_FOCUS_TIMEOUT_MS);
    function stop() {
      observer.disconnect();
      window.clearTimeout(timer);
      document.removeEventListener('pointerdown', abandon, true);
      document.removeEventListener('keydown', abandon, true);
    }
    observer.observe(root, { childList: true, subtree: true });
    document.addEventListener('pointerdown', abandon, true);
    document.addEventListener('keydown', abandon, true);
    return stop;
  }, [focusField, root]);

  return setRoot;
}
