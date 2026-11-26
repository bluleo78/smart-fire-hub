import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { Activity, ArrowLeft, Copy } from 'lucide-react';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { proactiveApi, type ProactiveJob } from '@/api/proactive';
import { EditConflictDialog } from '@/components/EditConflictDialog';
import { PageHeading } from '@/components/PageHeading';
import { DetailPageTitle } from '@/components/PageTitleSync';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cloneBlockedReason, cloneSuccessMessage, useCanWriteProactive,useCloneProactiveJob, useCreateProactiveJob, useCreateProactiveTemplate, useDeleteProactiveJob, useDeleteProactiveTemplate, useDisableProactiveJob, useExecuteProactiveJob, useFetchLatestProactiveJob, useProactiveJob, useProactiveTemplates, useUpdateProactiveJob } from '@/hooks/queries/useProactiveMessages';
import { focusFirstEditField, useEditModeFocus } from '@/hooks/useEditModeFocus';
import { useInvalidFlag } from '@/hooks/useInvalidFlag';
import { useUnsavedChangesGuard } from '@/hooks/useUnsavedChangesGuard';
import { applyDuplicateNameError, extractApiError, handleApiError, isDuplicateName, isEditConflict, isForbiddenError, isJobAlreadyRunning, NameRetryExhaustedError } from '@/lib/api-error';
import { createWithNumberedName } from '@/lib/clone-name';
import { focusFirstInvalidInteger } from '@/lib/focus-invalid-integer';
import { parseRouteId } from '@/lib/route-id';
import { resolveTabParam } from '@/lib/tab-param';
import { type ChannelConfigValues, createProactiveJobSchema, numberedGoalTemplateName, type ProactiveJobFormValues, proactiveJobSchema } from '@/lib/validations/proactive-job';

import { OwnerIneligibleBadge } from './components/OwnerIneligibleBadge';
import { ProactiveReadForbiddenNotice } from './components/ProactiveReadForbiddenNotice';
import { ProactiveWriteNotice } from './components/ProactiveWriteNotice';
import { navLocationKey } from './nav-location';
import JobExecutionsTab from './tabs/JobExecutionsTab';
import JobMonitoringTab from './tabs/JobMonitoringTab';
import JobOverviewTab from './tabs/JobOverviewTab';

function buildDefaultValues(job?: {
  name: string;
  prompt: string;
  templateId: number | null;
  cronExpression: string | null;
  timezone: string;
  triggerType?: string;
  config: Record<string, unknown>;
}): ProactiveJobFormValues {
  if (!job) {
    return {
      name: '',
      prompt: '',
      templateId: null,
      cronExpression: '0 9 * * *',
      timezone: 'Asia/Seoul',
      triggerType: 'SCHEDULE',
      config: { channels: [{ type: 'CHAT', recipientUserIds: [], recipientEmails: [] }] },
    };
  }

  // Normalize config.channels to new format
  const rawChannels = Array.isArray(job.config?.channels) ? job.config.channels : [];
  const channels = rawChannels.map((ch) => {
    if (typeof ch === 'string') {
      return { type: ch as ChannelConfigValues['type'], recipientUserIds: [], recipientEmails: [] };
    }
    const c = ch as { type?: string; recipientUserIds?: number[]; recipientEmails?: string[] };
    // WD-994: 받은 객체를 통째로 펼쳐 화면이 모르는 키(웹훅 url·secret, attachPdf 등)를 잃지 않게 한다 —
    // 스키마가 passthrough 라 저장 때 그대로 나간다. 배열 필드와 type 만 보정한다.
    return {
      ...(ch as Record<string, unknown>),
      type: (c.type ?? 'CHAT') as ChannelConfigValues['type'],
      recipientUserIds: c.recipientUserIds ?? [],
      recipientEmails: c.recipientEmails ?? [],
    } as ChannelConfigValues;
  });

  // Normalize anomaly config
  const rawAnomaly = job.config?.anomaly as Record<string, unknown> | undefined;
  const anomaly = rawAnomaly
    ? {
        enabled: (rawAnomaly.enabled as boolean) ?? false,
        metrics: Array.isArray(rawAnomaly.metrics) ? rawAnomaly.metrics : [],
        sensitivity: ((rawAnomaly.sensitivity as string) ?? 'medium') as 'low' | 'medium' | 'high',
        cooldownMinutes: (rawAnomaly.cooldownMinutes as number) ?? 30,
      }
    : undefined;

  return {
    name: job.name,
    prompt: job.prompt,
    templateId: job.templateId ?? null,
    cronExpression: job.cronExpression ?? '',
    timezone: job.timezone ?? 'Asia/Seoul',
    triggerType: (job.triggerType as 'SCHEDULE' | 'ANOMALY' | 'BOTH') ?? 'SCHEDULE',
    config: { channels, anomaly },
  };
}

/** ReportBuilderWidget 저장 버튼으로부터 전달된 프리필 데이터 타입 */
interface ReportPrefillState {
  name?: string;
  prompt?: string;
}

// 상세 페이지가 ?tab= 으로 받아들이는 탭 목록 — 렌더마다 배열을 새로 만들지 않도록 모듈 상수로 둔다 (WD-871)
const NEW_JOB_TABS = ['overview', 'monitoring'] as const;
const JOB_TABS = [...NEW_JOB_TABS, 'executions'] as const;

export default function ProactiveJobDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const isNew = !id || id === 'new';
  // 공백(`%20`)·`0` 은 `Number()` 가 0 이라 `Number.isFinite` 를 통과해 이름 `-` 인 유령 상세와
  // 실행·삭제 버튼이 보였다(WD-568). parseRouteId 로 숫자만·양의 정수만 받고, 그 밖은 null(없는 id)로 다룬다.
  const parsedId = isNew ? 0 : parseRouteId(id);
  // id가 'abc'처럼 숫자로 변환 불가능한 경우 — Number()가 NaN을 반환하면
  // useProactiveJob의 enabled:!!id가 항상 false가 되어 쿼리 자체가 실행되지 않고
  // isLoading/isError가 영구히 false로 남아 유령 상세 화면이 렌더링된다 (#545).
  // 이런 경우를 별도 플래그로 구분해 아래 에러 뷰 조건에 합류시킨다.
  const isInvalidId = !isNew && parsedId == null;
  const jobId = parsedId ?? 0;

  // ReportBuilderWidget 저장 버튼에서 전달된 프리필 데이터 (이슈 #213)
  const prefill = isNew ? (location.state as ReportPrefillState | null) : null;

  // 새 작업엔 「실행 이력」 탭이 없으므로 허용 목록을 isNew 로 정한다 — 그 밖의 값은 「개요」로 보정 (WD-871)
  const activeTab = resolveTabParam(searchParams.get('tab'), isNew ? NEW_JOB_TABS : JOB_TABS, 'overview');

  const { data: job, isLoading, isError, error } = useProactiveJob(jobId);
  const { data: templates = [] } = useProactiveTemplates();
  // 생성·수정·삭제·복제·지금 실행은 서버가 proactive:write 로 막는다 — 권한이 없으면 폼을 다 채운 뒤에야 403 을 받던
  // 진입점을 숨기고 조회 전용으로 보인다(WD-133). 최종 판정은 서버이고 여기는 노출 결정용이다.
  const { canWrite, isLoading: isPermissionsLoading } = useCanWriteProactive();

  // 이동 state 의 편집 시작 표시 — 복제 직후(WD-996)와 목록의 막힌 복제 토스트 「편집」 이동(WD-1026)이 넘긴다
  const startEditingState = (location.state as { startEditing?: boolean } | null)?.startEditing === true;
  // 목록에서 「편집」 안내로 처음 들어올 때(새로 마운트)도 startEditing 이면 편집 모드로 시작한다 —
  // 라우트 정체 변경 재설정(아래)은 이미 마운트된 페이지의 id→id 이동에서만 돌기 때문이다 (WD-1026)
  const [isEditing, setIsEditing] = useState(isNew || startEditingState);
  // 「편집」으로 편집 폼이 열리면 첫 칸으로, 「취소」·「저장」 뒤에는 「편집」 버튼으로 포커스를 옮긴다(WD-415)
  const { editButtonRef, editRegionRef } = useEditModeFocus(isEditing);

  // 「직접 입력」 칸에 사용자가 넣은 값(WD-962) — 개요 탭은 다른 탭으로 가면 언마운트되므로 페이지가 들고 있다.
  // 읽기 전용으로 돌아가면(저장·취소) 지운다 — 다시 편집할 때 저장된 주기대로 보이게.
  const [customCronValue, setCustomCronValue] = useState<string | null>(null);
  if (customCronValue !== null && !isEditing) setCustomCronValue(null);

  // 사용자 상호작용 후 변경 여부 추적 (이슈 #59)
  // - 초기 로드(values 동기화)는 변경으로 간주하지 않기 위해 명시적으로 markDirty()를 호출
  // - 작업명/프롬프트/템플릿/트리거/주기/타임존/채널/이상 탐지 설정 등 편집 모드 입력 시 dirty=true
  const [isDirty, setIsDirty] = useState(false);
  const markDirty = useCallback(() => setIsDirty(true), []);
  // 모니터링 탭의 숫자 칸(쿨다운·폴링 주기)이 표기 오류인 채인가 — 마지막 올바른 값이 말없이 저장되지 않게 저장을 막는다 (WD-906 후속)
  const [integerInvalid, handleIntegerValidity] = useInvalidFlag();

  // 이탈 확인 다이얼로그 상태 — 뒤로가기/취소 클릭 시 dirty면 오픈 (이슈 #59)
  const [leaveDialogOpen, setLeaveDialogOpen] = useState(false);
  // 이탈 다이얼로그 확인 후 실행할 동작 — 'back'(목록 이동) 또는 'cancel-edit'(편집 취소)
  const [leaveAction, setLeaveAction] = useState<'back' | 'cancel-edit'>('back');

  // 폼 기본값의 원천 — 조회 재조회로 job 이 바뀌면 여기에 먼저 반영된다.
  // 편집 중·미저장인 동안에는 재조회 결과로 폼을 다시 채우지 않는다: 저장 성공이 목록·상세를 재조회하면
  // RHF `values` 재동기화가 지금 다시 채운 내용을 서버 값으로 덮어 지운다 — 저장 요청이 도는 동안 다른
  // 화면을 거쳐 같은 작업으로 돌아와 다시 쓴 내용이 늦은 응답으로 사라지는 경로(WD-1042 완료 기준 1).
  // 편집이 끝나면(저장 성공·취소) 다시 조회 값에 맞추고, 한 번도 채워 본 적 없는 job(첫 로드)은 막지 않는다.
  // 값 시드만 막고 기준 버전은 서버 값을 따라가면, 폼은 옛 값인데 기준만 앞으로 가 편집 중 다른 곳에서 저장된
  // 변경이 409 없이 내 저장으로 덮어써진다(WD-364 낙관적 잠금 무력화) — 그래서 기준도 함께 막는다(baseJob).
  const [seedSource, setSeedSource] = useState(job);
  const holdFormSeed = isEditing && isDirty && !isNew && !!seedSource;
  useEffect(() => {
    if (!holdFormSeed) setSeedSource(job);
  }, [job, holdFormSeed]);

  // 저장 기준이 되는 job — 시드를 멈춘 동안(편집·미저장)에는 멈춘 시점의 조회 결과를 그대로 쓰고,
  // 그 밖에는 최신 조회 값을 쓴다. 기준 버전(baseUpdatedAt)과 주기 비교(cronUnchanged)가 이 값을 따르므로,
  // 시드와 기준이 어긋나지 않는다. 기준은 저장 성공 응답으로도 앞으로 밀지 않는다 — 왕복 재편집으로 늦은 응답이
  // 가드에 걸려 isDirty 를 못 풀면 폼은 늘 첫 저장 이전 내용이라, 그 위 재저장은 서버(첫 저장 반영)와 버전이
  // 달라야 409 로 막힌다. 버전만 앞당기면 재저장이 첫 저장 내용을 충돌 없이 되돌린다(WD-1042 반려 3).
  // 멈춘 동안 다른 곳이 서버 값을 바꾸면 내 저장은 기준 버전이 달라 409 EDIT_CONFLICT 로 충돌 창으로 간다(WD-1042 반려).
  const baseJob = holdFormSeed ? seedSource : job;

  // prefill이 있으면 기본값에 리포트 제목/질문을 덮어씌워 폼을 미리 채운다 (이슈 #213)
  // 기본값도 baseJob 을 쓴다 — 멈춰 있지 않을 때는 seedSource(이펙트라 한 렌더 늦다) 대신 지금 job 을 바로 쓰므로
  // 첫 로드·/jobs/new 로 가는 프레임에 옛 내용이 한 프레임 비치지 않는다. 멈춰 있을 때는 둘이 같다(WD-1042 반려 후속).
  const defaultValues = buildDefaultValues(baseJob);
  if (prefill && isNew) {
    if (prefill.name) defaultValues.name = prefill.name;
    if (prefill.prompt) defaultValues.prompt = prefill.prompt;
  }

  // 수정이면 저장된 트리거·주기를 기준으로 「안 건드린 주기는 필수 아님」 규칙을 적용한다(WD-995). 작업이 로드되면 resolver 도 바뀐다.
  // 기준은 폼 시드·주기 비교(cronUnchanged)와 같은 baseJob 이다 — 편집 중 재조회로 job 이 바뀌면 멈춘 폼과 어긋나,
  // 남이 바꾼 주기 덕분에 내 빈 주기가 필수로 뒤바뀌어 저장이 막힌다(WD-1109).
  const jobLoaded = !!job;
  const jobTriggerType = baseJob?.triggerType;
  const jobCron = baseJob?.cronExpression;
  const resolverSchema = useMemo(
    () =>
      isNew || !jobLoaded
        ? proactiveJobSchema
        : createProactiveJobSchema({ triggerType: jobTriggerType, cronExpression: jobCron }),
    [isNew, jobLoaded, jobTriggerType, jobCron],
  );
  const form = useForm<ProactiveJobFormValues>({
    resolver: zodResolver(resolverSchema),
    values: defaultValues,
  });

  // 만들기는 아래 submitJob 에서 mutateAsync 로 기다려 결과를 처리한다 — mutate(...) 의 onSuccess/onError 는 화면이
  // 언마운트되면 실행되지 않지만 mutateAsync 의 promise 는 마운트와 무관하게 끝나, 이탈 뒤 늦게 온 성공·실패도 같은 처리가 돈다(WD-1109 반려 3, WD-1153).
  const createMutation = useCreateProactiveJob();
  // 목표 기반 작업은 섹션 구성을 리포트 양식으로 먼저 저장한 뒤 연결한다(WD-297). 작업 생성이 실패하면 방금 만든 양식을 지운다.
  const createTemplateMutation = useCreateProactiveTemplate();
  const deleteTemplateMutation = useDeleteProactiveTemplate();
  // 양식 저장을 기다리는 동안 isSaving 이 버튼에 반영되기 전 두 번째 클릭으로 양식·작업이 두 번 만들어지지 않게 막는다(#49 와 같은 이유).
  const isCreatingRef = useRef(false);
  const updateMutation = useUpdateProactiveJob();
  const fetchLatestJob = useFetchLatestProactiveJob();
  // 마지막 저장이 편집 충돌로 거부됐는가 — 참이면 EditConflictDialog 를 띄운다(WD-364)
  const [editConflict, setEditConflict] = useState(false);

  // 상세 ↔ 새 작업은 같은 라우트 요소라 이동해도 컴포넌트가 재사용돼 상태가 남는다 — 상세→뒤로→/jobs/new 에 「만들기」 없이
  // 읽기 모드로 남고, 새 작업 폼에서 앞으로 가면 편집·변경 사항 표시가 남는다. 라우트 정체('new' 또는 id)가 바뀐 렌더에서 화면 상태를 다시 잡는다.
  // id→id(복제 뒤 뒤로 가기 등)도 같은 이유로 포함한다. 복제 직후만 편집 모드여야 하므로 이동 state 의 startEditing 으로 구분한다.
  // key 로 페이지를 재마운트하지 않는 이유: 만들기 뒤 new→id 이동의 WD-415 「편집」 포커스(useEditModeFocus)를 지키기 위해서다 (WD-996)
  const routeKey = isNew ? 'new' : id;
  const [prevRouteKey, setPrevRouteKey] = useState(routeKey);
  // 이동 카운터 — 라우트가 바뀔 때마다 1 증가한다. 문자열(routeKey) 비교는 요청 중 A → /jobs/new → A 되돌아오기처럼
  // 같은 경로로 돌아오면 값이 다시 같아 가드가 무력화되지만, 카운터는 되돌아와도 계속 커져 시작 시점 값과 반드시 어긋난다 (WD-1042)
  const navSeqRef = useRef(0);
  // 라우트 정체가 이번 렌더에서 바뀌었는가 — 아래 파생 상태 재설정의 조건. 카운터(ref)는 렌더에서 건드리지 않는다.
  const routeChanged = prevRouteKey !== routeKey;
  if (routeChanged) {
    setPrevRouteKey(routeKey);
    setIsEditing(isNew || startEditingState);
    setIsDirty(false);
    setEditConflict(false);
    setLeaveDialogOpen(false);
    setCustomCronValue(null);
  }
  // 카운터 증가는 커밋 직후(useLayoutEffect)로 옮긴다 — 렌더 중 ref 쓰기는 버려진 렌더(뒤에 다시 그려지지 않을 렌더)에서도
  // 값이 올라가, 화면은 그대로인데 「멈춘 이동」으로 읽혀 저장 성공 뒤에도 편집 모드·미저장 표시가 남았다(WD-1109).
  // 의존 값은 routeChanged 가 아니라 routeKey 자체다 — routeChanged 는 위 렌더 중 setPrevRouteKey 재설정 때문에
  // 두 번째 렌더(실제 커밋되는 렌더)에선 항상 false 로 남아, [routeChanged] 로 두면 effect 가 한 번도 안 돌아
  // 카운터 축이 죽는다(왕복 가드 회귀로 확인됨). routeKey 는 두 렌더에서 같은 새 값이라 커밋의 의존 비교에 걸려
  // 라우트가 바뀔 때마다 한 번씩 오르고, 마운트 때 1 오른 것은 모든 판정이 시작 시점 값과 상대 비교라 무방하다.
  // 늦게 온 응답은 여전히 카운터 축 가드에 걸린다(WD-1042 두 축 구조 유지).
  useLayoutEffect(() => {
    navSeqRef.current += 1;
  }, [routeKey]);
  // 화면이 사라질 때도 떠남으로 센다 — 같은 경로로 돌아와도 옛 요청의 판정이 「떠남」으로 읽히게 한다(WD-1153).
  // 라우트 이동 증가와 같은 useLayoutEffect 로 두어 언마운트 커밋 즉시 반영한다.
  useLayoutEffect(() => {
    const seqRef = navSeqRef; // react-hooks/exhaustive-deps 가 정리 함수의 ref.current 읽기를 경고하므로 별칭 유지
    return () => {
      seqRef.current += 1;
    };
  }, []);
  /**
   * 요청을 시작한 시점(카운터·위치)과 지금을 비교해 그 사이 화면이 바뀌었는지 한 곳에서 판정한다.
   *
   * 카운터만 비교하면 같은 작업으로 왕복하는 동안 늦게 온 응답이 지금 화면을 덮고, 위치만 비교하면
   * 왕복 뒤 값이 다시 같아 가드가 무력화된다 — 두 축이 함께여야 가드가 선다(WD-996, WD-1042).
   * 위치 축은 경로(navLocationKey) 로만 비교한다 — 쿼리·해시(탭 전환)는 이 페이지 안의 일이라 떠남이 아니다(WD-1109 반려 1).
   */
  const movedAway = (seq: number, locationKey: string) =>
    navSeqRef.current !== seq || navLocationKey() !== locationKey;
  // 편집 시작 state(startEditing)는 복제 직후(WD-996)와 목록의 막힌 복제 토스트 「편집」 이동(WD-1026, 새로 마운트)에서 쓰이며,
  // 어느 쪽이든 한 번만 쓴다 — 기록에 남으면 저장·취소 뒤 뒤로→앞으로 갈 때 다시 편집 모드로 열린다.
  // 같은 경로로 replace 하므로 라우트 정체(routeKey)가 바뀌지 않아 위 다시 잡기는 돌지 않는다.
  // 편집 폼이 그려진 뒤 첫 칸으로 포커스를 한 번 옮겨야 하는지 — 처음부터 편집 모드로 마운트되거나 토스트 버튼에서 들어오면
  // useEditModeFocus(WD-415)가 건너뛰어 포커스가 body 로 떨어지므로 직접 옮긴다 (WD-1026)
  const pendingEditFocusRef = useRef(false);
  // 토스트 「편집」의 포커스 요청 횟수 — 이미 개요 탭에서 편집 중이라 다른 의존 값이 안 바뀌어도 아래 effect 가 다시 돌게 한다
  const [editFocusRequest, setEditFocusRequest] = useState(0);
  const hasJob = !!job;
  useEffect(() => {
    if (startEditingState) {
      pendingEditFocusRef.current = true;
      navigate(
        { pathname: location.pathname, search: location.search, hash: location.hash },
        { replace: true, state: null },
      );
    }
  }, [startEditingState, navigate, location.pathname, location.search, location.hash]);
  // 폼(개요 탭·불러온 작업·편집 모드)이 실제로 그려진 뒤에 한 번만 첫 편집 칸으로 포커스한다 — 로딩 중이면 다음 렌더에 다시 시도
  useEffect(() => {
    if (!pendingEditFocusRef.current) return;
    if (!isEditing || isNew || !hasJob || isLoading || activeTab !== 'overview') return;
    // 탭 내용이 막 마운트된 같은 커밋에서는 칸이 아직 포커스를 받지 못하므로(숨김 상태) 다음 프레임에 옮긴다.
    // 아직 못 옮겼으면 플래그를 남겨 다음 렌더에 다시 시도한다.
    const frame = requestAnimationFrame(() => {
      if (focusFirstEditField(editRegionRef.current)) pendingEditFocusRef.current = false;
    });
    return () => cancelAnimationFrame(frame);
    // 작업 객체 대신 유무만 본다 — 재조회로 객체가 바뀔 때마다 예약한 프레임을 취소·재예약하지 않게
  }, [isEditing, isNew, hasJob, isLoading, activeTab, editRegionRef, editFocusRequest]);
  // 최신 routeKey — 「다시 불러오기」 응답이 늦게 도착했을 때 그 사이 다른 작업으로 옮겨 갔는지 비교한다 (WD-996)
  const routeKeyRef = useRef(routeKey);
  routeKeyRef.current = routeKey;
  // 충돌 창의 「다시 불러오기」 진행 중 — 저장 중과 함께 창의 버튼을 막는다
  const [isReloading, setIsReloading] = useState(false);
  /**
   * 저장의 기준 버전(WD-364 낙관적 잠금) — 서버 값과 다르면 409 EDIT_CONFLICT. 서버 문자열 그대로 보낸다(Date 로 바꾸면 마이크로초가 잘린다).
   *
   * 기준은 위 `baseJob` 을 따른다 — 시드를 멈춘 동안(미저장 편집 중)에는 그 시점의 조회 결과로 고정되고,
   * 그 밖에는 최신 조회 값을 쓴다. 폼 내용(시드)과 기준이 한 쌍이어야, 편집 중 다른 곳에서 저장된 뒤 재조회가
   * 와도 내 저장이 409 없이 그 변경을 덮어쓰지 않는다(WD-1042 반려 — WD-364 무력화).
   *
   * 편집 중 재조회가 와도 기준이 따라가지 않는 대신, 그 사이 서버 값이 바뀌면 저장이 409 로 막혀 충돌 창으로
   * 간다. 그때 「다시 불러오기」는 최신 값으로 시드를 다시 받고, 「내 변경으로 덮어쓰기」는 기준을 빼고 보낸다.
   * 기준이 앞으로 가는 것은 저장 응답이 아니라 재조회뿐이다 — useUpdateProactiveJob 이 성공 응답의 updatedAt 을
   * 캐시에 먼저 반영하고, 그 성공이 isDirty 를 풀어 시드가 최신 조회 값을 다시 따르게 하기 때문이다(WD-364 재조회 전 재저장 대비).
   * 왕복 재편집으로 그 성공의 가드 판정이 걸려 isDirty 를 못 풀면(시드·기준은 멈춘 채로) 재저장은 첫 저장 이전
   * 버전을 든다 — 왕복 후 폼은 늘 첫 저장 이전 내용이라 그 재저장의 409 는 맞는 충돌이고, 충돌 창의
   * 「다시 불러오기」로 첫 저장 내용을 다시 받아 쓴다. 기준을 응답으로 밀면 재저장이 첫 저장을 충돌 없이
   * 되돌려 버린다(WD-1042 반려 3).
   *
   * 다른 편집 화면과 달리 편집 시작 시점에 무조건 고정하지 않는 이유: 폼이 없는 상태(초기 로드)나 미저장 상태에서는
   * 서버 값이 곧 기준이다 — 그렇게 두지 않으면 내 「끄기」나 폴링 뒤 저장이 자기 변경과 충돌한다.
   */
  const baseUpdatedAt = baseJob?.updatedAt ?? null;
  const deleteMutation = useDeleteProactiveJob();
  const disableMutation = useDisableProactiveJob();
  const executeMutation = useExecuteProactiveJob();
  const cloneMutation = useCloneProactiveJob();

  const handleTabChange = (tab: string) => {
    setSearchParams({ tab });
  };

  // 사이드바 메뉴 클릭(SPA 내부 네비게이션) · 브라우저 뒤로/앞으로 · 탭 닫기·새로고침 가드 (이슈 #640)
  // - 페이지 내 명시적 "뒤로가기"/"취소" 버튼은 아래 handleBackClick/handleCancelEdit이 별도로 처리한다
  //   (leaveAction으로 back/cancel-edit을 구분해야 하므로 이 공용 훅으로 대체할 수 없음)
  // - 이슈 #59에서 커버하던 beforeunload도 이 훅이 함께 처리하므로 페이지 자체의 중복 리스너는 제거한다
  const { dialog: unsavedChangesDialog } = useUnsavedChangesGuard(isDirty);

  // 뒤로가기 버튼 클릭 핸들러 — dirty면 다이얼로그 표시, 아니면 즉시 이동 (이슈 #59)
  const handleBackClick = () => {
    if (isDirty) {
      setLeaveAction('back');
      setLeaveDialogOpen(true);
    } else {
      navigate('/ai-insights/jobs');
    }
  };

  // 편집 취소 본 동작 — 폼 초기화 후 읽기 모드로 복귀 (이슈 #59)
  const performCancelEdit = useCallback(() => {
    if (job) {
      form.reset(buildDefaultValues(job));
    }
    setIsEditing(false);
    setIsDirty(false);
  }, [form, job]);

  // 취소 버튼 클릭 — dirty면 다이얼로그 표시, 아니면 즉시 취소 (이슈 #59)
  const handleCancelEdit = () => {
    if (isDirty) {
      setLeaveAction('cancel-edit');
      setLeaveDialogOpen(true);
    } else {
      performCancelEdit();
    }
  };

  // 다이얼로그에서 '이탈' 클릭 — leaveAction에 따라 분기 (이슈 #59)
  const handleLeaveConfirm = () => {
    setLeaveDialogOpen(false);
    setIsDirty(false);
    if (leaveAction === 'back') {
      navigate('/ai-insights/jobs');
    } else {
      performCancelEdit();
    }
  };


  // handleSubmit 을 렌더 중이 아니라 클릭 때 부른다 — 콜백이 isCreatingRef 를 읽기 때문이다(react-hooks/refs).
  /** force: 기준 버전을 보내지 않아 서버가 비교하지 않는다 — 편집 충돌 뒤 「내 변경으로 덮어쓰기」(WD-364) */
  const submitJob = (force: boolean) => (e?: React.BaseSyntheticEvent) => form.handleSubmit(async (values) => {
    // 숫자 칸 오류가 보이는 채로는 저장하지 않는다 (버튼 외 경로 — 충돌 창의 「내 변경으로 덮어쓰기」 — 방어).
    // 말없이 끝나지 않게 이유를 알리고 오류 칸으로 초점을 옮긴다.
    if (integerInvalid) {
      toast.error('모니터링 탭의 숫자 칸 오류를 고친 뒤 저장하세요.', { id: 'job-integer-invalid' });
      focusFirstInvalidInteger();
      return;
    }
    // 수정에서 저장값 그대로인 주기는 보내지 않는다 — 서버는 생략(null)이면 저장값을 그대로 둔다(WD-995).
    // 스키마가 이미 trim 한 값과 저장 원값을 그대로 비교하므로, 앞뒤 공백만 붙은 옛 주기는 잘린 값이 나가 정리된다.
    // 서버는 공백만 다른 값을 변경으로 보지 않아 감사에도 남지 않는다.
    // 충돌 뒤 덮어쓰기(force)는 늘 보낸다 — baseJob 은 미저장 편집 중에는 편집 기준 시점의 캐시라, 다른 탭이 주기·트리거를
    // 바꿨으면 빼고 보낼 때 사용자가 못 본 주기가 남거나 주기 없는 정기 트리거로 400 이 난다(WD-995 리뷰).
    // 화면에 보이는 그대로 덮어쓴다.
    // 비교 대상도 baseJob 이다 — 폼(시드)은 멈춘 시점의 주기를 들고 있으니, 서버 값과 비교하면 안 건드린 주기가
    // 바뀐 것으로 잘못 잡혀 남의 주기를 내 값으로 되돌린다(WD-1042 반려 — 기준 버전과 같은 쌍이어야 한다).
    const cronUnchanged = !force && !isNew && !!baseJob && (values.cronExpression ?? '') === (baseJob.cronExpression ?? '');
    const payload = {
      name: values.name,
      prompt: values.prompt,
      templateId: values.templateId ?? null,
      ...(cronUnchanged ? {} : { cronExpression: values.cronExpression ?? '' }),
      timezone: values.timezone,
      triggerType: values.triggerType,
      config: values.config,
    };

    if (isNew) {
      if (isCreatingRef.current) return;
      isCreatingRef.current = true;
      // 만들기 요청 시작 시점의 이동 카운터 — 늦게 온 성공은 이동을, 실패는 지금 폼의 칸 오류를 막는다.
      // 양식 저장(await) 앞에서 잡는다 — 그 대기 동안 화면이 바뀌어도 가드 창이 이미 시작돼 요청 전체가 가드에 들어가기 때문이다.
      // 콜백은 숫자만 클로저로 받고 비교 대상은 매번 navSeqRef.current 를 새로 읽는다(WD-1042)
      const startSeq = navSeqRef.current;
      // 카운터는 커밋이 끝나야 늘어나는데 react-router 의 갱신은 startTransition 안에 있어 그 사이 창이 남는다 —
      // window.location 은 history 를 건드리는 순간 바뀌므로 카운터와 함께 봐야 그 창까지 덮는다.
      // 비교 값은 경로만 — 탭 전환(?tab)은 떠남이 아니므로 세지 않는다(WD-1042 반려 재검, WD-1109 반려 1)
      const startPath = navLocationKey();
      // 목표 기반 구성이 있으면 먼저 「<작업 이름> 양식」으로 저장해 그 양식을 작업에 연결한다(WD-297, 사람 결정 A안).
      let goalTemplateId: number | null = null;
      if (values.generatedSections?.length) {
        try {
          // 양식 이름은 작업 이름에서 파생된 것이라 사용자가 정한 적이 없다 — 작업을 지워도 양식은 남아 같은 작업 이름을 다시 쓰면 겹친다(WD-824).
          // 409 DUPLICATE_NAME 이면 「<작업> 양식 (2)」 … 로 번호를 올려 다시 저장하고, 그 밖의 오류는 아래 catch 로 넘긴다.
          const sections = values.generatedSections;
          const template = await createWithNumberedName(
            (attempt) => numberedGoalTemplateName(values.name, attempt),
            (templateName) =>
              createTemplateMutation.mutateAsync({
                name: templateName,
                description: `목표 기반 작업 「${values.name}」의 질문으로 만든 양식`,
                sections,
              }),
            {
              // 첫 409 뒤 양식 이름을 한 번 읽어 이미 쓰인 번호를 건너뛴다(WD-837) — 읽기 실패는 순차 재시도로 이어진다
              loadExistingNames: proactiveApi.getAllTemplateNames,
              // 안내의 양식 이름은 엔진이 넘기는 1회차 저장 이름(200자 자르기 반영)을 그대로 쓴다
              // 소진은 사용자가 정한 이름이 아니라 아래 catch 의 토스트(handleApiError)로 할 일을 안내한다 — 이름 칸 오류로 보내지 않는다
              onExhausted: (firstName) =>
                new NameRetryExhaustedError(
                  `자동 양식 이름을 정하지 못했습니다. 쓰지 않는 「${firstName}」 양식을 지우거나 작업 이름을 바꾼 뒤 다시 저장하세요.`,
                ),
            },
          );
          goalTemplateId = template.id;
        } catch (err) {
          isCreatingRef.current = false;
          handleApiError(err, '리포트 양식 저장에 실패해 작업을 만들지 않았습니다.');
          return;
        }
      }
      // mutateAsync 의 promise 는 화면이 언마운트돼도 끝까지 이어져 이탈 뒤 도착한 결과도 아래에서 처리된다(WD-1153).
      // 성공 처리는 try 밖에 둔다 — 이동 같은 후속 처리의 예외를 이미 서버에서 만든 작업의 생성 실패로 오인하지 않기 위해서다.
      let created: ProactiveJob;
      try {
        created = await createMutation.mutateAsync(goalTemplateId == null ? payload : { ...payload, templateId: goalTemplateId });
      } catch (err) {
        // 서버가 거절한 경우에만 작업 없이 남는 양식을 지운다(삭제 실패는 사용자 오류 안내가 먼저라 조용히 넘긴다).
        // 응답 없는 실패(시간 초과·연결 끊김)는 서버가 작업을 이미 만들었을 수 있어 지우지 않는다 — 지우면 그 작업의 양식이 끊긴다(ON DELETE SET NULL).
        if (goalTemplateId != null && axios.isAxiosError(err) && err.response) deleteTemplateMutation.mutate(goalTemplateId);
        // 이동 뒤에도 서버 사유(400·403)를 잃지 않는다 — 칸 오류는 이미 다른 화면이라 붙이지 않고 토스트만 남긴다(WD-1109)
        if (movedAway(startSeq, startPath)) {
          // 이름 중복은 서버 문구(「이미 있는 … 다른 이름을 입력하세요」)가 이미 떠난 칸을 가리키는 말이라 맥락이 없다 —
          // 보낸 이름을 넣어 어떤 작업이 안 만들어졌는지 알려 준다(WD-1153)
          if (isDuplicateName(err)) {
            toast.error(`「${payload.name}」 작업을 만들지 못했습니다 — 같은 이름의 작업이 이미 있습니다.`);
          } else {
            handleApiError(err, '작업 생성에 실패했습니다.');
          }
          return;
        }
        // 이름 중복 409(WD-824)는 서버 문구를 이름 칸 오류로 붙이고 토스트는 생략한다
        if (applyDuplicateNameError(form.setError, err)) return;
        handleApiError(err, '작업 생성에 실패했습니다.');
        return;
      } finally {
        // 만드는 일 한 번의 끝에서 중복 클릭 가드만 푼다
        isCreatingRef.current = false;
      }
      // 요청 중 다른 화면(예: 작업 상세)으로 옮겨 갔으면 새 작업 상세로 끌고 가지 않는다 (WD-1042)
      if (movedAway(startSeq, startPath)) {
        // 떠난 화면에서는 어떤 작업이 만들어졌는지 서버가 돌려준 이름으로 알린다 — 알림은 글로벌이라 화면을 덮지 않는다(WD-1153)
        toast.success(`「${created.name}」 작업이 생성되었습니다.`);
        return;
      }
      toast.success('작업이 생성되었습니다.');
      // 저장 성공 → dirty 해제 후 신규 작업 상세로 이동 (이슈 #59)
      setIsDirty(false);
      setIsEditing(false);
      navigate(`/ai-insights/jobs/${created.id}`);
    } else {
      // 「기본 (선택 안 함)」을 고르면 templateId 가 null 이 되는데, 서버는 null 을 "바꾸지 않음"으로 받는다.
      // 해제 의도를 clearTemplate 로 함께 보내야 옛 템플릿이 남지 않는다 (WD-136).
      // Promise 를 돌려 충돌 창이 저장 끝까지 잠기게 하고(WD-678), 거부는 콜백이 처리하므로 삼킨다(이 화면은 isSubmitSuccessful 을 읽지 않는다).
      // 저장 요청 중 다른 작업·새 작업으로 옮겨 가면 늦게 온 응답이 새 화면의 편집 상태를 덮지 않게, 요청 시작 시점의
      // 이동 카운터를 기억한다 — 같은 작업으로 왕복해도 카운터는 계속 커져 가드가 풀리지 않는다 (WD-996, WD-1042).
      // 카운터는 커밋이 끝나야 늘어나고 react-router 의 갱신은 startTransition 안이라 그 사이 창이 남기에,
      // history 를 건드리는 순간 바뀌는 window.location 을 카운터와 함께 비교한다 — 값은 경로만(WD-1042 반려 재검,
      // WD-1109 반려 1 — 탭 전환은 떠남이 아니다)
      const savedSeq = navSeqRef.current;
      const savedPath = navLocationKey();
      return updateMutation.mutateAsync(
        {
          id: jobId,
          data: {
            ...payload,
            clearTemplate: payload.templateId == null,
            // 편집 기준 버전(WD-364) — 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다
            ...(force ? {} : { expectedUpdatedAt: baseUpdatedAt }),
          },
        },
        {
          onSuccess: () => {
            // 알림은 글로벌이라 화면을 덮지 않는다 — 가드 판정과 무관하게 띄운다
            toast.success('작업이 저장되었습니다.');
            // 시드 기준 버전은 여기서 앞으로 밀지 않는다 — 왕복 재편집으로 아래 가드에 걸려 isDirty 를 못 풀면
            // 폼은 늘 첫 저장 이전 내용이라, 그 위 재저장은 서버(첫 저장 반영)와 버전이 달라야 409 로 막힌다.
            // 버전만 내 응답으로 밀면 재저장이 첫 저장 내용을 충돌 없이 되돌려 버린다(WD-1042 반려 3).
            // 정상 경로(가드가 풀린 저장)의 전진은 성공이 isDirty 를 풀어 시드가 최신 조회 값을 다시 따를 때 이뤄진다.
            // 그 사이 화면을 옮겼으면 새 화면의 충돌 창·편집 상태를 바꾸지 않는다 (WD-996, WD-1042)
            if (movedAway(savedSeq, savedPath)) return;
            // 저장 성공 → 충돌 창 닫고 dirty 해제 후 읽기 모드로 복귀 (이슈 #59)
            setEditConflict(false);
            setIsDirty(false);
            setIsEditing(false);
          },
          onError: (err) => {
            // 그 사이 화면을 옮겼으면 충돌 창·칸 오류를 새 화면에 띄우지 않고 실패만 알린다 (WD-996, WD-1042)
            if (movedAway(savedSeq, savedPath)) {
              handleApiError(err, '작업 저장에 실패했습니다.');
              return;
            }
            // 편집 충돌은 토스트 대신 안내 창으로 — 사용자가 다시 불러올지 덮어쓸지 고른다(WD-364)
            if (isEditConflict(err)) {
              setEditConflict(true);
              return;
            }
            if (applyDuplicateNameError(form.setError, err)) return;
            handleApiError(err, '작업 저장에 실패했습니다.');
          },
        },
      ).catch(() => undefined);
    }
  })(e);
  const handleSave = submitJob(false);

  /**
   * 편집 충돌의 「다시 불러오기」(WD-364) — 최신 작업을 서버에서 새로 받아 폼을 그것으로 바꾸고 내 변경을 버린다.
   * 캐시 갱신만으로는 폼 필드가 같을 때 폼이 다시 채워지지 않아(내 변경이 남는다) 직접 reset 한다. 편집 모드는 유지한다.
   */
  const handleReloadLatest = async () => {
    if (!jobId) return;
    setIsReloading(true);
    const requestedSeq = navSeqRef.current;
    // 위치 축도 경로만 — 「다시 불러오기」는 이 페이지 안의 일이라 탭이 바뀌어도 결과를 반영한다(WD-1109 반려 1)
    const requestedPath = navLocationKey();
    try {
      const latest = await fetchLatestJob(jobId);
      // 요청 중 다른 작업·새 작업으로 옮겨 갔다면 도착한 응답으로 폼을 덮지 않는다 (WD-996, WD-1042)
      if (movedAway(requestedSeq, requestedPath)) return;
      form.reset(buildDefaultValues(latest));
      // 내 변경을 버렸으니 「직접 입력」 선택도 버린다 — 최신 값이 내가 친 값과 같아도 저장된 주기대로 보인다(WD-962)
      setCustomCronValue(null);
      setIsDirty(false);
      setEditConflict(false);
    } catch (err) {
      handleApiError(err, '최신 내용을 불러오지 못했습니다.');
    } finally {
      setIsReloading(false);
    }
  };

  const handleDelete = () => {
    if (!job) return;
    deleteMutation.mutate(jobId, {
      onSuccess: () => {
        toast.success('작업이 삭제되었습니다.');
        navigate('/ai-insights/jobs');
      },
      onError: (err) => handleApiError(err, '작업 삭제에 실패했습니다.'),
    });
  };

  // 끄기 — 쓰기 권한이 없는 작성자도 쓸 수 있는 끄기 전용 API (WD-192). 켜기는 편집(쓰기 권한)으로만 한다.
  const handleDisable = () => {
    if (!job) return;
    disableMutation.mutate(jobId, {
      onSuccess: () => toast.success('작업이 비활성화되었습니다.'),
      onError: (err) => handleApiError(err, '작업을 끄지 못했습니다.'),
    });
  };

  const handleExecute = () => {
    if (!job) return;
    executeMutation.mutate(jobId, {
      onSuccess: () => {
        toast.success(`"${job.name}" 실행이 시작되었습니다.`, {
          action: {
            label: '결과 보기',
            onClick: () => {
              setSearchParams({ tab: 'executions' });
            },
          },
        });
      },
      onError: (err) => {
        // 이미 실행 중(409)이면 목록 화면과 같은 안내에 「실행 이력」 탭 바로가기를 붙인다(WD-368)
        if (isJobAlreadyRunning(err)) {
          toast.error(extractApiError(err, '이미 실행 중인 작업입니다.'), {
            action: {
              label: '실행 이력',
              onClick: () => setSearchParams({ tab: 'executions' }),
            },
          });
          return;
        }
        handleApiError(err, '실행에 실패했습니다.');
      },
    });
  };

  const handleClone = () => {
    if (!job) return;
    // 주기 없는 작업은 서버가 400 으로 거절하므로 요청 전에 이유를 알리고, 「편집」으로 그 자리에서 편집 모드를 연다(WD-1026)
    const blockedReason = cloneBlockedReason(job);
    if (blockedReason) {
      // 토스트가 떠 있는 동안 다른 작업으로 옮겼을 수 있으므로 띄운 시점의 라우트를 기억해 비교한다
      const sourceKey = routeKeyRef.current;
      const sourceId = job.id;
      toast.error(blockedReason, {
        // 같은 id 로 다시 누르면 같은 토스트를 갱신해 겹겹이 쌓이지 않게 한다
        id: `clone-blocked-${sourceId}`,
        action: {
          label: '편집',
          onClick: () => {
            if (routeKeyRef.current !== sourceKey) {
              // 다른 작업으로 옮겨 갔으면 현재 화면을 편집하지 않고 원본 상세를 편집 모드로 연다
              navigate(`/ai-insights/jobs/${sourceId}?tab=overview`, { state: { startEditing: true } });
              return;
            }
            // 주기 칸은 개요 탭에 있으므로 다른 탭(실행 이력 등)에서 눌렀어도 개요로 옮기고, 첫 칸 포커스를 요청한다
            pendingEditFocusRef.current = true;
            setEditFocusRequest((n) => n + 1);
            if (activeTab !== 'overview') handleTabChange('overview');
            setIsEditing(true);
          },
        },
      });
      return;
    }
    cloneMutation.mutate(job, {
      onSuccess: (created) => {
        toast.success(cloneSuccessMessage(created.name));
        // 편집 모드 시작은 이동 state 로 넘긴다 — 라우트가 바뀐 렌더의 다시 잡기가 setIsEditing 을 덮어쓰기 때문 (WD-996)
        navigate(`/ai-insights/jobs/${created.id}?tab=overview`, { state: { startEditing: true } });
      },
      onError: (err) => handleApiError(err, '작업 복제에 실패했습니다.'),
    });
  };

  // 저장 진행 표시는 화면 종류로 나눈다 — 버튼 라벨(isNew ? '만들기' : '저장')과 같은 축이다 (WD-1042 검토 반려 1·3)
  // · 수정 화면(isNew 아님): updateMutation 이 끝날 때까지 라우트와 상관없이 잠긴다. 저장 시작 카운터와 같을 때만
  //   잠그면 A 저장 중 A → /jobs/new → A 되돌아오기나 B 이동에서 잠금이 풀려 두 번째 PUT 이 나가
  //   내 저장끼리 409 충돌 창을 냈다 — 고치기 전엔 잠겨 있던 것이라 회귀다(반려 1·2).
  // · 새 작업 화면(isNew): 만들기(create)·양식 저장(createTemplate)이 pending 인 동안에만 잠긴다. 작업 상세에서 편집을
  //   시작해도 남의 만들기 진행으로 「저장 중...」에 잠기지 않게 하는 거울 결함 방지다(반려 3).
  const isSaving = isNew
    ? createMutation.isPending || createTemplateMutation.isPending
    : updateMutation.isPending;
  // 더블클릭 중복 실행 방지용 ref — disabled 상태 전파 지연 대응 (#49)
  const isExecutingRef = useRef(false);

  const handleExecuteSafe = () => {
    if (isExecutingRef.current) return;
    isExecutingRef.current = true;
    handleExecute();
    setTimeout(() => { isExecutingRef.current = false; }, 1000);
  };

  // 새 작업 화면은 권한을 알기 전까지 폼을 그리지 않는다 — 조회 전용 사용자에게 폼이 잠깐 비쳤다 사라지지 않게 한다.
  if ((!isNew && isLoading) || (isNew && isPermissionsLoading)) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-64 bg-muted animate-pulse rounded" />
        <div className="h-96 bg-muted animate-pulse rounded" />
      </div>
    );
  }

  // 조회 권한이 없으면(403) 「작업을 찾을 수 없습니다」는 거짓 안내다 — 작업은 있는데 볼 권한이 없을 뿐이다 (WD-137).
  // 아래 not-found 분기보다 먼저 보고, 편집·삭제 버튼과 쓰기 안내 없이 권한 안내만 보인다.
  if (!isNew && isError && isForbiddenError(error)) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/ai-insights/jobs')} aria-label="목록으로">
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <PageHeading>스마트 작업</PageHeading>
        </div>
        <ProactiveReadForbiddenNotice subject="스마트 작업" testId="job-forbidden" />
      </div>
    );
  }

  // 존재하지 않는 작업 ID 접근 시 에러 상태 처리 (#47)
  // NaN 등 비숫자 ID는 쿼리가 아예 실행되지 않아 isError가 true가 되지 않으므로
  // isInvalidId를 함께 확인한다 (#545)
  if (!isNew && (isError || isInvalidId)) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/ai-insights/jobs')} aria-label="목록으로">
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <PageHeading>작업을 찾을 수 없습니다</PageHeading>
        </div>
        <p className="text-muted-foreground text-sm">요청하신 작업이 존재하지 않거나 삭제되었습니다.</p>
        <Button variant="outline" size="sm" onClick={() => navigate('/ai-insights/jobs')}>
          <ArrowLeft className="h-4 w-4" />
          목록으로 돌아가기
        </Button>
      </div>
    );
  }

  // 쓰기 권한 없이 /ai-insights/jobs/new 로 직접 들어오면 채워도 저장할 수 없는 폼 대신 안내만 보인다(WD-133).
  if (isNew && !canWrite) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/ai-insights/jobs')} aria-label="목록으로">
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <PageHeading>새 스마트 작업</PageHeading>
        </div>
        <ProactiveWriteNotice subject="스마트 작업" testId="job-readonly-notice" />
        <Button variant="outline" size="sm" onClick={() => navigate('/ai-insights/jobs')}>
          <ArrowLeft className="h-4 w-4" />
          목록으로 돌아가기
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* 탭 제목에 서버에서 불러온 저장된 이름을 붙인다 (WD-534) */}
      <DetailPageTitle name={job?.name} />
      {/* 헤더 — 좁은 화면에서 제목과 버튼 줄이 한 줄에 다 안 들어가면 버튼 줄이 아래로 내려가고, 버튼끼리도 줄바꿈해
          본문이 옆으로 밀리지 않게 한다(WD-295). */}
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={handleBackClick}
            aria-label="목록으로"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div className="min-w-0">
            <PageHeading className="break-words">
              {isNew ? '새 스마트 작업' : (job?.name ?? '-')}
            </PageHeading>
            <div className="flex flex-wrap items-center gap-2 mt-1">
              {!isNew && job && (
                <Badge variant={job.enabled ? 'default' : 'secondary'}>
                  {job.enabled ? '활성' : '비활성'}
                </Badge>
              )}
              {/* 「활성」이어도 작성자 자격이 없으면 실행 때마다 건너뛴다 — 정상처럼 보이지 않게 배지로 알린다 (WD-192) */}
              {!isNew && job?.ownerEligible === false && (
                <OwnerIneligibleBadge reason={job.ownerIneligibleReason} variant="badge" />
              )}
              {/* 미저장 변경사항 표시 — #56/#57/#58과 동일 시각 패턴 (이슈 #59) */}
              {isDirty && (
                <span className="text-xs text-muted-foreground flex items-center gap-1">
                  <span className="text-muted-foreground">●</span>
                  미저장 변경사항
                </span>
              )}
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {/* 복제(POST)·지금 실행·편집(PUT)·삭제(DELETE)는 모두 쓰기 권한이 있을 때만 보인다(WD-133) */}
          {!isNew && !isEditing && canWrite && (
            <>
              <Button
                variant="outline"
                size="sm"
                onClick={handleClone}
                disabled={cloneMutation.isPending}
              >
                <Copy className="h-3.5 w-3.5" />
                복제
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={handleExecuteSafe}
                disabled={executeMutation.isPending}
              >
                지금 실행
              </Button>
              <Button ref={editButtonRef} variant="outline" size="sm" onClick={() => setIsEditing(true)}>
                편집
              </Button>
              {/* 삭제 확인 다이얼로그 (#37) */}
              <DeleteConfirmDialog
                entityName="스마트 작업"
                itemName={job?.name ?? ''}
                onConfirm={handleDelete}
                trigger={
                  <Button
                    variant="destructive"
                    size="sm"
                    disabled={deleteMutation.isPending}
                  >
                    삭제
                  </Button>
                }
              />
            </>
          )}
          {/* 쓰기 권한이 없는 작성자에게는 끄기·삭제만 보인다 (WD-192) — 권한을 잃은 뒤에도 자기 작업을 정리할 수 있게.
              서버는 끄기·삭제를 읽기 권한으로 허용하고 편집·켜기·복제·지금 실행은 여전히 쓰기 권한을 요구한다. */}
          {!isNew && !isEditing && !isPermissionsLoading && !canWrite && job && (
            <>
              {job.enabled && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleDisable}
                  disabled={disableMutation.isPending}
                >
                  끄기
                </Button>
              )}
              <DeleteConfirmDialog
                entityName="스마트 작업"
                itemName={job.name}
                onConfirm={handleDelete}
                trigger={
                  <Button variant="destructive" size="sm" disabled={deleteMutation.isPending}>
                    삭제
                  </Button>
                }
              />
            </>
          )}
          {isEditing && (
            <>
              {!isNew && (
                <Button variant="outline" size="sm" onClick={handleCancelEdit}>
                  취소
                </Button>
              )}
              {/* 폼이 유효하지 않으면(필수 필드 미입력 등) 저장/생성 버튼 비활성화 — #52 */}
              {/* 비활성 버튼은 title 툴팁이 뜨지 않으므로 이유를 버튼 옆에 보이는 문구로 둔다 */}
              {integerInvalid && (
                <span id="job-integer-invalid-hint" role="status" className="text-xs text-destructive">
                  모니터링 탭의 숫자 칸 오류를 고치세요
                </span>
              )}
              <Button
                size="sm"
                onClick={handleSave}
                disabled={isSaving || !form.formState.isValid || integerInvalid}
                aria-describedby={integerInvalid ? 'job-integer-invalid-hint' : undefined}
              >
                {isSaving ? (isNew ? '만드는 중...' : '저장 중...') : isNew ? '만들기' : '저장'}
              </Button>
            </>
          )}
        </div>
      </header>

      {/* 조회만 가능한 사용자에게 버튼이 없는 이유를 알린다 — 권한을 불러오는 중에는 깜빡이지 않게 숨긴다. */}
      {!isPermissionsLoading && !canWrite && (
        <ProactiveWriteNotice subject="스마트 작업" testId="job-readonly-notice" />
      )}

      {/* 탭 */}
      {/* 편집 폼 영역 — 지금 보이는 탭의 첫 편집 칸으로 포커스를 옮긴다(WD-415) */}
      <Tabs
        ref={(el) => {
          editRegionRef.current = el;
        }}
        value={activeTab}
        onValueChange={handleTabChange}
      >
        <TabsList>
          {/* 탭을 추가하면 위 NEW_JOB_TABS/JOB_TABS 에도 넣어야 한다 — 빠지면 그 탭으로 가는 링크가 「개요」로 보정된다 */}
          <TabsTrigger value="overview">개요</TabsTrigger>
          <TabsTrigger value="monitoring">
            <Activity className="h-3.5 w-3.5" />
            모니터링
          </TabsTrigger>
          {!isNew && <TabsTrigger value="executions">실행 이력</TabsTrigger>}
        </TabsList>

        <TabsContent value="overview">
          {/* key: 라우트가 바뀌면 탭 안 상태(생성 모드·목표 문구 등)를 새로 시작한다. 페이지는 재마운트하지 않는다 (WD-996) */}
          <JobOverviewTab
            key={routeKey}
            job={job}
            // 검증 스키마(resolverSchema)와 같은 기준 — 폼 시드와 같은 멈춘 값(WD-1109 반려 2)
            baseJob={baseJob}
            isNew={isNew}
            isEditing={isEditing}
            form={form}
            templates={templates}
            onChange={markDirty}
            customCronValue={customCronValue}
            onCustomCronValueChange={setCustomCronValue}
          />
        </TabsContent>

        {/* forceMount: 다른 탭으로 가도 모니터링 탭을 언마운트하지 않는다. 언마운트하면 오류 칸의 무효 상태가 풀려
            오류가 남은 채 마지막 올바른 값이 저장된다(WD-906 후속).
            Radix 는 forceMount 패널에 hidden 을 주지 않으므로, 비활성일 때 data-[state=inactive]:hidden 으로 화면·접근성 트리에서 숨긴다
            (안 그러면 개요·실행 이력 탭에도 모니터링 내용이 함께 보였다 — 반려 지적). */}
        <TabsContent value="monitoring" forceMount className="data-[state=inactive]:hidden">
          <JobMonitoringTab
            key={routeKey}
            form={form}
            isEditing={isEditing}
            jobId={jobId}
            onChange={markDirty}
            onIntegerValidityChange={handleIntegerValidity}
          />
        </TabsContent>

        {!isNew && (
          <TabsContent value="executions">
            {/* key: 작업이 바뀌면 탭 상태(더 보기 실패 안내 등)를 새로 시작한다 — A 작업의 실패 안내가 B 에 남지 않게 (WD-956 리뷰 후속) */}
            <JobExecutionsTab key={jobId} jobId={jobId} timezone={job?.timezone} />
          </TabsContent>
        )}
      </Tabs>

      {/* 사이드바 링크·브라우저 뒤로가기·탭 닫기 이탈 가드 다이얼로그 (#640) — useUnsavedChangesGuard가 렌더 */}
      {unsavedChangesDialog}

      {/*
        이탈 확인 다이얼로그 — 뒤로가기/취소 클릭 시 dirty면 표시 (이슈 #59)
        취소 시 머무름, 확인 시 변경사항 버리고 이탈/취소 동작 실행
      */}
      {/* 편집 충돌 안내 창(WD-364) — 저장이 409 EDIT_CONFLICT 로 거부되면 뜬다 */}
      <EditConflictDialog
        open={editConflict}
        onOpenChange={(open) => {
          if (!open) setEditConflict(false);
        }}
        resourceLabel="작업"
        onReload={handleReloadLatest}
        onOverwrite={() => submitJob(true)()}
        pending={isSaving || isReloading}
      />
      <AlertDialog open={leaveDialogOpen} onOpenChange={setLeaveDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>저장하지 않은 변경사항</AlertDialogTitle>
            <AlertDialogDescription>
              저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleLeaveConfirm}>이탈</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
