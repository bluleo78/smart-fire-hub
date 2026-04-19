import type { Request, Response, NextFunction } from 'express';

/**
 * 내부 서비스 간 통신 인증 미들웨어
 * Authorization: Internal <token> 형식만 허용
 */
export function internalAuth(req: Request, res: Response, next: NextFunction): void {
  const authHeader = req.headers['authorization'];
  const token = process.env.INTERNAL_TOKEN;

  if (!authHeader || !authHeader.startsWith('Internal ')) {
    res.status(401).json({ ok: false, error: 'unauthorized' });
    return;
  }

  const provided = authHeader.slice('Internal '.length);
  if (!token || provided !== token) {
    res.status(401).json({ ok: false, error: 'unauthorized' });
    return;
  }

  next();
}
