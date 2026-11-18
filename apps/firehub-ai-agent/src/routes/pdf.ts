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
 * api 의 PDF 요청 시간 제한(PdfExportService PDF_TIMEOUT 60초)보다 넉넉히 짧아야 api 가 먼저 끊지 않고
 * 이쪽 오류가 api 로 전달된다. 그래서 두 대기를 각각 30초가 아니라 합쳐서 30초로 묶는다.
 */
export const RENDER_TIMEOUT_MS = 30000;

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
      // 즉시 abort 되므로 네트워크 유휴 대기가 30초를 기다리지 않고 바로 끝난다.
      await page.setRequestInterception(true);
      // 메인 프레임이 다른 문서로 이동하려 했는지 기록한다. setContent 는 현재 about:blank 문서에 HTML 을
      // 써 넣을 뿐 이동 요청을 만들지 않으므로(실측), 메인 프레임 이동 요청은 모두 HTML 안의 meta refresh 등이
      // 일으킨 것이다. 이를 막기만 하면 페이지가 Chrome 오류 페이지로 바뀌어 빈 PDF 가 200 으로 나가므로 실패로 다룬다.
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
      // 리포트는 인라인 SVG·CSS 만 쓰고 JS 가 필요 없다(웹 뷰어 iframe 도 allow-scripts 없이 띄우므로
      // JS 의존 리포트는 이미 화면에서도 동작하지 않는다). fetch·XHR 은 위 가로채기로 막히지만,
      // 가로채기 대상 밖인 WebSocket·WebRTC 같은 경로까지 원천 차단하려고 JS 를 끈다(심층 방어).
      await page.setJavaScriptEnabled(false);

      // HTML을 페이지에 로드하고(load), 이어서 진행 중 요청 0건으로 500ms 조용해질 때까지 기다린다. 유휴 조건 자체
      // (0건·500ms)는 예전 networkidle0 과 같지만, 재는 시점이 load 이후라는 점이 다르다.
      // Puppeteer 25 는 setContent 의 waitUntil 에서 networkidle0/2 를 뺐다(#14940). 그래서 load 까지 기다린 뒤
      // waitForNetworkIdle 로 같은 유휴 대기를 따로 건다(WD-790). load 만 기다리면 meta refresh 의 이동 요청이 나오기 전에
      // 끝나 아래 이동 차단 판정(422)을 놓친다 — 실브라우저 시험(pdf.ssrf.test.ts)이 이를 잡았다.
      // 두 대기에 각각 30초를 주면 최악이 60초가 되어 api 의 PDF 요청 시간 제한(PdfExportService PDF_TIMEOUT 60초)에
      // 닿는다. 그래서 둘을 합쳐 하나의 30초 기한으로 묶고, 유휴 대기에는 load 뒤 남은 시간만 준다.
      const renderStartedAt = Date.now();
      await page.setContent(body.html, { waitUntil: 'load', timeout: RENDER_TIMEOUT_MS });
      // 남은 시간이 0 이하이면 Puppeteer 가 0 을 「무제한」으로 해석하므로 최소한의 양수(1ms)를 준다
      const remainingMs = Math.max(1, RENDER_TIMEOUT_MS - (Date.now() - renderStartedAt));
      await page.waitForNetworkIdle({ idleTime: 500, concurrency: 0, timeout: remainingMs });

      // 이동 거부 응답(422) — 렌더링 실패가 아니라 입력 HTML 문제라 422 로 알린다(api 는 이때 Flying Saucer 폴백으로 넘어간다)
      const rejectNavigation = () => {
        console.warn('[html-to-pdf] 메인 프레임 이동 시도를 차단해 PDF 생성을 거부했다:', page.url());
        res.status(422).json({
          error: 'PDF generation rejected',
          details: 'HTML 이 다른 주소로 이동하려 해(meta refresh 등) PDF 를 만들 수 없습니다. 외부 이동 없는 자기완결형 HTML 이어야 합니다.',
        });
      };

      // meta refresh 는 유휴 대기만으로는 잡히지 않는다(WD-790 실측): 갓 뜬 렌더러에서는 0초 refresh 의 이동 요청이
      // load 뒤 300~800ms(그 이상도)에 나와, load+500ms 에 끝나는 유휴 대기보다 늦다. 그러면 아래 판정을 통과한 뒤
      // page.pdf() 도중 이동이 일어나 Chrome 오류 페이지가 200 PDF 로 나갔다. 그래서 문서에 meta refresh 가 있는지
      // DOM 으로 직접 본다. 이동이 이미 일어났다면 이동 요청 이벤트(가로채기)가 오류 페이지 커밋보다 먼저 와
      // mainFrameNavigationBlocked 가 서 있고, 아직이라면 우리 문서에 meta 가 남아 있으므로, 두 순서 모두 422 로 잡힌다.
      // ($$eval 은 DevTools 평가라 페이지 JS 를 꺼도 동작한다. 속성값 비교는 Chrome 처럼 대소문자 무시)
      // 이 DOM 판정이 빈틈없는 것은 위에서 페이지 JS 를 껐기(setJavaScriptEnabled(false)) 때문이다. JS 를 다시 켜면
      // location.href 대입 같은 스크립트 이동은 DOM 에 흔적이 없어 여기서 잡히지 않는다 — 그 경우의 일반 방어는
      // page.pdf() 뒤의 이동 판정이다.
      const metaRefreshCount = await page.$$eval('meta[http-equiv="refresh" i]', (els) => els.length);

      // 메인 프레임이 이동을 시도했거나 원래 문서(about:blank)를 벗어났는지 — PDF 전후 두 판정이 같은 조건을 쓴다
      const navigatedAway = () => mainFrameNavigationBlocked || page.url() !== 'about:blank';

      // 메인 프레임이 원래 문서(about:blank)를 벗어났으면 우리 HTML 이 아닌 오류 페이지가 찍히므로 PDF 를 만들지 않는다.
      if (navigatedAway() || metaRefreshCount > 0) {
        rejectNavigation();
        return;
      }

      // PDF 생성 — 인쇄용 CSS가 적용된다
      const pdfBuffer = await page.pdf({
        format: body.format ?? 'A4',
        landscape: body.landscape ?? false,
        printBackground: true, // 배경색/배경이미지 포함
        margin: { top: '15mm', bottom: '15mm', left: '10mm', right: '10mm' },
      });

      // 심층 방어: 위 판정 뒤 PDF 를 찍는 사이에 다른 경로로 메인 프레임 이동이 일어났으면 찍힌 것이 오류 페이지일 수 있어 버린다
      if (navigatedAway()) {
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
