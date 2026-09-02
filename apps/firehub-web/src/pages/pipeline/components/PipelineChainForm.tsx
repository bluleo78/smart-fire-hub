import { Check, ChevronsUpDown } from 'lucide-react';
import { useId, useState } from 'react';

import { Button } from '@/components/ui/button';
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { usePipelines } from '@/hooks/queries/usePipelines';
import { cn } from '@/lib/utils';
import type { TriggerCondition } from '@/types/pipeline';

interface PipelineChainFormProps {
  pipelineId: number;
  config: {
    upstreamPipelineId: number | null;
    condition: TriggerCondition;
  };
  onChange: (config: PipelineChainFormProps['config']) => void;
  errors?: Record<string, string>;
}

export default function PipelineChainForm({ pipelineId, config, onChange, errors }: PipelineChainFormProps) {
  const [open, setOpen] = useState(false);
  const { data: pipelinesData } = usePipelines({ size: 1000 });
  // 접근성: 라벨↔컨트롤 연결용 id 접두사 (#432). 추가/수정 다이얼로그 양쪽에서 렌더되므로 useId.
  const baseId = useId();
  const upstreamId = `${baseId}-upstream`;
  const upstreamErrorId = `${baseId}-upstream-error`;
  const conditionLabelId = `${baseId}-condition-label`;

  const pipelines = (pipelinesData?.content ?? []).filter((p) => p.id !== pipelineId);
  const selected = pipelines.find((p) => p.id === config.upstreamPipelineId);

  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label htmlFor={upstreamId}>선행 파이프라인</Label>
        <Popover open={open} onOpenChange={setOpen}>
          <PopoverTrigger asChild>
            <Button
              id={upstreamId}
              variant="outline"
              role="combobox"
              aria-expanded={open}
              aria-describedby={errors?.upstreamPipelineId ? upstreamErrorId : undefined}
              aria-invalid={!!errors?.upstreamPipelineId}
              className={cn(
                'w-full justify-between font-normal',
                errors?.upstreamPipelineId && 'border-destructive',
              )}
            >
              <span className={cn('truncate', !selected && 'text-muted-foreground')}>
                {selected ? selected.name : '파이프라인 선택'}
              </span>
              <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-full p-0" align="start">
            <Command>
              <CommandInput placeholder="파이프라인 검색..." />
              <CommandList>
                <CommandEmpty>파이프라인을 찾을 수 없습니다.</CommandEmpty>
                <CommandGroup>
                  {pipelines.map((p) => (
                    <CommandItem
                      key={p.id}
                      value={p.name}
                      onSelect={() => {
                        onChange({ ...config, upstreamPipelineId: p.id });
                        setOpen(false);
                      }}
                    >
                      <Check
                        className={cn(
                          'h-4 w-4',
                          config.upstreamPipelineId === p.id ? 'opacity-100' : 'opacity-0',
                        )}
                      />
                      {p.name}
                    </CommandItem>
                  ))}
                </CommandGroup>
              </CommandList>
            </Command>
          </PopoverContent>
        </Popover>
        {errors?.upstreamPipelineId && (
          <p id={upstreamErrorId} className="text-sm text-destructive">{errors.upstreamPipelineId}</p>
        )}
      </div>

      <div className="space-y-2">
        {/*
          라디오 그룹 전체의 제목이라 대응하는 단일 컨트롤이 없다 — Label 컴포넌트 대신 span 으로 두고
          RadioGroup 에 aria-labelledby 로 연결한다 (#432).
          className 은 shadcn Label 기본 스타일을 그대로 옮겨 시각 결과를 유지한다.
        */}
        <span
          id={conditionLabelId}
          className="flex items-center gap-2 text-sm leading-none font-medium select-none"
        >
          트리거 조건
        </span>
        <RadioGroup
          aria-labelledby={conditionLabelId}
          value={config.condition}
          onValueChange={(val) => onChange({ ...config, condition: val as TriggerCondition })}
        >
          <div className="flex items-center space-x-2">
            <RadioGroupItem value="SUCCESS" id="cond-success" />
            <Label htmlFor="cond-success" className="font-normal">
              성공 시
            </Label>
          </div>
          <div className="flex items-center space-x-2">
            <RadioGroupItem value="FAILURE" id="cond-failure" />
            <Label htmlFor="cond-failure" className="font-normal">
              실패 시
            </Label>
          </div>
          <div className="flex items-center space-x-2">
            <RadioGroupItem value="ANY" id="cond-any" />
            <Label htmlFor="cond-any" className="font-normal">
              항상 (성공/실패 무관)
            </Label>
          </div>
        </RadioGroup>
      </div>
    </div>
  );
}
