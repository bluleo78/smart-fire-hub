/**
 * ExecutionDetailPage — 프로액티브 작업의 개별 실행 상세 페이지
 *
 * URL: /ai-insights/jobs/:jobId/executions/:executionId
 *
 * 실행 상태(RUNNING/COMPLETED/FAILED)에 따라 다른 UI를 렌더링한다.
 * - RUNNING: 스피너 + 폴링 안내 문구
 * - FAILED: 사유 분류 카드 (classifyExecutionOutcome) — 의도된 건너뜀은 중립 톤 「건너뜀」 카드
 * - COMPLETED: 요약(마크다운) + HTML 리포트(iframe) 또는 마크다운 섹션 폴백
 */

import { AlertTriangle, ArrowLeft, FileDown, Loader2, Printer } from 'lucide-react';
import { useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import { useParams } from 'react-router-dom';
import remarkGfm from 'remark-gfm';

import { getNumberCheck } from '@/api/proactive';
import ReportIframe from '@/components/ai/ReportIframe';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { InlineBanner } from '@/components/ui/inline-banner';
import { SecurityLevelBadge } from '@/components/ui/SecurityLevelBadge';
import { useExecution, useExecutionHtml, useProactiveJobTimezone } from '@/hooks/queries/useProactiveMessages';
import { useJobGoBack } from '@/hooks/useGoBack';
import { useReportActions } from '@/hooks/useReportActions';
import { isForbiddenError, isNotFoundError, isReportLevelBlocked } from '@/lib/api-error';
import { classifyExecutionOutcome, getExecutionDisplayStatus } from '@/lib/error-classifier';
import { formatDuration, getStatusBadgeVariant, getStatusLabel, timeAgo } from '@/lib/formatters';
import { formatExecutionStartInZone } from '@/lib/next-run';
import { EXECUTION_NOT_FOUND_NOTICE, getSections, reportBodyState } from '@/lib/proactive-utils';
import { parseRouteId } from '@/lib/route-id';

import { BackButton } from './components/BackButton';
import { ProactiveReadForbiddenNotice } from './components/ProactiveReadForbiddenNotice';
import { ReportLevelBlockedNotice } from './components/ReportLevelBlockedNotice';

/** 마크다운 렌더링용 prose 클래스 */
const PROSE_CLASSES = 'prose prose-sm dark:prose-invert max-w-none';
const REMARK_PLUGINS = [remarkGfm];

/**
 * 「실행 정보를 찾을 수 없습니다」 안내 + 돌아가기 — 잘못된 주소 id(WD-652)와 조회 결과 없음(404 등)이 같은 화면을 쓴다.
 * message 를 비우면 기본 문구를 보인다.
 */
function ExecutionNotFound({ message, onBack }: { message?: string; onBack: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center h-64 gap-4 text-muted-foreground">
      <p className="text-sm text-center break-keep px-4" data-testid="execution-not-found">
        {message ?? '실행 정보를 찾을 수 없습니다.'}
      </p>
      <BackButton onBack={onBack} />
    </div>
  );
}

/**
 * 주소 id 를 읽어 유효할 때만 상세 화면을 그린다.
 * 주소 id 는 숫자만 받는다(WD-628) — `Number('1e1')` 은 10 이라 다른 실행을 열고 `1.5`·` 7` 도 그대로 조회했다.
 * 잘못된 id 를 0 으로 바꿔 넘기면 조회 훅의 enabled 조건에만 기대게 되므로(WD-652), 여기서 명시적으로 갈라
 * 안쪽 화면(조회·PDF·인쇄 훅)은 아예 그리지 않는다 — 잘못된 id 로는 어떤 API 도 부를 수 없다.
 */
export default function ExecutionDetailPage() {
  const { jobId, executionId } = useParams<{ jobId: string; executionId: string }>();
  const jobIdNum = parseRouteId(jobId);
  const executionIdNum = parseRouteId(executionId);
  if (jobIdNum == null || executionIdNum == null) {
    return <InvalidExecutionId jobIdNum={jobIdNum} />;
  }
  return <ExecutionDetailContent jobIdNum={jobIdNum} executionIdNum={executionIdNum} />;
}

/**
 * 주소 id 가 잘못된 실행 상세 — 조회하지 않으므로 작업을 읽을 수 있는지 모른다.
 * 리포트 보기와 같은 규칙(useJobGoBack, WD-671)으로 작업 id 가 잘못되면 작업 목록, 아니면 리포트 목록으로 보낸다.
 */
function InvalidExecutionId({ jobIdNum }: { jobIdNum: number | null | undefined }) {
  const { goBack } = useJobGoBack(jobIdNum, false);
  return <ExecutionNotFound onBack={goBack} />;
}

/** 실행 상세 본문 — 검증된 양의 정수 id 를 받는다 */
function ExecutionDetailContent({ jobIdNum, executionIdNum }: { jobIdNum: number; executionIdNum: number }) {
  const iframeRef = useRef<HTMLIFrameElement>(null);

  // 실행 데이터 조회 — RUNNING 상태일 때 5초 폴링
  const { data: execution, isLoading, isError, error } = useExecution(jobIdNum, executionIdNum);
  // WD-1175: 「실행 시각」을 실행 이력과 같은 작업 시간대+약어로 보이려고 작업을 읽는다(이력 탭에서 들어오면 같은 캐시).
  const { data: jobTimezone, isPending: jobPending, isError: jobError } = useProactiveJobTimezone(jobIdNum);

  // 돌아갈 기록이 없을 때의 목적지는 리포트 보기와 같은 규칙이다(WD-671, jobBackFallback) — 실행 조회가 성공했으면
  // 작업을 읽을 수 있으니 작업 상세, 403·404·다른 오류면 리포트 목록. 새 탭으로 연 삭제된 작업·남의 작업의 실행이
  // 작업 상세의 오류 화면으로 돌아가지 않게 한다. 데이터 유무로 판단해 폴링 중 잠깐 실패해도 목적지가 뒤집히지 않는다.
  // 뒤로 가기는 여기서 한 번만 만들어 안내 화면·본문의 버튼에 내려준다(WD-667).
  const { goBack } = useJobGoBack(jobIdNum, execution !== undefined);

  // HTML 리포트 조회 — COMPLETED 상태일 때 활성화. 리포트 보기·모달과 같은 훅·캐시를 쓴다(WD-661)
  // result 가 없어도 조회한다(WD-1098) — 서버의 400 「이 실행에는 리포트 결과가 없습니다.」를 받아 빈 영역 대신 안내를 보인다.
  const { data: htmlResponse, error: htmlError, isPending: htmlPending } = useExecutionHtml(jobIdNum, executionIdNum, {
    enabled: execution?.status === 'COMPLETED',
  });

  const rawHtml = htmlResponse?.data ?? null;

  // PDF 다운로드 + 인쇄 공통 훅
  const { handleDownloadPdf, handlePrint, downloading } = useReportActions({
    jobId: jobIdNum,
    executionId: executionIdNum,
    iframeRef,
  });

  // 로딩 상태
  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64 gap-2 text-muted-foreground">
        <Loader2 className="h-5 w-5 animate-spin" />
        <span className="text-sm">실행 정보를 불러오는 중...</span>
      </div>
    );
  }

  // 리포트 등급이 작성자의 현재 보안 등급보다 높으면(403 + 코드) 권한 요청 안내가 아니라 등급 안내를 보인다 (WD-198).
  if (isError && isReportLevelBlocked(error)) {
    return (
      <div className="flex flex-col items-center gap-2">
        <ReportLevelBlockedNotice testId="execution-level-blocked" />
        <BackButton onBack={goBack} />
      </div>
    );
  }

  // 조회 권한이 없으면(403) 「실행 정보를 찾을 수 없습니다」 대신 권한 안내를 보인다 — 실행은 있는데 볼 권한이 없을 뿐이다 (WD-137).
  if (isError && isForbiddenError(error)) {
    return (
      <div className="flex flex-col items-center gap-2">
        <ProactiveReadForbiddenNotice subject="실행 결과" testId="execution-forbidden" />
        <BackButton onBack={goBack} />
      </div>
    );
  }

  // 실행 데이터 없음 — 404 는 「실행이 없음」과 「작업 작성자가 아님」을 똑같이 숨기므로 두 경우를 함께 말한다 (WD-162)
  // 숫자만이 아닌 주소 id(WD-628)는 바깥 ExecutionDetailPage 가 먼저 같은 안내로 돌려보낸다(WD-652)
  if (!execution) {
    return (
      <ExecutionNotFound
        message={isError && isNotFoundError(error) ? EXECUTION_NOT_FOUND_NOTICE : undefined}
        onBack={goBack}
      />
    );
  }

  // COMPLETED 상태의 요약 텍스트 추출
  const summary = execution.status === 'COMPLETED' && execution.result
    ? (execution.result.summary as string | undefined)
    : undefined;

  // 수치 출처 검증 결과(WD-767) — 검증을 마쳤고(checked) 미확인 수치가 있을 때만 값을 남겨 경고를 보인다.
  // 옛 실행은 필드가 없어 null 이므로 배너가 뜨지 않는다.
  const checkResult = execution.status === 'COMPLETED' ? getNumberCheck(execution.result) : null;
  const numberCheck = checkResult?.checked && checkResult.unverifiedTotal > 0 ? checkResult : null;

  // 마크다운 섹션 폴백용 — 「폴백 내용이 있는가」만 보고 HTML 유무·오류는 섞지 않는다(WD-1177).
  // html > 폴백 우선순위는 reportBodyState 가 판정하므로 여기서 rawHtml 조건을 뺀다.
  const sections = execution.status === 'COMPLETED' && execution.result
    ? getSections(execution.result)
    : [];

  // 리포트 본문 표시 상태를 한 곳에서 판정한다 (WD-1177) — 등급 차단 403 → 권한 403 → 내용(html > 폴백 > 문구).
  // 403(등급 차단·권한)이 캐시된 HTML·요약보다 앞서게 해, 한 화면에서 한 상태만 보이게 한다.
  // 비완료 실행은 HTML 조회가 켜지지 않으므로 본문 상태를 정하지 않는다 — 스피너 아래에 안내 카드가 뜨지 않게.
  // HTML 조회가 끝나기 전(로딩 중·조회 꺼짐)에도 정하지 않는다 — 오류도 본문도 없는 시점에
  // reportBodyState 가 「리포트가 없습니다」 를 내어, 아직 안 나온 결과를 없다고 단정하는 카드가 잠깐 떴다(WD-1177 리뷰).
  const bodyState = execution.status === 'COMPLETED' && !htmlPending
    ? reportBodyState(htmlError, rawHtml, sections.length > 0)
    : null;

  // 차단 403(등급 차단·권한) 여부 — 요약 카드와 수치 출처 경고 배너를 함께 감춘다(WD-1177 「안내만」).
  // 둘 다 result 에서 나온 리포트 내용이라, 열 수 없다는 안내만 남기고 403 앞에서 감춰야 한다.
  const isBlocked403 = bodyState?.state === 'level-blocked' || bodyState?.state === 'forbidden';

  // 실패·건너뜀 카드 분류 (WD-191) — 서버는 의도된 건너뜀(작업 주인 정지·권한 없음·워크스페이스 정지)도 FAILED 로 남기므로
  // 사유 문구로 가려낸다. 건너뜀이면 상태 뱃지도 「실패」(빨강) 대신 「건너뜀」(중립)으로 보여 카드와 어긋나지 않게 한다.
  const outcome = classifyExecutionOutcome(execution.status, execution.errorMessage);
  const isSkipped = outcome?.type === 'skipped';
  const displayStatus = getExecutionDisplayStatus(execution.status, execution.errorMessage);

  // 「실행 시각」 글자 (WD-1175) — 실행 이력과 같은 작업 시간대+약어. 규칙은 formatExecutionStartInZone 참고
  // (조회 중 '-', 아는 시간대 우선, 모르고 실패면 브라우저 시간대+약어). 본문은 실행 조회만으로 보이던 그대로다.
  const startedAtText = formatExecutionStartInZone(execution.startedAt, {
    pending: jobPending,
    error: jobError,
    timezone: jobTimezone,
  });

  return (
    <div className="px-6 py-4 space-y-6">
      {/* 고정 헤더: 뒤로가기 + 실행 번호 */}
      <div className="flex items-center gap-3">
        <Button
          variant="ghost"
          size="sm"
          onClick={goBack}
          className="gap-1.5"
        >
          <ArrowLeft className="h-4 w-4" />
          뒤로
        </Button>
        <div className="h-5 w-px bg-border" />
        {/* shrink-0: 긴 등급 배지(줄바꿈 모드)가 먼저 줄어들게 해 「실행 #N」 제목이 꺾이지 않게 한다 (WD-864) */}
        <h1 className="shrink-0 text-lg font-semibold text-foreground">
          실행 #{executionIdNum}
        </h1>
        {/* 리포트 등급 배지(WD-198) — 데이터셋과 같은 배지. 등급 데이터를 읽지 않은 리포트는 배지가 없다. */}
        {execution.securityLevel && <SecurityLevelBadge level={execution.securityLevel} />}
      </div>

      {/* 4열 메타 카드 그리드: 상태, 실행 시각, 소요 시간, 전달 채널 */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* 상태 */}
        <div className="rounded-lg border bg-card p-4">
          <p className="text-xs text-muted-foreground mb-1">상태</p>
          <Badge variant={getStatusBadgeVariant(displayStatus)}>
            {getStatusLabel(displayStatus)}
          </Badge>
        </div>

        {/* 실행 시각 */}
        <div className="rounded-lg border bg-card p-4">
          <p className="text-xs text-muted-foreground mb-1">실행 시각</p>
          <p className="text-sm font-medium">{startedAtText}</p>
          <p className="text-xs text-muted-foreground">{timeAgo(execution.startedAt)}</p>
        </div>

        {/* 소요 시간 */}
        <div className="rounded-lg border bg-card p-4">
          <p className="text-xs text-muted-foreground mb-1">소요 시간</p>
          <p className="text-sm font-medium">
            {execution.status === 'RUNNING' ? '진행중...' : formatDuration(execution.startedAt, execution.completedAt)}
          </p>
        </div>

        {/* 전달 채널 */}
        <div className="rounded-lg border bg-card p-4">
          <p className="text-xs text-muted-foreground mb-1">전달 채널</p>
          <div className="flex flex-wrap gap-1">
            {execution.deliveredChannels?.length > 0
              ? execution.deliveredChannels.map((ch) => (
                  <Badge key={ch} variant="outline" className="text-xs">
                    {ch}
                  </Badge>
                ))
              : <span className="text-sm text-muted-foreground">-</span>
            }
          </div>
        </div>
      </div>

      {/* 상태별 결과 영역 */}

      {/* RUNNING: 스피너 + 폴링 안내 */}
      {execution.status === 'RUNNING' && (
        <div className="flex flex-col items-center justify-center py-16 gap-4">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          <p className="text-sm text-muted-foreground">리포트를 생성하고 있습니다...</p>
          <p className="text-xs text-muted-foreground">5초마다 자동으로 상태를 확인합니다.</p>
        </div>
      )}

      {/* FAILED/건너뜀: 사유 분류 카드 — 건너뜀은 오류가 아니므로 빨강(destructive) 대신 중립 톤으로 그린다.
          서버 원문 사유는 두 경우 모두 그대로 보인다(제목·조언만 분류로 고른다). */}
      {outcome && (
        <div
          className={
            isSkipped
              ? 'rounded-lg border bg-muted p-6 space-y-3'
              : 'rounded-lg border border-destructive/50 bg-destructive/5 p-6 space-y-3'
          }
          data-testid={isSkipped ? 'execution-skipped' : 'execution-error'}
        >
          <div className="flex items-center gap-2">
            <span className="text-lg">{outcome.icon}</span>
            {/* h1 다음 단계라 h2 — 실패·건너뜀에는 요약·리포트 h2 가 없어 h3 이면 단계가 뛴다(WD-929, WCAG 1.3.1) */}
            <h2 className={isSkipped ? 'font-semibold text-foreground' : 'font-semibold text-destructive'}>
              {outcome.label}
            </h2>
          </div>
          {execution.errorMessage && (
            // break-words: 긴 URL/JSON/에러코드 포함 시 카드 너비 초과 방지
            <p className="text-sm text-muted-foreground font-mono whitespace-pre-wrap break-words">
              {execution.errorMessage}
            </p>
          )}
          <p className="text-sm text-muted-foreground">{outcome.guide}</p>
        </div>
      )}

      {/* 출처를 확인하지 못한 수치 경고 — 요약 카드 위에 둔다(WD-767).
          정적 안내이므로 InlineBanner 기본 role=status/aria-live 를 role=note 로 덮어쓴다.
          차단 403 때는 요약과 함께 감춘다 — 배너가 담는 수치·문맥도 result 의 리포트 내용이라
          「열 수 없습니다」 안내만 남기는 완료 기준(WD-1177 「안내만」)을 넘기면 안 된다. */}
      {!isBlocked403 && numberCheck && (
        <InlineBanner
          variant="warning"
          role="note"
          aria-live="off"
          data-testid="number-check-banner"
          icon={<AlertTriangle />}
          title={`출처를 확인하지 못한 수치 ${numberCheck.unverifiedTotal}개`}
        >
          <p>
            리포트의 아래 수치를 이 실행의 조회 결과에서 찾지 못했습니다. 계산하거나 지어낸 값일 수 있으니 확인하세요.
          </p>
          <ul className="mt-2 space-y-1">
            {numberCheck.unverified.map((item, i) => (
              <li key={`${item.text}-${i}`}>
                <span className="font-bold">{item.text}</span>
                {item.context && <span className="ml-2 text-muted-foreground">{item.context}</span>}
              </li>
            ))}
          </ul>
          {/* 목록은 최대 20개로 잘려 있다 — 나머지 개수를 알린다 */}
          {numberCheck.unverifiedTotal - numberCheck.unverified.length > 0 && (
            <p className="mt-1 text-muted-foreground">
              외 {numberCheck.unverifiedTotal - numberCheck.unverified.length}개
            </p>
          )}
        </InlineBanner>
      )}

      {/* COMPLETED 요약 섹션 — 마크다운으로 렌더링.
          HTML 리포트(rawHtml) 유무와 무관하게 항상 렌더한다 (#363).
          이전에는 rawHtml 분기 안에만 있어서, htmlContent 없이 sections만 있는
          폴백 경로에서는 AI가 만든 총평이 화면 어디에도 나오지 않았다.
          단, 차단 403(등급 차단·권한)일 때는 수치 경고 배너와 함께 요약까지 감춘다 — 안내만 보이는 완료 기준(WD-1177). */}
      {execution.status === 'COMPLETED' && summary && !isBlocked403 && (
        // WD-755: 요약은 아래 리포트와 갈라지는 영역 면이라 알파 없는 단색 bg-muted(§1-7 — /50 은 상태 표시에만)
        <div className="rounded-lg border bg-muted p-6" data-testid="execution-summary">
          <h2 className="text-sm font-semibold mb-3">요약</h2>
          <div className={PROSE_CLASSES}>
            <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>{summary}</ReactMarkdown>
          </div>
        </div>
      )}

      {/* 본문 상태가 html — HTML 리포트 iframe (차단 403 이 아닐 때만 들어온다, WD-1177).
          rawHtml 은 TS 를 위해 다시 좁힌다 — 상태가 html 이면 값이 반드시 있으므로 렌더는 바뀌지 않는다. */}
      {bodyState?.state === 'html' && rawHtml && (
        <>
          {/* HTML 리포트 영역 — 상단 바에 인쇄/PDF 버튼 + iframe */}
          <div className="rounded-lg border overflow-hidden">
            {/* 헤더 바: 리포트 제목 + 액션 버튼들 */}
            <div className="flex items-center justify-between px-4 py-2 border-b bg-muted">
              <h2 className="text-sm font-semibold">리포트</h2>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handlePrint}
                  className="gap-1.5"
                >
                  <Printer className="h-3.5 w-3.5" />
                  인쇄
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleDownloadPdf}
                  disabled={downloading}
                  className="gap-1.5"
                >
                  {downloading ? (
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  ) : (
                    <FileDown className="h-3.5 w-3.5" />
                  )}
                  PDF
                </Button>
              </div>
            </div>

            {/* 흰색 배경 위에 iframe 렌더링 — 콘텐츠 높이에 맞춰 자동 조절 */}
            <div className="bg-white">
              <ReportIframe ref={iframeRef} html={rawHtml} autoHeight />
            </div>
          </div>
        </>
      )}

      {/* 본문 상태가 fallback — 마크다운 섹션 폴백 (html 보다 우선순위가 낮아 html 이 없을 때만 이 블록이 그려진다, WD-1177) */}
      {bodyState?.state === 'fallback' && (
        <div className="space-y-4">
          {/* PDF 다운로드 버튼 */}
          <div className="flex justify-end">
            <Button
              variant="outline"
              size="sm"
              onClick={handleDownloadPdf}
              disabled={downloading}
              className="gap-1.5"
            >
              {downloading ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <FileDown className="h-3.5 w-3.5" />
              )}
              PDF
            </Button>
          </div>

          {/* 각 섹션을 마크다운 카드로 렌더링 */}
          {sections.map((section) => (
            <div key={section.key} className="rounded-lg border bg-card p-6">
              <h2 className="text-sm font-semibold mb-3">{section.label}</h2>
              <div className={PROSE_CLASSES}>
                <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>{section.content}</ReactMarkdown>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 본문 상태가 안내·문구 — 빈 영역 대신 뷰어와 같은 규칙의 카드 (WD-1098·WD-1177).
          등급 차단·권한 부족(403)은 전용 안내가 먼저고, 그 밖의 오류만 공용 문구를 쓴다.
          위 요약·iframe·폴백과 중복으로 그려지지 않는다 — reportBodyState 가 한 상태로 갈랐기 때문이다. */}
      {bodyState?.state === 'level-blocked' && (
        <ReportLevelBlockedNotice testId="execution-report-level-blocked" />
      )}
      {bodyState?.state === 'forbidden' && (
        <ProactiveReadForbiddenNotice subject="리포트" testId="execution-report-forbidden" />
      )}
      {bodyState?.state === 'message' && (
        <div
          className="rounded-lg border bg-card p-6 text-center"
          data-testid="execution-report-html-error"
        >
          <p className="text-sm text-muted-foreground">{bodyState.text}</p>
        </div>
      )}
    </div>
  );
}
