import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { useEffect, useId, useMemo, useState } from 'react';
import { toast } from 'sonner';

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
import { Button } from '../../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../../components/ui/card';
import { InlineBanner } from '../../../components/ui/inline-banner';
import { Label } from '../../../components/ui/label';
import { RadioGroup, RadioGroupItem } from '../../../components/ui/radio-group';
import { SecurityLevelBadge } from '../../../components/ui/SecurityLevelBadge';
import { useRoleClearance, useSecurityLevels } from '../../../hooks/queries/useSecurityLevels';
import { extractApiError, handleApiError, isNotFoundError } from '../../../lib/api-error';
import { cumulativeDescription } from '../../../lib/security-level';

/**
 * 역할의 최대 열람 등급(목업 s3) — 권한 체크박스와 성격이 달라 별도 Card·별도 저장.
 * 선택이 바뀌면 서버 미리보기로 잠김 방지 두 가지를 판단한다:
 * ① 저장 후 최상위 열람 역할이 0개가 되면 저장 비활성+배너, ② 본인 자격이 내려가면 확인 다이얼로그.
 * 시스템 ADMIN(fixed)은 편집 불가 — 최상위 고정 표시만 한다.
 * role:write 가 없으면(canEdit=false) 현재 등급만 읽기 전용으로 보인다 — 미리보기·저장 모두 서버가 role:write 로 막는다(WD-121).
 * 범위 밖 역할(scopeLockReason — 자기에게 없는 권한이 붙은 역할)은 권한 카드처럼 라디오·저장을 잠그고 이유를 보인다(WD-444).
 */
export function RoleClearanceCard({
  roleId,
  canEdit,
  scopeLockReason,
  onDirtyChange,
  onRoleNotFound,
}: {
  roleId: number;
  /** 등급을 바꿀 수 있는가(role:write). false 면 라디오를 잠그고 저장 버튼을 숨긴다. */
  canEdit: boolean;
  /** 이 역할의 등급을 바꿀 수 없는 이유(WD-444) — 있으면 라디오를 잠그고 저장 버튼을 숨긴 채 이 문구를 보인다. 서버도 같은 이유로 403 을 돌려준다. */
  scopeLockReason?: string;
  /** 미저장 등급 선택 여부가 바뀔 때 알린다 — 역할 상세의 이탈 경고가 이 카드의 변경도 알 수 있게(WD-118). */
  onDirtyChange?: (dirty: boolean) => void;
  /**
   * 역할이 그새 삭제돼 미리보기·저장이 404 일 때 부르는 콜백(WD-287) — 부모(역할 상세)가 안내 후 목록으로 돌아간다.
   * 주지 않으면 다른 실패처럼 이 카드 안에서 문구만 보인다.
   */
  onRoleNotFound?: (error: unknown) => void;
}) {
  const qc = useQueryClient();
  const { data: clearance } = useRoleClearance(roleId);
  const { data: rawLevels = [] } = useSecurityLevels();
  // 누적 설명은 rank 오름차순을 전제로 하므로 응답 순서에 기대지 않고 정렬한다.
  const levels = useMemo(() => [...rawLevels].sort((a, b) => a.rank - b.rank), [rawLevels]);
  // 사용자가 고른 미저장 값. null 이면 서버의 현재 자격을 그대로 보여 준다(효과 없이 파생 — 저장 후 자연 동기화).
  const [draft, setDraft] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  // 라디오 라벨-입력 연결용 id 접두(09-form-patterns §J — 반복 행은 `${baseId}-${id}`).
  const baseId = useId();
  // 등급을 실제로 바꿀 수 있는가 — role:write 가 있어도 범위 밖 역할이면 잠근다(WD-444).
  const editable = canEdit && !scopeLockReason;

  const current = clearance ? String(clearance.securityLevelId) : '';
  const selected = draft ?? current;
  const changed = !!clearance && !clearance.fixed && selected !== current;

  // 등급을 골라 두고 저장하지 않은 상태를 부모에 알린다 — 카드가 자기 draft 를 따로 들고 있어 부모 가드가 모르면 이탈 시 조용히 버려진다(WD-118).
  useEffect(() => {
    onDirtyChange?.(changed);
  }, [changed, onDirtyChange]);

  // 미리보기는 "어느 서버 상태에서 어느 등급으로 바꾸는 결과인지"를 쿼리 키로 묶는다 — 선택을 빠르게 바꾸거나
  // 저장 뒤 같은 등급을 다시 골라도 이전 결과(예: 손실 0)로 본인 하락 확인을 건너뛰지 않게 하기 위함.
  // 캐시를 남기지 않고(gcTime 0) 저장 버튼도 재조회 중에는 막아 항상 새 결과로만 판단한다.
  const preview = useQuery({
    queryKey: ['roles', roleId, 'clearance', 'preview', current, selected],
    queryFn: () => securityLevelsApi.previewRoleClearance(roleId, Number(selected)).then((r) => r.data),
    enabled: changed,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });

  // 미리보기가 404 면 역할이 삭제된 것이다 — 저장은 미리보기 성공 전엔 막혀 있어, 등급을 고르는 순간이 삭제를 처음 만나는 지점이다(WD-287).
  // 죽은 화면에 오류 문구만 남기지 않고 부모에 알려 목록으로 돌아가게 한다.
  useEffect(() => {
    if (onRoleNotFound && isNotFoundError(preview.error)) onRoleNotFound(preview.error);
  }, [preview.error, onRoleNotFound]);

  const save = useMutation({
    mutationFn: () => securityLevelsApi.setRoleClearance(roleId, Number(selected)),
    onSuccess: async () => {
      toast.success('열람 등급을 저장했습니다');
      // 등급별 역할 수(설정 화면)·본인 자격이 바뀔 수 있다.
      void qc.invalidateQueries({ queryKey: ['security-levels'] });
      // 본인 자격이 오르거나 내려 볼 수 있는 데이터셋이 달라질 수 있으므로 목록 캐시를 다시 받는다.
      void qc.invalidateQueries({ queryKey: ['datasets'] });
      // 서버 자격 재조회가 끝난 뒤에 draft 를 비운다 — 먼저 비우면 라디오가 잠깐 옛 값으로 돌아간다.
      // exact: 같은 접두사의 미리보기 쿼리까지 다시 부르지 않게 한다. onSuccess 가 끝날 때까지 isPending 이 유지돼 저장도 막힌다.
      await qc.invalidateQueries({
        queryKey: ['roles', roleId, 'clearance'],
        exact: true,
      });
      setDraft(null);
    },
    onError: (e) => {
      // 그새 삭제된 역할이면 부모가 안내 후 목록으로 돌아간다(WD-287).
      if (onRoleNotFound && isNotFoundError(e)) {
        onRoleNotFound(e);
        return;
      }
      handleApiError(e, '열람 등급 저장에 실패했습니다.');
    },
  });

  if (!clearance || levels.length === 0) return null;
  const top = levels[levels.length - 1];
  // 미리보기는 현재 (서버값, 선택) 키의 결과이고 재조회 중이 아닐 때만 판단에 쓴다.
  const previewData = changed && preview.isSuccess && !preview.isFetching ? preview.data : undefined;
  // 4xx 는 서버가 이유를 알려 주므로(예: 권한 없음) 그 메시지를, 네트워크·5xx 는 재시도 안내를 보여 준다.
  const previewErrorMessage = previewErrorText(preview.error);
  const blocked = previewData?.topLevelRoleCountAfter === 0;
  const lostCount = previewData?.callerLostDatasetCount ?? 0;

  return (
    <Card data-testid="role-clearance-card">
      <CardHeader>
        <CardTitle>데이터 열람 등급</CardTitle>
        <CardDescription>이 역할이 볼 수 있는 가장 높은 보안 등급</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {clearance.fixed ? (
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <SecurityLevelBadge level={top} />
              <span className="text-sm text-muted-foreground">(최상위)</span>
            </div>
            <p className="text-sm text-muted-foreground">
              시스템 역할은 최상위 등급으로 고정됩니다. 허용 목록 우회 여부는 「데이터 보안」 설정을 따릅니다.
            </p>
          </div>
        ) : (
          <>
            <RadioGroup
              value={selected}
              onValueChange={setDraft}
              disabled={!editable}
              aria-label="데이터 열람 등급"
              aria-describedby={canEdit && scopeLockReason ? `${baseId}-scope-lock` : undefined}
              className="gap-3"
            >
              {levels.map((l, i) => (
                <Label key={l.id} htmlFor={`${baseId}-${l.id}`} className="flex items-start gap-2 font-normal">
                  <RadioGroupItem id={`${baseId}-${l.id}`} value={String(l.id)} aria-label={l.name} className="mt-0.5" />
                  <span className="space-y-1">
                    <SecurityLevelBadge level={l} />
                    <span className="block text-sm text-muted-foreground">{cumulativeDescription(levels, i)}</span>
                  </span>
                </Label>
              ))}
            </RadioGroup>
            <p className="text-sm text-muted-foreground">
              {`이 역할 사용자 ${clearance.userCount}명. 여러 역할이 있으면 가장 높은 등급이 적용됩니다.`}
            </p>
            {canEdit && scopeLockReason && (
              <p id={`${baseId}-scope-lock`} className="text-[13px] text-muted-foreground">
                {scopeLockReason}
              </p>
            )}
            {blocked && (
              <InlineBanner variant="warning">최상위 등급을 열람할 수 있는 역할이 최소 1개 필요합니다.</InlineBanner>
            )}
            {changed && preview.isError && <p className="text-sm text-destructive">{previewErrorMessage}</p>}
            {editable && (
              <div className="flex justify-end">
                <Button
                  disabled={!changed || !previewData || blocked || save.isPending}
                  onClick={() => (lostCount > 0 ? setConfirmOpen(true) : save.mutate())}
                >
                  {save.isPending ? '저장 중...' : '저장'}
                </Button>
              </div>
            )}
          </>
        )}
      </CardContent>
      {/* 잠김 방지 ② — 본인 자격이 내려가 볼 수 있던 데이터셋을 잃는 경우에만 확인한다 */}
      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent size="sm">
          <AlertDialogHeader>
            <AlertDialogTitle>열람 등급을 낮출까요?</AlertDialogTitle>
            <AlertDialogDescription>{`저장 후 본인은 데이터셋 ${lostCount}개를 볼 수 없게 됩니다.`}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                setConfirmOpen(false);
                save.mutate();
              }}
            >
              낮추기
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

/** 미리보기 실패 문구 — 4xx 는 서버 메시지(없으면 일반 문구), 네트워크 오류·5xx 는 재시도 안내. */
function previewErrorText(error: unknown): string {
  const retry = '변경 영향을 확인하지 못했습니다. 잠시 후 다시 선택해 주세요.';
  if (!error) return retry;
  const status = axios.isAxiosError(error) ? error.response?.status : undefined;
  if (status !== undefined && status >= 400 && status < 500) {
    return extractApiError(error, '변경 영향을 확인하지 못했습니다.');
  }
  return retry;
}
