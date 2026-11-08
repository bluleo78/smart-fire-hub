/**
 * 라우트 전용 JSON 본문 파서 헬퍼 (WD-550)
 *
 * 전역 `app.use(express.json())` 를 두지 않는다 — 전역 파서가 먼저 본문을 읽으면 라우트에 단 다른 한도
 * (classify 2mb, html-to-pdf 5mb)가 영영 적용되지 않기 때문이다. 대신 본문을 읽는 모든 POST 라우트가
 * 이 헬퍼로 자기 파서를 단다. `router.use(...)` 로 달지 않는 이유: 모든 라우터가 '/agent' 에 마운트되므로
 * 라우터 수준 파서는 다른 라우터로 가는 요청의 본문까지 먼저 읽어 버린다.
 *
 * 파서는 인증 미들웨어보다 앞에 둔다 — 깨진/과대 본문은 토큰 여부와 무관하게 400/413 으로 일관되게 응답한다.
 */
import express, { type RequestHandler } from 'express';
import { JSON_BODY_LIMIT } from './error-handler.js';

/** 지정 한도(기본 1mb)의 JSON 파서를 만든다. strict 모드라 top-level null/문자열은 400 이 된다. */
export function jsonBody(limit: string = JSON_BODY_LIMIT): RequestHandler {
  return express.json({ limit });
}
