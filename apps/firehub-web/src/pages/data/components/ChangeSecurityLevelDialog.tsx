import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { toast } from 'sonner';

import { securityLevelsApi } from '../../../api/security-levels';
import { Button } from '../../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../../components/ui/dialog';
import { InlineBanner } from '../../../components/ui/inline-banner';
import { Label } from '../../../components/ui/label';
import { RadioGroup, RadioGroupItem } from '../../../components/ui/radio-group';
import { SecurityLevelBadge } from '../../../components/ui/SecurityLevelBadge';
import { Textarea } from '../../../components/ui/textarea';
import { useMyClearance, useSecurityLevels } from '../../../hooks/queries/useSecurityLevels';
import { handleApiError } from '../../../lib/api-error';
import type { SecurityLevelSummary } from '../../../types/security-level';

/** 하향 사유 최소 길이 — 백엔드 SecurityLevelService.MIN_DOWNGRADE_REASON 과 같다. */
const MIN_REASON = 10;

/**
 * 데이터셋 등급 변경(목업 s2, dataset:classify). 본인 자격보다 높은 등급은 비활성, 하향은 사유 필수, 허용 목록 필요 등급으로 올리면
 * 서버가 필요할 때 본인을 허용 목록에 넣는다(판단 사항 11 — 안내 배너로 알린다).
 * 서버 거부(CLASSIFY_ABOVE_CLEARANCE·DOWNGRADE_REASON_REQUIRED)는 한국어 message 를 싣고 오므로 handleApiError 로 그대로 보여 준다.
 */
export function ChangeSecurityLevelDialog({
  datasetId,
  current,
  open,
  onOpenChange,
}: {
  datasetId: number;
  current: SecurityLevelSummary;
  open: boolean;
  onOpenChange: (o: boolean) => void;
}) {
  const qc = useQueryClient();
  const { data: levels = [] } = useSecurityLevels();
  const { data: mine, isSuccess: clearanceLoaded } = useMyClearance();
  const [selected, setSelected] = useState<string>(String(current.id));
  const [reason, setReason] = useState('');
  // 라디오 라벨-입력 연결용 id 접두(09-form-patterns §J — 반복 행은 `${baseId}-${id}`).
  const baseId = useId();
  const target = levels.find((l) => String(l.id) === selected);
  const downgrade = !!target && target.rank < current.rank;
  const enteringAllowlist = !!target && target.allowlistRequired && !current.allowlistRequired;
  const valid = !!target && target.id !== current.id && (!downgrade || reason.trim().length >= MIN_REASON);

  // 닫을 때 선택·사유를 초기화한다 — 취소 후 다시 열었을 때 이전 입력이 남아 엉뚱한 등급이 제출되는 것을 막는다.
  const handleOpenChange = (o: boolean) => {
    if (!o) {
      setSelected(String(current.id));
      setReason('');
    }
    onOpenChange(o);
  };

  const change = useMutation({
    mutationFn: () =>
      securityLevelsApi.changeDatasetLevel(datasetId, {
        securityLevelId: target!.id,
        ...(downgrade ? { reason: reason.trim() } : {}),
      }),
    onSuccess: () => {
      toast.success('보안 등급을 변경했습니다');
      // ['datasets'] 접두 무효화 — 상세·목록·허용 목록(서버가 본인을 시드했을 수 있음)을 함께 갱신한다.
      void qc.invalidateQueries({ queryKey: ['datasets'] });
      handleOpenChange(false);
    },
    onError: (e) => handleApiError(e, '보안 등급 변경에 실패했습니다.'),
  });

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>보안 등급 변경</DialogTitle>
          <DialogDescription>본인 열람 등급 이하의 등급으로만 지정할 수 있습니다.</DialogDescription>
        </DialogHeader>
        <RadioGroup value={selected} onValueChange={setSelected} aria-label="보안 등급" className="space-y-2">
          {levels.map((l) => {
            // 자격 로딩 중엔 일단 비활성만 하고 안내 문구는 띄우지 않는다(로딩 깜빡임에 "선택 불가"가 모든 줄에 뜨지 않도록).
            // 자격이 정말 없으면(rank null) 어떤 등급도 지정할 수 없다 — 서버도 거부한다.
            const aboveMine = !clearanceLoaded || mine?.rank == null || l.rank > mine.rank;
            const isCurrent = l.id === current.id;
            return (
              <Label
                key={l.id}
                htmlFor={`${baseId}-${l.id}`}
                className={`flex items-center gap-2 font-normal ${aboveMine ? 'opacity-60' : ''}`}
              >
                <RadioGroupItem
                  id={`${baseId}-${l.id}`}
                  value={String(l.id)}
                  disabled={aboveMine}
                  aria-label={isCurrent ? `${l.name} (현재)` : l.name}
                />
                <SecurityLevelBadge level={l} />
                {isCurrent && <span className="text-xs text-muted-foreground">(현재)</span>}
                {clearanceLoaded && aboveMine && (
                  <span className="text-xs text-muted-foreground">본인 열람 등급보다 높아 선택 불가</span>
                )}
              </Label>
            );
          })}
        </RadioGroup>
        {downgrade && (
          <div className="space-y-1.5">
            <Label htmlFor="downgrade-reason">하향 사유 *</Label>
            <Textarea
              id="downgrade-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={`${MIN_REASON}자 이상`}
            />
            <p className="text-xs text-muted-foreground">
              등급을 낮추면 더 많은 사람이 볼 수 있게 됩니다. 사유는 감사 로그에 남습니다.
            </p>
          </div>
        )}
        {enteringAllowlist && (
          <InlineBanner variant="info">
            허용 목록이 필요한 등급입니다. 변경 후에도 본인이 접근할 수 있도록, 필요하면 본인을 허용 목록에 자동으로 추가합니다.
          </InlineBanner>
        )}
        <DialogFooter className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="outline" onClick={() => handleOpenChange(false)}>
            취소
          </Button>
          <Button disabled={!valid || change.isPending} onClick={() => change.mutate()}>
            변경
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
