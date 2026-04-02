import { useMemo } from 'react';
import { type UseFormReturn } from 'react-hook-form';

import type { ProactiveJob, ReportTemplate } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Separator } from '@/components/ui/separator';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { cronToLabel } from '@/lib/cron-label';
import { formatDate, getStatusBadgeVariant, getStatusLabel } from '@/lib/formatters';
import { formatNextRun,getNextRunDate } from '@/lib/next-run';
import { TIMEZONE_OPTIONS } from '@/lib/timezone-data';
import type { ProactiveJobFormValues } from '@/lib/validations/proactive-job';

import ChannelRecipientEditor from '../components/ChannelRecipientEditor';

const CRON_PRESETS = [
  { label: '매시간', value: '0 * * * *' },
  { label: '매 30분', value: '*/30 * * * *' },
  { label: '매일 오전 8시', value: '0 8 * * *' },
  { label: '매일 오전 9시', value: '0 9 * * *' },
  { label: '매일 오후 6시', value: '0 18 * * *' },
  { label: '매주 월요일 오전 9시', value: '0 9 * * 1' },
  { label: '매주 금요일 오전 9시', value: '0 9 * * 5' },
  { label: '매월 1일 오전 9시', value: '0 9 1 * *' },
  { label: '직접 입력', value: '__custom__' },
];

interface JobOverviewTabProps {
  job: ProactiveJob | undefined;
  isNew: boolean;
  isEditing: boolean;
  form: UseFormReturn<ProactiveJobFormValues>;
  templates: ReportTemplate[];
}

function ReadonlyCard({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">{label}</p>
      <div className="text-sm">{value}</div>
    </div>
  );
}

export default function JobOverviewTab({ job, isNew, isEditing, form, templates }: JobOverviewTabProps) {
  const { register, watch, setValue, formState: { errors } } = form;
  const channels = watch('config.channels');
  const cronExpression = watch('cronExpression');
  const timezone = watch('timezone');

  const cronPreset = CRON_PRESETS.find((p) => p.value !== '__custom__' && p.value === cronExpression)
    ? cronExpression
    : '__custom__';

  const nextRunInfo = useMemo(() => {
    if (!cronExpression || cronExpression === '__custom__') return null;
    const nextDate = getNextRunDate(cronExpression, timezone);
    if (!nextDate) return null;
    const tzOption = TIMEZONE_OPTIONS.find((t) => t.value === timezone);
    return {
      text: formatNextRun(nextDate, timezone),
      abbr: tzOption?.abbr ?? '',
    };
  }, [cronExpression, timezone]);

  if (!isEditing && !isNew && job) {
    // Read-only view
    const config = job.config ?? {};
    const channelList = Array.isArray(config.channels) ? config.channels : [];

    return (
      <div className="space-y-6 pt-4">
        {/* 기본 정보 */}
        <div className="rounded-lg border p-4 space-y-4">
          <h3 className="text-sm font-semibold">기본 정보</h3>
          <div className="grid grid-cols-2 gap-4">
            <ReadonlyCard label="작업명" value={job.name} />
            <ReadonlyCard
              label="템플릿"
              value={job.templateName ?? <span className="text-muted-foreground">없음</span>}
            />
            <ReadonlyCard label="생성일" value={formatDate(job.createdAt)} />
            <ReadonlyCard
              label="활성 여부"
              value={
                <Badge variant={job.enabled ? 'default' : 'secondary'}>
                  {job.enabled ? '활성' : '비활성'}
                </Badge>
              }
            />
          </div>
        </div>

        {/* 실행 주기 */}
        <div className="rounded-lg border p-4 space-y-4">
          <h3 className="text-sm font-semibold">실행 주기</h3>
          <div className="grid grid-cols-2 gap-4">
            <ReadonlyCard label="스케줄" value={cronToLabel(job.cronExpression)} />
            <ReadonlyCard label="타임존" value={job.timezone} />
            {job.nextExecuteAt && (
              <ReadonlyCard label="다음 실행" value={formatDate(job.nextExecuteAt)} />
            )}
            {job.lastExecutedAt && (
              <ReadonlyCard label="마지막 실행" value={formatDate(job.lastExecutedAt)} />
            )}
          </div>
        </div>

        {/* 프롬프트 */}
        <div className="rounded-lg border p-4 space-y-2">
          <h3 className="text-sm font-semibold">분석 프롬프트</h3>
          <p className="text-sm whitespace-pre-wrap text-muted-foreground">{job.prompt}</p>
        </div>

        {/* 전달 채널 */}
        <div className="rounded-lg border p-4 space-y-3">
          <h3 className="text-sm font-semibold">전달 채널</h3>
          {channelList.length === 0 ? (
            <p className="text-sm text-muted-foreground">설정된 채널이 없습니다.</p>
          ) : (
            <div className="space-y-2">
              {channelList.map((ch, idx) => {
                if (typeof ch === 'string') {
                  return (
                    <div key={idx} className="flex items-center gap-2">
                      <Badge variant="outline">{ch === 'CHAT' ? '채팅' : ch === 'EMAIL' ? '이메일' : ch}</Badge>
                    </div>
                  );
                }
                const c = ch as { type?: string; recipientUserIds?: unknown[]; recipientEmails?: unknown[] };
                const label = c.type === 'CHAT' ? '채팅' : c.type === 'EMAIL' ? '이메일' : c.type ?? '';
                const userCount = c.recipientUserIds?.length ?? 0;
                const emailCount = c.recipientEmails?.length ?? 0;
                return (
                  <div key={idx} className="flex items-center gap-2">
                    <Badge variant="outline">{label}</Badge>
                    {userCount > 0 && <span className="text-xs text-muted-foreground">사용자 {userCount}명</span>}
                    {emailCount > 0 && <span className="text-xs text-muted-foreground">외부 이메일 {emailCount}건</span>}
                    {userCount === 0 && emailCount === 0 && (
                      <span className="text-xs text-muted-foreground">본인에게만 전달</span>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* 마지막 실행 상태 */}
        {job.lastExecution && (
          <div className="rounded-lg border p-4 space-y-2">
            <h3 className="text-sm font-semibold">마지막 실행 상태</h3>
            <div className="flex items-center gap-2">
              <Badge variant={getStatusBadgeVariant(job.lastExecution.status)}>
                {getStatusLabel(job.lastExecution.status)}
              </Badge>
              {job.lastExecution.errorMessage && (
                <span className="text-sm text-destructive">{job.lastExecution.errorMessage}</span>
              )}
            </div>
          </div>
        )}
      </div>
    );
  }

  // Edit / Create mode
  return (
    <div className="space-y-6 pt-4 max-w-2xl">
      {/* 작업명 */}
      <div className="space-y-2">
        <Label htmlFor="job-name">작업 이름 *</Label>
        <Input
          id="job-name"
          placeholder="일일 파이프라인 리포트"
          {...register('name')}
        />
        {errors.name && <p className="text-xs text-destructive">{errors.name.message}</p>}
      </div>

      {/* 템플릿 */}
      <div className="space-y-2">
        <Label htmlFor="job-template">리포트 템플릿 (선택)</Label>
        <Select
          value={watch('templateId') ? String(watch('templateId')) : 'none'}
          onValueChange={(v) => setValue('templateId', v === 'none' ? null : Number(v))}
        >
          <SelectTrigger id="job-template">
            <SelectValue placeholder="템플릿 선택 (없으면 기본)" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="none">기본 (선택 안 함)</SelectItem>
            {templates.map((t) => (
              <SelectItem key={t.id} value={String(t.id)}>
                {t.name}{t.builtin && ' (기본)'}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* 프롬프트 */}
      <div className="space-y-2">
        <Label htmlFor="job-prompt">분석 프롬프트 *</Label>
        <Textarea
          id="job-prompt"
          rows={4}
          placeholder="어제 파이프라인 실행 결과를 분석하고 주요 이슈를 알려주세요."
          {...register('prompt')}
        />
        {errors.prompt && <p className="text-xs text-destructive">{errors.prompt.message}</p>}
      </div>

      {/* 실행 주기 */}
      <div className="space-y-2">
        <Label htmlFor="job-cron-preset">실행 주기</Label>
        <Select
          value={cronPreset}
          onValueChange={(v) => {
            if (v !== '__custom__') setValue('cronExpression', v);
          }}
        >
          <SelectTrigger id="job-cron-preset">
            <SelectValue placeholder="실행 주기 선택" />
          </SelectTrigger>
          <SelectContent>
            {CRON_PRESETS.map((p) => (
              <SelectItem key={p.value} value={p.value}>{p.label}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        {cronPreset === '__custom__' && (
          <div className="space-y-1">
            <Input
              placeholder="0 9 * * *"
              className="font-mono text-sm"
              {...register('cronExpression')}
            />
            <p className="text-xs text-muted-foreground">Cron 표현식 (분 시 일 월 요일)</p>
          </div>
        )}
        {errors.cronExpression && (
          <p className="text-xs text-destructive">{errors.cronExpression.message}</p>
        )}
      </div>

      {/* 타임존 */}
      <div className="space-y-2">
        <Label htmlFor="job-timezone">타임존</Label>
        <Select
          value={timezone}
          onValueChange={(v) => setValue('timezone', v)}
        >
          <SelectTrigger id="job-timezone">
            <SelectValue placeholder="타임존 선택" />
          </SelectTrigger>
          <SelectContent>
            {TIMEZONE_OPTIONS.map((tz) => (
              <SelectItem key={tz.value} value={tz.value}>
                {tz.value} ({tz.abbr}, {tz.offset})
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* 다음 실행 시간 */}
      {nextRunInfo && (
        <div className="flex items-center gap-2 rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-800 dark:border-blue-800 dark:bg-blue-950/50 dark:text-blue-300">
          <span>📅</span>
          <span>다음 실행: <strong>{nextRunInfo.text}{nextRunInfo.abbr ? ` ${nextRunInfo.abbr}` : ''}</strong></span>
        </div>
      )}

      <Separator />

      {/* 전달 채널 */}
      <div className="space-y-2">
        <Label>전달 채널 및 수신자</Label>
        <ChannelRecipientEditor
          channels={channels}
          onChange={(updated) => setValue('config.channels', updated)}
        />
      </div>

      {/* 활성 여부 (편집 모드에서만) */}
      {!isNew && job && (
        <div className="flex items-center gap-3">
          <Switch
            id="job-enabled"
            checked={job.enabled}
            disabled
          />
          <Label htmlFor="job-enabled" className="text-muted-foreground">
            활성 여부 (목록에서 변경)
          </Label>
        </div>
      )}
    </div>
  );
}
