/**
 * 사이드바 하단의 워크스페이스(테넌트) 전환 컴포넌트.
 *
 * <p>무엇을: 현재 실행 중인 테넌트 이름을 보여주고, 참여 중인 테넌트가 2개 이상이면
 * 드롭다운으로 전환할 수 있게 한다. {@link UserNav} 바로 위에 놓이는 형제 컴포넌트다.
 *
 * <p>왜 여기에: 전환은 "지금 어느 워크스페이스에 있는가" 를 항상 볼 수 있어야 안전하다.
 * 사용자 메뉴와 같은 하단 앵커에 붙여 두면 화면을 옮겨 다녀도 표시가 사라지지 않는다.
 *
 * <p>격리는 이 컴포넌트의 책임이 아니다 — 실제 테넌트 격리는 서버측 JWT + RLS 가 한다.
 * 여기서 하는 일은 표시와 전환 요청뿐이다.
 */
import { Building2, Check, ChevronsUpDown, Loader2 } from 'lucide-react';

import { useAuth } from '../../hooks/useAuth';
import { useTenantSelection } from '../../hooks/useTenantSelection';
import { cn } from '../../lib/utils';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu';
import { Tooltip, TooltipContent, TooltipTrigger } from '../ui/tooltip';

interface TenantSwitcherProps {
  collapsed?: boolean;
}

export function TenantSwitcher({ collapsed = false }: TenantSwitcherProps) {
  const { activeTenantId, tenantOptions } = useAuth();
  // 훅은 조기 반환보다 먼저 호출해야 한다(react-hooks/rules-of-hooks).
  const { pendingTenantId, select } = useTenantSelection('워크스페이스를 전환할 수 없습니다.');

  const active = tenantOptions.find((option) => option.tenantId === activeTenantId);

  // 미선택이거나 목록에서 현재 테넌트를 못 찾으면 아무것도 그리지 않는다.
  // activeTenantId(토큰 유래)와 tenantOptions(멤버십 응답 유래)는 서로 다른 경로로 채워지므로
  // id 는 있는데 목록이 아직 빈 렌더가 실제로 존재한다 — 그때 이름을 undefined 로 그리면 안 된다.
  // 미선택 상태 자체는 워크스페이스 선택 화면(SelectTenantPage)이 담당한다.
  if (activeTenantId === null || !active) {
    return null;
  }

  const iconClass = cn('shrink-0 text-muted-foreground', collapsed ? 'h-5 w-5' : 'h-4 w-4');

  // 확장 상태의 본문(이름 + 권한 라벨). 표시 전용과 드롭다운 트리거가 같은 모양을 공유한다.
  const body = (
    <>
      <Building2 className={iconClass} aria-hidden="true" />
      {!collapsed && (
        <div className="min-w-0 flex-1 text-left">
          <p className="truncate text-sm font-medium leading-tight">{active.tenantName}</p>
          {/* role 은 표시용 라벨 — 인가 판단에 쓰지 않는다(types/tenant.ts 참조). */}
          <p className="truncate text-xs text-muted-foreground leading-tight">{active.role}</p>
        </div>
      )}
    </>
  );

  // 참여 워크스페이스가 1개 이하면 표시 전용이다.
  // 왜: 선택지가 하나면 드롭다운이 바꿀 수 있는 게 없고, 클릭 가능한 어포던스는 지킬 수 없는
  // 약속이 된다(눌러도 아무 일이 없는 버튼). 설계서 §6 의 명시 요구이기도 하다.
  if (tenantOptions.length <= 1) {
    const displayOnly = (
      <div
        className={cn(
          'flex w-full items-center gap-2 rounded-md p-2 text-sm',
          collapsed ? 'justify-center' : 'px-3'
        )}
      >
        {body}
        {/* 접힌 상태에서는 텍스트가 없으므로 보조기술용 이름을 따로 제공한다.
            비대화형 요소이므로 tabIndex 는 주지 않는다(가짜 탭 정지는 오히려 방해다). */}
        {collapsed && (
          <span className="sr-only">현재 워크스페이스: {active.tenantName}</span>
        )}
      </div>
    );

    if (!collapsed) return displayOnly;

    // 접힌 사이드바의 아이콘에는 툴팁이 필수다(디자인 시스템 04-components).
    return (
      <Tooltip>
        <TooltipTrigger asChild>{displayOnly}</TooltipTrigger>
        <TooltipContent side="right" sideOffset={8}>
          현재 워크스페이스: {active.tenantName}
        </TooltipContent>
      </Tooltip>
    );
  }

  const trigger = (
    <DropdownMenuTrigger
      aria-label={`워크스페이스 전환 (현재: ${active.tenantName})`}
      className={cn(
        'flex w-full items-center gap-2 rounded-md p-2 text-sm transition-colors',
        'hover:bg-accent/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        collapsed ? 'justify-center' : 'px-3'
      )}
    >
      {body}
      {!collapsed && (
        <ChevronsUpDown className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      )}
    </DropdownMenuTrigger>
  );

  return (
    <DropdownMenu>
      {collapsed ? (
        <Tooltip>
          <TooltipTrigger asChild>{trigger}</TooltipTrigger>
          <TooltipContent side="right" sideOffset={8}>
            워크스페이스: {active.tenantName}
          </TooltipContent>
        </Tooltip>
      ) : (
        trigger
      )}
      <DropdownMenuContent side={collapsed ? 'right' : 'top'} align="start" className="w-56">
        <DropdownMenuLabel>워크스페이스</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {tenantOptions.map((option) => {
          const isActive = option.tenantId === activeTenantId;
          const isPending = pendingTenantId === option.tenantId;
          return (
            <DropdownMenuItem
              key={option.tenantId}
              // 전환 중에는 모든 항목을 잠근다 — 두 번째 전환이 첫 번째 하드 리로드와 경합하면
              // 어느 테넌트로 들어갈지 예측할 수 없게 된다.
              disabled={pendingTenantId !== null}
              onSelect={(event) => {
                // 현재 테넌트면 요청도 하드 리로드도 하지 않고 기본 동작(메뉴 닫힘)을 그대로
                // 둔다 — 아무것도 바뀌지 않는 리로드는 손해이고, 닫힘까지 막으면 아무 반응도
                // 없는 죽은 클릭이 되어 메뉴가 멈춘 것처럼 보인다.
                if (option.tenantId === activeTenantId) return;
                // 전환을 시작할 때만 닫힘을 막는다: 진행 표시가 보여야 하고, 실패 시 목록이
                // 열린 채 남아 바로 재시도할 수 있다.
                event.preventDefault();
                select(option.tenantId);
              }}
            >
              <div className="min-w-0 flex-1">
                <p className="truncate">{option.tenantName}</p>
                {/* role 은 표시용 라벨 — types/tenant.ts 참조. */}
                <p className="truncate text-xs text-muted-foreground">{option.role}</p>
              </div>
              {/* 상태 아이콘. svg 의 aria-label 은 보조기술에 안정적으로 노출되지 않으므로
                  sr-only 텍스트로 따로 알린다. */}
              {isPending ? (
                <>
                  <Loader2
                    className="h-4 w-4 animate-spin text-muted-foreground"
                    aria-hidden="true"
                  />
                  <span className="sr-only">전환 중</span>
                </>
              ) : (
                isActive && (
                  <>
                    <Check className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
                    <span className="sr-only">현재 워크스페이스</span>
                  </>
                )
              )}
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
