import { useState, useEffect } from 'react';
import { Loader2 } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { toast } from 'sonner';
import axios from 'axios';
import type { ErrorResponse } from '@/types/auth';
import type { TriggerResponse } from '@/types/pipeline';
import { useUpdateTrigger } from '@/hooks/queries/usePipelines';
import ScheduleTriggerForm from './ScheduleTriggerForm';
import ApiTriggerForm from './ApiTriggerForm';
import PipelineChainForm from './PipelineChainForm';
import WebhookTriggerForm from './WebhookTriggerForm';
import DatasetChangeTriggerForm from './DatasetChangeTriggerForm';

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
}

export function EditTriggerDialog({ open, onOpenChange, pipelineId, trigger }: EditTriggerDialogProps) {
  const [name, setName] = useState(trigger.name);
  const [description, setDescription] = useState(trigger.description ?? '');
  const [config, setConfig] = useState<Record<string, unknown>>(trigger.config);
  const [validationErrors, setValidationErrors] = useState<Record<string, string>>({});

  const updateTrigger = useUpdateTrigger(pipelineId);

  // Reset when trigger changes
  useEffect(() => {
    setName(trigger.name);
    setDescription(trigger.description ?? '');
    setConfig(trigger.config);
    setValidationErrors({});
  }, [trigger]);

  const validate = (): boolean => {
    const errors: Record<string, string> = {};
    if (!name.trim()) {
      errors.name = '트리거 이름을 입력하세요';
    }
    if (trigger.triggerType === 'SCHEDULE') {
      const c = config as { cron: string };
      if (!c.cron?.trim()) errors.cron = 'Cron 표현식을 입력하세요';
    }
    if (trigger.triggerType === 'PIPELINE_CHAIN') {
      const c = config as { upstreamPipelineId: number | null };
      if (!c.upstreamPipelineId) errors.upstreamPipelineId = '선행 파이프라인을 선택하세요';
    }
    if (trigger.triggerType === 'DATASET_CHANGE') {
      const c = config as { datasetIds: number[] };
      if (!c.datasetIds?.length) errors.datasetIds = '감시 대상 데이터셋을 선택하세요';
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
          description: description.trim() || undefined,
          config,
        },
      });
      toast.success(`트리거 "${name}"이(가) 수정되었습니다.`);
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
      <DialogContent className="max-w-lg max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            트리거 편집
            <Badge variant="outline">{TRIGGER_TYPE_LABELS[trigger.triggerType]}</Badge>
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="edit-trigger-name">
              이름 <span className="text-destructive">*</span>
            </Label>
            <Input
              id="edit-trigger-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
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
              onChange={setConfig}
              errors={validationErrors}
            />
          )}
          {trigger.triggerType === 'API' && (
            <ApiTriggerForm
              config={config as { allowedIps: string[] }}
              onChange={setConfig}
              isEditMode
            />
          )}
          {trigger.triggerType === 'PIPELINE_CHAIN' && (
            <PipelineChainForm
              pipelineId={pipelineId}
              config={config as { upstreamPipelineId: number | null; condition: 'SUCCESS' | 'FAILURE' | 'ANY' }}
              onChange={setConfig}
              errors={validationErrors}
            />
          )}
          {trigger.triggerType === 'WEBHOOK' && (
            <WebhookTriggerForm
              config={config as { webhookId?: string; secret?: string }}
              onChange={setConfig}
              isEditMode
            />
          )}
          {trigger.triggerType === 'DATASET_CHANGE' && (
            <DatasetChangeTriggerForm
              config={config as { datasetIds: number[]; pollingIntervalSeconds: number; debounceSeconds: number }}
              onChange={setConfig}
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
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
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
