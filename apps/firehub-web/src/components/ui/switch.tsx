"use client"

import { Switch as SwitchPrimitive } from "radix-ui"
import * as React from "react"

import { cn } from "@/lib/utils"

function Switch({
  className,
  size = "default",
  ...props
}: React.ComponentProps<typeof SwitchPrimitive.Root> & {
  size?: "sm" | "default"
}) {
  return (
    // 루트는 클릭·포커스를 받는 히트 상자로만 둔다(WD-891): 항상 32×32 이고 배경은 투명 — 시각 알약은
    // 아래 안쪽 [data-slot=switch-track] 이 담당해, 알약 크기(32×18)는 그대로 두고 히트 영역만 넓힌다.
    // data-size 는 루트에만 붙으므로 안쪽 요소는 group-data-[size=…]/switch 로 읽는다 — 루트 크기는 두 값이
    // 같으므로 size 마다 나누지 않고 h-8 w-8 하나로 둔다(모든 크기의 히트 상자가 32×32).
    <SwitchPrimitive.Root
      data-slot="switch"
      data-size={size}
      className={cn(
        "peer group/switch inline-flex shrink-0 items-center justify-center rounded-full border border-transparent bg-transparent transition-all outline-none disabled:cursor-not-allowed disabled:opacity-50 h-8 w-8",
        className
      )}
      {...props}
    >
      {/* 시각 알약(track) — 상태색·테두리·그림자·알약 크기를 루트에서 옮겨 왔다. data-state 도 루트의 그룹에서 읽는다.
          WD-924: 키보드 포커스 링도 루트(32×32 원)가 아니라 알약에 그린다 — 루트의 focus-visible 을 group-focus-visible/switch 로 읽어 알약 모양 링이 되게 한다. */}
      <span
        data-slot="switch-track"
        className={cn(
          "flex shrink-0 items-center rounded-full border border-transparent shadow-xs transition-all group-focus-visible/switch:border-ring group-focus-visible/switch:ring-ring group-focus-visible/switch:ring-[3px] group-data-[size=default]/switch:h-[1.15rem] group-data-[size=default]/switch:w-8 group-data-[size=sm]/switch:h-3.5 group-data-[size=sm]/switch:w-6 group-data-[state=checked]/switch:bg-primary group-data-[state=unchecked]/switch:bg-input dark:group-data-[state=unchecked]/switch:bg-input/80"
        )}
      >
        <SwitchPrimitive.Thumb
          data-slot="switch-thumb"
          className={cn(
            "bg-background dark:data-[state=unchecked]:bg-foreground dark:data-[state=checked]:bg-primary-foreground pointer-events-none block rounded-full ring-0 transition-transform group-data-[size=default]/switch:size-4 group-data-[size=sm]/switch:size-3 data-[state=checked]:translate-x-[calc(100%-2px)] data-[state=unchecked]:translate-x-0"
          )}
        />
      </span>
    </SwitchPrimitive.Root>
  )
}

export { Switch }
