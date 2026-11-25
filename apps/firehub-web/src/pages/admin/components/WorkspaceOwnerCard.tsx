import { toast } from 'sonner';

import { CardSectionHeading } from '@/components/CardSectionHeading';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { useTransferOwnership } from '@/hooks/queries/useUsers';
import { useAuth } from '@/hooks/useAuth';
import { handleApiError } from '@/lib/api-error';
import type { UserDetailResponse } from '@/types/user';

import { TransferOwnershipDialog } from './TransferOwnershipDialog';

interface WorkspaceOwnerCardProps {
  user: UserDetailResponse;
  /** 소유권을 넘긴 뒤 상세를 다시 읽는다 */
  onTransferred: () => void;
}

/**
 * 사용자 상세의 「워크스페이스 소유자」 카드(WD-1132) — 지금 소유자를 보이고, 소유자 본인에게만 이 사용자에게 소유권을 넘기는 버튼을 준다.
 * 워크스페이스 이름이 없으면(자기 프로필 경로 등) 카드를 그리지 않는다.
 * 버튼 조건: 조회자가 소유자 + 대상이 이미 소유자가 아님 + 대상 멤버십·계정 활성 — 서버가 같은 조건을 403/409 로 다시 막는다.
 */
export function WorkspaceOwnerCard({ user, onTransferred }: WorkspaceOwnerCardProps) {
  // 요청은 확인 창이 아니라 이 카드가 들고 있다 — 확인 버튼이 창을 닫아 창 내용이 사라져도 응답 후 안내·갱신이 실행된다
  const transfer = useTransferOwnership();
  const { refreshSession } = useAuth();

  if (!user.workspaceName) return null;

  const handleConfirm = (confirmName: string) => {
    transfer.mutate(
      { id: user.id, confirmName },
      {
        onSuccess: () => {
          toast.success('소유권을 넘겼습니다');
          onTransferred();
          // 내 멤버십 역할(소유자→관리자)이 바뀌었으므로 전환기 라벨·프로필을 새로 읽는다 — 실패해도 이전 자체는 성공이라 무시
          void refreshSession().catch(() => {});
        },
        // 서버가 400(이름 불일치)·403·404·409 마다 구체 메시지를 준다 — 그대로 보인다
        onError: (error) => handleApiError(error, '소유권을 넘기지 못했습니다.'),
      },
    );
  };

  const canTransfer =
    user.viewerIsOwner === true &&
    user.membershipRole !== 'OWNER' &&
    user.isActive &&
    user.accountActive !== false;

  return (
    <Card>
      <CardHeader>
        <CardSectionHeading>워크스페이스 소유자</CardSectionHeading>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-sm text-muted-foreground">
          {user.workspaceOwnerName ? `지금 소유자: ${user.workspaceOwnerName}` : '지정된 소유자가 없습니다'}
        </p>
        {canTransfer && (
          <TransferOwnershipDialog
            targetName={user.name || user.username}
            workspaceName={user.workspaceName}
            onConfirm={handleConfirm}
            trigger={
              // 요청이 끝나 재조회로 카드가 바뀌기 전에 한 번 더 눌러 같은 이전을 두 번 보내지 않게 막는다
              <Button
                variant="outline"
                className="border-destructive text-destructive hover:text-destructive"
                disabled={transfer.isPending}
              >
                소유권 넘기기…
              </Button>
            }
          />
        )}
      </CardContent>
    </Card>
  );
}
