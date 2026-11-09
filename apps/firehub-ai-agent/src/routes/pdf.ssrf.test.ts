/**
 * html-to-pdf SSRF 차단 실브라우저 테스트 (WD-617)
 *
 * 무엇을: 실제 headless Chrome 으로 POST /agent/html-to-pdf 를 호출해, HTML 안의 하위 리소스
 * (<img>·<iframe>·<link>·<script src>·CSS url()·meta refresh)가 서버 측에서 내부 주소로
 * 요청을 보내지 않는지 로컬 HTTP 리스너로 확인한다.
 * 왜: 리포트 HTML 은 사용자 프롬프트로 조종될 수 있는 AI 산출물이라, 렌더러가 내부망·메타데이터
 * 주소(169.254.169.254 등)를 대신 호출하면 SSRF 가 된다. 목(mock)으로는 Chrome 의 실제 요청
 * 경로(OOPIF iframe 포함)를 검증할 수 없어 실브라우저로 확인한다.
 * WD-697: IP 리터럴 대상의 투기적 연결(preconnect 등)까지 원시 TCP accept 수로 확인한다(아래 describe).
 * Chrome 이 설치되지 않은 환경(CI 등)에서는 건너뛴다 — 가드 판정 로직은 pdf-request-guard.test.ts 가 덮는다.
 */
import { describe, it, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { existsSync } from 'node:fs';
import http from 'node:http';
import net, { type AddressInfo } from 'node:net';
import express from 'express';
import puppeteer from 'puppeteer';
import pdfRouter, { closePdfBrowser } from './pdf.js';

const TOKEN = 'test-internal-token-pdf-ssrf';
/** 렌더 결과 확인용 문서 제목 — Chrome 은 이를 PDF 메타데이터 /Title 에 그대로 싣는다 */
const REPORT_TITLE = 'WD617-REPORT-TITLE';

/** Chrome 실행 파일이 있을 때만 실브라우저 테스트를 돈다 */
function chromeAvailable(): boolean {
  try {
    return existsSync(puppeteer.executablePath());
  } catch {
    return false;
  }
}

/** 서버를 임의 포트로 띄우고 포트를 돌려준다 */
function listen(server: http.Server): Promise<number> {
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve((server.address() as AddressInfo).port));
  });
}

/** keep-alive 소켓까지 끊고 서버가 완전히 닫힐 때까지 기다린다(테스트 간 소켓 누수 방지) */
function closeServer(server: http.Server): Promise<void> {
  return new Promise((resolve) => {
    server.closeAllConnections();
    server.close(() => resolve());
  });
}

describe.skipIf(!chromeAvailable())('POST /agent/html-to-pdf — 실브라우저 SSRF 차단', () => {
  /** 내부 서비스 흉내 — 렌더러가 보낸 요청 경로를 모두 기록한다 */
  const hits: string[] = [];
  const victim = http.createServer((req, res) => {
    hits.push(req.url ?? '');
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('secret');
  });
  /** HTTP 요청 없이 TCP 연결만 여는 경로(preconnect 등)도 잡기 위해 연결 수를 따로 센다 */
  let connections = 0;
  victim.on('connection', () => {
    connections += 1;
  });
  let victimPort = 0;

  const appServer = http.createServer(express().use('/agent', pdfRouter));
  let appPort = 0;

  beforeAll(async () => {
    process.env.INTERNAL_SERVICE_TOKEN = TOKEN;
    victimPort = await listen(victim);
    appPort = await listen(appServer);
  });

  afterAll(async () => {
    await closePdfBrowser();
    await Promise.all([closeServer(victim), closeServer(appServer)]);
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  /** 실제 라우트로 HTML 을 보내고 응답과 걸린 시간을 돌려준다 */
  async function renderPdf(html: string): Promise<{ res: Response; elapsed: number }> {
    const started = Date.now();
    const res = await fetch(`http://127.0.0.1:${appPort}/agent/html-to-pdf`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Internal ${TOKEN}` },
      body: JSON.stringify({ html }),
    });
    return { res, elapsed: Date.now() - started };
  }

  /** 테스트마다 리스너 기록을 비워 앞 테스트의 요청이 섞이지 않게 한다 */
  beforeEach(() => {
    hits.length = 0;
    connections = 0;
  });

  it('내부 주소를 가리키는 하위 리소스는 요청되지 않고, 리포트 내용이 담긴 PDF 가 생성된다', async () => {
    const base = `http://127.0.0.1:${victimPort}`;
    // localhost 이름(다른 사이트 → OOPIF iframe)도 함께 넣어 프로세스 분리된 프레임까지 확인한다
    const html = `<!DOCTYPE html><html><head>
      <title>${REPORT_TITLE}</title>
      <link rel="stylesheet" href="${base}/css">
      <link rel="preconnect" href="${base}">
      <link rel="dns-prefetch" href="${base}">
      <script src="${base}/script"></script>
      <style>body { background: url('${base}/bg'); }</style>
      </head><body>
      <h1>리포트</h1>
      <img src="${base}/img">
      <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">
      <iframe src="${base}/iframe"></iframe>
      <iframe src="http://localhost:${victimPort}/iframe-oopif"></iframe>
      <script>fetch('${base}/fetch'); new Image().src = '${base}/js-img';</script>
      <svg viewBox="0 0 10 10"><rect width="10" height="10" fill="#228be6"/></svg>
      </body></html>`;

    const { res, elapsed } = await renderPdf(html);

    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('application/pdf');
    const pdf = Buffer.from(await res.arrayBuffer());
    expect(pdf.subarray(0, 5).toString()).toBe('%PDF-');
    // 헤더만으로는 Chrome 오류 페이지 PDF 와 구분되지 않는다 — 문서 제목이 PDF 메타데이터(/Title)에
    // 실렸는지로 우리 HTML 이 실제로 렌더됐는지 확인한다
    expect(pdf.includes(Buffer.from(REPORT_TITLE))).toBe(true);
    // 렌더러가 내부 리스너에 단 한 건도 요청·연결하지 않아야 한다
    expect(hits).toEqual([]);
    expect(connections).toBe(0);
    // 차단된 요청이 networkidle0 을 30초 타임아웃까지 붙잡지 않아야 한다
    expect(elapsed).toBeLessThan(15000);
  }, 40000);

  it('meta refresh 로 다른 주소로 이동하려는 HTML 은 요청 없이 422 로 거부한다(빈 오류 페이지 PDF 금지)', async () => {
    const base = `http://127.0.0.1:${victimPort}`;
    // meta refresh 는 JS 를 꺼도 동작해 메인 프레임 이동을 일으킨다
    const html = `<!DOCTYPE html><html><head><title>${REPORT_TITLE}</title>
      <meta http-equiv="refresh" content="0;url=${base}/refresh">
      </head><body><h1>리포트</h1></body></html>`;

    const { res } = await renderPdf(html);

    expect(res.status).toBe(422);
    expect(res.headers.get('content-type')).toContain('application/json');
    expect(await res.json()).toHaveProperty('error');
    expect(hits).toEqual([]);
    expect(connections).toBe(0);
  }, 40000);

  /**
   * IP 리터럴 대상 TCP 연결 차단 (WD-697)
   *
   * 무엇을: 호스트 이름이 아닌 IP 리터럴(127.0.0.1·[::1]·169.254.169.254·10진수 IP)을 가리키는 벡터별로 렌더한 뒤,
   * 원시 TCP 리스너(net.Server)가 받은 연결 수가 0 인지 확인한다(HTTP 요청 수가 아니라 accept 수).
   * 왜: preconnect·dns-prefetch 같은 투기적 연결은 소켓 수준이라 요청 가로채기(page.on('request'))에 보이지 않는다.
   * 실측(2026-11-09, Chrome 153): --host-resolver-rules 없이 돌리면 preconnect·preload·iframe/object/embed·meta refresh 가
   * 127.0.0.1·[::1]·169.254.169.254 로 실제 TCP 연결을 연다. 'MAP * ~NOTFOUND' 는 IP 리터럴의 "해석"도 실패시켜 연결이
   * 0 이 된다(net-log TCP_CONNECT 0건, 전부 ERR_NAME_NOT_RESOLVED). 이 성질이 Chrome 업그레이드로 바뀌면 여기서 드러나야 한다.
   * 169.254.169.254 는 테스트에서 리스닝할 수 없어 HTML 에만 넣는다(같은 규칙으로 막히는지는 net-log 실측으로 확인했다).
   */
  describe('IP 리터럴 대상 — 투기적 연결(preconnect 등)까지 TCP 연결 0건 (WD-697)', () => {
    /** 원시 TCP 리스너 — HTTP 를 말하지 않는 연결(preconnect)도 accept 단계에서 센다 */
    interface CountingServer {
      server: net.Server;
      port: number;
      accepted: number;
    }

    /** 주어진 루프백 주소에 TCP 리스너를 띄운다. IPv6 가 없는 환경이면 null(그 대상만 빼고 검증) */
    function listenTcp(host: string): Promise<CountingServer | null> {
      return new Promise((resolve) => {
        const counting: CountingServer = { server: net.createServer(), port: 0, accepted: 0 };
        counting.server.on('connection', (socket) => {
          counting.accepted += 1;
          socket.destroy();
        });
        counting.server.once('error', () => resolve(null));
        counting.server.listen(0, host, () => {
          counting.port = (counting.server.address() as AddressInfo).port;
          resolve(counting);
        });
      });
    }

    let tcp4: CountingServer | null = null;
    let tcp6: CountingServer | null = null;

    beforeAll(async () => {
      tcp4 = await listenTcp('127.0.0.1');
      tcp6 = await listenTcp('::1');
    });

    afterAll(async () => {
      await Promise.all(
        [tcp4, tcp6].map(
          (c) => new Promise<void>((resolve) => (c ? c.server.close(() => resolve()) : resolve())),
        ),
      );
    });

    beforeEach(() => {
      if (tcp4) tcp4.accepted = 0;
      if (tcp6) tcp6.accepted = 0;
    });

    /** 벡터를 꽂을 IP 리터럴 기준 주소들 — 10진수 표기(2130706433 = 127.0.0.1)도 URL 파서 정규화 우회 여부 확인용으로 넣는다 */
    function ipLiteralBases(): string[] {
      const bases = [
        `http://127.0.0.1:${tcp4!.port}`,
        `http://2130706433:${tcp4!.port}`,
        'http://169.254.169.254',
      ];
      if (tcp6) bases.push(`http://[::1]:${tcp6.port}`);
      return bases;
    }

    /** 벡터별 HTML 조각 — 각 기준 주소마다 하나씩 만들어 한 문서에 넣는다 */
    const vectors: Record<string, (b: string) => string> = {
      preconnect: (b) =>
        `<link rel="preconnect" href="${b}"><link rel="preconnect" href="${b}" crossorigin>`,
      'dns-prefetch': (b) => `<link rel="dns-prefetch" href="${b}">`,
      'preload/prefetch': (b) =>
        `<link rel="preload" as="image" href="${b}/pl"><link rel="prefetch" href="${b}/pf"><link rel="modulepreload" href="${b}/mp">`,
      'img src': (b) => `<img src="${b}/img">`,
      srcset: (b) =>
        `<img srcset="${b}/s1 1x, ${b}/s2 2x"><picture><source srcset="${b}/ps"><img alt=""></picture>`,
      'CSS url()': (b) =>
        `<style>body{background:url('${b}/bg')} div{list-style-image:url('${b}/li')}</style><div>x</div>`,
      '@import': (b) => `<style>@import url('${b}/imp.css');</style>`,
      'link stylesheet': (b) => `<link rel="stylesheet" href="${b}/s.css">`,
      'font-face src': (b) =>
        `<style>@font-face{font-family:X;src:url('${b}/f.woff2')} p{font-family:X}</style><p>폰트</p>`,
      'iframe/object/embed': (b) =>
        `<iframe src="${b}/if"></iframe><object data="${b}/obj"></object><embed src="${b}/emb">`,
      'video/audio/svg image/icon': (b) =>
        `<video poster="${b}/poster" src="${b}/v"></video><audio src="${b}/a"></audio><svg><image href="${b}/svgimg"/></svg><link rel="icon" href="${b}/ico">`,
    };

    /** 투기적 연결은 비동기라 렌더 응답 직후에는 아직 accept 되지 않았을 수 있다 — 잠깐 기다린 뒤 센다 */
    const settle = () => new Promise((resolve) => setTimeout(resolve, 500));

    it('테스트 리스너가 실제 연결을 셀 수 있다(검증 수단 자체의 확인)', async () => {
      await new Promise<void>((resolve, reject) => {
        const socket = net.connect(tcp4!.port, '127.0.0.1', () => {
          socket.destroy();
          resolve();
        });
        socket.on('error', reject);
      });
      await settle();
      expect(tcp4!.accepted).toBe(1);
    });

    it.each(Object.entries(vectors))(
      '%s — IP 리터럴로 TCP 연결을 열지 않고 리포트 PDF 는 정상 생성된다',
      async (_name, fragment) => {
        const html = `<!DOCTYPE html><html><head><title>${REPORT_TITLE}</title>
        ${ipLiteralBases().map(fragment).join('\n')}
        </head><body><h1>리포트</h1>
        <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">
        <svg viewBox="0 0 10 10"><rect width="10" height="10" fill="#228be6"/></svg>
        </body></html>`;

        const { res, elapsed } = await renderPdf(html);
        await settle();

        // data: 이미지·인라인 SVG 가 든 우리 HTML 이 실제로 렌더됐는지(오류 페이지 PDF 아님) 제목 메타데이터로 확인한다
        expect(res.status).toBe(200);
        const pdf = Buffer.from(await res.arrayBuffer());
        expect(pdf.subarray(0, 5).toString()).toBe('%PDF-');
        expect(pdf.includes(Buffer.from(REPORT_TITLE))).toBe(true);
        expect(tcp4!.accepted).toBe(0);
        expect(tcp6?.accepted ?? 0).toBe(0);
        expect(elapsed).toBeLessThan(15000);
      },
      40000,
    );

    it('meta refresh 가 IP 리터럴을 가리켜도 TCP 연결 없이 422 로 거부한다', async () => {
      const targets = ipLiteralBases().map(
        (b) => `<meta http-equiv="refresh" content="0;url=${b}/refresh">`,
      );
      const html = `<!DOCTYPE html><html><head><title>${REPORT_TITLE}</title>${targets.join('')}</head><body><h1>리포트</h1></body></html>`;

      const { res } = await renderPdf(html);
      await settle();

      expect(res.status).toBe(422);
      expect(tcp4!.accepted).toBe(0);
      expect(tcp6?.accepted ?? 0).toBe(0);
    }, 40000);
  });
});
