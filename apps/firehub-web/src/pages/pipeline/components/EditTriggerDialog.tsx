import { useQueryClient } from '@tanstack/react-query';
import { Loader2 } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import { pipelinesApi } from '@/api/pipelines';
import { EditConflictDialog } from '@/components/EditConflictDialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { useAllPipelines, useUpdateTrigger } from '@/hooks/queries/usePipelines';
import { handleApiError, isEditConflict, isNotFoundError } from '@/lib/api-error';
import { cronInputError } from '@/lib/cron-frequency';
import { iGa } from '@/lib/utils';
import type { TriggerResponse } from '@/types/pipeline';

import ApiTriggerForm from './ApiTriggerForm';
import DatasetChangeTriggerForm from './DatasetChangeTriggerForm';
import PipelineChainForm from './PipelineChainForm';
import ScheduleTriggerForm from './ScheduleTriggerForm';
import WebhookTriggerForm from './WebhookTriggerForm';

const TRIGGER_TYPE_LABELS: Record<string, string> = {
  SCHEDULE: '스케줄',
  API: 'API',
  PIPELINE_CHAIN: '파이프라인 연쇄',
  WEBHOOK: '웹훅',
  DATASET_CHANGE: '데이터셋 변경',
};

interface EditTriggerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  pipelineId: number;
  trigger: TriggerResponse;
  /** 창이 닫힌 뒤 포커스를 돌려줄 곳을 호출처가 정한다(WD-407) — ⋯ 메뉴 항목으로 열면 돌려받을 트리거 요소가 없다. */
  onCloseAutoFocus?: (event: Event) => void;
}

export function EditTriggerDialog({ open, onOpenChange, pipelineId, trigger, onCloseAutoFocus }: EditTriggerDialogProps) {
  const [name, setName] = useState(trigger.name);
  const [description, setDescription] = useState(trigger.description ?? '');
  const [config, setConfig] = useState<Record<string, unknown>>(trigger.config);
  const [validationErrors, setValidationErrors] = useState<Record<string, string>>({});
  // 편집 기준 버전(WD-576) — 이 창의 입력값을 불러온 트리거의 updatedAt. 입력값과 함께만 바뀐다(창을 열 때·「다시 불러오기」).
  // 저장 때 expectedUpdatedAt 으로 보내, 그 사이 다른 사람이 먼저 저장했으면 서버가 409 EDIT_CONFLICT 로 거부한다 —
  // 예전엔 옛 화면에서 이름만 바꿔 저장해도 그 화면이 들고 있던 옛 cron 이 함께 저장돼 남의 변경이 경고 없이 사라졌다.
  // 없으면(옛 서버·옛 행) null — 비교 없이 저장된다.
  const [baseUpdatedAt, setBaseUpdatedAt] = useState<string | null>(trigger.updatedAt ?? null);
  // 마지막 저장이 편집 충돌로 거부됐는가 — 참이면 EditConflictDialog 를 띄운다(WD-576)
  const [editConflict, setEditConflict] = useState(false);
  const [isReloading, setIsReloading] = useState(false);

  const queryClient = useQueryClient();
  const updateTrigger = useUpdateTrigger(pipelineId);
  // 선행 파이프라인 존재 확인용(WD-218) — PipelineChainForm 과 같은 쿼리라 react-query 가 한 번만 받는다. 연쇄가 아니면 쓰지 않는다.
  const { data: allPipelines, isError: pipelinesError } = useAllPipelines();

  /**
   * 서버에서 읽은 트리거 값으로 입력값과 편집 기준 버전을 함께 바꾼다 — 둘이 어긋나면 낡은 입력이 새 기준으로 통과한다(WD-576).
   * 다른 트리거로 바뀔 때의 초기화와 「다시 불러오기」가 같은 함수를 써서, 필드가 늘어도 한 곳만 고치면 된다.
   */
  function applyTrigger(latest: TriggerResponse) {
    setName(latest.name);
    setDescription(latest.description ?? '');
    setConfig(latest.config);
    setValidationErrors({});
    setBaseUpdatedAt(latest.updatedAt ?? null);
  }

  // Reset when trigger changes (render-time state adjustment)
  const [prevTrigger, setPrevTrigger] = useState(trigger);
  if (prevTrigger !== trigger) {
    setPrevTrigger(trigger);
    applyTrigger(trigger);
    setEditConflict(false);
  }

  // 이름 입력값이 바뀌면 이전 제출 시점의 stale한 name 검증 오류를 지운다 (#638).
  const handleNameChange = (value: string) => {
    setName(value);
    if (validationErrors.name) {
      setValidationErrors((prev) => {
        const next = { ...prev };
        delete next.name;
        return next;
      });
    }
  };

  // config(Cron, 선행 파이프라인, 데이터셋 등) 필드가 바뀔 때마다 이전 제출 시점의
  // stale한 config 관련 검증 오류를 전부 지운다. validate()는 제출 시에만 호출되므로
  // 값이 바뀌어도 오류가 그대로 남아있던 문제(#638)를 막는다.
  const handleConfigChange = (newConfig: Record<string, unknown>) => {
    setConfig(newConfig);
    setValidationErrors((prev) => {
      const nameError = prev.name;
      const hasConfigError = Object.keys(prev).some((key) => key !== 'name');
      if (!hasConfigError) return prev;
      return nameError ? { name: nameError } : {};
    });
  };

  const validate = (): boolean => {
    const errors: Record<string, string> = {};
    if (!name.trim()) {
      errors.name = '트리거 이름을 입력하세요';
    }
    if (trigger.triggerType === 'SCHEDULE') {
      const c = config as { cron: string };
      // AddTriggerDialog와 동일하게 형식까지 검증한다 (#641) —
      // 빈 값만 체크하면 문법적으로 잘못된 Cron이 그대로 저장되어 스케줄러가 실행할 수 없다.
      if (!c.cron?.trim()) {
        errors.cron = 'Cron 표현식을 입력하세요';
      } else {
        // 형식·1분보다 잦은 주기(WD-366)를 공용 검사로 — 서버도 같은 규칙으로 거부한다
        const cronError = cronInputError(c.cron);
        if (cronError) errors.cron = cronError;
      }
    }
    if (trigger.triggerType === 'PIPELINE_CHAIN') {
      const c = config as { upstreamPipelineId: number | null };
      if (!c.upstreamPipelineId) {
        errors.upstreamPipelineId = '선행 파이프라인을 선택하세요';
      } else if (
        !pipelinesError &&
        allPipelines !== undefined &&
        !allPipelines.some((p) => p.id === c.upstreamPipelineId)
      ) {
        // 지워진 선행 id 를 그대로 저장하면 영원히 발화하지 않는다(WD-218). 목록을 못 받았으면 막지 않고 서버 검증(400)에 맡긴다.
        errors.upstreamPipelineId = '선행 파이프라인이 삭제되었습니다. 다른 파이프라인을 고르세요';
      }
    }
    if (trigger.triggerType === 'DATASET_CHANGE') {
      const c = config as { datasetIds: number[]; pollingIntervalSeconds: number; debounceSeconds: number };
      if (!c.datasetIds?.length) errors.datasetIds = '감시 대상 데이터셋을 선택하세요';
      // 폴링 주기 범위 검증: 최솟값 30초, 최댓값 3600초
      if (c.pollingIntervalSeconds < 30 || c.pollingIntervalSeconds > 3600)
        errors.pollingIntervalSeconds = '폴링 주기는 30~3600초 사이여야 합니다';
      // 디바운스 시간 범위 검증: 0초 이상 3600초 이하
      if (c.debounceSeconds < 0 || c.debounceSeconds > 3600)
        errors.debounceSeconds = '디바운스 시간은 0~3600초 사이여야 합니다';
    }
    setValidationErrors(errors);
    return Object.keys(errors).length === 0;
  };

  /**
   * 저장한다. force 면 편집 기준 버전을 빼고 보낸다 — 충돌 창의 「내 변경으로 덮어쓰기」(WD-576).
   */
  const persist = async (force: boolean) => {
    if (!validate()) {
      // 덮어쓰기 중 입력 검증에 걸리면 충돌 창을 닫는다 — 모달이 남아 있으면 편집 창의 오류 문구를 볼 수도 고칠 수도 없다(WD-576).
      setEditConflict(false);
      return;
    }
    try {
      await updateTrigger.mutateAsync({
        triggerId: trigger.id,
        data: {
          name: name.trim(),
          // 빈 설명도 빈 문자열로 보내야 서버가 "지움"으로 처리한다(WD-113) — undefined 면 키가 빠져 "바꾸지 않음"이 된다.
          description: description.trim(),
          config,
          // 편집 기준 버전(WD-576) — 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다
          ...(force || !baseUpdatedAt ? {} : { expectedUpdatedAt: baseUpdatedAt }),
        },
      });
      setEditConflict(false);
      toast.success(`트리거 "${name}"${iGa(name)} 수정되었습니다.`);
      onOpenChange(false);
    } catch (error) {
      // 편집 충돌은 토스트 대신 안내 창으로 — 사용자가 다시 불러올지 덮어쓸지 고른다(WD-576, 파이프라인 WD-353 과 같은 흐름)
      if (isEditConflict(error)) {
        setEditConflict(true);
        return;
      }
      handleApiError(error, '트리거 수정에 실패했습니다.');
    }
  };

  const handleSubmit = () => persist(false);

  /**
   * 충돌 창의 「다시 불러오기」(WD-576) — 이 트리거 한 건을 새로 받아 최신 값으로 입력값·기준 버전을 바꾼다(내 변경은 버린다).
   * 창은 닫지 않는다 — 최신 값을 보고 이어서 고칠 수 있게. 뒤에 보이는 트리거 목록도 최신 값으로 다시 받게 한다.
   * 그 사이 트리거가 지워졌으면(404) 안내하고 편집 창을 닫는다.
   */
  const handleReloadLatest = async () => {
    setIsReloading(true);
    try {
      const { data: latest } = await pipelinesApi.getTrigger(pipelineId, trigger.id);
      setEditConflict(false);
      applyTrigger(latest);
      void queryClient.invalidateQueries({ queryKey: ['triggers', pipelineId] });
      toast.info('최신 내용을 불러왔습니다.');
    } catch (error) {
      if (isNotFoundError(error)) {
        setEditConflict(false);
        toast.error('트리거를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.');
        void queryClient.invalidateQueries({ queryKey: ['triggers', pipelineId] });
        onOpenChange(false);
        return;
      }
      handleApiError(error, '최신 내용을 불러오지 못했습니다.');
    } finally {
      setIsReloading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto overscroll-contain" onCloseAutoFocus={onCloseAutoFocus}>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            트리거 편집
            <Badge variant="outline">{TRIGGER_TYPE_LABELS[trigger.triggerType]}</Badge>
          </DialogTitle>
          <DialogDescription className="sr-only">파이프라인 트리거 설정을 수정합니다.</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="edit-trigger-name">
              이름 <span className="text-destructive">*</span>
            </Label>
            <Input
              id="edit-trigger-name"
              value={name}
              onChange={(e) => handleNameChange(e.target.value)}
              placeholder="트리거 이름"
              className={validationErrors.name ? 'border-destructive' : undefined}
            />
            {validationErrors.name && (
              <p className="text-sm text-destructive">{validationErrors.name}</p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="edit-trigger-desc">설명</Label>
            <Textarea
              id="edit-trigger-desc"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="트리거 설명 (선택)"
              rows={2}
            />
          </div>

          {trigger.triggerType === 'SCHEDULE' && (
            <ScheduleTriggerForm
              config={config as { cron: string; timezone: string; concurrencyPolicy: 'SKIP' | 'ALLOW' }}
              onChange={handleConfigChange}
              errors={validationErrors}
            />
          )}
          {trigger.triggerType === 'API' && (
            <ApiTriggerForm
              config={config as { allowedIps: string[] }}
              onChange={handleConfigChange}
              isEditMode
            />
          )}
          {trigger.triggerType === 'PIPELINE_CHAIN' && (
            <PipelineChainForm
              pipelineId={pipelineId}
              config={config as { upstreamPipelineId: number | null; condition: 'SUCCESS' | 'FAILURE' | 'ANY' }}
              onChange={handleConfigChange}
              errors={validationErrors}
            />
          )}
          {trigger.triggerType === 'WEBHOOK' && (
            <WebhookTriggerForm
              config={config as { webhookId?: string; secret?: string; hasSecret?: boolean }}
              onChange={handleConfigChange}
              isEditMode
            />
          )}
          {trigger.triggerType === 'DATASET_CHANGE' && (
            <DatasetChangeTriggerForm
              config={config as { datasetIds: number[]; pollingIntervalSeconds: number; debounceSeconds: number }}
              onChange={handleConfigChange}
              errors={validationErrors}
            />
          )}

          <Button
            type="button"
            className="w-full"
            onClick={handleSubmit}
            disabled={updateTrigger.isPending || isReloading}
          >
            {updateTrigger.isPending ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" />
                저장 중...
              </>
            ) : (
              '저장'
            )}
          </Button>
        </div>
      </DialogContent>

      {/* 편집 충돌 안내 창(WD-576) — 저장이 409 EDIT_CONFLICT 로 거부되면 편집 창 위에 뜬다 */}
      <EditConflictDialog
        open={editConflict}
        onOpenChange={(next) => {
          if (!next) setEditConflict(false);
        }}
        resourceLabel="트리거"
        onReload={handleReloadLatest}
        onOverwrite={() => persist(true)}
        pending={updateTrigger.isPending || isReloading}
      />
    </Dialog>
  );
}
