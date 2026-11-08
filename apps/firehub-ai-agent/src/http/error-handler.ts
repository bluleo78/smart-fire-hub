/**
 * 최종 에러 핸들러 + 전역 JSON 본문 한도 (WD-550)
 *
 * 예전에는 모든 오류를 500 `Internal server error` 로 돌려, 깨진 JSON·한도 초과 본문 같은
 * "클라이언트 잘못"까지 서버 장애처럼 보였다. body-parser 는 이런 오류에 `status`(4xx)와
 * `type`(예: `entity.parse.failed`)을 실어 next(err) 로 넘기므로, 그 조합만 골라 해당 상태와
 * 한국어 메시지로 응답한다. 그 밖의 오류는 내부 정보가 새지 않게 여전히 500 으로 숨긴다.
 */
import type { NextFunction, Request, Response } from 'express';

/** 전역 JSON 파서 한도 — 기본값(100kb)에 기대지 않고 명시한다. 채팅 본문(최대 5만 자 + 컨텍스트)을 넉넉히 수용. */
export const JSON_BODY_LIMIT = '1mb';

/** 본문을 JSON 으로 해석하지 못했을 때(깨진 JSON, strict 모드의 top-level null/문자열 등) 메시지 */
export const BODY_PARSE_FAILED_MESSAGE = '요청 본문을 해석할 수 없습니다 — 올바른 JSON 이어야 합니다.';

/**
 * body-parser(raw-body 포함)가 4xx 와 함께 실어 보내는 오류 type 별 한국어 메시지.
 * 목록에 없는 type 은 body-parser 오류로 인정하지 않는다 — 다른 라이브러리가 우연히 status 를 단 오류를
 * 그대로 노출하지 않기 위함이다. 413 은 한도 값을 넣어야 하므로 함수로 만든다.
 * 일반 객체 + `in` 대신 Map 을 쓰는 이유: `in` 은 'constructor'·'toString' 같은 프로토타입 키도 참으로 판정한다.
 */
const BODY_ERROR_MESSAGES = new Map<string, (err: BodyParserError) => string>(Object.entries({
  'entity.parse.failed': () => BODY_PARSE_FAILED_MESSAGE,
  'entity.too.large': (err) => tooLargeMessage(err.limit),
  'parameters.too.many': () => '요청 본문의 항목 수가 너무 많습니다.',
  'charset.unsupported': () => '지원하지 않는 문자셋입니다 — UTF-8 JSON 이어야 합니다.',
  'encoding.unsupported': () => '지원하지 않는 Content-Encoding 입니다.',
  'entity.verify.failed': () => '요청 본문 검증에 실패했습니다.',
  'request.aborted': () => '요청 본문 수신이 중단되었습니다.',
  'request.size.invalid': () => '요청 본문 크기가 Content-Length 와 일치하지 않습니다.',
} satisfies Record<string, (err: BodyParserError) => string>));

/** body-parser 가 만드는 http-errors 오류의 관심 필드 */
interface BodyParserError {
  type: string;
  status: number;
  /** entity.too.large 일 때 설정되는 한도(바이트) */
  limit?: number;
}

/**
 * 바이트 수를 사람이 읽는 단위로 표기한다(1048576 → "1MB", 102400 → "100KB").
 * 한도는 보통 정확한 MB/KB 배수로 설정되므로 나누어떨어질 때만 큰 단위를 쓴다.
 */
export function formatByteLimit(bytes: number): string {
  const MB = 1024 * 1024;
  if (bytes >= MB && bytes % MB === 0) return `${bytes / MB}MB`;
  if (bytes >= 1024 && bytes % 1024 === 0) return `${bytes / 1024}KB`;
  return `${bytes}B`;
}

/** 413 메시지 — 오류가 한도 정보를 갖고 있으면 그 파서의 실제 한도(전역 1MB / PDF 5MB)를 알려준다. */
function tooLargeMessage(limit: number | undefined): string {
  if (typeof limit === 'number' && Number.isFinite(limit) && limit > 0) {
    return `요청 본문이 너무 큽니다(최대 ${formatByteLimit(limit)}).`;
  }
  return '요청 본문이 너무 큽니다.';
}

/**
 * body-parser 클라이언트 오류인지 판별한다 — 알려진 type 이면서 status(또는 statusCode)가 400–499.
 * 같은 type 이라도 5xx(예: stream.not.readable)는 서버 측 문제이므로 여기서 제외해 500 으로 둔다.
 */
function asBodyParserError(err: unknown): BodyParserError | null {
  if (typeof err !== 'object' || err === null) return null;
  const e = err as { type?: unknown; status?: unknown; statusCode?: unknown; limit?: unknown };
  if (typeof e.type !== 'string' || !BODY_ERROR_MESSAGES.has(e.type)) return null;
  const status = typeof e.status === 'number' ? e.status : e.statusCode;
  if (typeof status !== 'number' || status < 400 || status > 499) return null;
  return { type: e.type, status, limit: typeof e.limit === 'number' ? e.limit : undefined };
}

/**
 * Express 최종 에러 핸들러.
 * - 응답이 이미 시작됐으면(SSE 스트리밍 중 등) 상태를 바꿀 수 없으므로 Express 기본 처리기에 넘겨 연결을 정리하게 한다.
 * - body-parser 4xx → 해당 상태 + 한국어 `{ error }` (클라이언트 잘못이므로 스택 없이 warn 한 줄)
 * - 그 외 → 500 `Internal server error` (내부 메시지 비노출, 스택은 error 로그)
 */
export function errorHandler(err: unknown, req: Request, res: Response, next: NextFunction): void {
  if (res.headersSent) {
    next(err);
    return;
  }

  const bodyError = asBodyParserError(err);
  if (bodyError) {
    console.warn(`[http] ${bodyError.status} ${bodyError.type} ${req.method} ${req.originalUrl}`);
    const toMessage = BODY_ERROR_MESSAGES.get(bodyError.type)!;
    res.status(bodyError.status).json({ error: toMessage(bodyError) });
    return;
  }

  console.error('Unhandled error:', err);
  res.status(500).json({ error: 'Internal server error' });
}
