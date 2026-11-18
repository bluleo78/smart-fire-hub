/**
 * HTML → PDF 변환 라우트
 *
 * Puppeteer(headless Chrome)를 사용하여 HTML 문자열을 PDF 바이너리로 변환한다.
 * 백엔드 PdfExportService가 AI 생성 HTML 리포트를 PDF로 변환할 때 호출한다.
 * 브라우저와 동일한 렌더링 엔진을 사용하므로 CSS3, SVG, 한글 폰트가 완벽하게 지원된다.
 */
import { Router, Request, Response } from 'express';
import puppeteer, { type Browser } from 'puppeteer';
import { authedJsonBody, PDF_BODY_LIMIT } from '../http/json-body.js';
import { isPdfRequestAllowed } from './pdf-request-guard.js';

const router = Router();

/** Puppeteer 브라우저 인스턴스 — 재사용하여 매 요청마다 브라우저를 띄우는 비용을 절약 */
let browser: Browser | null = null;

/**
 * HTML 로드(load)와 이어지는 네트워크 유휴 대기를 합친 렌더 기한(ms) — WD-790.
 * 두 대기를 각각 30초가 아니라 합쳐서 30초로 묶는다. 이어지는 PDF 찍기까지 포함한 전체 상한은 TOTAL_TIMEOUT_MS 다.
 */
export const RENDER_TIMEOUT_MS = 30000;

/**
 * 요청 처리기 시작부터 page.pdf() 까지 한 요청 전체의 기한(ms) — WD-799.
 * api 의 PDF 요청 시간 제한(PdfExportService PDF_TIMEOUT 60초)보다 10초 짧게 둬(50초 < api 60초), api 가 먼저 끊지 않고
 * 이쪽 오류(500/422)가 api 로 전달되게 한다. 기한은 처리기에 들어온 순간부터 잰다 — 브라우저 띄우기(getBrowser)·
 * 새 탭(newPage)에 쓴 시간도 api 가 기다리는 시간이기 때문이다. 렌더(setContent+유휴 대기)는 RENDER_TIMEOUT_MS 와
 * 이 기한의 남은 시간 중 짧은 쪽, page.pdf() 는 이 기한에서 남은 시간만 쓴다(예전에는 page.pdf() 가 기본 30초를 따로 써
 * 렌더 30초 + PDF 30초로 api 기한 60초에 닿았다).
 * Puppeteer 는 timeout 0 을 「무제한」으로 읽으므로, 이 기한에서 나눠 주는 timeout 은 늘 1ms 이상의 양수로 둔다.
 */
export const TOTAL_TIMEOUT_MS = 50000;

/**
 * 네트워크 유휴 판정 시간(ms) — 진행 중 요청 0건이 이만큼 이어지면 렌더가 안정됐다고 본다(예전 networkidle0 과 같음).
 * 남은 시간이 이보다 짧으면 유휴 대기는 성공할 수 없고 타임아웃 오류(500)만 내므로 건너뛴다(WD-799).
 */
const NETWORK_IDLE_TIME_MS = 500;

/** ASCII 공백(HTML 규칙의 ASCII whitespace: TAB·LF·FF·CR·SPACE) 판정 — NBSP 같은 유니코드 공백은 포함하지 않는다 */
function isAsciiWhitespace(ch: string): boolean {
  return ch === '\t' || ch === '\n' || ch === '\f' || ch === '\r' || ch === ' ';
}

/**
 * meta refresh 의 content 값이 렌더 기한(limitSec 초) 안에 실제 이동·다시 읽기를 일으키는지 판정한다 — WD-799.
 *
 * 왜: 지연이 길어 렌더가 끝난 뒤에야 일어날 refresh(예: content="3600")나 Chrome 이 무시하는 값까지 거부하면
 * 정상 리포트가 이유 없이 422(→ api 의 Flying Saucer 폴백)로 떨어진다. 그래서 렌더 중 일어날 수 있는 것만 거부한다.
 *
 * 해석은 HTML 표준의 「shared declarative refresh steps」를 따른다. Chrome 153 실측(근거: WD-799 탐침)과 일치했다.
 *  - 앞의 ASCII 공백은 건너뛴다(NBSP 는 공백이 아니라 무시됨 — 실측 이동 없음).
 *  - 이어지는 ASCII 숫자가 지연(초)이다. 숫자가 없고 다음 글자가 '.' 도 아니면 Chrome 은 refresh 자체를 무시한다
 *    (빈 값·「abc」·「-1」·「+1」·「;url=…」 — 실측 이동 없음).
 *  - 숫자 없이 '.' 로 시작하면 지연 0(「.5」 → 실측 즉시 이동). 정수 부분 뒤의 '.'·숫자는 버린다(「1.9」 → 실측 1초).
 *
 * 일부러 보수적으로 둔 곳(거부 쪽으로 기울여 이동이 새지 않게 함):
 *  - URL 없는 「0」 은 about:blank 다시 읽기라 빈 PDF 위험이 있어 거부한다. 이 다시 읽기는 이동 요청(가로채기)에는 보이지
 *    않지만 실측으로 setContent 도중 새 문서가 커밋된다 — 그때는 meta 가 DOM 에서 사라지므로 라우트의 문서 교체 판정
 *    (메인 프레임 loaderId 비교)이 잡는다(WD-799). 이 함수의 판정은 아직 커밋되기 전(지연 1초 이상 등)을 덮는다.
 *  - limitSec 은 호출하는 쪽이 정한다(라우트: 렌더 시작 때 남은 시간을 초로 올림 — 그 까닭은 라우트의 limitSec 주석).
 *  - 숫자 뒤에 `;`·`,`·공백이 아닌 글자가 오면 표준·Chrome 은 무시하지만(「1abc」 실측 이동 없음), 여기서는 숫자만 보고 거부한다.
 *  - content 속성이 아예 없으면(null) 표준대로 refresh 가 일어나지 않으므로 통과시킨다.
 */
export function isRefreshWithinRender(content: string | null, limitSec: number): boolean {
  if (content === null) return false;
  let pos = 0;
  while (pos < content.length && isAsciiWhitespace(content[pos])) pos++;
  const digitsStart = pos;
  while (pos < content.length && content[pos] >= '0' && content[pos] <= '9') pos++;
  const digits = content.slice(digitsStart, pos);
  if (digits === '') {
    // 숫자 없이 '.' 로 시작하면 지연 0 으로 이동한다 — 그 밖(빈 값·문자·부호)은 Chrome 이 무시한다
    return content[pos] === '.';
  }
  // 아주 긴 숫자열도 Number 로 읽으면 큰 값(또는 Infinity)이 되어 기한 밖으로 판정된다
  return Number(digits) <= limitSec;
}

/** 브라우저 인스턴스를 가져오거나 새로 생성한다 (lazy initialization) */
async function getBrowser(): Promise<Browser> {
  if (!browser || !browser.connected) {
    browser = await puppeteer.launch({
      headless: true,
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage', // Docker 환경에서 /dev/shm 크기 제한 우회
        '--disable-gpu',
        // SSRF 백스톱(WD-617): 요청 가로채기로 막힌 하위 리소스·preconnect 도 Chrome 은 소켓을 미리 열어
        // 내부 호스트·포트를 TCP 로 두드릴 수 있다(실측: 차단된 <img> 하나에 연결 1건). 모든 호스트 이름과
        // IP 리터럴의 해석을 실패시켜 브라우저가 어떤 주소에도 연결하지 못하게 한다. 리포트는 외부
        // 리소스가 필요 없으므로 잃는 것이 없다.
        // WD-697 실측(Chrome 153): 이 규칙은 IP 리터럴(127.0.0.1·[::1]·169.254.169.254)에도 적용돼, 가로채기에
        // 보이지 않는 preconnect·preload·iframe·meta refresh 의 투기적 TCP 연결까지 0건이 된다(net-log TCP_CONNECT 0).
        // 그래서 프록시(--proxy-server) 백스톱은 두지 않는다 — 회귀는 pdf.ssrf.test.ts 의 원시 TCP accept 검사가 잡는다.
        '--host-resolver-rules=MAP * ~NOTFOUND',
      ],
    });
  }
  return browser;
}

/** 재사용 중인 브라우저를 닫는다 — 실브라우저 테스트 정리용(프로세스가 남지 않게) */
export async function closePdfBrowser(): Promise<void> {
  const b = browser;
  browser = null;
  if (b) await b.close();
}

interface HtmlToPdfRequest {
  html: string;
  /** PDF 용지 크기 (기본: A4) */
  format?: 'A4' | 'Letter' | 'A3';
  /** 가로 모드 여부 (기본: false) */
  landscape?: boolean;
}

/**
 * POST /agent/html-to-pdf
 * HTML 문자열을 받아 PDF 바이너리를 반환한다.
 */
router.post('/html-to-pdf', ...authedJsonBody({ limit: PDF_BODY_LIMIT }), async (req: Request, res: Response) => {
  // 전체 기한(TOTAL_TIMEOUT_MS)의 끝 시각 — 브라우저 준비 시간까지 포함하도록 처리기 첫 줄에서 잰다(WD-799)
  const deadline = Date.now() + TOTAL_TIMEOUT_MS;
  /** 기한 d(절대 시각)까지 지금 남은 시간(ms) — 0 이하일 수 있다 */
  const left = (d: number) => d - Date.now();
  const body = req.body as HtmlToPdfRequest;

  if (!body.html) {
    res.status(400).json({ error: 'html field is required' });
    return;
  }

  try {
    const b = await getBrowser();
    const page = await b.newPage();

    try {
      // SSRF 차단(WD-617): 이 라우트가 보안 경계다. 리포트 HTML 은 사용자 프롬프트로 조종될 수 있는
      // AI 산출물이라, 렌더러가 내부망·메타데이터 주소로 요청을 보내지 못하게 모든 요청을 가로채
      // data:/blob:/about: 만 통과시키고 나머지(iframe·이동·리다이렉트 포함)는 즉시 abort 한다.
      // 즉시 abort 되므로 네트워크 유휴 대기가 렌더 기한까지 붙잡히지 않고 바로 끝난다.
      await page.setRequestInterception(true);
      // 메인 프레임이 다른 문서로 이동하려 했는지 기록한다. setContent 는 현재 about:blank 문서에 HTML 을
      // 써 넣을 뿐 이동 요청을 만들지 않으므로(실측), 메인 프레임 이동 요청은 모두 HTML 안의 meta refresh 등이
      // 일으킨 것이다. 이를 막기만 하면 페이지가 Chrome 오류 페이지로 바뀌어 빈 PDF 가 200 으로 나가므로 실패로 다룬다.
      // 이 표시는 이동 「요청」 시점(커밋 전)에 서므로, 아직 loaderId 에 드러나지 않은 이동 시도도 잡는다.
      let mainFrameNavigationBlocked = false;
      page.on('request', (request) => {
        const isMainFrameNavigation =
          request.isNavigationRequest() && request.frame() === page.mainFrame();
        if (isMainFrameNavigation) mainFrameNavigationBlocked = true;
        const decision =
          !isMainFrameNavigation && isPdfRequestAllowed(request.url())
            ? request.continue()
            : request.abort('blockedbyclient');
        // 페이지가 이미 닫힌 뒤 늦게 도착한 요청은 continue/abort 가 거부될 수 있다 — 렌더 결과와 무관하므로
        // 무시해 처리되지 않은 rejection 으로 프로세스가 죽지 않게 한다
        decision.catch(() => undefined);
      });
      // 메인 프레임 문서 교체의 권위 있는 판정 — WD-799.
      // 왜: 위 가로채기 표시는 「요청」이 보이는 이동만 잡는다. 주소 없는 meta refresh(content="0" 등)는 about:blank 를
      // 다시 읽는데, about: 스킴이라 가로채기에 요청이 오지 않고 page.url() 도 about:blank 그대로다. 그 사이 DOM 은 빈 새
      // 문서로 바뀌어 meta 판정도 meta 를 찾지 못해, 빈 PDF 가 200 으로 나갔다(라이브 실측 860바이트 1쪽).
      // 문서를 새로 커밋하면(이동·다시 읽기·오류 페이지) 메인 프레임의 loaderId 가 바뀐다. setContent 직전의 loaderId 를
      // 적어 두고, 판정할 때마다 CDP Page.getFrameTree 로 지금 값을 물어 비교한다. 명령 응답은 그 명령을 처리한 순간의
      // 렌더러 상태라 이벤트 처리 지연이 없고(Puppeteer 의 framenavigated* 이벤트와 page.url() 은 waitForFrame() 뒤에야
      // 갱신돼 늦을 수 있다), 앞서 보낸 명령($$eval·page.pdf)이 끝난 뒤 보내므로 그 명령이 본 문서보다 오래된 상태를 보지
      // 않는다. 실측(Chrome, 근거: evidence/WD-799/loaderid-probe.txt): 정상 HTML 은 setContent(document.open/write) 뒤에도
      // loaderId 그대로, 주소 없는 0초 refresh 는 setContent 가 끝날 때 이미 바뀜, 주소 있는 refresh(가로채기로 막혀 오류
      // 페이지 커밋)도 바뀜, 같은 문서 안 #앵커 이동(문서·내용이 그대로라 PDF 에 문제 없음)은 그대로.
      // 별도 CDP 세션은 page.close() 로 대상이 닫힐 때 함께 끊긴다.
      const cdp = await page.createCDPSession();
      const readMainFrameLoaderId = async () => (await cdp.send('Page.getFrameTree')).frameTree.frame.loaderId;
      // 리포트는 인라인 SVG·CSS 만 쓰고 JS 가 필요 없다(웹 뷰어 iframe 도 allow-scripts 없이 띄우므로
      // JS 의존 리포트는 이미 화면에서도 동작하지 않는다). fetch·XHR 은 위 가로채기로 막히지만,
      // 가로채기 대상 밖인 WebSocket·WebRTC 같은 경로까지 원천 차단하려고 JS 를 끈다(심층 방어).
      await page.setJavaScriptEnabled(false);

      // HTML을 페이지에 로드하고(load), 이어서 진행 중 요청 0건으로 500ms 조용해질 때까지 기다린다. 유휴 조건 자체
      // (0건·500ms)는 예전 networkidle0 과 같지만, 재는 시점이 load 이후라는 점이 다르다.
      // Puppeteer 25 는 setContent 의 waitUntil 에서 networkidle0/2 를 뺐다(#14940). 그래서 load 까지 기다린 뒤
      // waitForNetworkIdle 로 같은 유휴 대기를 따로 건다(WD-790). load 만 기다리면 meta refresh 의 이동 요청이 나오기 전에
      // 끝나 아래 이동 차단 판정(422)을 놓친다 — 실브라우저 시험(pdf.ssrf.test.ts)이 이를 잡았다.
      // 두 대기는 하나의 렌더 기한(RENDER_TIMEOUT_MS)을 나눠 쓰고, 렌더 기한은 전체 기한(deadline)을 넘지 않는다.
      // 0초 다시 읽기는 setContent 가 끝나기 전에 커밋되므로(실측) 기준 loaderId 는 반드시 setContent 보다 먼저 읽는다.
      const initialLoaderId = await readMainFrameLoaderId();
      const renderStart = Date.now();
      if (deadline - renderStart <= 0) {
        // 브라우저 준비만으로 전체 기한을 다 썼다 — 렌더를 시작해도 api 기한 안에 답할 수 없으니 바로 500 으로 끝낸다
        throw new Error(`PDF 렌더 기한(${TOTAL_TIMEOUT_MS}ms)을 브라우저 준비에 다 썼습니다`);
      }
      const renderDeadline = Math.min(renderStart + RENDER_TIMEOUT_MS, deadline);
      // 위 검사로 renderDeadline > renderStart 이므로 timeout 은 늘 양수다
      await page.setContent(body.html, { waitUntil: 'load', timeout: renderDeadline - renderStart });
      const idleTimeoutMs = left(renderDeadline);
      // 남은 시간이 유휴 판정 시간보다 짧으면 건너뛴다(NETWORK_IDLE_TIME_MS 주석) — 그래도 아래 이동·meta 판정은 그대로 거친다
      if (idleTimeoutMs >= NETWORK_IDLE_TIME_MS) {
        await page.waitForNetworkIdle({ idleTime: NETWORK_IDLE_TIME_MS, concurrency: 0, timeout: idleTimeoutMs });
      }

      // 이동 거부 응답(422) — 렌더링 실패가 아니라 입력 HTML 문제라 422 로 알린다(api 는 이때 Flying Saucer 폴백으로 넘어간다)
      const rejectNavigation = () => {
        console.warn('[html-to-pdf] 메인 프레임 이동 시도를 차단해 PDF 생성을 거부했다:', page.url());
        res.status(422).json({
          error: 'PDF generation rejected',
          details: 'HTML 이 다른 주소로 이동하려 해(meta refresh 등) PDF 를 만들 수 없습니다. 외부 이동 없는 자기완결형 HTML 이어야 합니다.',
        });
      };

      // 메인 프레임이 이동을 시도했거나(가로채기 표시) 새 문서로 바뀌었는지(loaderId) — 모든 이동 판정이 이 조건을 쓴다
      const navigatedAway = async () =>
        mainFrameNavigationBlocked || (await readMainFrameLoaderId()) !== initialLoaderId;

      // meta refresh 는 유휴 대기만으로는 잡히지 않는다(WD-790 실측): 갓 뜬 렌더러에서는 0초 refresh 의 이동 요청이
      // load 뒤 300~800ms(그 이상도)에 나와, load+500ms 에 끝나는 유휴 대기보다 늦다. 그러면 판정을 통과한 뒤
      // page.pdf() 도중 이동이 일어나 Chrome 오류 페이지가 200 PDF 로 나갔다. 그래서 문서에 meta refresh 가 있는지
      // DOM 으로 직접 본다. 이동이 이미 일어났다면 이동 판정(navigatedAway)이, 아직이라면 우리 문서에 남은 meta 가 잡는다.
      // ($$eval 은 DevTools 평가라 페이지 JS 를 꺼도 동작한다. 속성값 비교는 Chrome 처럼 대소문자 무시)
      // 이 DOM 판정이 빈틈없는 것은 위에서 페이지 JS 를 껐기 때문이다. JS 를 다시 켜면 location.href 대입 같은 스크립트
      // 이동은 DOM 에 흔적이 없어 여기서 잡히지 않는다 — 그 경우의 일반 방어는 page.pdf() 뒤의 이동 판정이다.
      // 이동이 커밋되며 실행 컨텍스트가 사라지면 $$eval 은 「Execution context was destroyed」로 던진다(WD-799) — 그래서
      // 오류를 바로 던지지 않고 붙잡아 두었다가, 이동 판정을 먼저 본 뒤에 다시 던진다.
      const evalOutcome = await page
        .$$eval('meta[http-equiv="refresh" i]', (els) => els.map((el) => el.getAttribute('content')))
        .then(
          (contents) => ({ contents }),
          (error: unknown) => ({ error }),
        );
      // $$eval 이 던졌는데 loaderId 조회마저 실패하면(대상이 닫힘 등) 그 오류가 아니라 원래 $$eval 오류를 500 으로 보낸다
      // — 원인 파악에 더 쓸모 있다. $$eval 이 성공했으면 조회 실패는 그대로 500 이다.
      const navigated = 'error' in evalOutcome ? await navigatedAway().catch(() => false) : await navigatedAway();
      if (navigated) {
        rejectNavigation();
        return;
      }
      // 이동 표시가 없는데 $$eval 이 던졌으면 원인이 다른 진짜 오류다 — 422 로 덮지 않고 500 으로 보낸다
      if ('error' in evalOutcome) throw evalOutcome.error;

      // WD-799: 이 요청이 끝나기 전에 일어날 수 있는 refresh 만 거부한다(content="3600"·빈 값·숫자 아님은 통과 —
      // isRefreshWithinRender 주석). 기준은 판정 시점이 아니라 렌더 시작 때 남아 있던 시간이다: refresh 타이머는 문서
      // load(또는 파싱) 때부터 돌므로, 판정 시점 기준으로 재면 load 와 판정 사이에 흐른 만큼 기준이 짧아져 판정 뒤·기한 전
      // (page.pdf() 도중)에 발동할 refresh 를 통과시킨다. 렌더 시작 때 남은 시간은 「load 때 남은 시간」 이상이라 거부 쪽으로
      // 기운 보수적 기준이다. 초 단위는 올림(ceil)해 경계값도 거부한다(위 검사로 1 이상이라 0초 refresh 는 늘 거부된다).
      const limitSec = Math.ceil((deadline - renderStart) / 1000);
      if (evalOutcome.contents.some((content) => isRefreshWithinRender(content, limitSec))) {
        rejectNavigation();
        return;
      }

      // PDF 생성 — 인쇄용 CSS가 적용된다. timeout 은 전체 기한에서 남은 시간(TOTAL_TIMEOUT_MS 주석 — 최소 1ms)
      const pdfBuffer = await page.pdf({
        format: body.format ?? 'A4',
        landscape: body.landscape ?? false,
        printBackground: true, // 배경색/배경이미지 포함
        margin: { top: '15mm', bottom: '15mm', left: '10mm', right: '10mm' },
        timeout: Math.max(1, left(deadline)),
      });

      // 심층 방어: 위 판정 뒤 PDF 를 찍는 사이에 메인 프레임 이동이 일어났으면 찍힌 것이 오류 페이지일 수 있어 버린다.
      // page.pdf() 응답을 받은 뒤 loaderId 를 물으므로, 찍는 동안 커밋된 새 문서도 여기서 잡힌다.
      if (await navigatedAway()) {
        rejectNavigation();
        return;
      }

      res.setHeader('Content-Type', 'application/pdf');
      res.setHeader('Content-Length', pdfBuffer.length);
      res.send(Buffer.from(pdfBuffer));
    } finally {
      await page.close();
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('[html-to-pdf] PDF generation failed:', message);
    res.status(500).json({ error: 'PDF generation failed', details: message });
  }
});

export default router;
