"use client"

import { AlertDialog as AlertDialogPrimitive } from "radix-ui"
import * as React from "react"

import { Button } from "@/components/ui/button"
import { useDialogFocusRestore } from "@/components/ui/use-dialog-focus-restore"
import { cn } from "@/lib/utils"

function AlertDialog({
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Root>) {
  return <AlertDialogPrimitive.Root data-slot="alert-dialog" {...props} />
}

function AlertDialogTrigger({
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Trigger>) {
  return (
    <AlertDialogPrimitive.Trigger data-slot="alert-dialog-trigger" {...props} />
  )
}

function AlertDialogPortal({
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Portal>) {
  return (
    <AlertDialogPrimitive.Portal data-slot="alert-dialog-portal" {...props} />
  )
}

function AlertDialogOverlay({
  className,
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Overlay>) {
  return (
    <AlertDialogPrimitive.Overlay
      data-slot="alert-dialog-overlay"
      className={cn(
        "data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 fixed inset-0 z-50 bg-black/50",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogContent({
  className,
  size = "default",
  onOpenAutoFocus,
  onCloseAutoFocus,
  restoreFocusRef,
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Content> & {
  size?: "default" | "sm"
  /** 트리거가 닫힘과 함께 사라지는 경우(행 삭제 확인 등) 포커스를 대신 받을 요소 (이슈 #328). */
  restoreFocusRef?: React.RefObject<HTMLElement | null>
}) {
  // 닫을 때 포커스를 트리거로 되돌린다 — 제어형(Trigger 없는) 다이얼로그에서 <body> 유실 방지.
  const focusRestore = useDialogFocusRestore({
    onOpenAutoFocus,
    onCloseAutoFocus,
    restoreFocusRef,
  })

  return (
    <AlertDialogPortal>
      <AlertDialogOverlay />
      <AlertDialogPrimitive.Content
        data-slot="alert-dialog-content"
        data-size={size}
        {...focusRestore}
        // grid-cols-[minmax(0,1fr)] (WD-632): 암시적 auto 칸은 자식의 최소 내용 폭까지 늘어나,
        // 공백 없는 긴 이름(설명·details 영향 목록 등)이 창을 넓혀 「삭제」 버튼을 화면 밖으로 민다. 칸 최소 폭을 0 으로 묶는다.
        // wrap-break-word (WD-648): 칸 폭이 묶였으니 한 줄에 안 들어가는 낱말만 끊으면 충분하다. 예전 overflow-wrap:anywhere 는
        // 모든 자식의 최소 내용 폭까지 줄여 flex 행의 짧은 식별자·이메일을 글자 중간에서 끊었다. 사용처가 wrap-anywhere 를 주면 cn 이 그것을 남긴다.
        // 일반 DialogContent 도 같은 두 클래스를 쓴다 — 근거는 이 주석 하나에 둔다.
        // 알고 받아들인 점(WD-648 리뷰): (a) 물려받은 break-word 는 whitespace-pre-wrap 블록 안의 긴 토큰도 감는다 — pre-wrap 은 이미
        // 줄바꿈을 고른 것이고 AlertDialog 는 anywhere 로 같은 동작이었다. (b) AlertDialogHeader 의 직접 자식 밖에 있는 inline-block·flex·grid
        // 후손은 최소 내용 폭이 줄지 않으니, 그런 요소에 긴 값을 넣으면 그 요소에 직접 min-w-0 / wrap-anywhere 를 준다(상속으로 전부 끊지 않는 의도된 선택).
        className={cn(
          "grid-cols-[minmax(0,1fr)] wrap-break-word bg-background data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 group/alert-dialog-content fixed top-[50%] left-[50%] z-50 grid w-full max-w-[calc(100%-2rem)] translate-x-[-50%] translate-y-[-50%] gap-4 rounded-lg border p-6 shadow-lg duration-200 data-[size=sm]:max-w-xs data-[size=default]:sm:max-w-lg",
          className
        )}
        {...props}
      />
    </AlertDialogPortal>
  )
}

function AlertDialogHeader({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-dialog-header"
      // *:min-w-0 *:max-w-full (WD-648): 헤더는 칸을 정하지 않은 grid(place-items-center)라, 암시적 auto 칸이 자식의 최소 내용 폭까지
      // 늘어나고 자식도 내용에 맞춘 폭(shrink-to-fit — 최소 내용 폭 이상)을 갖는다. 상속받는 break-word 는 최소 내용 폭을 줄이지 않으므로,
      // 직접 자식(제목·설명·요약 등) 모두의 최소 폭을 풀고(min-w-0) 폭을 칸 폭으로 묶어(max-w-full) 공백 없는 긴 이름이 그 안에서 끊기게 한다.
      // AlertDialogMedia(size-16)는 칸보다 좁아 max-w-full 에 눌리지 않고, 제목의 col-start-2 배치도 그대로다.
      className={cn(
        "grid grid-rows-[auto_1fr] place-items-center *:min-w-0 *:max-w-full gap-1.5 text-center has-data-[slot=alert-dialog-media]:grid-rows-[auto_auto_1fr] has-data-[slot=alert-dialog-media]:gap-x-6 sm:group-data-[size=default]/alert-dialog-content:place-items-start sm:group-data-[size=default]/alert-dialog-content:text-left sm:group-data-[size=default]/alert-dialog-content:has-data-[slot=alert-dialog-media]:grid-rows-[auto_1fr]",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogFooter({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-dialog-footer"
      className={cn(
        "flex flex-col-reverse gap-2 group-data-[size=sm]/alert-dialog-content:grid group-data-[size=sm]/alert-dialog-content:grid-cols-2 sm:flex-row sm:justify-end",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogTitle({
  className,
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Title>) {
  return (
    <AlertDialogPrimitive.Title
      data-slot="alert-dialog-title"
      // heading-card (02-typography §2.1)
      className={cn(
        "text-xl leading-7 font-semibold sm:group-data-[size=default]/alert-dialog-content:group-has-data-[slot=alert-dialog-media]/alert-dialog-content:col-start-2",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogDescription({
  className,
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Description>) {
  return (
    <AlertDialogPrimitive.Description
      data-slot="alert-dialog-description"
      className={cn("text-muted-foreground text-sm", className)}
      {...props}
    />
  )
}

function AlertDialogMedia({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-dialog-media"
      className={cn(
        "bg-muted mb-2 inline-flex size-16 items-center justify-center rounded-md sm:group-data-[size=default]/alert-dialog-content:row-span-2 *:[svg:not([class*='size-'])]:size-8",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogAction({
  className,
  variant = "default",
  size = "default",
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Action> &
  Pick<React.ComponentProps<typeof Button>, "variant" | "size">) {
  return (
    <Button variant={variant} size={size} asChild>
      <AlertDialogPrimitive.Action
        data-slot="alert-dialog-action"
        className={cn(className)}
        {...props}
      />
    </Button>
  )
}

function AlertDialogCancel({
  className,
  variant = "outline",
  size = "default",
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Cancel> &
  Pick<React.ComponentProps<typeof Button>, "variant" | "size">) {
  return (
    <Button variant={variant} size={size} asChild>
      <AlertDialogPrimitive.Cancel
        data-slot="alert-dialog-cancel"
        className={cn(className)}
        {...props}
      />
    </Button>
  )
}

export {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogMedia,
  AlertDialogOverlay,
  AlertDialogPortal,
  AlertDialogTitle,
  AlertDialogTrigger,
}
