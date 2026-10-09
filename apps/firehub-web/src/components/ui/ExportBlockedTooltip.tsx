import { cloneElement, type ReactElement } from 'react';

import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';

/** 스펙 §5-4 내보내기 차단 문구 — 서버 정책 거부 사유와 무관하게 화면에는 이 한 문장만 쓴다. */
export const EXPORT_BLOCKED_MESSAGE = '보안 등급 정책상 이 데이터는 내보낼 수 없습니다.';

interface Props {
  /** true 면 버튼을 비활성하고 사유 툴팁을 띄운다. */
  blocked: boolean;
  /** 툴팁·접근 이름 문구 — 기본은 정책 차단 문구. 정책과 무관한 사유(실행 기록 없음 등)일 때만 바꾼다. */
  message?: string;
  /** 감쌀 주 내보내기 버튼 — 차단이면 disabled 로 복제한다. */
  children: ReactElement<{ disabled?: boolean }>;
}

/**
 * 주 내보내기 버튼 래퍼 — 차단이면 비활성 + 사유 툴팁(UI 수준 차단, 스펙 §4.4·§5-4). 서버가 내보내기를 다시 판정하므로
 * 이 래퍼는 안내 수단일 뿐 보안 경계가 아니다.
 * disabled 버튼은 포인터 이벤트가 막혀 툴팁이 뜨지 않으므로 포커스 가능한 span 으로 감싼다(MemberDangerZone 관례).
 * AppLayout 의 TooltipProvider 는 Outlet 바깥이라 페이지 안에서 쓸 수 없어 로컬 Provider 로 감싼다.
 */
export function ExportBlockedTooltip({ blocked, message = EXPORT_BLOCKED_MESSAGE, children }: Props) {
  if (!blocked) return children;
  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          {/* 포커스 가능한 래퍼라 이름이 필요하다 — role 없는 span 엔 aria-label 이 금지(ARIA 1.2)라 group 으로 이름을 단다. */}
          <span
            tabIndex={0}
            role="group"
            aria-label={message}
            className="inline-flex rounded-md focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring"
          >
            {cloneElement(children, { disabled: true })}
          </span>
        </TooltipTrigger>
        <TooltipContent>{message}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
