import { useMutation, useQueryClient } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { Plus, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { datasetsApi } from '../../../api/datasets';
import { securityLevelsApi } from '../../../api/security-levels';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../../components/ui/alert-dialog';
import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../../components/ui/card';
import { InlineBanner } from '../../../components/ui/inline-banner';
import { SecurityLevelBadge } from '../../../components/ui/SecurityLevelBadge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import { useMyPermissions } from '../../../hooks/queries/useMyPermissions';
import { useAccessGrants } from '../../../hooks/queries/useSecurityLevels';
import { useAuth } from '../../../hooks/useAuth';
import { useRecentDatasets } from '../../../hooks/useRecentDatasets';
import { handleApiError } from '../../../lib/api-error';
import { formatDateShort } from '../../../lib/formatters';
import { policyChips } from '../../../lib/security-level';
import type { DatasetDetailResponse } from '../../../types/dataset';
import type { AccessGrant } from '../../../types/security-level';
import { AddAccessGrantDialog } from '../components/AddAccessGrantDialog';

/**
 * 「보안」 탭(목업 s2) — "무엇이 제한되나 / 누가 볼 수 있나". 모든 데이터셋 유형(표·문서·파일) 공통.
 * 잠김 방지 규칙(마지막 항목 제거 불가·본인 제거 확인)은 서버가 강제하고, 여기서는 미리 막거나 확인을 받아 놀라지 않게 한다.
 */
export function DatasetSecurityTab({ dataset }: { dataset: DatasetDetailResponse }) {
  const level = dataset.securityLevel;
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { user, roles } = useAuth();
  const { removeRecent } = useRecentDatasets();
  const { permissions } = useMyPermissions();
  const canGrant = permissions.has('dataset:grant');
  const { data: grants = [] } = useAccessGrants(dataset.id, !!level?.allowlistRequired);
  const [addOpen, setAddOpen] = useState(false);
  const [selfRemoval, setSelfRemoval] = useState<AccessGrant | null>(null);
  // 접근을 잃어 목록으로 보낼 때 true — 언마운트 시점에 이 데이터셋의 캐시를 지운다(아래 effect).
  const lostAccessRef = useRef(false);

  // 접근을 잃었으면 언마운트(=목록으로 이동 완료) 때 ['datasets', id] 접두 캐시(상세·허용 목록·데이터 등)를 지운다.
  // 마운트 중에 지우면 상세 쿼리가 곧바로 다시 조회해 404 → 일반 "찾을 수 없습니다" 토스트가 뜨므로 언마운트까지 미룬다.
  // 지우지 않으면 다시 방문할 때 낡은 상세가 잠깐 보인다.
  useEffect(
    () => () => {
      if (lostAccessRef.current) qc.removeQueries({ queryKey: ['datasets', dataset.id] });
    },
    [qc, dataset.id],
  );

  /** 본인 사용자 항목이거나 본인이 가진 역할 항목인가 — 제거하면 본인이 잠길 수 있어 확인을 받는다. */
  const isOwnEntry = (g: AccessGrant) =>
    g.type === 'USER' ? g.subjectId === user?.id : roles.some((r) => r.id === g.subjectId);

  const remove = useMutation({
    mutationFn: (grantId: number) => securityLevelsApi.removeGrant(dataset.id, grantId),
    onSuccess: async () => {
      // 제거 뒤 여전히 볼 수 있는지 캐시 밖에서 확인한다. 숨김 데이터셋은 어디서나 404 라서,
      // 캐시된 상세를 그냥 무효화하면 페이지가 "찾을 수 없습니다" 오류로 튕겨 원인을 알 수 없다.
      // 관리자 우회·역할 항목 등으로 계속 보이면 그대로 머문다.
      const stillVisible = await datasetsApi
        .getDatasetById(dataset.id)
        .then((r) => r.data)
        .catch((e: unknown) => (isAxiosError(e) && e.response?.status === 404 ? null : undefined));
      if (stillVisible === null) {
        // 마운트된 상세 쿼리가 404 로 재조회되지 않도록 refetch 없이 stale 표시만 하고 목록으로 보낸다.
        // 이 데이터셋 캐시는 언마운트 때 지우고(lostAccessRef), 최근 본 데이터셋 바로가기에서도 뺀다.
        lostAccessRef.current = true;
        removeRecent(dataset.id);
        void qc.invalidateQueries({ queryKey: ['datasets'], refetchType: 'none' });
        toast.info('허용 목록에서 제거했습니다. 더 이상 이 데이터셋에 접근할 수 없습니다.');
        navigate('/data/datasets');
        return;
      }
      toast.success('허용 목록에서 제거했습니다');
      if (stillVisible) qc.setQueryData(['datasets', dataset.id], stillVisible);
      void qc.invalidateQueries({ queryKey: ['datasets', dataset.id, 'access-grants'] });
    },
    // ALLOWLIST_LAST_ENTRY·GRANT_NOT_FOUND 는 서버가 한국어 message 를 싣는다.
    onError: (e) => handleApiError(e, '허용 목록 제거에 실패했습니다.'),
  });

  // 생성 응답 등에서 등급이 비어 올 수 있다 — 깨지지 않고 한 줄 안내로 대체한다.
  if (!level) {
    return <p className="text-sm text-muted-foreground">보안 등급 정보를 불러올 수 없습니다.</p>;
  }
  const chips = policyChips(level);
  const isLast = grants.length <= 1;

  return (
    <div className="space-y-4">
      {dataset.securityLevelAutoRaisedAt && (
        <InlineBanner variant="info">
          {`입력 데이터셋의 등급에 따라 '${level.name}'(으)로 자동 상향되었습니다 (${formatDateShort(dataset.securityLevelAutoRaisedAt)}).`}
        </InlineBanner>
      )}
      <Card>
        <CardHeader>
          <CardTitle>보안 등급</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-2">
          <SecurityLevelBadge level={level} />
          {chips.length === 0 ? (
            <span className="text-sm text-muted-foreground">제한 없음</span>
          ) : (
            chips.map((c) => (
              <Badge key={c} variant="outline">
                {c}
              </Badge>
            ))
          )}
        </CardContent>
      </Card>
      {level.allowlistRequired ? (
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <CardTitle>허용 목록</CardTitle>
            {canGrant && (
              <Button size="sm" variant="outline" onClick={() => setAddOpen(true)}>
                <Plus className="h-4 w-4" />
                추가
              </Button>
            )}
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>유형</TableHead>
                  <TableHead>이름</TableHead>
                  <TableHead>추가한 사람</TableHead>
                  <TableHead>추가일</TableHead>
                  <TableHead className="w-[48px]" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {grants.map((g) => (
                  <TableRow key={g.id}>
                    <TableCell>{g.type === 'ROLE' ? '역할' : '사용자'}</TableCell>
                    <TableCell>{g.subjectName}</TableCell>
                    <TableCell>{g.grantedByName ?? '-'}</TableCell>
                    <TableCell>{formatDateShort(g.grantedAt)}</TableCell>
                    <TableCell>
                      {canGrant && (
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7"
                          aria-label={`${g.subjectName} 제거`}
                          title={isLast ? '마지막 항목은 제거할 수 없습니다' : undefined}
                          disabled={isLast || remove.isPending}
                          // 본인 사용자·본인 역할 항목은 확인을 받는다 — 제거하는 순간 본인이 잠길 수 있다.
                          onClick={() => (isOwnEntry(g) ? setSelfRemoval(g) : remove.mutate(g.id))}
                        >
                          <X className="h-4 w-4" />
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <p className="mt-2 text-xs text-muted-foreground">마지막 항목은 제거할 수 없습니다.</p>
          </CardContent>
        </Card>
      ) : (
        <p className="text-sm text-muted-foreground">{`열람 등급이 '${level.name}' 이상인 역할은 누구나 볼 수 있습니다.`}</p>
      )}
      <AddAccessGrantDialog datasetId={dataset.id} open={addOpen} onOpenChange={setAddOpen} />
      <AlertDialog open={!!selfRemoval} onOpenChange={(o) => !o && setSelfRemoval(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {selfRemoval?.type === 'ROLE'
                ? '본인이 속한 역할을 허용 목록에서 제거할까요?'
                : '본인을 허용 목록에서 제거할까요?'}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {selfRemoval?.type === 'ROLE'
                ? '다른 항목으로 허용되지 않았다면, 제거 후 이 데이터셋에 더 이상 접근할 수 없습니다.'
                : '제거하면 이 데이터셋에 더 이상 접근할 수 없습니다.'}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                if (selfRemoval) remove.mutate(selfRemoval.id);
                setSelfRemoval(null);
              }}
            >
              제거
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
