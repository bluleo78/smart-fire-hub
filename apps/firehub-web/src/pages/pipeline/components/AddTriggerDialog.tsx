import axios from 'axios';
import { ArrowLeft,Clock, Code, Database, Globe, Link, Loader2 } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { useCreateTrigger } from '@/hooks/queries/usePipelines';
import type { ErrorResponse } from '@/types/auth';
import type { TriggerResponse,TriggerType } from '@/types/pipeline';

import ApiTriggerForm from './ApiTriggerForm';
import DatasetChangeTriggerForm from './DatasetChangeTriggerForm';
import PipelineChainForm from './PipelineChainForm';
import ScheduleTriggerForm from './ScheduleTriggerForm';
import WebhookTriggerForm from './WebhookTriggerForm';

const TRIGGER_TYPES: { type: TriggerType; label: string; description: string; icon: React.ReactNode }[] = [
  { type: 'SCHEDULE', label: '스케줄', description: 'Cron 표현식으로 주기적 실행', icon: <Clock className="h-6 w-6" /> },
  { type: 'API', label: 'API', description: '외부 API 호출로 실행', icon: <Code className="h-6 w-6" /> },
  { type: 'PIPELINE_CHAIN', label: '파이프라인 연쇄', description: '선행 파이프라인 완료 시 실행', icon: <Link className="h-6 w-6" /> },
  { type: 'WEBHOOK', label: '웹훅', description: 'HTTP POST 수신 시 실행', icon: <Globe className="h-6 w-6" /> },
  { type: 'DATASET_CHANGE', label: '데이터셋 변경', description: '데이터셋 변경 감지 시 실행', icon: <Database className="h-6 w-6" /> },
];

function getDefaultConfig(type: TriggerType): Record<string, unknown> {
  switch (type) {
    case 'SCHEDULE':
      return { cron: '0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' };
    case 'API':
      return { allowedIps: [] };
    case 'PIPELINE_CHAIN':
      return { upstreamPipelineId: null, condition: 'SUCCESS' };
    case 'WEBHOOK':
      return { secret: '' };
    case 'DATASET_CHANGE':
      return { datasetIds: [], pollingIntervalSeconds: 60, debounceSeconds: 60 };
  }
}

interface AddTriggerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  pipelineId: number;
}

export function AddTriggerDialog({ open, onOpenChange, pipelineId }: AddTriggerDialogProps) {
  const [step, setStep] = useState<1 | 2>(1);
  const [selectedType, setSelectedType] = useState<TriggerType | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [config, setConfig] = useState<Record<string, unknown>>({});
  const [createdTrigger, setCreatedTrigger] = useState<TriggerResponse | null>(null);
  const [validationErrors, setValidationErrors] = useState<Record<string, string>>({});

  const createTrigger = useCreateTrigger(pipelineId);

  const handleSelectType = (type: TriggerType) => {
    setSelectedType(type);
    setConfig(getDefaultConfig(type));
    setName('');
    setDescription('');
    setValidationErrors({});
    setStep(2);
  };

  const handleBack = () => {
    setStep(1);
    setSelectedType(null);
    setCreatedTrigger(null);
  };

  const validate = (): boolean => {
    const errors: Record<string, string> = {};
    if (!name.trim()) {
      errors.name = '트리거 이름을 입력하세요';
    }
    if (selectedType === 'SCHEDULE') {
      const c = config as { cron: string };
      if (!c.cron?.trim()) errors.cron = 'Cron 표현식을 입력하세요';
    }
    if (selectedType === 'PIPELINE_CHAIN') {
      const c = config as { upstreamPipelineId: number | null };
      if (!c.upstreamPipelineId) errors.upstreamPipelineId = '선행 파이프라인을 선택하세요';
    }
    if (selectedType === 'DATASET_CHANGE') {
      const c = config as { datasetIds: number[] };
      if (!c.datasetIds?.length) errors.datasetIds = '감시 대상 데이터셋을 선택하세요';
    }
    setValidationErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const handleSubmit = async () => {
    if (!selectedType || !validate()) return;
    try {
      const result = await createTrigger.mutateAsync({
        name: name.trim(),
        triggerType: selectedType,
        description: description.trim() || undefined,
        config,
      });
      setCreatedTrigger(result);
      toast.success(`트리거 "${name}"이(가) 생성되었습니다.`);
      // For API type, keep dialog open to show token
      if (selectedType !== 'API') {
        handleClose();
      }
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '트리거 생성에 실패했습니다.');
      } else {
        toast.error('트리거 생성에 실패했습니다.');
      }
    }
  };

  const handleClose = () => {
    onOpenChange(false);
    // Reset state after close animation
    setTimeout(() => {
      setStep(1);
      setSelectedType(null);
      setName('');
      setDescription('');
      setConfig({});
      setCreatedTrigger(null);
      setValidationErrors({});
    }, 200);
  };

  const typeInfo = TRIGGER_TYPES.find((t) => t.type === selectedType);

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-lg max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {step === 1 ? '트리거 추가' : `트리거 추가 - ${typeInfo?.label}`}
          </DialogTitle>
        </DialogHeader>

        {step === 1 ? (
          <div className="grid gap-3">
            {TRIGGER_TYPES.map((t) => (
              <button
                key={t.type}
                type="button"
                className="flex items-center gap-4 p-4 rounded-lg border hover:bg-muted/50 transition-colors text-left"
                onClick={() => handleSelectType(t.type)}
              >
                <div className="shrink-0 text-muted-foreground">{t.icon}</div>
                <div>
                  <div className="font-medium text-sm">{t.label}</div>
                  <div className="text-xs text-muted-foreground">{t.description}</div>
                </div>
              </button>
            ))}
          </div>
        ) : (
          <div className="space-y-4">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="mb-2"
              onClick={handleBack}
              disabled={!!createdTrigger}
            >
              <ArrowLeft className="mr-1 h-4 w-4" />
              유형 선택으로 돌아가기
            </Button>

            <div className="space-y-1.5">
              <Label htmlFor="trigger-name">
                이름 <span className="text-destructive">*</span>
              </Label>
              <Input
                id="trigger-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="트리거 이름"
                className={validationErrors.name ? 'border-destructive' : undefined}
                disabled={!!createdTrigger}
              />
              {validationErrors.name && (
                <p className="text-sm text-destructive">{validationErrors.name}</p>
              )}
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="trigger-desc">설명</Label>
              <Textarea
                id="trigger-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="트리거 설명 (선택)"
                rows={2}
                disabled={!!createdTrigger}
              />
            </div>

            {selectedType === 'SCHEDULE' && (
              <ScheduleTriggerForm
                config={config as { cron: string; timezone: string; concurrencyPolicy: 'SKIP' | 'ALLOW' }}
                onChange={setConfig}
                errors={validationErrors}
              />
            )}
            {selectedType === 'API' && (
              <ApiTriggerForm
                config={config as { allowedIps: string[] }}
                onChange={setConfig}
                generatedToken={createdTrigger?.config?.rawToken as string | undefined}
              />
            )}
            {selectedType === 'PIPELINE_CHAIN' && (
              <PipelineChainForm
                pipelineId={pipelineId}
                config={config as { upstreamPipelineId: number | null; condition: 'SUCCESS' | 'FAILURE' | 'ANY' }}
                onChange={setConfig}
                errors={validationErrors}
              />
            )}
            {selectedType === 'WEBHOOK' && (
              <WebhookTriggerForm
                config={config as { webhookId?: string; secret?: string }}
                onChange={setConfig}
              />
            )}
            {selectedType === 'DATASET_CHANGE' && (
              <DatasetChangeTriggerForm
                config={config as { datasetIds: number[]; pollingIntervalSeconds: number; debounceSeconds: number }}
                onChange={setConfig}
                errors={validationErrors}
              />
            )}

            {createdTrigger ? (
              <Button type="button" className="w-full" onClick={handleClose}>
                닫기
              </Button>
            ) : (
              <Button
                type="button"
                className="w-full"
                onClick={handleSubmit}
                disabled={createTrigger.isPending}
              >
                {createTrigger.isPending ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    생성 중...
                  </>
                ) : (
                  '트리거 생성'
                )}
              </Button>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
