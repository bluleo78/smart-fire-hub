import * as React from "react"

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
 * 포커스가 `<body>` 로 유실된다 — 키보드 사용자는 매번 페이지 맨 위에서 Tab 을 다시 눌러야 한다.
 *
 * ## 어떻게 고치는가
 * 열릴 때의 `document.activeElement` 를 보관해 두고,
 * **Radix 기본 동작을 그대로 먼저 돌린 뒤** 실제로 포커스가 `<body>` 로 떨어졌을 때만 보정한다.
 * 선점(preventDefault)하지 않으므로 `<Dialog.Trigger>` 를 제대로 쓰는 기존 다이얼로그의
 * 복귀 동작은 전혀 바뀌지 않는다 — 공유 컴포넌트 수정의 전역 회귀 위험을 피하기 위한 설계다.
 *
 * 캡처 시점으로 `onOpenAutoFocus` 를 쓰는 이유: FocusScope 는 마운트 이펙트에서
 * 이 이벤트를 먼저 dispatch 한 뒤에야 다이얼로그 내부로 포커스를 옮기므로,
 * 핸들러 안에서는 아직 트리거가 `activeElement` 다.
 * (`useLayoutEffect` 로 잡으면 자식 이펙트보다 늦게 돌아 이미 옮겨간 포커스를 잡게 된다.)
 */
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

  const handleOpenAutoFocus = React.useCallback(
    (event: Event) => {
      const active = document.activeElement
      // <body> 는 복귀해봐야 의미가 없으므로 캡처 대상에서 제외한다.
      const trigger =
        active instanceof HTMLElement && active !== document.body ? active : null
      capturedRef.current = trigger

      const ancestors: HTMLElement[] = []
      for (let el = trigger?.parentElement; el && el !== document.body; el = el.parentElement) {
        ancestors.push(el)
      }
      ancestorsRef.current = ancestors

      onOpenAutoFocus?.(event)
    },
    [onOpenAutoFocus]
  )

  const handleCloseAutoFocus = React.useCallback(
    (event: Event) => {
      onCloseAutoFocus?.(event)
      // 소비자가 직접 포커스 목적지를 지정했으면 존중한다.
      if (event.defaultPrevented) return

      const captured = capturedRef.current
      const ancestors = ancestorsRef.current
      requestAnimationFrame(() => {
        // Radix/FocusScope 기본 복귀가 성공했으면(=body 가 아니면) 손대지 않는다.
        // 트리거가 언마운트된 경우 Radix 의 focus() 는 조용히 실패해 여기로 떨어진다.
        const active = document.activeElement
        if (active && active !== document.body) return

        // 1순위: 살아 있는 트리거. 2순위: 호출측이 지정한 대체 지점.
        // 3순위: 살아남은 가장 가까운 조상(표/목록 컨테이너) — 페이지 맨 위로 튕기는 것보다
        //        원래 위치 근처에 남는 편이 낫다. FocusScope 가 컨테이너에 포커스를 주는 것과 같은 패턴.
        if (captured?.isConnected) return void captured.focus()
        if (restoreFocusRef?.current) return void restoreFocusRef.current.focus()

        const container = ancestors.find((el) => el.isConnected)
        if (!container) return
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
