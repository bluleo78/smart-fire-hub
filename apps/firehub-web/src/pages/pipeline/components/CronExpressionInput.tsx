import { CronExpressionParser } from 'cron-parser';
import cronstrue from 'cronstrue/i18n';
import { useId, useMemo } from 'react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
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
  // 접근성: 라벨↔입력 연결용 id 접두사 (#432).
  // 이 컴포넌트는 추가/수정 트리거 다이얼로그 양쪽에서 렌더될 수 있어 하드코딩 id 는 충돌한다.
  const baseId = useId();
  const cronId = `${baseId}-cron`;
  const cronDescId = `${baseId}-cron-desc`;
  const cronErrorId = `${baseId}-cron-error`;
  const timezoneId = `${baseId}-timezone`;
  const nextRunsId = `${baseId}-next-runs`;

  const { description, parseError } = useMemo(() => {
    if (!value.trim()) return { description: '', parseError: null as string | null };
    try {
      const desc = cronstrue.toString(value, { locale: 'ko', use24HourTimeFormat: true });
      return { description: desc, parseError: null as string | null };
    } catch {
      return { description: '', parseError: '유효하지 않은 cron 표현식입니다' };
    }
  }, [value]);

  const nextExecutions = useMemo(() => {
    if (!value.trim() || parseError) return [];
    try {
      const interval = CronExpressionParser.parse(value, {
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
        <Label htmlFor={cronId}>Cron 표현식</Label>
        <Input
          id={cronId}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="0 9 * * *"
          className={displayError ? 'border-destructive' : undefined}
          /* 설명(사람이 읽는 cron 해석)과 오류 문구를 모두 스크린리더에 연결한다 — 둘 다 조건부 렌더 */
          aria-describedby={
            [description ? cronDescId : null, displayError ? cronErrorId : null]
              .filter(Boolean)
              .join(' ') || undefined
          }
          aria-invalid={!!displayError}
        />
        {description && (
          <p id={cronDescId} className="text-sm text-muted-foreground">{description}</p>
        )}
        {displayError && (
          <p id={cronErrorId} className="text-sm text-destructive">{displayError}</p>
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
        <Label htmlFor={timezoneId}>타임존</Label>
        <Select value={timezone} onValueChange={onTimezoneChange}>
          <SelectTrigger id={timezoneId} className="w-full">
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
          {/*
            대응하는 단일 입력 요소가 없는 목록 제목이라 Label 컴포넌트가 아니라 span 으로 둔다 (#432).
            className 은 shadcn Label 기본 스타일(flex/leading-none/font-medium/select-none)을
            그대로 옮겨 시각 결과를 유지하고, 크기는 기존대로 text-xs 를 쓴다.
          */}
          <span
            id={nextRunsId}
            className="flex items-center gap-2 leading-none font-medium select-none text-muted-foreground text-xs"
          >
            다음 5회 실행 예정
          </span>
          <ul className="space-y-0.5" aria-labelledby={nextRunsId}>
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
