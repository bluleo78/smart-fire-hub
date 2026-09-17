import { Request, Response, NextFunction } from 'express';
import { timingSafeEqual } from 'crypto';
import { ON_BEHALF_OF_HEADER, ON_BEHALF_OF_TENANT_HEADER } from '../constants.js';
import { isValidTenantId } from '../agent/tenant-paths.js';

export function internalAuth(req: Request, res: Response, next: NextFunction): void {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Internal ')) {
    res.status(401).json({ error: 'Unauthorized: Missing or invalid authorization header' });
    return;
  }

  const token = authHeader.substring(9); // Remove 'Internal ' prefix
  const expectedToken = process.env.INTERNAL_SERVICE_TOKEN;

  if (!expectedToken) {
    res.status(500).json({ error: 'Internal server error' });
    return;
  }

  const tokenBuf = Buffer.from(token);
  const expectedBuf = Buffer.from(expectedToken);
  if (tokenBuf.length !== expectedBuf.length || !timingSafeEqual(tokenBuf, expectedBuf)) {
    res.status(401).json({ error: 'Unauthorized: Invalid token' });
    return;
  }

  next();
}

/** 대행 주체 — 원요청을 낸 사용자와 그가 보고 있던 테넌트. `res.locals.delegation` 에 담긴다. */
export interface Delegation {
  userId: number;
  tenantId: number;
}

/**
 * 내부 대행 헤더를 검증해 `res.locals.delegation` 에 담는다. 라우트가 api 를 역호출할 때 쓸 주체다.
 *
 * <p>미들웨어로 두는 이유: 라우트마다 가드를 복사하면 라우트가 늘 때 한 곳을 빼먹고, 그러면 검증
 * 안 된 값이 그대로 흘러간다. `internalAuth` 와 같은 자리에서 끝내면 라우트 본문은 주체가 이미
 * 확정됐다고 가정할 수 있다.
 *
 * <p>헤더가 없거나 성립하지 않으면 추측하지 않고 400 이다. 이 헤더를 싣는 쪽은 firehub-api 의
 * `GraphMutationClient` 뿐이므로, 없다는 건 배포 스큐(구버전 api)나 호출측 버그다 — 조용히 폴백하면
 * 그 사실이 단일 멤버십 환경에서만 숨는다(누락 시 api 동작은 FireHubApiClient 생성자 주석 참고).
 * 술어는 경로 판정의 권위인 isValidTenantId 를 재사용한다 — userId 도 같은 강도(양수 정수)여야
 * `Number('')` 이 0 으로 통과해 존재하지 않는 사용자로 대행되는 일이 없다.
 */
export function requireDelegation(req: Request, res: Response, next: NextFunction): void {
  const userId = Number(req.headers[ON_BEHALF_OF_HEADER.toLowerCase()]);
  const tenantId = Number(req.headers[ON_BEHALF_OF_TENANT_HEADER.toLowerCase()]);

  if (!isValidTenantId(userId) || !isValidTenantId(tenantId)) {
    res.status(400).json({
      error: 'missing delegation headers',
      details: `${ON_BEHALF_OF_HEADER} / ${ON_BEHALF_OF_TENANT_HEADER} 헤더가 필요합니다.`,
    });
    return;
  }

  const delegation: Delegation = { userId, tenantId };
  res.locals.delegation = delegation;
  next();
}
