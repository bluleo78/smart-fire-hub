/**
 * Express 앱 조립 — 미들웨어·라우터·에러 핸들러 마운트만 담당한다.
 * listen·시그널 처리는 index.ts 에 남겨, 테스트가 포트를 열지 않고 앱을 그대로 만들어 검증할 수 있게 한다(WD-550).
 */
import express from 'express';
import chatRouter from './routes/chat.js';
import classifyRouter from './routes/classify.js';
import proactiveRouter from './routes/proactive.js';
import chartRenderRouter from './routes/chart-render.js';
import pdfRouter from './routes/pdf.js';
import graphRouter from './routes/graph.js';
import templateGenerateRouter from './routes/template-generate.js';
import { errorHandler } from './http/error-handler.js';

/** 라우터·미들웨어가 모두 마운트된 앱을 만든다. */
export function createApp(): express.Express {
  const app = express();

  // 전역 JSON 파서는 두지 않는다 — 전역 파서가 먼저 본문을 읽으면 라우트별 한도(classify 2mb, html-to-pdf 5mb)가
  // 무시된다. 본문을 읽는 각 POST 라우트가 jsonBody() 로 자기 파서를 단다(src/http/json-body.ts).

  app.use('/agent', chatRouter);
  app.use('/agent', classifyRouter);
  app.use('/agent', proactiveRouter);
  app.use('/agent', chartRenderRouter);
  app.use('/agent', pdfRouter);
  app.use('/agent', graphRouter); // 온톨로지 시각화 읽기 엔드포인트
  app.use('/agent', templateGenerateRouter); // 목표 기반 리포트 양식 섹션 생성(WD-455)

  // 본문 파싱 오류는 400/413, 그 외는 500 으로 응답한다(WD-550)
  app.use(errorHandler);

  return app;
}
