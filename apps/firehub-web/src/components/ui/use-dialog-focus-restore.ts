import * as React from "react"

import { focusMainContent, isFocusLost, isMainContent } from "../../lib/focus-lost"

/**
 * 다이얼로그를 닫을 때 포커스를 원래 트리거로 되돌린다. (이슈 #328)
 *
 * ## 왜 필요한가
 * Radix 의 modal Content 는 `onCloseAutoFocus` 에서
 * `event.preventDefault()` 를 부른 뒤 `context.triggerRef.current?.focus()` 로 복귀시킨다.
 * 이 `triggerRef` 는 `<Dialog.Trigger>` 가 렌더될 때만 채워지므로,
 * 외부 상태(`open` prop)로만 제어되는 다이얼로그에서는 항상 `null` 이다.
 * 결과적으로 복귀는 no-op 인데 `preventDefault()` 때문에
 * FocusScope 의 기본 복귀(직전 포커스 요소로 되돌리기)까지 함께 막혀
 * 포커스가 body·본문 main(isFocusLost, WD-980)으로 유실된다 — 키보드 사용자는 매번 페이지 맨 위에서 Tab 을 다시 눌러야 한다.
 *
 * ## 어떻게 고치는가
 * 열릴 때의 `document.activeElement` 를 보관해 두고,
 * **Radix 기본 동작을 그대로 먼저 돌린 뒤** 실제로 포커스가 body·본문 main(isFocusLost, WD-980)으로 떨어졌을 때만 보정한다.
 * 선점(preventDefault)하지 않으므로 `<Dialog.Trigger>` 를 제대로 쓰는 기존 다이얼로그의
 * 복귀 동작은 전혀 바뀌지 않는다 — 공유 컴포넌트 수정의 전역 회귀 위험을 피하기 위한 설계다.
 *
 * 캡처 시점으로 `onOpenAutoFocus` 를 쓰는 이유: FocusScope 는 마운트 이펙트에서
 * 이 이벤트를 먼저 dispatch 한 뒤에야 다이얼로그 내부로 포커스를 옮기므로,
 * 보통은 핸들러 안에서 아직 트리거가 `activeElement` 다.
 * (`useLayoutEffect` 로 잡으면 자식 이펙트보다 늦게 돌아 이미 옮겨간 포커스를 잡게 된다.)
 *
 * ## 예외 — 다이얼로그 내부에 `autoFocus` 가 있으면 캡처 자체가 일어나지 않는다 (이슈 #337)
 * React 는 `autoFocus` 를 **commit 단계**에서 적용하므로, FocusScope 의 마운트 이펙트가
 * 도는 시점에는 포커스가 이미 Content 안에 있다. 그런데 FocusScope 는
 * ```js
 * const hasFocusedCandidate = container.contains(document.activeElement)
 * if (!hasFocusedCandidate) { ...dispatch(AUTOFOCUS_ON_MOUNT)... }  // focus-scope/dist/index.mjs:74-86
 * ```
 * 처럼 **포커스가 이미 안에 있으면 이벤트를 아예 dispatch 하지 않는다**.
 * 즉 `onOpenAutoFocus` 가 호출되지 않아 `capturedRef` 가 `null` 로 남고, 1순위 복귀가
 * 통째로 사라져 포커스가 `<body>` 로 떨어진다. ("잘못된 요소를 캡처"가 아니라 "캡처 자체가 없음"이다.)
 * 반면 `onCloseAutoFocus` 는 정상적으로 호출되므로 보정은 닫힘 쪽에서 한다.
 *
 * 그래서 전역 `focusin` 이력을 따로 남겨 두고, **캡처가 비어 있을 때에 한해**
 * "Content 바깥의 가장 최근 포커스 요소"를 트리거로 간주해 복귀시킨다.
 * 이 폴백은 `capturedRef.current === null` 인 경우 — 즉 **지금 100% 복귀에 실패하는 상태** —
 * 에서만 쓰이므로 정상 동작 중인 다이얼로그의 복귀 경로는 전혀 바뀌지 않는다.
 *
 * ## 포인터 트리거 (WD-1039)
 * 「본문으로 건너뛰기」 뒤 main 에 초점이 남은 채 마우스로 버튼을 눌러 연 대화상자는(Safari 는 버튼에 초점을 주지 않는다)
 * 열릴 때 activeElement 가 main 이라 트리거를 알 수 없다. 그래서 최근(1초 이내) 누른 요소를 기억해 두었다가 main 안의 것이면 트리거로 쓴다.
 * 이 보정은 `onOpenAutoFocus` 가 오는 대화상자에만 적용된다 — 내부 `autoFocus` 창(#337 경로)은 열림 이벤트가 오지 않아 기존 동작 그대로다.
 */

// 전역 포커스 이력 — 위 예외 폴백에서만 참조한다.
// 슬롯 1개로는 부족하다: 다이얼로그가 열리는 동안 Content 내부 요소가 여러 번 포커스를 받을 수 있어
// 직전 항목까지 내부로 채워지기 때문이다. 짧은 이력을 두고 바깥 요소를 거슬러 찾는다.
const FOCUS_HISTORY_LIMIT = 8
const focusHistory: HTMLElement[] = []
if (typeof document !== "undefined") {
  document.addEventListener(
    "focusin",
    (event) => {
      const target = event.target
      if (!(target instanceof HTMLElement)) return
      if (focusHistory[focusHistory.length - 1] === target) return
      focusHistory.push(target)
      // 오래된 항목은 버린다 — 떨어져 나간 DOM 노드를 오래 붙들지 않기 위해서다.
      if (focusHistory.length > FOCUS_HISTORY_LIMIT) focusHistory.shift()
    },
    true
  )
}

// 최근 pointerdown 대상 — 초점이 main 에 남은 채 열린 대화상자의 트리거 추정에만 쓴다(WD-1039).
// 시각은 performance.now() 로 잰다(시스템 시계 변경에 영향받지 않는다).
const RECENT_POINTER_MS = 1000
let lastPointer: { el: Element; at: number } | null = null
if (typeof document !== "undefined") {
  document.addEventListener(
    "pointerdown",
    (event) => {
      const target = event.target
      if (!(target instanceof Element)) return
      // svg 아이콘 등 HTMLElement 가 아닌 Element 도 그대로 저장한다 — 조작 요소로 올려 잡는 정규화는 열림 때 한 곳에서만 한다.
      lastPointer = { el: target, at: performance.now() }
    },
    true
  )
}

// 누른 요소가 아이콘 등 자식일 때 실제 조작 요소(버튼·링크 등)로 올려 잡는다.
const INTERACTIVE_SELECTOR = 'button, a[href], [tabindex], input, select, textarea, [role="button"]'

/**
 * 이력에서 `content` 바깥에 있으면서 아직 살아 있는 가장 최근 요소를 찾는다.
 * 그 요소가 본문(main) 같은 「잃음」 자리여도 그대로 돌려준다 — 더 오래된 항목(건너뛰기 링크 등)까지
 * 거슬러 가면 페이지 맨 위로 튕기므로, 호출측이 isFocusLost 로 판단해 대체 지점을 쓰게 한다(WD-980).
 */
function findFocusBefore(content: HTMLElement): HTMLElement | null {
  for (let i = focusHistory.length - 1; i >= 0; i--) {
    const el = focusHistory[i]
    if (el !== document.body && el.isConnected && !content.contains(el)) return el
  }
  return null
}
export function useDialogFocusRestore({
  onOpenAutoFocus,
  onCloseAutoFocus,
  restoreFocusRef,
}: {
  onOpenAutoFocus?: (event: Event) => void
  onCloseAutoFocus?: (event: Event) => void
  /** 트리거가 닫힘과 함께 사라지는 경우(행 삭제 확인 등) 포커스를 대신 받을 요소. */
  restoreFocusRef?: React.RefObject<HTMLElement | null>
}) {
  const capturedRef = React.useRef<HTMLElement | null>(null)
  // 트리거의 조상 체인. 트리거가 DOM 에서 떨어지면 parentElement 가 null 이 되므로
  // 열릴 때 미리 기록해 둬야 "살아남은 가장 가까운 조상"을 찾을 수 있다.
  const ancestorsRef = React.useRef<HTMLElement[]>([])
  // 열릴 때 포커스가 본문(main)에 있었는지(건너뛰기 뒤 트리거에 포커스 없이 열림, WD-980).
  // 이 경우 전역 이력의 「직전 포커스」는 오래된 요소(건너뛰기 링크 등)라 복귀 목적지로 쓰지 않는다.
  const openedFromLostRef = React.useRef<HTMLElement | null>(null)

  const handleOpenAutoFocus = React.useCallback(
    (event: Event) => {
      const active = document.activeElement
      // body·본문 main(isFocusLost, WD-980)은 복귀해봐야 의미가 없으므로 캡처 대상에서 제외한다.
      // 이 이벤트가 dispatch 되었다는 것 자체가 "포커스가 아직 Content 밖"이라는 뜻이므로
      // (위 예외 설명 참고) 여기서 잡히는 것은 언제나 트리거다.
      let trigger =
        active instanceof HTMLElement && !isFocusLost(active) ? active : null

      // WD-1039: 초점이 main 에 남은 채 마우스로 연 경우 — 최근 누른 요소(main 안, Content 밖)를 트리거로 본다.
      if (!trigger && active instanceof HTMLElement && isMainContent(active) && lastPointer) {
        const content =
          event.currentTarget instanceof Element
            ? event.currentTarget
            : event.target instanceof Element
              ? event.target
              : null
        const { el, at } = lastPointer
        // 조작 요소(버튼·링크 등)로 올려 잡되, main 자신·비활성 요소는 초점을 받지 못하거나 의미가 없어 트리거로 쓰지 않는다.
        // 그 조작 요소가 main 안(따라서 붙어 있음)이면 누른 요소도 main 안이다. 못 찾으면 트리거 없음 — 닫힘 때 restoreFocusRef·조상·main 폴백을 탄다.
        const interactive =
          performance.now() - at <= RECENT_POINTER_MS && !content?.contains(el)
            ? el.closest(INTERACTIVE_SELECTOR)
            : null
        if (
          interactive instanceof HTMLElement &&
          interactive !== active &&
          active.contains(interactive) &&
          !interactive.matches(":disabled")
        ) {
          trigger = interactive
        }
      }
      capturedRef.current = trigger
      openedFromLostRef.current =
        active instanceof HTMLElement && isMainContent(active) ? active : null

      const ancestors: HTMLElement[] = []
      for (let el = trigger?.parentElement; el && el !== document.body; el = el.parentElement) {
        ancestors.push(el)
      }
      ancestorsRef.current = ancestors

      // 열림 판정에서 읽었으면 비운다 — 떨어진 DOM 노드를 오래 붙들지 않고, 다음 열림이 지난 누름을 재사용하지 않게 한다.
      lastPointer = null

      onOpenAutoFocus?.(event)
    },
    [onOpenAutoFocus]
  )

  const handleCloseAutoFocus = React.useCallback(
    (event: Event) => {
      onCloseAutoFocus?.(event)
      // 열릴 때 보관한 값을 지역 변수로 옮기고 곧바로 비운다 — onOpenAutoFocus 가 dispatch 되지 않는 다음 열림(#337)이
      // 지난 열림의 값을 재사용하지 않게 한다.
      const captured = capturedRef.current
      const ancestors = ancestorsRef.current
      const openedFromLost = openedFromLostRef.current
      capturedRef.current = null
      ancestorsRef.current = []
      openedFromLostRef.current = null
      // 소비자가 직접 포커스 목적지를 지정했으면 존중한다.
      if (event.defaultPrevented) return
      // 이벤트는 Content 노드에서 dispatch 된다. dispatch 가 끝나면 currentTarget 이
      // null 이 되므로 여기서 동기적으로 읽어 둔다(아래 rAF 안에서는 이미 늦다).
      const content =
        event.currentTarget instanceof HTMLElement
          ? event.currentTarget
          : event.target instanceof HTMLElement
            ? event.target
            : null
      requestAnimationFrame(() => {
        // Radix/FocusScope 기본 복귀가 성공했으면(=body·본문 main(isFocusLost, WD-980)이 아니면) 손대지 않는다.
        // 트리거가 언마운트된 경우 Radix 의 focus() 는 조용히 실패해 여기로 떨어진다.
        const active = document.activeElement
        // 건너뛰기 뒤 본문(main)에 떨어진 포커스도 잃음으로 본다(WD-980).
        if (!isFocusLost(active)) return

        // 1순위: 살아 있는 트리거. 2순위: 호출측이 지정한 대체 지점.
        // 3순위: 살아남은 가장 가까운 조상(표/목록 컨테이너) — 페이지 맨 위로 튕기는 것보다
        //        원래 위치 근처에 남는 편이 낫다. FocusScope 가 컨테이너에 포커스를 주는 것과 같은 패턴.
        // focus() 가 조용히 실패할 수 있으므로(포커스 불가 요소) 실제로 받았는지 확인하고, 아니면 다음 폴백으로 흘린다.
        if (captured?.isConnected) {
          captured.focus()
          if (document.activeElement === captured) return
        }

        // 캡처가 아예 없었던 경우(다이얼로그 내부 `autoFocus` — 이슈 #337): 전역 이력에서
        // Content 바깥의 가장 최근 포커스 요소를 트리거로 간주한다. 살아 있지 않으면
        // 아래 restoreFocusRef/조상 폴백으로 그대로 흘려보낸다.
        // 그 요소가 본문(main)이면 건너뛰기 직후라 오래된 이력으로 더 거슬러 가지 않고, 아래 대체 지점 뒤 마지막에 main 으로 돌려준다.
        let lostTarget: HTMLElement | null = openedFromLost
        if (!captured && content && !openedFromLost) {
          const previous = findFocusBefore(content)
          if (previous && !isFocusLost(previous)) return void previous.focus()
          if (previous) lostTarget = previous
        }

        if (restoreFocusRef?.current) return void restoreFocusRef.current.focus()

        const container = ancestors.find((el) => el.isConnected)
        if (!container) {
          // 대체 지점이 전혀 없으면 열릴 때의 자리(본문 main)로 돌려준다 — 건너뛰기 뒤 단축키 도움말처럼 사라지는 트리거가 없는 대화상자(WD-968).
          // main 은 그새 임시 tabindex 가 떼어졌을 수 있다(AppLayout releaseMainFocusTarget) —
          // tabindex 가 없는 main 에 focus() 는 조용히 실패하므로 focusMainContent 가 tabindex 를 다시 붙이고 초점을 준다(WD-1039).
          // 다만 tabindex 를 다시 붙이는 것은 이번 열림 때 초점이 main 에 있었을 때(openedFromLost)뿐이다 — 이력에서 온 오래된 main 은
          // 사용자가 본문을 눌러 tabindex 가 떼어진 뒤라 이미 떠난 자리일 수 있어, 예전처럼 포커스를 받을 수 있을 때만 돌려준다(WD-1039 코드 리뷰).
          if (!lostTarget?.isConnected || !isMainContent(lostTarget)) return
          if (lostTarget === openedFromLost) focusMainContent(lostTarget)
          else if (lostTarget.hasAttribute("tabindex")) lostTarget.focus()
          return
        }
        // 컨테이너는 보통 포커스 불가이므로 일시적으로 tabIndex 를 부여하고,
        // 포커스가 떠나면 원상복구해 Tab 순서를 오염시키지 않는다.
        if (!container.hasAttribute("tabindex")) {
          container.setAttribute("tabindex", "-1")
          container.addEventListener(
            "blur",
            () => container.removeAttribute("tabindex"),
            { once: true }
          )
        }
        container.focus()
      })
    },
    [onCloseAutoFocus, restoreFocusRef]
  )

  return { onOpenAutoFocus: handleOpenAutoFocus, onCloseAutoFocus: handleCloseAutoFocus }
}
