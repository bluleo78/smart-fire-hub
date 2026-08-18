/**
 * 워크스페이스(테넌트) 선택 화면.
 *
 * <p>언제 보이나: 인증은 됐지만 토큰에 테넌트가 없을 때. 두 경로로 들어온다 —
 * (1) 로그인 시 ACTIVE 멤버십이 2개 이상이라 백엔드가 자동 선택을 하지 않은 경우,
 * (2) 세션 중 테넌트/멤버십이 정지돼 `refresh` 가 토큰을 테넌트 미선택으로 **강등**한 경우.
 *
 * <p>라우트가 아니라 게이트에서 직접 렌더링한다({@link ProtectedRoute}) — 별도 경로로 두면
 * 사용자가 URL 로 우회 진입을 시도할 수 있고, "선택 안 된 상태로 앱에 들어와 있다" 는 순간이
 * 생긴다. 게이트에서 렌더하면 그 순간 자체가 없다.
 */
import { Building2, LogOut } from 'lucide-react';
import { useEffect, useState } from 'react';

import { authApi } from '../api/auth';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { useAuth } from '../hooks/useAuth';
import { useTenantSelection } from '../hooks/useTenantSelection';
import type { MembershipResponse } from '../types/tenant';

export function SelectTenantPage() {
  const { tenantOptions, logout, user } = useAuth();
  // 컨텍스트의 목록이 비어 있을 때만 직접 조회한다. 정상 경로(로그인·refresh 응답)에서는 이미
  // 채워져 있으므로 요청이 나가지 않는다.
  const [fetched, setFetched] = useState<MembershipResponse[] | null>(null);
  const { pendingTenantId, select } = useTenantSelection('워크스페이스를 선택할 수 없습니다.');

  const options = tenantOptions.length > 0 ? tenantOptions : (fetched ?? []);
  const isLoadingOptions = tenantOptions.length === 0 && fetched === null;

  useEffect(() => {
    if (tenantOptions.length > 0) return;
    let ignore = false;
    authApi
      .memberships()
      .then(({ data }) => { if (!ignore) setFetched(data); })
      .catch(() => { if (!ignore) setFetched([]); });
    return () => { ignore = true; };
  }, [tenantOptions.length]);

  return (
    <div className="flex min-h-dvh items-center justify-center bg-muted/30 p-4">
      <div className="w-full max-w-md space-y-4">
        <div className="space-y-1 text-center">
          <h1 className="text-2xl font-semibold tracking-tight">워크스페이스 선택</h1>
          <p className="text-sm text-muted-foreground">
            {user?.name ? `${user.name} 님, 어느 워크스페이스로 들어갈까요?` : '어느 워크스페이스로 들어갈까요?'}
          </p>
        </div>

        {isLoadingOptions ? (
          <p className="text-center text-sm text-muted-foreground">불러오는 중...</p>
        ) : options.length === 0 ? (
          // 멤버십 0개 — 사용자가 스스로 해결할 수 없는 상태다. 운영자 초대가 필요하다는 것을
          // 분명히 알리고 로그아웃만 남긴다(재시도 버튼은 상태를 바꾸지 못한다).
          <Card>
            <CardHeader>
              <CardTitle className="text-base">참여 중인 워크스페이스가 없습니다</CardTitle>
              <CardDescription>
                운영자에게 워크스페이스 초대를 요청하세요. 초대 후 다시 로그인하면 여기에 표시됩니다.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : (
          <div className="space-y-2">
            {options.map((option) => (
              <Card
                key={option.tenantId}
                className="cursor-pointer transition-colors hover:border-primary"
                role="button"
                tabIndex={0}
                aria-label={`${option.tenantName} 워크스페이스 선택`}
                // 진행 중 재클릭은 훅이 무시한다(useTenantSelection).
                onClick={() => select(option.tenantId)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    // 스페이스의 스크롤 기본 동작을 막는다 — role="button" 이라 브라우저가 대신
                    // 해 주지 않는다.
                    e.preventDefault();
                    select(option.tenantId);
                  }
                }}
              >
                <CardContent className="flex items-center gap-3 py-4">
                  <Building2 className="size-5 shrink-0 text-muted-foreground" aria-hidden />
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">{option.tenantName}</p>
                    {/* role 은 표시용 라벨 — 인가 판단에 쓰지 않는다(types/tenant.ts 참조). */}
                    <p className="text-xs text-muted-foreground">{option.role}</p>
                  </div>
                  {pendingTenantId === option.tenantId && (
                    <span className="text-xs text-muted-foreground">전환 중...</span>
                  )}
                </CardContent>
              </Card>
            ))}
          </div>
        )}

        <Button variant="ghost" className="w-full" onClick={() => void logout()}>
          <LogOut className="size-4" />
          로그아웃
        </Button>
      </div>
    </div>
  );
}
