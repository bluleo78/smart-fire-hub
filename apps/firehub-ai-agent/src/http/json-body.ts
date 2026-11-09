/**
 * 라우트 전용 JSON 본문 파서 헬퍼 (WD-550, WD-631)
 *
 * 전역 `app.use(express.json())` 를 두지 않는다 — 전역 파서가 먼저 본문을 읽으면 라우트에 단 다른 한도
 * (classify·chat 2mb, html-to-pdf 5mb)가 영영 적용되지 않기 때문이다. 대신 본문을 읽는 모든 POST 라우트가
 * 이 헬퍼로 자기 파서를 단다. `router.use(...)` 로 달지 않는 이유: 모든 라우터가 '/agent' 에 마운트되므로
 * 라우터 수준 파서는 다른 라우터로 가는 요청의 본문까지 먼저 읽어 버린다.
 *
 * 파서는 인증 **뒤**에 둔다(WD-631, {@link authedJsonBody}) — 토큰 없는 호출은 본문을 메모리에 모으거나 JSON 파싱하지 않고 401 이다
 * (본문 바이트는 Node 가 연결 재사용을 위해 흘려 버린다). 400/413 한국어 안내는 토큰 있는 호출(=firehub-api)에만 간다.
 */
import express, { type RequestHandler } from 'express';
import { internalAuth } from '../middleware/auth.js';
import { JSON_BODY_LIMIT } from './error-handler.js';

/**
 * `/agent/chat` 전용 본문 한도 (WD-631).
 *
 * firehub-api 의 ChatRequest 상한은 **문자 수**(message 50,000 · navigationContext 10,000 · screenContext 100,000)이고,
 * api 는 그 값을 Jackson 으로 직렬화해 보낸다. 최악의 경우 모든 문자가 제어문자라 `\uXXXX`(6바이트)로 이스케이프되면
 * (50,000 + 10,000 + 100,000) × 6 = 960,000 바이트 ≈ 938KB 이고, 여기에 모델·자격증명·시스템 프롬프트 같은
 * 나머지 필드가 붙는다. 기본 1mb 는 이 최악값에 여유가 거의 없어 검증을 통과한 요청이 413 이 될 수 있으므로,
 * 최악값의 2배 남짓인 2mb 로 둔다. api 상한을 바꾸면 이 값도 다시 계산한다.
 */
export const CHAT_BODY_LIMIT = '2mb';

/** `/agent/classify` 본문 한도 — 행 100개 배치(긴 텍스트 포함)를 받는다 */
export const CLASSIFY_BODY_LIMIT = '2mb';

/** `/agent/html-to-pdf` 본문 한도 — 이미지가 인라인된 리포트 HTML 을 받는다 */
export const PDF_BODY_LIMIT = '5mb';

/** 지정 한도(기본 1mb)의 JSON 파서를 만든다. strict 모드라 top-level null/문자열은 400 이 된다. */
export function jsonBody(limit: string = JSON_BODY_LIMIT): RequestHandler {
  return express.json({ limit });
}

/**
 * POST 라우트의 앞단 미들웨어 묶음 — `[internalAuth, ...afterAuth, jsonBody(limit)]` (WD-631).
 * 인증 → (대행 헤더 검증 등) → 파서 순서를 라우트마다 손으로 쓰다 뒤집히지 않게 한곳에 고정한다.
 * 라우트에서는 `router.post(path, ...authedJsonBody(), handler)` 처럼 펼쳐 쓴다.
 *
 * @param options.limit 본문 한도(기본 1mb)
 * @param options.afterAuth 인증과 파서 사이에 둘 미들웨어(예: graph 변경 라우트의 requireDelegation)
 */
export function authedJsonBody(
  options: { limit?: string; afterAuth?: RequestHandler[] } = {},
): RequestHandler[] {
  return [internalAuth, ...(options.afterAuth ?? []), jsonBody(options.limit)];
}
