import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useFormContext, useWatch } from 'react-hook-form';

import { tenantsApi } from '@/api/tenants';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/ui/form-field';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Input } from '@/components/ui/input';
import { PasswordInput } from '@/components/ui/password-input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { BACKEND_LABELS, DEFAULT_BACKEND, MODE_LABELS } from '@/lib/data-store-labels';
import { serverMessage } from '@/lib/http-errors';
import type { CreateTenantFormData } from '@/lib/validations/tenant';
import type {
  ConnectionTestResult,
  DataStoreBackend,
  DataStoreBackendOption,
  DataStoreProvisionMode,
} from '@/types/platform';

/** 백엔드 목록을 못 받았을 때(로딩·오류)의 대체값 — PostgreSQL 만 보여 기존 흐름을 막지 않는다. */
const FALLBACK_BACKENDS: DataStoreBackendOption[] = [{ backend: 'POSTGRESQL', available: true }];

/**
 * 테넌트 생성 폼의 "데이터셋 저장소" 섹션.
 *
 * react-hook-form 의 `useFormContext` 로 부모 폼의 `dataStore.*` 필드에 붙는다. 오류 문구의 출처는
 * zod(`dataStoreSchema`) 하나다. 연결 테스트(DBA_AUTO 전용, R7)는 폼 전체를 검증하지 않고
 * `dataStore` 필드만 검증한 뒤 호출한다 — 이름·Owner 를 비워 둔 채로도 접속 정보를 먼저 확인할 수 있게.
 *
 * 연결 테스트 뮤테이션은 요청 변수로 DBA 비밀번호를 들고 있으므로, 백엔드·방식을 바꾸거나 생성이 성공하면(`submitted`)
 * `reset()` 으로 결과와 변수를 함께 비우고, `gcTime: 0` 으로 관찰자가 없어지면 TanStack 캐시에서도 즉시 버린다.
 * (`reset()` 은 이 훅의 상태만 비울 뿐 캐시 항목의 제거는 `gcTime` 이 맡는다.)
 */
export function DataStoreFields({ submitted = false }: { submitted?: boolean }) {
  const {
    register,
    control,
    setValue,
    trigger,
    getValues,
    formState: { errors },
  } = useFormContext<CreateTenantFormData>();
  const backendsQuery = useQuery({
    queryKey: ['platform-data-store-backends'],
    queryFn: () => tenantsApi.dataStoreBackends().then((r) => r.data),
    // 백엔드 사용 가능 여부는 배포 설정(기능 플래그)이라 화면을 다시 열 때마다 받을 필요가 없다.
    staleTime: 5 * 60_000,
  });
  // 로딩·오류이거나 응답이 비었거나 배열이 아니면(예상 밖 본문) 대체값을 쓴다. 배열 검사는 문자열 본문이
  // `.length` 를 통과해 `.map` 에서 터지지 않게 남겨 둔다.
  const backends =
    Array.isArray(backendsQuery.data) && backendsQuery.data.length
      ? backendsQuery.data
      : FALLBACK_BACKENDS;

  // useWatch 는 이 컴포넌트만 다시 그린다(폼 전체 watch 는 부모까지 다시 그린다).
  const [backend, mode] = useWatch({ control, name: ['dataStore.backend', 'dataStore.mode'] });
  const fieldErrors = errors.dataStore;

  // 결과·오류는 뮤테이션 상태에서 바로 읽는다 — 별도 state 로 복제하면 reset() 으로 함께 비우는 경로가 둘로 갈린다.
  // 새로 mutate 하면 이전 결과는 자동으로 사라진다.
  const testMutation = useMutation({ mutationFn: tenantsApi.testDataStore, gcTime: 0 });
  const testResult: ConnectionTestResult | null = testMutation.data?.data ?? null;
  const testError = testMutation.isError
    ? (serverMessage(testMutation.error, [400, 422]) ?? '연결 테스트를 실행하지 못했습니다.')
    : null;

  // 생성이 성공하면 연결 테스트 결과·변수(DBA 비밀번호 포함)를 비운다.
  const { reset: resetTest } = testMutation;
  useEffect(() => {
    if (submitted) resetTest();
  }, [submitted, resetTest]);

  const runConnectionTest = async () => {
    // 저장소 필드만 검증한다(이름·Owner 오류는 건드리지 않는다).
    const valid = await trigger('dataStore');
    if (!valid) return;
    const v = getValues('dataStore');
    testMutation.mutate({
      backend: v.backend,
      host: v.host.trim(),
      port: Number(v.port),
      database: v.database.trim(),
      credentials: { username: v.dbaUsername.trim(), password: v.dbaPassword },
    });
  };

  return (
    <div className="space-y-4" data-testid="data-store-fields">
      <div>
        <h2 className="text-base font-semibold">데이터셋 저장소</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          이 테넌트의 데이터셋 테이블이 저장될 곳입니다. 한 번 정하면 바꿀 수 없습니다.
        </p>
      </div>

      <FormField label="저장소 종류" htmlFor="data-store-backend">
        <Select
          value={backend}
          onValueChange={(value) => {
            setValue('dataStore.backend', value as DataStoreBackend, { shouldValidate: false });
            // 백엔드·방식이 바뀌면 이전 연결 테스트 결과는 더 이상 이 설정에 대한 것이 아니다.
            resetTest();
          }}
        >
          <SelectTrigger id="data-store-backend" className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {backends.map((b) => (
              <SelectItem key={b.backend} value={b.backend} disabled={!b.available}>
                {BACKEND_LABELS[b.backend] ?? b.backend}
                {b.backend === DEFAULT_BACKEND && ' (기본)'}
                {!b.available && ' (준비 중)'}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </FormField>

      {backend === 'TIBERO' && (
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-[1fr_140px_1fr]">
            <FormField label="호스트" htmlFor="data-store-host" error={fieldErrors?.host?.message}>
              <Input id="data-store-host" className="font-mono" {...register('dataStore.host')} />
            </FormField>
            <FormField label="포트" htmlFor="data-store-port" error={fieldErrors?.port?.message}>
              <Input
                id="data-store-port"
                inputMode="numeric"
                className="font-mono"
                {...register('dataStore.port')}
              />
            </FormField>
            <FormField label="SID" htmlFor="data-store-sid" error={fieldErrors?.database?.message}>
              <Input id="data-store-sid" className="font-mono" {...register('dataStore.database')} />
            </FormField>
          </div>

          <FormField
            label="테이블스페이스"
            htmlFor="data-store-tablespace"
            error={fieldErrors?.tablespace?.message}
          >
            <Input
              id="data-store-tablespace"
              className="font-mono"
              {...register('dataStore.tablespace')}
            />
            <p className="mt-1.5 text-sm text-muted-foreground">비우면 USR 을 사용합니다.</p>
          </FormField>

          <FormField label="프로비저닝 방식" htmlFor="data-store-mode">
            <Select
              value={mode}
              onValueChange={(value) => {
                setValue('dataStore.mode', value as DataStoreProvisionMode, { shouldValidate: false });
                resetTest();
              }}
            >
              <SelectTrigger id="data-store-mode" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {(Object.keys(MODE_LABELS) as DataStoreProvisionMode[]).map((m) => (
                  <SelectItem key={m} value={m}>
                    {MODE_LABELS[m]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </FormField>

          {mode === 'DBA_AUTO' ? (
            <div className="space-y-4">
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <FormField
                  label="DBA 계정"
                  htmlFor="data-store-dba-username"
                  error={fieldErrors?.dbaUsername?.message}
                >
                  <Input
                    id="data-store-dba-username"
                    autoComplete="off"
                    {...register('dataStore.dbaUsername')}
                  />
                </FormField>
                <FormField
                  label="DBA 비밀번호"
                  htmlFor="data-store-dba-password"
                  error={fieldErrors?.dbaPassword?.message}
                >
                  <PasswordInput
                    id="data-store-dba-password"
                    autoComplete="new-password"
                    {...register('dataStore.dbaPassword')}
                  />
                </FormField>
              </div>
              <p className="text-sm text-muted-foreground">
                DBA 계정은 테넌트 전용 계정을 만드는 데에만 쓰이며 저장되지 않습니다.
              </p>
              <div className="space-y-2">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => void runConnectionTest()}
                  disabled={testMutation.isPending}
                >
                  연결 테스트
                </Button>
                {/* ok 인데 missingPrivileges 가 null 이면 "접속은 됐지만 권한을 확인하지 못함" — 성공처럼 보이면 생성 시
                    권한 부족을 놓치므로 경고로 보이고 사유를 덧붙인다. `== null` 은 필드가 빠진(undefined) 응답도 같이 잡는다. */}
                {testResult?.ok && (
                  <InlineBanner variant={testResult.missingPrivileges == null ? 'warning' : 'success'}>
                    <div className="space-y-1">
                      <p>연결되었습니다. 서버 버전: {testResult.serverVersion ?? '알 수 없음'}</p>
                      {testResult.missingPrivileges == null && (
                        <p>{testResult.message ?? '권한을 확인하지 못했습니다.'}</p>
                      )}
                    </div>
                  </InlineBanner>
                )}
                {testResult && !testResult.ok && (
                  <InlineBanner variant="caution">
                    <div className="space-y-1">
                      <p>{testResult.message ?? '연결할 수 없습니다.'}</p>
                      {testResult.missingPrivileges && testResult.missingPrivileges.length > 0 && (
                        <>
                          <p>부족한 권한</p>
                          <ul className="list-disc pl-5">
                            {testResult.missingPrivileges.map((p) => (
                              <li key={p}>{p}</li>
                            ))}
                          </ul>
                        </>
                      )}
                    </div>
                  </InlineBanner>
                )}
                {testError && <InlineBanner variant="caution">{testError}</InlineBanner>}
              </div>
            </div>
          ) : (
            <InlineBanner variant="info">
              생성하면 DBA 가 실행할 스크립트가 한 번만 표시됩니다. 실행 후 테넌트 상세에서 연결을 확인하세요.
            </InlineBanner>
          )}
        </div>
      )}
    </div>
  );
}
