import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { settingsApi } from '@/api/settings';
import { PermissionDeniedBanner } from '@/components/PermissionDeniedBanner';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAuth } from '@/hooks/useAuth';
import { formatDateTimeMinute } from '@/lib/formatters';
import { isForbidden } from '@/lib/http-errors';
// `validateSettingValue` 는 여기서 import 하지 않는다 — 그것을 쓰는 `validateForm` 은
// `@/lib/settings-form` 으로 옮겨졌다(react-refresh/only-export-components).
import { ALL_SETTING_KEYS, SETTING_CATALOG, SETTINGS_TABS } from '@/lib/settings-catalog';
import type { SettingResponse } from '@/types/platform';

import { SettingField } from './settings/SettingField';

/**
 * 서버 응답을 화면 폼 상태로 만든다.
 *
 * - 비밀 키는 **항상 빈 문자열**로 시작한다. 서버가 준 마스크(`****` / `****last4`)를 입력창에
 *   넣으면 (a) 사용자가 지우고 다시 써야 하는 어색함, (b) 진짜 8자 비밀번호가 `****` 로 시작할 때
 *   조용히 드롭되는 잔여 위험을 UI 가 떠안는다. 클라이언트가 마스크 문자열을 **만들 일이 없다**.
 * - 응답에 없는 키(`ai.session_max_tokens`)는 내장 기본값으로 채운다.
 */
function buildForm(settings: SettingResponse[]): Record<string, string> {
  const byKey = Object.fromEntries(settings.map((s) => [s.key, s]));
  const form: Record<string, string> = {};
  for (const key of ALL_SETTING_KEYS) {
    const spec = SETTING_CATALOG[key];
    if (spec.secret) {
      form[key] = '';
      continue;
    }
    form[key] = byKey[key]?.value ?? spec.builtinDefault ?? '';
  }
  return form;
}

export default function SettingsPage() {
  const { hasPermission } = useAuth();
  const canWrite = hasPermission('platform:settings:write');

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['platform-settings'],
    queryFn: () => settingsApi.getAll().then((r) => r.data),
    retry: false,
  });

  const [form, setForm] = useState<Record<string, string> | null>(null);
  const [original, setOriginal] = useState<Record<string, string> | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  /**
   * 폼을 어느 응답 객체로 세웠는지 기억한다.
   *
   * <b>`form === null` 을 재초기화 신호로 쓰지 않는 이유</b>: 저장 후 `invalidateQueries` 는
   * `data` 를 **동기적으로** 바꾸지 않는다. 그 사이 `form` 을 null 로 되돌리면 아직 낡은
   * `data` 로 폼이 다시 세워져, 방금 저장한 값이 한 틱 동안 되돌아간 것처럼 보인다(그리고
   * `cleared` 를 이미 비웠으므로 방금 지운 비밀 키가 "현재 설정됨"으로 다시 뜬다).
   * 그래서 재초기화 신호를 <b>응답 객체의 정체성</b>에 건다 — 새 응답이 도착한 순간에만 세운다.
   */
  const [seededFrom, setSeededFrom] = useState<SettingResponse[] | null>(null);

  // 렌더 중 setState — 파생 상태를 즉시 반영하는 React 권장 패턴이다.
  if (data && data !== seededFrom) {
    setSeededFrom(data);
    const next = buildForm(data);
    setForm(next);
    setOriginal(next);
    setErrors({});
    // Task 10 이 여기에 `setCleared(new Set());` 한 줄을 더한다.
  }

  const byKey = useMemo(
    () => Object.fromEntries((data ?? []).map((s) => [s.key, s])),
    [data],
  );

  const lastUpdatedOf = (keys: string[]): string | null => {
    const stamps = keys
      .map((k) => byKey[k]?.updatedAt)
      .filter((v): v is string => Boolean(v))
      .sort();
    return stamps.length > 0 ? stamps[stamps.length - 1] : null;
  };

  const setValue = (key: string, value: string) => {
    setForm((prev) => ({ ...(prev ?? {}), [key]: value }));
    setErrors((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  if (isError && isForbidden(error)) {
    return (
      <div className="space-y-6">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">플랫폼 설정</h1>
        <PermissionDeniedBanner />
      </div>
    );
  }

  if (isLoading || !form || !original) {
    if (isError) {
      return (
        <div className="space-y-4">
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">플랫폼 설정</h1>
          <p className="text-sm text-destructive">설정을 불러오는데 실패했습니다.</p>
          <Button variant="outline" onClick={() => void refetch()}>
            다시 시도
          </Button>
        </div>
      );
    }
    return (
      <div className="space-y-6">
        <Skeleton className="h-9 w-48" />
        <Skeleton className="h-10 w-96" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">플랫폼 설정</h1>

      {/* info 이지 warning 이 아니다 — 이 화면의 정상 동작이지 이상 징후가 아니다(D-4). */}
      <InlineBanner variant="info">
        여기서 저장한 값은 전 테넌트의 기본값입니다. 일부 항목은 각 워크스페이스가 자기 값으로
        재정의할 수 있습니다.
      </InlineBanner>

      {!canWrite && (
        <InlineBanner variant="info">
          조회 권한만 있습니다. 값을 변경하려면 platform:settings:write 권한이 필요합니다.
        </InlineBanner>
      )}

      <Tabs defaultValue="ai">
        <TabsList className="overflow-x-auto overflow-y-hidden flex-nowrap">
          {SETTINGS_TABS.map((tab) => (
            <TabsTrigger key={tab.id} value={tab.id}>
              {tab.label}
            </TabsTrigger>
          ))}
        </TabsList>

        {SETTINGS_TABS.map((tab) => {
          const updatedAt = lastUpdatedOf(tab.keys);
          return (
            <TabsContent key={tab.id} value={tab.id}>
              <Card>
                <CardContent className="space-y-6 pt-6">
                  {tab.keys.map((key, index) => (
                    <div key={key} className="space-y-6">
                      {index > 0 && <Separator />}
                      <SettingField
                        spec={SETTING_CATALOG[key]}
                        value={form[key] ?? ''}
                        onChange={(value) => setValue(key, value)}
                        disabled={!canWrite}
                        error={errors[key]}
                        description={byKey[key]?.description ?? null}
                        usingBuiltinDefault={
                          byKey[key] === undefined &&
                          SETTING_CATALOG[key].builtinDefault !== undefined
                        }
                      />
                    </div>
                  ))}
                  {updatedAt && (
                    <p className="text-sm text-muted-foreground">
                      마지막 변경: {formatDateTimeMinute(updatedAt)}
                    </p>
                  )}
                </CardContent>
              </Card>
            </TabsContent>
          );
        })}
      </Tabs>

      {/* 저장 버튼과 확인 다이얼로그는 Task 10 이 붙인다. */}
    </div>
  );
}
