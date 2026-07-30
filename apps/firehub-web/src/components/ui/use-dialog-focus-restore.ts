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

  const handleOpenAutoFocus = React.useCallback(
    (event: Event) => {
      const active = document.activeElement
      // <body> 는 복귀해봐야 의미가 없으므로 캡처 대상에서 제외한다.
      capturedRef.current =
        active instanceof HTMLElement && active !== document.body ? active : null
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
      requestAnimationFrame(() => {
        // Radix/FocusScope 기본 복귀가 성공했으면(=body 가 아니면) 손대지 않는다.
        const active = document.activeElement
        if (active && active !== document.body) return

        // 트리거가 살아 있으면 트리거로, 사라졌으면(행 삭제 등) 지정된 대체 지점으로.
        const target =
          captured && captured.isConnected ? captured : (restoreFocusRef?.current ?? null)
        target?.focus()
      })
    },
    [onCloseAutoFocus, restoreFocusRef]
  )

  return { onOpenAutoFocus: handleOpenAutoFocus, onCloseAutoFocus: handleCloseAutoFocus }
}
