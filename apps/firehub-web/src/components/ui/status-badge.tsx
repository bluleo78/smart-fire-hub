import * as React from 'react';

import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

/**
 * 공통 상태 배지 — 의미(semantic) 단위로 색을 통일한다.
 *
 * 디자인 시스템 매핑 (앱 전체 동일하게 유지):
 * - active     활성/켜짐                → success (녹색)
 * - inactive   비활성/꺼짐              → secondary (회색)
 * - success    완료/정상/성공           → success (녹색)
 * - error      실패/이상/오류           → destructive (빨강)
 * - warning    경고/재인증 필요/주의    → warning (주황)
 * - info       진행중/실행중/처리중     → info (파랑)
 * - pending    대기/예정                → outline (테두리만, 회색)
 * - unknown    미확인/미연결            → outline (테두리만, muted)
 *
 * 사용 예:
 *   <StatusBadge type="active">활성</StatusBadge>
 *   <StatusBadge type="error" title="2026-04-26 확인">이상</StatusBadge>
 *
 * 직접 Badge variant를 쓰지 말고 의미 기반으로 이 컴포넌트를 사용한다.
 */
export type StatusBadgeType =
  | 'active'
  | 'inactive'
  | 'success'
  | 'error'
  | 'warning'
  | 'info'
  | 'pending'
  | 'unknown';

const TYPE_TO_VARIANT: Record<
  StatusBadgeType,
  'default' | 'secondary' | 'destructive' | 'outline' | 'success' | 'warning' | 'info'
> = {
  active: 'success',
  inactive: 'secondary',
  success: 'success',
  error: 'destructive',
  warning: 'warning',
  info: 'info',
  pending: 'outline',
  unknown: 'outline',
};

interface StatusBadgeProps extends React.ComponentProps<'span'> {
  type: StatusBadgeType;
  /** 추가 className (확장용). variant 색은 type으로만 결정한다. */
  className?: string;
}

export function StatusBadge({ type, className, children, ...rest }: StatusBadgeProps) {
  const variant = TYPE_TO_VARIANT[type];
  // unknown은 muted 톤을 살짝 더 줘서 pending과 시각적으로 구분
  const extra = type === 'unknown' ? 'text-muted-foreground' : undefined;
  return (
    <Badge
      variant={variant}
      data-status={type}
      className={cn(extra, className)}
      {...rest}
    >
      {children}
    </Badge>
  );
}
