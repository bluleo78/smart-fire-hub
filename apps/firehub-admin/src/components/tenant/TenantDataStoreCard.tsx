import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { toast } from 'sonner';

import { tenantsApi } from '@/api/tenants';
import { DataStoreScriptDialog } from '@/components/tenant/DataStoreScriptDialog';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Skeleton } from '@/components/ui/skeleton';
import { StatusBadge } from '@/components/ui/status-badge';
import { BACKEND_LABELS, MODE_LABELS } from '@/lib/data-store-labels';
import { isForbidden, serverMessage } from '@/lib/http-errors';
import type { AccountCheck } from '@/types/platform';

/**
 * 테넌트 상세의 "데이터셋 저장소" 카드.
 *
 * PostgreSQL 테넌트는 서버가 `backend` 외 전부 null 로 주므로 백엔드 이름만 보인다.
 * 외부 저장소(Tibero)는 접속 정보·스키마·상태를 보이고, PENDING(스크립트 모드로 만들어 DBA 가
 * 아직 실행하지 않았거나 확인 전)일 때만 「연결 확인」(verify)과 「스크립트 재발급」을 둔다 —
 * READY 에서 재발급하면 동작 중인 계정의 비밀번호가 바뀌어 장애가 되므로 서버도 409 로 막는다.
 * 쓰기 버튼은 서버가 `platform:tenant:create` 를 요구하므로 `canManage` 일 때만 보인다.
 */
export function TenantDataStoreCard({
  tenantId,
  canManage,
}: {
  tenantId: number;
  canManage: boolean;
}) {
  const queryClient = useQueryClient();
  const [failedChecks, setFailedChecks] = useState<AccountCheck[]>([]);
  const [reissuedScript, setReissuedScript] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ['platform-tenant-data-store', tenantId],
    queryFn: () => tenantsApi.dataStore(tenantId).then((r) => r.data),
  });

  const verifyMutation = useMutation({
    mutationFn: () => tenantsApi.verifyDataStore(tenantId).then((r) => r.data),
    onSuccess: (res) => {
      if (res.result === 'PASSWORDS_CHANGED') {
        // 확인하는 동안 스크립트가 재발급돼 비밀번호가 바뀌었다 — 이전 비밀번호 기준 결과이므로 READY 가 아니다.
        setFailedChecks([]);
        toast.info('확인 중 비밀번호가 재발급되었습니다. 새 스크립트를 실행한 뒤 다시 확인하세요.');
        return;
      }
      if (res.status === 'READY') {
        setFailedChecks([]);
        toast.success('저장소 연결을 확인했습니다.');
        // 카드 상태(READY)·버튼 노출을 서버 값으로 다시 그린다.
        void queryClient.invalidateQueries({ queryKey: ['platform-tenant-data-store', tenantId] });
        return;
      }
      setFailedChecks(res.checks.filter((c) => !c.ok));
    },
    onError: (error) =>
      toast.error(serverMessage(error, [400, 409, 422]) ?? '연결 확인에 실패했습니다.'),
  });

  // gcTime: 0 — 응답(스크립트 = 계정 비밀번호)이 관찰자가 없어지는 즉시 TanStack 캐시에서 사라지게 한다.
  const reissueMutation = useMutation({
    gcTime: 0,
    mutationFn: () => tenantsApi.reissueDataStoreScript(tenantId).then((r) => r.data),
    onSuccess: (res) => {
      // 새 비밀번호로 바뀌었으므로 이전 비밀번호로 확인한 실패 목록은 더 이상 맞지 않다.
      setFailedChecks([]);
      setReissuedScript(res.script);
    },
    onError: (error) =>
      toast.error(serverMessage(error, [400, 409, 422]) ?? '스크립트 재발급에 실패했습니다.'),
  });

  const store = query.data;
  const isExternal = !!store && store.backend !== 'POSTGRESQL';
  const isPending = isExternal && store.status === 'PENDING';

  return (
    <Card data-testid="data-store-card">
      <CardHeader>
        <CardTitle>데이터셋 저장소</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {query.isLoading ? (
          <Skeleton className="h-20 w-full" />
        ) : query.isError && isForbidden(query.error) ? (
          <InlineBanner variant="info">저장소 정보를 조회할 권한이 없습니다.</InlineBanner>
        ) : query.isError || !store ? (
          // 못 불러온 것을 "PostgreSQL" 로 보여 주면 안 된다 — 다른 DB 를 쓰는 테넌트를 오인하게 된다.
          <InlineBanner
            variant="caution"
            actions={
              <Button variant="outline" size="sm" onClick={() => void query.refetch()}>
                다시 시도
              </Button>
            }
          >
            저장소 정보를 불러오지 못했습니다.
          </InlineBanner>
        ) : (
          <>
            <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
              <span className="text-muted-foreground">저장소</span>
              <span>{BACKEND_LABELS[store.backend] ?? store.backend}</span>
              {isExternal && (
                <>
                  <span className="text-muted-foreground">접속 정보</span>
                  <span className="font-mono text-[13px]">
                    {store.host}:{store.port}/{store.database}
                  </span>
                  <span className="text-muted-foreground">스키마</span>
                  <span className="font-mono text-[13px]">{store.schemaName ?? '-'}</span>
                  <span className="text-muted-foreground">테이블스페이스</span>
                  <span className="font-mono text-[13px]">{store.tablespace ?? '-'}</span>
                  <span className="text-muted-foreground">프로비저닝 방식</span>
                  <span>{store.mode ? (MODE_LABELS[store.mode] ?? store.mode) : '-'}</span>
                  <span className="text-muted-foreground">상태</span>
                  <span>
                    {store.status === 'READY' ? (
                      <StatusBadge type="active">사용 가능</StatusBadge>
                    ) : (
                      <StatusBadge type="warning">확인 필요</StatusBadge>
                    )}
                  </span>
                </>
              )}
            </div>

            {isPending && canManage && (
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => verifyMutation.mutate()}
                  disabled={verifyMutation.isPending}
                >
                  연결 확인
                </Button>
                {store.mode === 'SCRIPT' && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => reissueMutation.mutate()}
                    disabled={reissueMutation.isPending}
                  >
                    스크립트 재발급
                  </Button>
                )}
              </div>
            )}

            {isPending && failedChecks.length > 0 && (
              <InlineBanner variant="caution">
                <div className="space-y-1">
                  <p>다음 계정을 확인하지 못했습니다.</p>
                  <ul className="list-disc pl-5">
                    {failedChecks.map((c) => (
                      <li key={c.username}>
                        {c.username} — {c.message ?? '확인하지 못했습니다.'}
                      </li>
                    ))}
                  </ul>
                </div>
              </InlineBanner>
            )}
          </>
        )}
      </CardContent>

      {/* 재발급은 이동 없이 상세에 남는다 — 생성 흐름과 같은 대화상자를 쓰되 확인은 닫기만 한다. */}
      {/* 닫을 때 뮤테이션 상태의 응답(스크립트 = 계정 비밀번호)도 비운다(캐시는 gcTime: 0 이 비운다). */}
      <DataStoreScriptDialog
        script={reissuedScript}
        onConfirm={() => {
          setReissuedScript(null);
          reissueMutation.reset();
        }}
      />
    </Card>
  );
}
