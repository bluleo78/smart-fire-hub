/**
 * ReportViewerPage — HTML 리포트 전체 화면 뷰어
 *
 * URL: /ai-insights/jobs/:jobId/executions/:executionId/report
 * 백엔드가 반환하는 HTML 리포트를 iframe srcdoc으로 렌더링한다.
 * sandbox 속성으로 스크립트 실행을 차단하여 XSS를 방지한다.
 * 상단 바에 뒤로가기, PDF 다운로드, 인쇄 버튼을 제공한다.
 */

import { ArrowLeft, ExternalLink, FileDown, Loader2, Printer } from 'lucide-react';
import { useRef } from 'react';
import { Link, useParams } from 'react-router-dom';

import ReportIframe from '@/components/ai/ReportIframe';
import { Button } from '@/components/ui/button';
import { useExecutionHtml } from '@/hooks/queries/useProactiveMessages';
import { useJobGoBack } from '@/hooks/useGoBack';
import { useReportActions } from '@/hooks/useReportActions';
import { isNotFoundError } from '@/lib/api-error';
import { reportBodyState } from '@/lib/proactive-utils';
import { parseRouteId } from '@/lib/route-id';

import { BackButton } from './components/BackButton';
import { ProactiveReadForbiddenNotice } from './components/ProactiveReadForbiddenNotice';
import { ReportLevelBlockedNotice } from './components/ReportLevelBlockedNotice';

export default function ReportViewerPage() {
  const { jobId, executionId } = useParams<{ jobId: string; executionId: string }>();
  const iframeRef = useRef<HTMLIFrameElement>(null);

  // 경로 파라미터가 잘못된 경우(잘못된 URL 직접 접근, 손상된 링크 등) — 조회하지 않고 별도 「잘못된 요청」 상태로 분기한다.
  // parseRouteId 는 숫자로만 된 양의 정수가 아니면(`abc`·`1e1`·`1.5`·` 7`·`0` 등) null 을 돌려준다(WD-628).
  // "리포트가 없습니다" 빈 상태는 정상적으로 리포트가 없는 경우를 위한 것이라 여기에 섞지 않는다.
  const parsedJobId = parseRouteId(jobId);
  const parsedExecutionId = parseRouteId(executionId);
  const isInvalidParams = parsedJobId == null || parsedExecutionId == null;

  // HTML 리포트 조회 — 잘못된 id 면 대체값 없이 훅이 skipToken 으로 조회 자체를 끈다(WD-652). 실행 상세·모달과 캐시를 함께 쓴다(WD-661)
  const { data: htmlResponse, isLoading, isError, error } = useExecutionHtml(parsedJobId, parsedExecutionId);

  // 이 탭의 첫 화면(WD-347) — 이메일 「웹에서 보기」·리포트 창 「새 탭에서 열기」는 새 탭으로 열려 돌아갈 기록이 없다.
  // 그때 navigate(-1) 은 무반응이거나 앱 밖(about:blank)으로 나가므로 대체 목적지로 보내고, 버튼 이름도 그 목적지로 알린다.
  // 목적지는 실행 상세와 같은 규칙(jobBackFallback, WD-671)이다 — 이 화면의 HTML 조회가 성공했으면 작업을 읽을 수 있으니
  // 작업 상세, 아직 모르거나(조회 중·멈춘 요청) 403·404·다른 오류면 리포트 목록. 목적지를 정하려고 작업을 따로 조회하지 않고
  // 버튼도 잠그지 않는다 — 요청이 멈춰도 언제든 리포트 목록으로 돌아갈 수 있다.
  // 오류가 있으면 캐시 데이터를 목적지 판단에 쓰지 않는다(WD-1177) — 재조회가 403·404 로 실패해 화면이 권한·오류 안내인 동안
  // 캐시 HTML 을 보고 「작업 상세」로 알리면, 그 안내 화면으로 되돌아가게 된다. 데이터는 남아 있으니 뒤로가기는 여전히 눌린다.
  // 이름은 조회가 끝나지 않은 동안 「리포트 목록」 그대로이고, 성공했을 때만 「작업 상세」로 바뀐다(조회 중에 뒤집히지 않음).
  // 뒤로 가기는 여기서 한 번만 만들고 안내 화면의 「돌아가기」 버튼들에도 넘긴다(WD-667).
  const { goBack, label: backLabel } = useJobGoBack(parsedJobId, htmlResponse !== undefined && !isError);

  const rawHtml = htmlResponse?.data ?? null;
  // 404 — 서버는 리포트가 없을 때와 작업 작성자가 아닐 때(받은 사람이 주소로 직접 연 경우)를 똑같이 404 로 숨긴다.
  // 「불러올 수 없습니다」는 일시 오류처럼 읽혀 다시 시도하게 만들므로, 두 경우를 함께 말하는 안내를 보인다 (WD-162).
  const isNotFound = isError && isNotFoundError(error);
  // 본문에 그릴 것 하나를 한곳에서 고른다(WD-1098·WD-1177) — reportBodyState 한 곳의 판정만 쓴다.
  // 우선순위: 로딩 > 등급 차단 > 권한 부족(403) > 캐시 HTML > 문구. 403 밖 오류(404·500)는 캐시 HTML 이 이겨,
  // 이 화면은 마크다운 폴백을 그리지 않으니 hasFallback 은 늘 false 다.
  // 로딩 중에는 상태를 정하지 않는다 — 오류도 본문도 없는 시점에 「리포트가 없습니다」 가 잠깐 뜨지 않게.
  const bodyState = isLoading ? null : reportBodyState(isError ? error : null, rawHtml, false);
  // 인쇄·PDF 를 위한 본문이 있는가 — 안내·오류·빈 상태에서는 누를 내용이 없어 두 버튼을 함께 잠군다(WD-1177).
  const hasBodyToPrint = bodyState?.state === 'html';

  // PDF 다운로드 + 인쇄 공통 훅
  const { handleDownloadPdf, handlePrint, downloading } = useReportActions({
    // 잘못된 id 는 그대로 넘긴다 — 훅이 다운로드 요청을 보내지 않는다(WD-652)
    jobId: parsedJobId,
    executionId: parsedExecutionId,
    iframeRef,
  });

  return (
    <div className="flex flex-col h-dvh bg-background">
      {/* 상단 고정 헤더 바 — 네비게이션 + 액션 버튼들 */}
      <div className="flex items-center justify-between px-4 py-3 border-b bg-background print:hidden">
        {/* 왼쪽: 뒤로가기 + 제목 */}
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="sm"
            onClick={goBack}
            className="gap-1.5"
          >
            <ArrowLeft className="h-4 w-4" />
            {backLabel}
          </Button>
          <div className="h-5 w-px bg-border" />
          <h1 className="text-sm font-semibold text-foreground">
            {isInvalidParams ? '리포트' : `리포트 #${parsedExecutionId}`}
          </h1>
        </div>

        {/* 오른쪽: 액션 버튼들 */}
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={handlePrint}
            disabled={!hasBodyToPrint}
            className="gap-1.5"
          >
            <Printer className="h-3.5 w-3.5" />
            인쇄
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={handleDownloadPdf}
            disabled={downloading || !hasBodyToPrint}
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

      {/* 본문 영역 — bodyState 하나만 그린다(잘못된 요청 / 로딩 / 등급 차단 / 권한 부족 / HTML / 문구) */}
      <div className="flex-1 overflow-hidden">
        {isInvalidParams && (
          <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
            <p className="text-sm">잘못된 요청입니다.</p>
            {/* jobId/executionId가 유효하지 않으므로 "작업 상세 보기" 같은 깨진 링크는 노출하지 않는다 */}
            <BackButton onBack={goBack} />
          </div>
        )}

        {!isInvalidParams && isLoading && (
          <div className="flex items-center justify-center h-full gap-2 text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin" />
            <span className="text-sm">리포트를 불러오는 중...</span>
          </div>
        )}

        {/* 본문 상태가 level-blocked — 등급 차단 안내 (WD-198) — 캐시 HTML 과 겹치지 않게 이 안내만 그린다(WD-1177) */}
        {!isInvalidParams && bodyState?.state === 'level-blocked' && (
          <ReportLevelBlockedNotice testId="report-level-blocked" />
        )}

        {/* 본문 상태가 forbidden — 권한 부족(403) 안내 (WD-137·WD-1098) — 캐시 HTML 이 이겨도 안내가 먼저다(WD-1177) */}
        {!isInvalidParams && bodyState?.state === 'forbidden' && (
          <ProactiveReadForbiddenNotice subject="리포트" testId="report-forbidden" />
        )}

        {/* 본문 상태가 html — HTML 리포트 iframe.
            403(등급 차단·권한)만 이 상태를 막는다 — 404·500 은 캐시 HTML 이 이기므로 오류 블록과 함께 그려지지 않는다(WD-1177). */}
        {!isInvalidParams && bodyState?.state === 'html' && rawHtml && (
          <ReportIframe ref={iframeRef} html={rawHtml} />
        )}

        {/* 본문 상태가 message — 조회 오류 안내 또는 빈 상태 (WD-1096·WD-1098·WD-1177).
            문구는 reportBodyState 가 정한 값이다(404 서버 문구·WD-162 안내·400 서버 문구·5xx 고정 문구).
            오류면 돌아가기, 오류가 없는 빈 상태만 「작업 상세 보기」 링크를 보인다. */}
        {!isInvalidParams && bodyState?.state === 'message' && (
          <div
            className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground"
            data-testid={isNotFound ? 'report-not-found' : undefined}
          >
            <p className="text-sm text-center break-keep px-4">{bodyState.text}</p>
            {isError ? (
              <BackButton onBack={goBack} />
            ) : (
              <Button variant="outline" size="sm" asChild>
                <Link to={`/ai-insights/jobs/${parsedJobId}`}>
                  <ExternalLink className="h-3.5 w-3.5" />
                  작업 상세 보기
                </Link>
              </Button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
