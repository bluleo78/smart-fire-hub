import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { ArrowLeft } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { tenantsApi } from '@/api/tenants';
import { OwnerPicker } from '@/components/OwnerPicker';
import { PermissionDeniedBanner } from '@/components/PermissionDeniedBanner';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { FormField } from '@/components/ui/form-field';
import { Input } from '@/components/ui/input';
import { Separator } from '@/components/ui/separator';
import { useAuth } from '@/hooks/useAuth';
import { isForbidden } from '@/lib/http-errors';
import type { CreateTenantFormData } from '@/lib/validations/tenant';
import { createTenantSchema } from '@/lib/validations/tenant';
import type { ErrorResponse, PlatformUserResponse } from '@/types/platform';

/**
 * 테넌트 생성. 서버는 테넌트 행 + 초기 Owner + 기본 시드를 한 트랜잭션으로 만든다.
 *
 * 서버 400 메시지(`존재하지 않는 사용자입니다: {id}` / `이미 사용 중인 slug 입니다: {slug}`)는
 * **그대로** 표시한다 — 프런트가 문구를 다시 쓰면 두 판정이 갈라진다. Owner 를 검색으로 골라도
 * 이 처리를 남겨 두는 이유: 고른 뒤 제출 전에 그 사용자가 삭제될 수 있다.
 */
export default function TenantCreatePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { hasPermission } = useAuth();
  // `owner` 는 **표시 전용** 상태다(픽커가 이름·이메일을 그리는 데 필요). 검증에 쓰이는 값은
  // 폼 필드 `ownerUserId` 이고, 그 판정자는 zod 하나다.
  const [owner, setOwner] = useState<PlatformUserResponse | null>(null);
  const [serverFieldError, setServerFieldError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<CreateTenantFormData>({ resolver: zodResolver(createTenantSchema) });

  /**
   * 픽커의 선택/해제를 표시 상태와 폼 값에 **동시에** 반영한다.
   * 해제 시 폼 값을 비우지 않으면 낡은 id 로 POST 가 나가는 조용한 오기록이 된다.
   */
  const handleOwnerChange = (user: PlatformUserResponse | null) => {
    setOwner(user);
    if (user) {
      setValue('ownerUserId', user.id, { shouldValidate: true });
    } else {
      // 해제도 `setValue` 로 쓴다. `resetField` 는 등록된 필드에만 동작하고
      // (`react-hook-form` 구현이 `_fields[name]` 존재를 먼저 검사한다) `ownerUserId` 는
      // register 대상이 아니라 setValue 로만 쓰이므로 무동작이 된다.
      // shouldValidate 는 false 다 — 해제 직후가 아니라 제출 시점에 오류를 띄운다.
      setValue('ownerUserId', undefined as unknown as number, { shouldValidate: false });
    }
  };

  const createMutation = useMutation({
    mutationFn: (data: CreateTenantFormData) => tenantsApi.create(data).then((r) => r.data),
    onSuccess: (tenant) => {
      toast.success('테넌트가 생성되었습니다.');
      // 전역 staleTime(30_000)이 있어 무효화 없이 목록으로 돌아가면 방금 만든 테넌트가
      // 최대 30초간 안 보인다 — 운영자는 생성이 실패했다고 오판해 중복 생성을 시도할 수 있다.
      // TenantDetailPage 의 정지/활성화 무효화와 같은 대상(`platform-tenants`)을 쓴다.
      // `platform-tenant`(상세, id 별)는 무효화하지 않는다 — 방금 만든 id 는 캐시에
      // 존재한 적이 없는 새 쿼리 키라 무효화할 대상이 없다(정지/활성화는 이미 캐시된
      // 기존 상세를 갱신하는 경우라 다르다).
      void queryClient.invalidateQueries({ queryKey: ['platform-tenants'] });
      navigate(`/tenants/${tenant.id}`, { replace: true });
    },
    onError: (error) => {
      if (axios.isAxiosError(error) && error.response?.status === 400) {
        const message = (error.response.data as ErrorResponse | undefined)?.message;
        if (message) {
          setServerFieldError(message);
          return;
        }
      }
      // 라우트 게이트가 열린 뒤(폼을 보고 있는 동안) 권한이 회수되는 경합처럼, 게이트를
      // 통과했는데도 서버가 403 을 주는 경우를 대비한 심층방어(M-1). 일반 실패 토스트로
      // 뭉뚱그리면 운영자가 서버 장애로 오인해 재시도를 반복한다 — PermissionDeniedBanner 와
      // 같은 문구를 쓴다.
      if (isForbidden(error)) {
        toast.error('이 작업을 수행할 권한이 없습니다.');
        return;
      }
      toast.error('테넌트 생성에 실패했습니다.');
    },
  });

  // zod 가 이미 `ownerUserId` 를 포함해 전부 통과시킨 뒤에만 호출된다 — 여기서 다시 검사하지 않는다.
  const onSubmit = (data: CreateTenantFormData) => {
    setServerFieldError(null);
    createMutation.mutate(data);
  };

  // 라우트 자체가 게이트되지 않으면(M-1) URL 로 직접 열었을 때 폼 전체가 그려지고, 다 채워
  // 제출한 뒤에야 403 을 만난다 — TenantListPage/SettingsPage 와 같은 형태(제목 + 배너)로
  // 진입 시점에 막는다.
  if (!hasPermission('platform:tenant:create')) {
    return (
      <div className="space-y-6">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">테넌트 생성</h1>
        <PermissionDeniedBanner />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Button
          variant="ghost"
          size="icon"
          onClick={() => navigate('/tenants')}
          aria-label="목록으로 돌아가기"
          title="목록으로 돌아가기"
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">테넌트 생성</h1>
      </div>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-6" noValidate>
        <Card>
          <CardContent className="space-y-4 pt-6">
            <FormField label="이름" htmlFor="tenant-name" error={errors.name?.message}>
              <Input id="tenant-name" {...register('name')} />
              <p className="mt-1.5 text-sm text-muted-foreground">조직에 표시될 이름입니다.</p>
            </FormField>

            <Separator />

            <FormField label="slug" htmlFor="tenant-slug" error={errors.slug?.message}>
              <Input id="tenant-slug" className="font-mono" {...register('slug')} />
              <p className="mt-1.5 text-sm text-muted-foreground">소문자·숫자·하이픈만 사용합니다.</p>
              <p className="text-sm text-muted-foreground">생성 후에는 변경할 수 없습니다.</p>
            </FormField>

            <Separator />

            {/* 오류 문구의 출처는 zod 하나다 — 별도 ownerError state 는 두지 않는다. */}
            <OwnerPicker value={owner} onChange={handleOwnerChange} error={errors.ownerUserId?.message} />

            {serverFieldError && <p className="text-sm text-destructive">{serverFieldError}</p>}
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate('/tenants')}>
            취소
          </Button>
          <Button type="submit" disabled={createMutation.isPending}>
            테넌트 생성
          </Button>
        </div>
      </form>
    </div>
  );
}
