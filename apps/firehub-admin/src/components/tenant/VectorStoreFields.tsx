import { useMutation } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useFormContext, useWatch } from 'react-hook-form';

import { tenantsApi } from '@/api/tenants';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/ui/form-field';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { PasswordInput } from '@/components/ui/password-input';
import { Separator } from '@/components/ui/separator';
import { Switch } from '@/components/ui/switch';
import { useDedicatedVectorStoreSupported } from '@/hooks/useDedicatedVectorStoreSupported';
import { serverMessage } from '@/lib/http-errors';
import type { CreateTenantFormData } from '@/lib/validations/tenant';
import type { VectorStoreTestResult } from '@/types/platform';

/**
 * 테넌트 생성 폼의 "벡터 저장소" 섹션(WD-742).
 *
 * 기본은 공유 Qdrant 다. 「전용 Qdrant 사용」을 켜면 행 검색 색인을 둘 테넌트 전용 인스턴스의 접속 정보를 받는다.
 * 설치가 Qdrant 를 쓰지 않으면(`dedicatedSupported=false`) 섹션 자체를 그리지 않는다.
 *
 * `DataStoreFields` 와 같은 구조다 — 부모 폼의 `vectorStore.*` 필드에 붙고, 오류 문구의 출처는 zod(`vectorStoreSchema`)
 * 하나다. 연결 확인은 `vectorStore` 필드만 검증한 뒤 호출해 이름·Owner 를 비운 채로도 먼저 확인할 수 있다.
 * 연결 확인 뮤테이션은 변수로 API 키를 들고 있으므로 토글을 바꾸거나 생성이 성공하면(`submitted`) `reset()` 으로
 * 결과·변수를 비우고, `gcTime: 0` 으로 TanStack 캐시에서도 즉시 버린다.
 */
export function VectorStoreFields({ submitted = false }: { submitted?: boolean }) {
  const {
    register,
    control,
    setValue,
    trigger,
    getValues,
    formState: { errors },
  } = useFormContext<CreateTenantFormData>();
  const supported = useDedicatedVectorStoreSupported();

  // useWatch 는 이 컴포넌트만 다시 그린다. Radix Switch 는 네이티브 입력이 아니라 register 대신 값을 읽고 setValue 로 쓴다.
  const [enabled, tls] = useWatch({ control, name: ['vectorStore.enabled', 'vectorStore.tls'] });
  const fieldErrors = errors.vectorStore;

  // 결과·오류는 뮤테이션 상태에서 바로 읽는다(DataStoreFields 와 같은 이유 — reset() 경로를 하나로).
  const testMutation = useMutation({ mutationFn: tenantsApi.testVectorStore, gcTime: 0 });
  const testResult: VectorStoreTestResult | null = testMutation.data?.data ?? null;
  const testError = testMutation.isError
    ? (serverMessage(testMutation.error, [400, 422]) ?? '연결 확인을 실행하지 못했습니다.')
    : null;

  // 생성이 성공하면 연결 확인 결과·변수(API 키 포함)를 비운다.
  const { reset: resetTest } = testMutation;
  useEffect(() => {
    if (submitted) resetTest();
  }, [submitted, resetTest]);

  if (!supported) return null;

  const runConnectionTest = async () => {
    // 벡터 저장소 필드만 검증한다(이름·Owner·데이터 저장소 오류는 건드리지 않는다).
    const valid = await trigger('vectorStore');
    if (!valid) return;
    const v = getValues('vectorStore');
    testMutation.mutate({
      host: v.host.trim(),
      grpcPort: Number(v.grpcPort.trim()),
      tls: v.tls,
      apiKey: v.apiKey,
    });
  };

  return (
    <div className="space-y-4" data-testid="vector-store-fields">
      {/* 다른 섹션처럼 구분선을 앞에 둔다 — 섹션 안에 두어 미지원 설치에서는 함께 사라지게 한다. */}
      <Separator />
      <div>
        <h2 className="text-base font-semibold">벡터 저장소</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          기본은 공유 Qdrant 입니다. 전용 Qdrant 를 쓰면 행 검색 색인을 이 인스턴스에 둡니다. 테넌트를 만든 뒤에는 바꿀 수
          없습니다.
        </p>
      </div>

      <div className="flex items-center gap-3">
        <Switch
          id="vector-store-enabled"
          checked={enabled}
          onCheckedChange={(checked) => {
            setValue('vectorStore.enabled', checked, { shouldValidate: false });
            // 켜고 끄면 이전 연결 확인 결과는 더 이상 이 설정에 대한 것이 아니다.
            resetTest();
          }}
        />
        <Label htmlFor="vector-store-enabled">전용 Qdrant 사용</Label>
      </div>

      {enabled && (
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-[1fr_140px]">
            <FormField label="Qdrant 호스트" htmlFor="vector-store-host" error={fieldErrors?.host?.message}>
              <Input id="vector-store-host" className="font-mono" {...register('vectorStore.host')} />
            </FormField>
            <FormField label="gRPC 포트" htmlFor="vector-store-grpc-port" error={fieldErrors?.grpcPort?.message}>
              <Input
                id="vector-store-grpc-port"
                inputMode="numeric"
                className="font-mono"
                {...register('vectorStore.grpcPort')}
              />
            </FormField>
          </div>
          <p className="-mt-2 text-sm text-muted-foreground">gRPC 포트입니다(기본 6334). REST 포트 6333 이 아닙니다.</p>

          <div className="flex items-center gap-3">
            <Switch
              id="vector-store-tls"
              checked={tls}
              onCheckedChange={(checked) => {
                setValue('vectorStore.tls', checked, { shouldValidate: false });
                resetTest();
              }}
            />
            <Label htmlFor="vector-store-tls">TLS 사용</Label>
          </div>

          <FormField label="Qdrant API 키" htmlFor="vector-store-api-key" error={fieldErrors?.apiKey?.message}>
            <PasswordInput
              id="vector-store-api-key"
              autoComplete="new-password"
              {...register('vectorStore.apiKey')}
            />
            <p className="mt-1.5 text-sm text-muted-foreground">
              서버에 API 키와 JWT RBAC(QDRANT__SERVICE__JWT_RBAC=true)가 켜져 있어야 합니다. 키는 암호화해 저장합니다.
            </p>
          </FormField>

          <div className="space-y-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => void runConnectionTest()}
              disabled={testMutation.isPending}
            >
              연결 확인
            </Button>
            {testResult?.ok && (
              <InlineBanner variant="success">
                <div className="space-y-1">
                  <p>연결되었습니다. 서버 버전: {testResult.serverVersion ?? '알 수 없음'}</p>
                  {testResult.message && <p>{testResult.message}</p>}
                </div>
              </InlineBanner>
            )}
            {testResult && !testResult.ok && (
              <InlineBanner variant="caution">{testResult.message || '연결할 수 없습니다.'}</InlineBanner>
            )}
            {testError && <InlineBanner variant="caution">{testError}</InlineBanner>}
          </div>
        </div>
      )}
    </div>
  );
}
