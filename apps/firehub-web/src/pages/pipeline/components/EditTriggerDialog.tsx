import axios from 'axios';
import { Loader2 } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { useAllPipelines, useUpdateTrigger } from '@/hooks/queries/usePipelines';
import { cronInputError } from '@/lib/cron-frequency';
import { iGa } from '@/lib/utils';
import type { ErrorResponse } from '@/types/auth';
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

  const updateTrigger = useUpdateTrigger(pipelineId);
  // 선행 파이프라인 존재 확인용(WD-218) — PipelineChainForm 과 같은 쿼리라 react-query 가 한 번만 받는다. 연쇄가 아니면 쓰지 않는다.
  const { data: allPipelines, isError: pipelinesError } = useAllPipelines();

  // Reset when trigger changes (render-time state adjustment)
  const [prevTrigger, setPrevTrigger] = useState(trigger);
  if (prevTrigger !== trigger) {
    setPrevTrigger(trigger);
    setName(trigger.name);
    setDescription(trigger.description ?? '');
    setConfig(trigger.config);
    setValidationErrors({});
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

  const handleSubmit = async () => {
    if (!validate()) return;
    try {
      await updateTrigger.mutateAsync({
        triggerId: trigger.id,
        data: {
          name: name.trim(),
          // 빈 설명도 빈 문자열로 보내야 서버가 "지움"으로 처리한다(WD-113) — undefined 면 키가 빠져 "바꾸지 않음"이 된다.
          description: description.trim(),
          config,
        },
      });
      toast.success(`트리거 "${name}"${iGa(name)} 수정되었습니다.`);
      onOpenChange(false);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '트리거 수정에 실패했습니다.');
      } else {
        toast.error('트리거 수정에 실패했습니다.');
      }
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
            disabled={updateTrigger.isPending}
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
    </Dialog>
  );
}
