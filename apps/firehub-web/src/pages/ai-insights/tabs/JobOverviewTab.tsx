import { ChevronDown, Sparkles } from 'lucide-react';
import { useCallback, useId, useMemo, useRef, useState } from 'react';
import { type UseFormReturn } from 'react-hook-form';

import { proactiveApi, type ProactiveJob, type ReportTemplateSummary, type TemplateSection, type TriggerType } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
import { InlineBanner } from '@/components/ui/inline-banner';
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
import { useCronPreview } from '@/hooks/queries/useCronPreview';
import { cronTooFrequentMessage } from '@/lib/cron-frequency';
import { cronToLabel } from '@/lib/cron-label';
import { getExecutionDisplayStatus } from '@/lib/error-classifier';
import { formatDate, getStatusBadgeVariant, getStatusLabel } from '@/lib/formatters';
import { formatNextRun, timeZoneLabel } from '@/lib/next-run';
import { TIMEZONE_OPTIONS } from '@/lib/timezone-data';
import { goalTemplateName, MAX_JOB_PROMPT_LENGTH, type ProactiveJobFormValues } from '@/lib/validations/proactive-job';

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
  templates: ReportTemplateSummary[];
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

/** AI 생성(WD-455)이 실패해 고정 구성으로 대신했을 때 보여 주는 안내. */
const AI_FALLBACK_NOTICE = 'AI 생성에 실패해 기본 구성(4개 섹션)으로 만들었습니다.';

/**
 * 고정 4개 섹션 구성(요약·상세 분석·주요 지표·권고사항). WD-455 부터는 AI 생성(POST /proactive/templates/generate)이 실패했을 때의
 * 대체 구성이다 — AI 를 쓸 수 없는 테넌트(자격증명 미설정 등)에서도 목표 기반 작업을 만들 수 있게 남겨 둔다.
 */
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
  const { register, watch, setValue, getValues, formState: { errors } } = form;
  // 접근성: 그룹 라벨↔위젯 연결용 id 접두사 (#432).
  // 기존 하드코딩 id(job-name, mode-manual 등)는 E2E 계약이라 그대로 둔다.
  const baseId = useId();
  // dirty 마킹 헬퍼 — onChange가 없으면 no-op (이슈 #59)
  const markDirty = useCallback(() => onChange?.(), [onChange]);
  const channels = watch('config.channels');
  const cronExpression = watch('cronExpression');
  const timezone = watch('timezone');

  // 「다음 실행」은 서버가 스케줄러와 같은 서머타임 규칙으로 계산한 값을 쓴다(WD-391) — 화면이 따로 계산하면 전환일에
  // 실제 실행과 달랐다. 이상 감지 전용(ANOMALY)·너무 잦은 주기(폼 검증이 이미 안내)일 때는 묻지 않는다.
  const cronForPreview = cronExpression && cronExpression !== '__custom__' ? cronExpression : '';
  const nextRunPreview = useCronPreview('proactive', cronForPreview, timezone, {
    count: 1,
    // 읽기 전용 화면에는 이 안내가 없으므로 편집·생성 중에만 묻는다
    enabled:
      (isEditing || isNew) &&
      watch('triggerType') !== 'ANOMALY' &&
      !cronTooFrequentMessage(cronForPreview),
  });

  // Goal-based mode state
  const [creationMode, setCreationMode] = useState<CreationMode>('manual');
  const [businessQuestion, setBusinessQuestion] = useState('');
  const [autoSections, setAutoSections] = useState<TemplateSection[] | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(true);
  // AI 생성이 실패해 고정 구성으로 대신했는지(WD-455) — 사용자에게 그 사실을 알린다.
  const [usedFallback, setUsedFallback] = useState(false);
  // 생성 요청 차례 번호 — 응답을 기다리는 동안 질문을 고치거나 모드를 바꾸면 늦게 온 응답을 버린다(이전 질문의 구성이 되살아나지 않게).
  const generationSeqRef = useRef(0);

  /** 진행 중인 생성 응답을 무효로 하고 미리보기·안내를 비운다. 질문 수정·모드 전환 때 부른다. */
  const discardGenerated = useCallback(() => {
    generationSeqRef.current += 1;
    setIsGenerating(false);
    setAutoSections(null);
    setUsedFallback(false);
  }, []);

  const handleGenerateTemplate = useCallback(async () => {
    const question = businessQuestion.trim();
    if (!question) return;
    const seq = ++generationSeqRef.current;
    setIsGenerating(true);
    setAutoSections(null);
    setUsedFallback(false);

    // AI 로 질문에 맞는 섹션 구성을 만든다(WD-455). 서버가 양식 섹션 검사(WD-298)를 통과한 구성만 돌려주므로 그대로 저장할 수 있다.
    // 실패(AI 미설정·타임아웃·규칙 위반 등)하면 고정 4개 구성으로 대신하고 그 사실을 알린다 — 목표 기반 작업 생성 자체는 막지 않는다.
    let sections: TemplateSection[];
    let fallback = false;
    try {
      const { data } = await proactiveApi.generateTemplate(question);
      if (!Array.isArray(data?.sections) || data.sections.length === 0) throw new Error('empty sections');
      sections = data.sections;
    } catch {
      sections = generateAutoTemplate(question);
      fallback = true;
    }
    // 기다리는 동안 질문을 고쳤거나 모드를 바꿨으면 이 응답은 버린다.
    if (seq !== generationSeqRef.current) return;

    setAutoSections(sections);
    setUsedFallback(fallback);
    // 미리보기 구성을 폼에 둬 「생성」 때 리포트 양식으로 저장·연결되게 한다(WD-297) — 전에는 미리보기에만 쓰고 버려 「기본 형식」이 됐다.
    setValue('generatedSections', sections);
    setIsGenerating(false);
    setPreviewOpen(true);

    // Auto-fill form fields
    // shouldValidate: true — 모드 전환 시 prompt를 비워 폼을 invalid 상태로 만들어두므로(#523),
    // 템플릿 생성으로 prompt가 채워진 뒤에는 즉시 재검증해 "생성" 버튼이 다시 활성화되도록 한다.
    setValue('prompt', businessQuestion.trim(), { shouldValidate: true });
    if (!watch('name')) {
      // Generate a name from the question (first 30 chars)
      const autoName = businessQuestion.trim().slice(0, 30) + (businessQuestion.trim().length > 30 ? '...' : '');
      // shouldValidate: true — 위 prompt 검증 시점엔 이름이 비어 폼이 invalid 로 판정되므로, 이름을 채운 뒤 다시 검증해야
      // 자동 채운 이름만으로도 "생성"이 활성화된다(WD-297).
      setValue('name', autoName, { shouldValidate: true });
    }
    // Clear template selection since we're using auto-generated structure
    setValue('templateId', null);
  }, [businessQuestion, setValue, watch]);

  const cronPreset = CRON_PRESETS.find((p) => p.value !== '__custom__' && p.value === cronExpression)
    ? cronExpression
    : '__custom__';

  const nextRunInfo = useMemo(() => {
    const nextDate = nextRunPreview.times[0];
    if (!nextDate) return null;
    return {
      text: formatNextRun(nextDate, timezone),
      // 그 날짜에 실제로 쓰는 약어(EST/EDT 등) — 고정 표의 약어는 서머타임 기간을 구분하지 못한다
      abbr: timeZoneLabel(nextDate, timezone),
    };
  }, [nextRunPreview.times, timezone]);

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
              // 템플릿 미지정(또는 템플릿 삭제로 NULL이 된 경우)은 "없음"이 아니라
              // 실제 동작인 "기본 형식"으로 명시한다 (#364)
              value={job.templateName ?? <span className="text-muted-foreground">기본 형식</span>}
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
            {job.nextExecuteAt ? (
              <ReadonlyCard label="다음 실행" value={formatDate(job.nextExecuteAt)} />
            ) : (
              job.enabled &&
              !!job.cronExpression?.trim() && (
                /* 활성 + cron 이 있는데 다음 실행 시각이 없으면 스케줄러 미등록 상태다 (#354).
                   목록과 같은 신호를 상세에도 노출해, '활성' 배지만 보고 도는 줄 아는 상황을 막는다.
                   cron 이 없는 작업(triggerType=ANOMALY)은 등록 대상이 아니므로 제외한다. */
                <ReadonlyCard
                  label="다음 실행"
                  value={
                    <span
                      className="text-destructive font-medium"
                      data-testid="schedule-unregistered"
                    >
                      미등록 — 스케줄러에 등록되지 않아 실행되지 않습니다
                    </span>
                  }
                />
              )
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
        {job.lastExecution && (() => {
          // 의도된 건너뜀(서버는 FAILED 로 남김)은 실행 상세와 같게 회색 「건너뜀」 뱃지 + 중립 톤 사유로 보인다 (WD-191)
          const displayStatus = getExecutionDisplayStatus(job.lastExecution.status, job.lastExecution.errorMessage);
          const isSkipped = displayStatus === 'SKIPPED';
          return (
            <div className="rounded-lg border p-4 space-y-2">
              <h3 className="text-sm font-semibold">마지막 실행 상태</h3>
              <div className="flex items-center gap-2">
                <Badge variant={getStatusBadgeVariant(displayStatus)}>
                  {getStatusLabel(displayStatus)}
                </Badge>
                {job.lastExecution.errorMessage && (
                  <span
                    className={isSkipped ? 'text-sm text-muted-foreground' : 'text-sm text-destructive'}
                    data-testid="last-execution-reason"
                  >
                    {job.lastExecution.errorMessage}
                  </span>
                )}
              </div>
            </div>
          );
        })()}
      </div>
    );
  }

  // Edit / Create mode
  return (
    <div className="space-y-6 pt-4 max-w-2xl">
      {/* 작성 모드 선택 (신규 생성 시에만) */}
      {isNew && (
        <div className="space-y-3">
          {/* 라디오 그룹 전체의 제목이라 단일 입력이 없다 — span 으로 두고
              role=radiogroup 루트에 aria-labelledby 로 이름을 연결한다 (#432) */}
          <span id={`${baseId}-creation-mode-label`} className="block text-sm leading-none font-medium">
            작성 모드
          </span>
          <RadioGroup
            aria-labelledby={`${baseId}-creation-mode-label`}
            value={creationMode}
            onValueChange={(v) => {
              setCreationMode(v as CreationMode);
              // 모드 전환 시 이전 모드에서 입력된 prompt를 초기화한다.
              // prompt textarea는 조건부 렌더링으로만 숨겨질 뿐 RHF 상태(unregister 안 됨)에는
              // 값이 그대로 남아있어, 초기화하지 않으면 "목표 기반" 모드에서 비즈니스 질문/템플릿
              // 생성 없이도 이전 "직접 설정" 모드의 stale한 prompt가 그대로 제출되는 문제가 있었다 (#523).
              setValue('prompt', '', { shouldValidate: true });
              // 목표 기반 구성도 같은 이유로 비운다 — 「직접 설정」으로 돌아가 만든 작업에 양식이 몰래 붙지 않게(WD-297).
              setValue('generatedSections', null);
              discardGenerated();
              markDirty();
            }}
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
              : 'AI가 비즈니스 질문에 맞는 리포트 섹션 구성을 만듭니다. 작업을 생성하면 이 구성이 리포트 양식으로 함께 저장됩니다.'}
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
              // 질문이 그대로 분석 프롬프트가 되므로 같은 상한을 둔다 — 넘으면 생성 버튼이 안내 없이 비활성화된다(WD-425)
              maxLength={MAX_JOB_PROMPT_LENGTH}
              value={businessQuestion}
              onChange={(e) => {
                setBusinessQuestion(e.target.value);
                // 만든 뒤 질문을 고치면 이전 질문의 구성·프롬프트가 양식으로 저장되지 않게 비운다 — 다시 「템플릿 자동 생성」을 눌러야 한다(WD-297).
                // 생성 중에 고쳐도 늦게 온 응답이 이전 질문의 구성을 채우지 않게 함께 무효로 한다(WD-455).
                if (autoSections || isGenerating) {
                  discardGenerated();
                  setValue('generatedSections', null);
                  setValue('prompt', '', { shouldValidate: true });
                }
              }}
            />
          </div>

          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleGenerateTemplate}
            disabled={!businessQuestion.trim() || isGenerating}
          >
            <Sparkles className="h-3.5 w-3.5" />
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

          {/* AI 생성 실패 → 고정 구성으로 대신했음을 알린다(WD-455) */}
          {autoSections && !isGenerating && usedFallback && (
            <InlineBanner variant="warning">{AI_FALLBACK_NOTICE}</InlineBanner>
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
                {/* 생성 때 무엇이 저장되는지 미리 알린다(WD-297) — 양식 목록에서 나중에 고칠 수 있다. */}
                <p className="mt-2 text-xs text-muted-foreground">
                  작업을 생성하면 이 구성이 리포트 양식 「{goalTemplateName(watch('name') ?? '')}」(으)로 저장되어 연결됩니다.
                </p>
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
            const triggerType = v as TriggerType;
            setValue('triggerType', triggerType);
            // 트리거 유형을 이상 탐지(ANOMALY/BOTH)로 바꿔도 여기서는 config.anomaly.enabled를
            // 건드리지 않아, 모니터링 탭을 방문하지 않고 저장하면 상단 배지는 "활성"인데
            // 실제로는 폴러 대상에서 제외돼 영원히 발화하지 않는 모순 상태가 됐다 (#655).
            // 트리거 유형 선택 시점에 anomaly.enabled를 함께 켜서 폼 상태를 동기화한다.
            if (triggerType === 'ANOMALY' || triggerType === 'BOTH') {
              const currentAnomaly = getValues('config.anomaly');
              setValue(
                'config.anomaly',
                {
                  enabled: true,
                  metrics: currentAnomaly?.metrics ?? [],
                  sensitivity: currentAnomaly?.sensitivity ?? 'medium',
                  cooldownMinutes: currentAnomaly?.cooldownMinutes ?? 30,
                },
                { shouldValidate: true },
              );
            }
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
            // 서버 상한과 같은 길이까지만 입력받는다 — 넘으면 저장이 400 으로 거부된다(WD-425)
            maxLength={MAX_JOB_PROMPT_LENGTH}
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
        <InlineBanner variant="info" icon={<span>📅</span>}>
          다음 실행: <strong>{nextRunInfo.text}{nextRunInfo.abbr ? ` ${nextRunInfo.abbr}` : ''}</strong>
        </InlineBanner>
      )}
      {/* 서버 계산 대기(입력 디바운스 포함)·실패 안내 (WD-391) */}
      {watch('triggerType') !== 'ANOMALY' && nextRunPreview.isLoading && (
        <p role="status" className="text-xs text-muted-foreground">
          다음 실행 시각을 계산하는 중…
        </p>
      )}
      {watch('triggerType') !== 'ANOMALY' && nextRunPreview.error && (
        <InlineBanner variant="warning">
          다음 실행을 계산할 수 없습니다: {nextRunPreview.error}
        </InlineBanner>
      )}

      <Separator />

      {/* 전달 채널 */}
      <div className="space-y-2">
        {/* 채널 편집기는 체크박스 여러 개의 묶음이라 대응하는 단일 입력이 없다 —
            각 채널 체크박스가 자체 라벨을 갖고 있으므로 여기는 섹션 제목 span 으로 둔다 (#432) */}
        <span className="block text-sm leading-none font-medium">전달 채널 및 수신자</span>
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
