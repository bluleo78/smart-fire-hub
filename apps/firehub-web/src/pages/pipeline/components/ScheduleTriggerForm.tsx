import { useId } from 'react';

import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import type { ConcurrencyPolicy } from '@/types/pipeline';

import CronExpressionInput from './CronExpressionInput';

interface ScheduleTriggerFormProps {
  config: {
    cron: string;
    timezone: string;
    concurrencyPolicy: ConcurrencyPolicy;
  };
  onChange: (config: ScheduleTriggerFormProps['config']) => void;
  errors?: Record<string, string>;
}

export default function ScheduleTriggerForm({ config, onChange, errors }: ScheduleTriggerFormProps) {
  // 접근성: 라디오 그룹 제목↔그룹 연결용 id (#432). 추가/수정 다이얼로그 양쪽에서 렌더되므로 useId.
  const baseId = useId();
  const policyLabelId = `${baseId}-policy-label`;

  return (
    <div className="space-y-4">
      <CronExpressionInput
        value={config.cron}
        onChange={(cron) => onChange({ ...config, cron })}
        timezone={config.timezone}
        onTimezoneChange={(timezone) => onChange({ ...config, timezone })}
        error={errors?.cron}
      />

      <div className="space-y-2">
        {/*
          라디오 그룹 전체의 제목이라 대응하는 단일 컨트롤이 없다 — Label 컴포넌트 대신 span 으로 두고
          RadioGroup 에 aria-labelledby 로 연결해 스크린리더에는 그룹 이름으로 읽히게 한다 (#432).
          className 은 shadcn Label 기본 스타일을 그대로 옮겨 시각 결과를 유지한다.
        */}
        <span
          id={policyLabelId}
          className="flex items-center gap-2 text-sm leading-none font-medium select-none"
        >
          동시 실행 정책
        </span>
        <RadioGroup
          aria-labelledby={policyLabelId}
          value={config.concurrencyPolicy}
          onValueChange={(val) => onChange({ ...config, concurrencyPolicy: val as ConcurrencyPolicy })}
        >
          <div className="flex items-center space-x-2">
            <RadioGroupItem value="SKIP" id="policy-skip" />
            <Label htmlFor="policy-skip" className="font-normal">
              건너뛰기 (SKIP) - 이전 실행이 진행 중이면 건너뜁니다
            </Label>
          </div>
          <div className="flex items-center space-x-2">
            <RadioGroupItem value="ALLOW" id="policy-allow" />
            <Label htmlFor="policy-allow" className="font-normal">
              허용 (ALLOW) - 동시 실행을 허용합니다
            </Label>
          </div>
        </RadioGroup>
      </div>
    </div>
  );
}
