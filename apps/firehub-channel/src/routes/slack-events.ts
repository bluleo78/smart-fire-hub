import { Router } from 'express';
import { slackSignature } from '../middleware/slack-signature.js';
import { forwardSlackInbound } from '../clients/firehub-api.js';

export const slackEventsRouter = Router();

/**
 * POST /slack/events — Slack Event API 수신 엔드포인트
 * - url_verification: challenge 응답
 * - event_callback: 즉시 200 ack 후 비동기 포워딩
 */
slackEventsRouter.post(
  '/events',
  (req, _res, next) => {
    // raw Buffer를 문자열로 변환 (서명 검증용)
    if (Buffer.isBuffer(req.body)) {
      req.body = req.body.toString('utf8');
    }
    next();
  },
  slackSignature,
  (req, res) => {
    const rawBody = typeof req.body === 'string' ? req.body : JSON.stringify(req.body);
    const payload = JSON.parse(rawBody) as { type: string; challenge?: string; team_id?: string; event?: unknown };

    if (payload.type === 'url_verification') {
      res.json({ challenge: payload.challenge });
      return;
    }

    res.json({ ok: true });

    // 비동기 포워딩 — 응답 후 처리하여 3초 타임아웃 준수
    if (payload.type === 'event_callback' && payload.team_id && payload.event) {
      forwardSlackInbound(payload.team_id, payload.event).catch((err: Error) => {
        console.error('[slack-events] inbound forward 실패:', err.message);
      });
    }
  },
);
