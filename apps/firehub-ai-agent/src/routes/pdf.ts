/**
 * HTML → PDF 변환 라우트
 *
 * Puppeteer(headless Chrome)를 사용하여 HTML 문자열을 PDF 바이너리로 변환한다.
 * 백엔드 PdfExportService가 AI 생성 HTML 리포트를 PDF로 변환할 때 호출한다.
 * 브라우저와 동일한 렌더링 엔진을 사용하므로 CSS3, SVG, 한글 폰트가 완벽하게 지원된다.
 */
import express, { Router, Request, Response } from 'express';
import puppeteer, { type Browser } from 'puppeteer';
import { internalAuth } from '../middleware/auth.js';

const router = Router();

/** Puppeteer 브라우저 인스턴스 — 재사용하여 매 요청마다 브라우저를 띄우는 비용을 절약 */
let browser: Browser | null = null;

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
      ],
    });
  }
  return browser;
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
router.post('/html-to-pdf', express.json({ limit: '5mb' }), internalAuth, async (req: Request, res: Response) => {
  const body = req.body as HtmlToPdfRequest;

  if (!body.html) {
    res.status(400).json({ error: 'html field is required' });
    return;
  }

  try {
    const b = await getBrowser();
    const page = await b.newPage();

    try {
      // HTML을 페이지에 로드 — waitUntil: 'networkidle0'으로 모든 리소스 로드 완료 대기
      await page.setContent(body.html, { waitUntil: 'networkidle0', timeout: 30000 });

      // PDF 생성 — 인쇄용 CSS가 적용된다
      const pdfBuffer = await page.pdf({
        format: body.format ?? 'A4',
        landscape: body.landscape ?? false,
        printBackground: true, // 배경색/배경이미지 포함
        margin: { top: '15mm', bottom: '15mm', left: '10mm', right: '10mm' },
      });

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
