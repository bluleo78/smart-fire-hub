import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import CronExpressionInput from './CronExpressionInput';
import type { ConcurrencyPolicy } from '@/types/pipeline';

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
        <Label>동시 실행 정책</Label>
        <RadioGroup
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
