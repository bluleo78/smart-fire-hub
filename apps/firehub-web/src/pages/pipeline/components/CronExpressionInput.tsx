import { useState, useEffect, useMemo } from 'react';
import cronstrue from 'cronstrue/i18n';
import { CronExpressionParser } from 'cron-parser';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

const TIMEZONES = [
  'Asia/Seoul',
  'Asia/Tokyo',
  'Asia/Shanghai',
  'America/New_York',
  'America/Los_Angeles',
  'America/Chicago',
  'Europe/London',
  'Europe/Berlin',
  'Europe/Paris',
  'UTC',
];

const PRESETS = [
  { label: '매 시간', cron: '0 * * * *' },
  { label: '매일 09:00', cron: '0 9 * * *' },
  { label: '매주 월요일', cron: '0 9 * * 1' },
  { label: '매월 1일', cron: '0 9 1 * *' },
];

interface CronExpressionInputProps {
  value: string;
  onChange: (value: string) => void;
  timezone: string;
  onTimezoneChange: (tz: string) => void;
  error?: string;
}

export default function CronExpressionInput({
  value,
  onChange,
  timezone,
  onTimezoneChange,
  error,
}: CronExpressionInputProps) {
  const [description, setDescription] = useState('');
  const [parseError, setParseError] = useState<string | null>(null);

  useEffect(() => {
    if (!value.trim()) {
      setDescription('');
      setParseError(null);
      return;
    }
    try {
      const desc = cronstrue.toString(value, { locale: 'ko', use24HourTimeFormat: true });
      setDescription(desc);
      setParseError(null);
    } catch {
      setDescription('');
      setParseError('유효하지 않은 cron 표현식입니다');
    }
  }, [value]);

  const nextExecutions = useMemo(() => {
    if (!value.trim() || parseError) return [];
    try {
      const interval = CronExpressionParser.parseExpression(value, {
        tz: timezone,
      });
      const times: string[] = [];
      for (let i = 0; i < 5; i++) {
        const next = interval.next();
        times.push(
          new Date(next.getTime()).toLocaleString('ko-KR', { timeZone: timezone })
        );
      }
      return times;
    } catch {
      return [];
    }
  }, [value, timezone, parseError]);

  const displayError = error || parseError;

  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <Label>Cron 표현식</Label>
        <Input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="0 9 * * *"
          className={displayError ? 'border-destructive' : undefined}
        />
        {description && (
          <p className="text-sm text-muted-foreground">{description}</p>
        )}
        {displayError && (
          <p className="text-sm text-destructive">{displayError}</p>
        )}
      </div>

      <div className="flex flex-wrap gap-1.5">
        {PRESETS.map((preset) => (
          <Button
            key={preset.cron}
            type="button"
            variant="outline"
            size="sm"
            className="text-xs"
            onClick={() => onChange(preset.cron)}
          >
            {preset.label}
          </Button>
        ))}
      </div>

      <div className="space-y-1.5">
        <Label>타임존</Label>
        <Select value={timezone} onValueChange={onTimezoneChange}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {TIMEZONES.map((tz) => (
              <SelectItem key={tz} value={tz}>
                {tz}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {nextExecutions.length > 0 && (
        <div className="space-y-1.5">
          <Label className="text-muted-foreground text-xs">다음 5회 실행 예정</Label>
          <ul className="space-y-0.5">
            {nextExecutions.map((time, i) => (
              <li key={i} className="text-xs text-muted-foreground font-mono">
                {i + 1}. {time}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
