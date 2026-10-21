import { AlertTriangle } from 'lucide-react';
import { Outlet } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { InlineBanner } from '@/components/ui/inline-banner';
import { RestrictedNotice } from '@/components/ui/restricted-notice';
import { Skeleton } from '@/components/ui/skeleton';

import { useMyPermissions } from '../hooks/queries/useMyPermissions';
import { useAuth } from '../hooks/useAuth';

/** 화면 권한이 없을 때 보이는 안내 — 관리 화면 전체가 같은 문구를 쓴다(WD-121, WD-126 문구 선례). */
const SCREEN_FORBIDDEN_MESSAGE = '이 화면을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.';

/**
 * ADMIN 이거나 지정 권한 보유자만 화면을 연다(스펙 §5-1, WD-121).
 *
 * 관리 화면은 예전에 역할 이름(ADMIN)만 봐서, 역할에 user:read·role:read 등을 줘도 웹에서는 아무 효과가 없었다.
 * 서버가 화면별 권한 코드로 인가하므로 화면 진입도 같은 코드로 판정한다.
 *
 * - 권한이 없으면 홈으로 튕기지 않고 레이아웃 안에서 권한 안내를 보인다 — 말없이 홈으로 보내면 사용자는 왜 안 열리는지 모른다.
 * - 권한 조회가 끝나기 전에는 판단하지 않는다(로딩 중 안내가 깜빡이는 것 방지). ADMIN 도 기다린다 — 화면 안 버튼이
 *   권한 코드로 노출되므로, 기다리지 않으면 빈 권한으로 먼저 그려졌다가 버튼이 뒤늦게 나타난다.
 * - 권한 조회 자체가 실패하면 「권한 없음」이 아니라 불러오기 실패로 알린다 — 권한이 있는 사람에게 권한 요청을 하라고 하면 틀린 안내다.
 *   ADMIN 은 진입 판정에 권한 코드가 필요 없으므로 그대로 연다(예전과 같다).
 */
export function AdminOrPermissionRoute({ permission }: { permission: string }) {
  const { isAdmin, isLoading } = useAuth();
  const { permissions, isLoading: permLoading, isError: permError, refetch } = useMyPermissions();
  if (isLoading || permLoading) {
    // 레이아웃 안 로딩 — 다른 화면의 Suspense 폴백(PageSkeleton, 06-feedback-states §A-1)과 같은 모양이다.
    return (
      <div className="space-y-6" aria-busy="true" data-testid="screen-permission-loading">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }
  if (!isAdmin && permError) {
    return (
      <div className="flex justify-center py-12">
        <InlineBanner
          variant="warning"
          icon={<AlertTriangle />}
          className="max-w-md"
          data-testid="screen-permission-error"
          actions={
            <Button size="sm" variant="outline" onClick={() => void refetch()}>
              다시 시도
            </Button>
          }
        >
          권한 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </InlineBanner>
      </div>
    );
  }
  if (!isAdmin && !permissions.has(permission)) {
    return (
      <div className="flex justify-center py-12">
        <RestrictedNotice message={SCREEN_FORBIDDEN_MESSAGE} className="max-w-md" data-testid="screen-forbidden" />
      </div>
    );
  }
  return <Outlet />;
}
