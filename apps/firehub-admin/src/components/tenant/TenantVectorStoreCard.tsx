import { useQuery } from '@tanstack/react-query';

import { tenantsApi } from '@/api/tenants';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Skeleton } from '@/components/ui/skeleton';
import { useDedicatedVectorStoreSupported } from '@/hooks/useDedicatedVectorStoreSupported';
import { isForbidden } from '@/lib/http-errors';

/**
 * 테넌트 상세의 "벡터 저장소" 카드(WD-742). `TenantDataStoreCard` 와 같은 모양이다.
 *
 * 공유 Qdrant 면 「공유 Qdrant」만, 전용이면 접속 정보(host:gRPC 포트)와 TLS 여부를 보인다. API 키는 서버가 주지 않는다.
 * 설치가 Qdrant 를 쓰지 않으면(`dedicatedSupported=false`) 카드를 그리지 않는다 — 그런 설치에서 「공유 Qdrant」라고
 * 보이면 사실과 다르고, 고를 수 있는 선택지도 없으므로 보일 정보가 없다.
 */
export function TenantVectorStoreCard({ tenantId }: { tenantId: number }) {
  const supported = useDedicatedVectorStoreSupported();
  const query = useQuery({
    queryKey: ['platform-tenant-vector-store', tenantId],
    queryFn: () => tenantsApi.getTenantVectorStore(tenantId).then((r) => r.data),
    // 지원하지 않는 설치에서는 부르지 않는다(카드를 그리지 않으므로 받을 이유가 없다).
    enabled: supported,
  });

  if (!supported) return null;

  const store = query.data;
  // 예상 밖 본문(문자열 등)도 「불러오지 못함」으로 본다 — 「공유」로 잘못 보이지 않게 dedicated 가 boolean 인지 확인한다.
  const valid = !!store && typeof store === 'object' && typeof store.dedicated === 'boolean';

  return (
    <Card data-testid="vector-store-card">
      <CardHeader>
        <CardTitle>벡터 저장소</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {query.isLoading ? (
          <Skeleton className="h-10 w-full" />
        ) : query.isError && isForbidden(query.error) ? (
          <InlineBanner variant="info">벡터 저장소 정보를 조회할 권한이 없습니다.</InlineBanner>
        ) : query.isError || !valid ? (
          // 못 불러온 것을 「공유 Qdrant」로 보여 주면 안 된다 — 전용 인스턴스를 쓰는 테넌트를 오인하게 된다.
          <InlineBanner
            variant="caution"
            actions={
              <Button variant="outline" size="sm" onClick={() => void query.refetch()}>
                다시 시도
              </Button>
            }
          >
            벡터 저장소 정보를 불러오지 못했습니다.
          </InlineBanner>
        ) : (
          <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
            <span className="text-muted-foreground">저장소</span>
            <span>{store.dedicated ? '전용 Qdrant' : '공유 Qdrant'}</span>
            {store.dedicated && (
              <>
                <span className="text-muted-foreground">접속 정보</span>
                <span className="font-mono text-[13px]">
                  {store.host}:{store.grpcPort}
                </span>
                <span className="text-muted-foreground">TLS</span>
                <span>{store.tls ? '사용' : '사용 안 함'}</span>
              </>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
