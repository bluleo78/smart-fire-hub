import { ChevronDown, Sparkles } from 'lucide-react';
import { useCallback, useMemo, useState } from 'react';
import { type UseFormReturn } from 'react-hook-form';

import type { ProactiveJob, ReportTemplate, TemplateSection, TriggerType } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { cronToLabel } from '@/lib/cron-label';
import { formatDate, getStatusBadgeVariant, getStatusLabel } from '@/lib/formatters';
import { formatNextRun,getNextRunDate } from '@/lib/next-run';
import { TIMEZONE_OPTIONS } from '@/lib/timezone-data';
import type { ProactiveJobFormValues } from '@/lib/validations/proactive-job';

import ChannelRecipientEditor from '../components/ChannelRecipientEditor';
import { SectionPreview } from '../components/SectionPreview';

type CreationMode = 'manual' | 'goal';

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
  /** 사용자 입력으로 폼이 변경됐을 때 호출 — 이탈 가드용 dirty 마킹 (이슈 #59) */
  onChange?: () => void;
}

function ReadonlyCard({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">{label}</p>
      <div className="text-sm">{value}</div>
    </div>
  );
}

function generateAutoTemplate(question: string): TemplateSection[] {
  return [
    {
      key: 'summary',
      label: '요약',
      type: 'text',
      required: true,
      instruction: `"${question}"에 대한 핵심 발견사항을 요약하세요.`,
    },
    {
      key: 'analysis',
      label: '상세 분석',
      type: 'text',
      instruction: `"${question}"에 대해 데이터를 기반으로 상세 분석하세요.`,
    },
    {
      key: 'metrics',
      label: '주요 지표',
      type: 'cards',
      instruction: '관련 핵심 지표를 카드로 표시하세요.',
    },
    {
      key: 'recommendation',
      label: '권고사항',
      type: 'recommendation',
      instruction: '분석 결과를 바탕으로 구체적 조치를 제안하세요.',
    },
  ];
}

export default function JobOverviewTab({ job, isNew, isEditing, form, templates, onChange }: JobOverviewTabProps) {
  const { register, watch, setValue, formState: { errors } } = form;
  // dirty 마킹 헬퍼 — onChange가 없으면 no-op (이슈 #59)
  const markDirty = useCallback(() => onChange?.(), [onChange]);
  const channels = watch('config.channels');
  const cronExpression = watch('cronExpression');
  const timezone = watch('timezone');

  // Goal-based mode state
  const [creationMode, setCreationMode] = useState<CreationMode>('manual');
  const [businessQuestion, setBusinessQuestion] = useState('');
  const [autoSections, setAutoSections] = useState<TemplateSection[] | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(true);

  const handleGenerateTemplate = useCallback(async () => {
    if (!businessQuestion.trim()) return;
    setIsGenerating(true);
    setAutoSections(null);

    // Simulate brief delay for UX feedback
    await new Promise((r) => setTimeout(r, 600));

    const sections = generateAutoTemplate(businessQuestion.trim());
    setAutoSections(sections);
    setIsGenerating(false);
    setPreviewOpen(true);

    // Auto-fill form fields
    setValue('prompt', businessQuestion.trim());
    if (!watch('name')) {
      // Generate a name from the question (first 30 chars)
      const autoName = businessQuestion.trim().slice(0, 30) + (businessQuestion.trim().length > 30 ? '...' : '');
      setValue('name', autoName);
    }
    // Clear template selection since we're using auto-generated structure
    setValue('templateId', null);
  }, [businessQuestion, setValue, watch]);

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
            <ReadonlyCard
              label="트리거 유형"
              value={
                <Badge variant="outline">
                  {job.triggerType === 'ANOMALY' ? '이상 탐지' : job.triggerType === 'BOTH' ? '스케줄 + 이상 탐지' : '스케줄'}
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
      {/* 작성 모드 선택 (신규 생성 시에만) */}
      {isNew && (
        <div className="space-y-3">
          <Label>작성 모드</Label>
          <RadioGroup
            value={creationMode}
            onValueChange={(v) => setCreationMode(v as CreationMode)}
            className="flex gap-4"
          >
            <div className="flex items-center gap-2">
              <RadioGroupItem value="manual" id="mode-manual" />
              <Label htmlFor="mode-manual" className="font-normal cursor-pointer">
                직접 설정
              </Label>
            </div>
            <div className="flex items-center gap-2">
              <RadioGroupItem value="goal" id="mode-goal" />
              <Label htmlFor="mode-goal" className="font-normal cursor-pointer">
                목표 기반
              </Label>
            </div>
          </RadioGroup>
          <p className="text-xs text-muted-foreground">
            {creationMode === 'manual'
              ? '템플릿과 프롬프트를 직접 구성합니다.'
              : '비즈니스 질문을 입력하면 AI가 리포트 템플릿을 자동으로 생성합니다.'}
          </p>
        </div>
      )}

      {/* 목표 기반 모드 UI */}
      {isNew && creationMode === 'goal' && (
        <div className="space-y-4 rounded-lg border p-4 bg-muted/30">
          <div className="space-y-2">
            <Label htmlFor="business-question">비즈니스 질문</Label>
            <Textarea
              id="business-question"
              rows={3}
              placeholder="예: 이번 달 매출 하락의 원인을 분석하고 싶습니다"
              value={businessQuestion}
              onChange={(e) => setBusinessQuestion(e.target.value)}
            />
          </div>

          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleGenerateTemplate}
            disabled={!businessQuestion.trim() || isGenerating}
          >
            <Sparkles className="h-3.5 w-3.5 mr-1.5" />
            {isGenerating ? '생성 중...' : '템플릿 자동 생성'}
          </Button>

          {/* 생성 중 스켈레톤 */}
          {isGenerating && (
            <div className="space-y-3">
              <Skeleton className="h-4 w-24" />
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-3/4" />
            </div>
          )}

          {/* 생성된 템플릿 프리뷰 */}
          {autoSections && !isGenerating && (
            <Collapsible open={previewOpen} onOpenChange={setPreviewOpen}>
              <CollapsibleTrigger asChild>
                <Button variant="ghost" size="sm" className="gap-1.5 px-2 -ml-2">
                  <ChevronDown
                    className={`h-4 w-4 transition-transform ${previewOpen ? '' : '-rotate-90'}`}
                  />
                  자동 생성된 템플릿 ({autoSections.length}개 섹션)
                </Button>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="rounded-lg border p-3 mt-2 bg-background">
                  <SectionPreview sections={autoSections} />
                </div>
              </CollapsibleContent>
            </Collapsible>
          )}
        </div>
      )}

      {/* 작업명 */}
      <div className="space-y-2">
        <Label htmlFor="job-name">작업 이름 *</Label>
        <Input
          id="job-name"
          placeholder="일일 파이프라인 리포트"
          maxLength={200}
          {...register('name', { onChange: markDirty })}
        />
        {errors.name && <p className="text-xs text-destructive">{errors.name.message}</p>}
      </div>

      {/* 수동 모드 전용 필드 (템플릿, 프롬프트) */}
      {(creationMode === 'manual' || !isNew) && (
        <>
          {/* 템플릿 */}
          <div className="space-y-2">
            <Label htmlFor="job-template">리포트 템플릿 (선택)</Label>
            <Select
              value={watch('templateId') ? String(watch('templateId')) : 'none'}
              onValueChange={(v) => {
                markDirty();
                setValue('templateId', v === 'none' ? null : Number(v));
              }}
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
        </>
      )}

      {/* 트리거 유형 */}
      <div className="space-y-2">
        <Label htmlFor="job-trigger-type">트리거 유형</Label>
        <Select
          value={watch('triggerType') ?? 'SCHEDULE'}
          onValueChange={(v) => {
            markDirty();
            setValue('triggerType', v as TriggerType);
          }}
        >
          <SelectTrigger id="job-trigger-type">
            <SelectValue placeholder="트리거 유형 선택" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="SCHEDULE">스케줄 (정기 실행)</SelectItem>
            <SelectItem value="ANOMALY">이상 탐지 (이벤트 기반)</SelectItem>
            <SelectItem value="BOTH">스케줄 + 이상 탐지</SelectItem>
          </SelectContent>
        </Select>
        <p className="text-xs text-muted-foreground">
          {watch('triggerType') === 'ANOMALY'
            ? '이상 탐지 시에만 실행됩니다. 모니터링 탭에서 메트릭을 설정하세요.'
            : watch('triggerType') === 'BOTH'
              ? '정기 스케줄과 이상 탐지 모두에 의해 실행됩니다.'
              : '설정된 스케줄에 따라 정기적으로 실행됩니다.'}
        </p>
      </div>

      {/* 프롬프트 — 수동 모드에서만 직접 편집, 목표 모드에서는 자동 채워짐 */}
      {(creationMode === 'manual' || !isNew) && (
        <div className="space-y-2">
          <Label htmlFor="job-prompt">분석 프롬프트 *</Label>
          <Textarea
            id="job-prompt"
            rows={4}
            placeholder="어제 파이프라인 실행 결과를 분석하고 주요 이슈를 알려주세요."
            {...register('prompt', { onChange: markDirty })}
          />
          {errors.prompt && <p className="text-xs text-destructive">{errors.prompt.message}</p>}
        </div>
      )}

      {/* 실행 주기 — ANOMALY 전용 트리거에서는 불필요 (#38) */}
      {watch('triggerType') !== 'ANOMALY' && (
        <div className="space-y-2">
          <Label htmlFor="job-cron-preset">실행 주기</Label>
          <Select
            value={cronPreset}
            onValueChange={(v) => {
              markDirty();
              // __custom__ 선택 시 cronExpression을 빈 값으로 설정해 커스텀 입력 필드를 표시 (#43)
              if (v === '__custom__') setValue('cronExpression', '');
              else setValue('cronExpression', v);
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
                {...register('cronExpression', { onChange: markDirty })}
              />
              <p className="text-xs text-muted-foreground">Cron 표현식 (분 시 일 월 요일)</p>
            </div>
          )}
          {errors.cronExpression && (
            <p className="text-xs text-destructive">{errors.cronExpression.message}</p>
          )}
        </div>
      )}

      {/* 타임존 — ANOMALY 전용 트리거에서는 불필요 (#38) */}
      {watch('triggerType') !== 'ANOMALY' && (
        <div className="space-y-2">
          <Label htmlFor="job-timezone">타임존</Label>
          <Select
            value={timezone}
            onValueChange={(v) => {
              markDirty();
              setValue('timezone', v);
            }}
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
      )}

      {/* 다음 실행 시간 — ANOMALY 전용 트리거에서는 불필요 (#38) */}
      {watch('triggerType') !== 'ANOMALY' && nextRunInfo && (
        <div className="flex items-center gap-2 rounded-lg border border-info/20 bg-info-subtle p-3 text-sm text-info">
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
          onChange={(updated) => {
            markDirty();
            setValue('config.channels', updated);
          }}
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
