# firehub-channel 마이크로서비스 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `firehub-api`의 외부 채널(Slack/Kakao/Email) 발송·수신 코드를 `firehub-channel` Node.js/TypeScript 서비스로 분리하고, `firehub-api`는 HTTP로 위임한다.

**Architecture:** `firehub-channel`은 DB 없는 얇은 HTTP 어댑터. `POST /send`로 외부 API 호출, `POST /slack/events`로 Slack 이벤트 수신 후 `firehub-api`에 포워딩. `firehub-api` OutboxWorker가 `firehub-channel /send`를 호출하는 방식으로 교체.

**Tech Stack:** Node.js/TypeScript (NodeNext), Express, Vitest, nock, Nodemailer, axios. Java/Spring Boot (`firehub-api`) 변경분은 jOOQ/WebClient/JUnit5/WireMock.

**Spec:** `docs/superpowers/specs/2026-04-19-firehub-channel-design.md`

---

## File Structure

### 신규 (`apps/firehub-channel/`)
```
apps/firehub-channel/
├── src/
│   ├── index.ts                        # Express 서버 (포트 3002)
│   ├── routes/
│   │   ├── send.ts                     # POST /send
│   │   ├── send.test.ts
│   │   ├── slack-events.ts             # POST /slack/events
│   │   └── slack-events.test.ts
│   ├── channels/
│   │   ├── slack.ts                    # Slack API 호출
│   │   ├── slack.test.ts
│   │   ├── kakao.ts                    # Kakao API 호출
│   │   ├── kakao.test.ts
│   │   ├── email.ts                    # Nodemailer SMTP
│   │   └── email.test.ts
│   ├── middleware/
│   │   ├── internal-auth.ts            # Authorization: Internal <token>
│   │   ├── internal-auth.test.ts
│   │   ├── slack-signature.ts          # HMAC-SHA256 서명 검증
│   │   └── slack-signature.test.ts
│   └── clients/
│       ├── firehub-api.ts              # POST /inbound/slack 포워딩
│       └── firehub-api.test.ts
├── Dockerfile
├── package.json
└── tsconfig.json
```

### 수정 (`apps/firehub-api/`)
```
수정:
  src/main/java/com/smartfirehub/notification/
    channels/ChannelHttpClient.java          # 신규: firehub-channel /send 호출
    channels/KakaoChannel.java               # ChannelHttpClient로 교체
    channels/SlackChannel.java               # ChannelHttpClient로 교체
    channels/EmailChannel.java               # ChannelHttpClient로 교체
    inbound/SlackInboundController.java      # 신규: POST /api/v1/channels/slack/inbound (Internal)
  src/main/java/com/smartfirehub/global/config/SecurityConfig.java  # inbound 경로 Internal 허용
  src/main/resources/application.yml                                  # channel.service.url 추가
  docker-compose.yml (루트)                                           # firehub-channel 서비스 추가
  nginx.conf (루트 또는 ~/prod)                                       # /slack/events 라우팅 추가

제거:
  src/main/java/com/smartfirehub/notification/inbound/SlackEventsController.java
  src/main/java/com/smartfirehub/notification/inbound/SlackSignatureVerifier.java
  src/main/java/com/smartfirehub/notification/inbound/SlackInboundAsyncConfig.java
  src/main/java/com/smartfirehub/notification/channels/slack/SlackApiClient.java (reactionsAdd 등)
  src/main/java/com/smartfirehub/notification/channels/kakao/KakaoApiClient.java
  (EmailChannel의 JavaMail 직접 호출 코드)
```

---

## Task 1: 프로젝트 설정

**Files:**
- Create: `apps/firehub-channel/package.json`
- Create: `apps/firehub-channel/tsconfig.json`
- Create: `apps/firehub-channel/src/index.ts`
- Create: `apps/firehub-channel/Dockerfile`
- Modify: `pnpm-workspace.yaml` (firehub-channel 추가)

- [ ] **Step 1: package.json 생성**

```json
{
  "name": "@smart-fire-hub/firehub-channel",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "tsx watch src/index.ts",
    "build": "tsc",
    "start": "node dist/index.js",
    "test": "vitest run",
    "test:watch": "vitest",
    "lint": "eslint src",
    "lint:fix": "eslint src --fix"
  },
  "dependencies": {
    "axios": "^1.7.9",
    "cors": "^2.8.5",
    "dotenv": "^16.4.7",
    "express": "^4.21.2",
    "nodemailer": "^6.9.16"
  },
  "devDependencies": {
    "@types/cors": "^2.8.17",
    "@types/express": "^5.0.0",
    "@types/nodemailer": "^6.4.17",
    "@types/node": "^22.0.0",
    "nock": "^14.0.11",
    "tsx": "^4.19.3",
    "typescript": "^5.7.3",
    "vitest": "^4.1.4"
  }
}
```

- [ ] **Step 2: tsconfig.json 생성**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "lib": ["ES2022"],
    "moduleResolution": "NodeNext",
    "esModuleInterop": true,
    "skipLibCheck": true,
    "strict": true,
    "resolveJsonModule": true,
    "outDir": "./dist",
    "rootDir": "./src",
    "declaration": true,
    "sourceMap": true,
    "forceConsistentCasingInFileNames": true,
    "allowSyntheticDefaultImports": true
  },
  "include": ["src/**/*"],
  "exclude": ["node_modules", "dist"]
}
```

- [ ] **Step 3: src/index.ts 생성**

```typescript
import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import { sendRouter } from './routes/send.js';
import { slackEventsRouter } from './routes/slack-events.js';

const app = express();
const PORT = process.env.PORT ?? '3002';

app.use(cors());
app.use(express.json());

app.get('/health', (_req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

app.use('/send', sendRouter);
app.use('/slack', slackEventsRouter);

app.listen(Number(PORT), () => {
  console.log(`firehub-channel listening on port ${PORT}`);
});
```

- [ ] **Step 4: Dockerfile 생성**

```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
RUN npm install -g pnpm
COPY package.json tsconfig.json ./
RUN pnpm install
COPY src/ ./src/
RUN pnpm build && pnpm deploy --prod /app/deploy

FROM node:22-alpine
WORKDIR /app
COPY --from=build /app/deploy ./
EXPOSE 3002
CMD ["node", "dist/index.js"]
```

- [ ] **Step 5: pnpm-workspace.yaml에 추가**

`pnpm-workspace.yaml` 파일에서 packages 목록에 `'apps/firehub-channel'` 추가.

- [ ] **Step 6: 의존성 설치 및 빌드 확인**

```bash
cd apps/firehub-channel && pnpm install && pnpm build
```
Expected: `dist/` 생성, 에러 없음.

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-channel/ pnpm-workspace.yaml
git commit -m "feat(channel): firehub-channel 프로젝트 설정"
```

---

## Task 2: Internal Auth 미들웨어

**Files:**
- Create: `apps/firehub-channel/src/middleware/internal-auth.ts`
- Create: `apps/firehub-channel/src/middleware/internal-auth.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

`src/middleware/internal-auth.test.ts`:
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Request, Response, NextFunction } from 'express';
import { internalAuth } from './internal-auth.js';

function mockReq(authHeader?: string): Partial<Request> {
  return { headers: authHeader ? { authorization: authHeader } : {} };
}

function mockRes() {
  const res: Partial<Response> & { statusCode?: number; body?: unknown } = {};
  res.status = vi.fn((code: number) => { res.statusCode = code; return res as Response; });
  res.json = vi.fn((data: unknown) => { res.body = data; return res as Response; });
  return res;
}

describe('internalAuth', () => {
  const VALID_TOKEN = 'test-internal-token-123';

  beforeEach(() => {
    process.env.INTERNAL_TOKEN = VALID_TOKEN;
  });

  it('유효한 토큰 → next() 호출', () => {
    const next = vi.fn();
    internalAuth(mockReq(`Internal ${VALID_TOKEN}`) as Request, mockRes() as Response, next as NextFunction);
    expect(next).toHaveBeenCalledOnce();
  });

  it('토큰 없음 → 401', () => {
    const res = mockRes();
    internalAuth(mockReq() as Request, res as Response, vi.fn() as NextFunction);
    expect(res.statusCode).toBe(401);
  });

  it('잘못된 토큰 → 401', () => {
    const res = mockRes();
    internalAuth(mockReq('Internal wrong-token') as Request, res as Response, vi.fn() as NextFunction);
    expect(res.statusCode).toBe(401);
  });

  it('Bearer 형식 → 401 (Internal만 허용)', () => {
    const res = mockRes();
    internalAuth(mockReq(`Bearer ${VALID_TOKEN}`) as Request, res as Response, vi.fn() as NextFunction);
    expect(res.statusCode).toBe(401);
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
cd apps/firehub-channel && pnpm test src/middleware/internal-auth.test.ts
```
Expected: FAIL (internalAuth not found)

- [ ] **Step 3: 구현**

`src/middleware/internal-auth.ts`:
```typescript
import type { Request, Response, NextFunction } from 'express';

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
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd apps/firehub-channel && pnpm test src/middleware/internal-auth.test.ts
```
Expected: 4 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-channel/src/middleware/
git commit -m "feat(channel): Internal auth 미들웨어"
```

---

## Task 3: Slack 서명 검증 미들웨어

**Files:**
- Create: `apps/firehub-channel/src/middleware/slack-signature.ts`
- Create: `apps/firehub-channel/src/middleware/slack-signature.test.ts`

기존 `SlackSignatureVerifier.java`의 로직을 TypeScript로 이식한다.

- [ ] **Step 1: 테스트용 서명 생성 헬퍼 준비 및 실패 테스트 작성**

`src/middleware/slack-signature.test.ts`:
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createHmac } from 'node:crypto';
import type { Request, Response, NextFunction } from 'express';
import { slackSignature } from './slack-signature.js';

function makeSignature(secret: string, ts: string, body: string): string {
  const baseString = `v0:${ts}:${body}`;
  const hash = createHmac('sha256', secret).update(baseString).digest('hex');
  return `v0=${hash}`;
}

function mockReq(headers: Record<string, string>, rawBody: string): Partial<Request> {
  return { headers, body: rawBody } as unknown as Partial<Request>;
}

function mockRes() {
  const res: Partial<Response> & { statusCode?: number; body?: unknown } = {};
  res.status = vi.fn((code: number) => { res.statusCode = code; return res as Response; });
  res.json = vi.fn((data: unknown) => { res.body = data; return res as Response; });
  return res;
}

const SECRET = 'test-signing-secret';
const BODY = '{"type":"event_callback"}';

describe('slackSignature', () => {
  beforeEach(() => {
    process.env.SLACK_SIGNING_SECRET = SECRET;
  });

  it('유효한 서명 → next()', () => {
    const ts = String(Math.floor(Date.now() / 1000));
    const sig = makeSignature(SECRET, ts, BODY);
    const next = vi.fn();
    slackSignature(
      mockReq({ 'x-slack-request-timestamp': ts, 'x-slack-signature': sig }, BODY) as Request,
      mockRes() as Response,
      next as NextFunction,
    );
    expect(next).toHaveBeenCalledOnce();
  });

  it('타임스탬프 5분 초과 → 401', () => {
    const ts = String(Math.floor(Date.now() / 1000) - 400);
    const sig = makeSignature(SECRET, ts, BODY);
    const res = mockRes();
    slackSignature(
      mockReq({ 'x-slack-request-timestamp': ts, 'x-slack-signature': sig }, BODY) as Request,
      res as Response,
      vi.fn() as NextFunction,
    );
    expect(res.statusCode).toBe(401);
  });

  it('서명 조작 → 401', () => {
    const ts = String(Math.floor(Date.now() / 1000));
    const res = mockRes();
    slackSignature(
      mockReq({ 'x-slack-request-timestamp': ts, 'x-slack-signature': 'v0=fakehash' }, BODY) as Request,
      res as Response,
      vi.fn() as NextFunction,
    );
    expect(res.statusCode).toBe(401);
  });

  it('헤더 없음 → 401', () => {
    const res = mockRes();
    slackSignature(mockReq({}, BODY) as Request, res as Response, vi.fn() as NextFunction);
    expect(res.statusCode).toBe(401);
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd apps/firehub-channel && pnpm test src/middleware/slack-signature.test.ts
```
Expected: FAIL

- [ ] **Step 3: 구현**

`src/middleware/slack-signature.ts`:
```typescript
import { createHmac, timingSafeEqual } from 'node:crypto';
import type { Request, Response, NextFunction } from 'express';

const SKEW_TOLERANCE_SECONDS = 300;

export function slackSignature(req: Request, res: Response, next: NextFunction): void {
  const ts = req.headers['x-slack-request-timestamp'] as string | undefined;
  const sig = req.headers['x-slack-signature'] as string | undefined;
  const secret = process.env.SLACK_SIGNING_SECRET;

  if (!ts || !sig || !secret) {
    res.status(401).json({ ok: false, error: 'missing_signature_headers' });
    return;
  }

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - Number(ts)) > SKEW_TOLERANCE_SECONDS) {
    res.status(401).json({ ok: false, error: 'timestamp_expired' });
    return;
  }

  const rawBody = typeof req.body === 'string' ? req.body : JSON.stringify(req.body);
  const baseString = `v0:${ts}:${rawBody}`;
  const expected = `v0=${createHmac('sha256', secret).update(baseString).digest('hex')}`;

  const expectedBuf = Buffer.from(expected, 'utf8');
  const sigBuf = Buffer.from(sig, 'utf8');

  if (expectedBuf.length !== sigBuf.length || !timingSafeEqual(expectedBuf, sigBuf)) {
    res.status(401).json({ ok: false, error: 'invalid_signature' });
    return;
  }

  next();
}
```

> **주의:** `express.json()` 이전에 raw body를 보존해야 서명 검증이 정확하다. `index.ts`에서 `/slack` 경로는 `express.raw({ type: '*/*' })` → 서명 검증 → `JSON.parse` 순으로 처리해야 한다. Task 9에서 처리.

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd apps/firehub-channel && pnpm test src/middleware/slack-signature.test.ts
```
Expected: 4 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-channel/src/middleware/
git commit -m "feat(channel): Slack HMAC-SHA256 서명 검증 미들웨어"
```

---

## Task 4: Slack 채널 어댑터

**Files:**
- Create: `apps/firehub-channel/src/channels/slack.ts`
- Create: `apps/firehub-channel/src/channels/slack.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

`src/channels/slack.test.ts`:
```typescript
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { sendSlackMessage, addSlackReaction, postSlackEphemeral } from './slack.js';

const BOT_TOKEN = 'xoxb-test';
const CHANNEL = 'C123';

beforeEach(() => nock.cleanAll());
afterEach(() => { if (!nock.isDone()) throw new Error('nock pending: ' + nock.pendingMocks()); });

describe('sendSlackMessage', () => {
  it('DM 전송 성공', async () => {
    nock('https://slack.com')
      .post('/api/chat.postMessage', { channel: CHANNEL, text: '안녕', token: undefined })
      .reply(200, { ok: true, ts: '123.456' });

    await expect(sendSlackMessage({ botToken: BOT_TOKEN, channel: CHANNEL, text: '안녕' })).resolves.toEqual({ ok: true, ts: '123.456' });
  });

  it('스레드 답글 전송', async () => {
    nock('https://slack.com')
      .post('/api/chat.postMessage')
      .reply(200, { ok: true, ts: '123.789' });

    await expect(sendSlackMessage({ botToken: BOT_TOKEN, channel: CHANNEL, text: '답글', threadTs: '123.456' })).resolves.toMatchObject({ ok: true });
  });

  it('Slack API 오류 → 에러 throw', async () => {
    nock('https://slack.com')
      .post('/api/chat.postMessage')
      .reply(200, { ok: false, error: 'channel_not_found' });

    await expect(sendSlackMessage({ botToken: BOT_TOKEN, channel: CHANNEL, text: '실패' })).rejects.toThrow('channel_not_found');
  });
});

describe('addSlackReaction', () => {
  it('reaction 추가 성공', async () => {
    nock('https://slack.com')
      .post('/api/reactions.add')
      .reply(200, { ok: true });

    await expect(addSlackReaction({ botToken: BOT_TOKEN, channel: CHANNEL, timestamp: '123.456', name: 'eyes' })).resolves.toBeUndefined();
  });
});

describe('postSlackEphemeral', () => {
  it('ephemeral 전송 성공', async () => {
    nock('https://slack.com')
      .post('/api/chat.postEphemeral')
      .reply(200, { ok: true });

    await expect(postSlackEphemeral({ botToken: BOT_TOKEN, channel: CHANNEL, user: 'U123', text: '안내' })).resolves.toBeUndefined();
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd apps/firehub-channel && pnpm test src/channels/slack.test.ts
```

- [ ] **Step 3: 구현**

`src/channels/slack.ts`:
```typescript
import axios from 'axios';

const SLACK_API = 'https://slack.com/api';

interface SendMessageParams {
  botToken: string;
  channel: string;
  text: string;
  threadTs?: string;
  blocks?: unknown[];
}

interface ReactionParams {
  botToken: string;
  channel: string;
  timestamp: string;
  name: string;
}

interface EphemeralParams {
  botToken: string;
  channel: string;
  user: string;
  text: string;
}

export async function sendSlackMessage(params: SendMessageParams): Promise<{ ok: boolean; ts?: string }> {
  const { data } = await axios.post(
    `${SLACK_API}/chat.postMessage`,
    {
      channel: params.channel,
      text: params.text,
      ...(params.threadTs ? { thread_ts: params.threadTs } : {}),
      ...(params.blocks ? { blocks: params.blocks } : {}),
    },
    { headers: { Authorization: `Bearer ${params.botToken}`, 'Content-Type': 'application/json' } },
  );
  if (!data.ok) throw new Error(data.error ?? 'slack_error');
  return data as { ok: boolean; ts?: string };
}

export async function addSlackReaction(params: ReactionParams): Promise<void> {
  const { data } = await axios.post(
    `${SLACK_API}/reactions.add`,
    { channel: params.channel, timestamp: params.timestamp, name: params.name },
    { headers: { Authorization: `Bearer ${params.botToken}`, 'Content-Type': 'application/json' } },
  );
  if (!data.ok && data.error !== 'already_reacted') throw new Error(data.error ?? 'reaction_error');
}

export async function postSlackEphemeral(params: EphemeralParams): Promise<void> {
  const { data } = await axios.post(
    `${SLACK_API}/chat.postEphemeral`,
    { channel: params.channel, user: params.user, text: params.text },
    { headers: { Authorization: `Bearer ${params.botToken}`, 'Content-Type': 'application/json' } },
  );
  if (!data.ok) throw new Error(data.error ?? 'ephemeral_error');
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd apps/firehub-channel && pnpm test src/channels/slack.test.ts
```
Expected: 5 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-channel/src/channels/slack.ts apps/firehub-channel/src/channels/slack.test.ts
git commit -m "feat(channel): Slack 채널 어댑터 (postMessage/reactionsAdd/postEphemeral)"
```

---

## Task 5: Kakao 채널 어댑터

**Files:**
- Create: `apps/firehub-channel/src/channels/kakao.ts`
- Create: `apps/firehub-channel/src/channels/kakao.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

`src/channels/kakao.test.ts`:
```typescript
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { sendKakaoMessage } from './kakao.js';

beforeEach(() => nock.cleanAll());
afterEach(() => { if (!nock.isDone()) throw new Error('nock pending'); });

describe('sendKakaoMessage', () => {
  it('전송 성공', async () => {
    nock('https://kapi.kakao.com')
      .post('/v2/api/talk/memo/default/send')
      .reply(200, { result_code: 0 });

    await expect(sendKakaoMessage({ accessToken: 'test-token', text: '안녕하세요' })).resolves.toBeUndefined();
  });

  it('토큰 만료 (401) → auth_error throw', async () => {
    nock('https://kapi.kakao.com')
      .post('/v2/api/talk/memo/default/send')
      .reply(401, { msg: 'not authorized' });

    await expect(sendKakaoMessage({ accessToken: 'expired', text: '메시지' })).rejects.toThrow('auth_error');
  });

  it('서버 오류 (500) → upstream_error throw', async () => {
    nock('https://kapi.kakao.com')
      .post('/v2/api/talk/memo/default/send')
      .reply(500);

    await expect(sendKakaoMessage({ accessToken: 'token', text: '메시지' })).rejects.toThrow('upstream_error');
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd apps/firehub-channel && pnpm test src/channels/kakao.test.ts
```

- [ ] **Step 3: 구현**

`src/channels/kakao.ts`:
```typescript
import axios, { AxiosError } from 'axios';

interface SendKakaoParams {
  accessToken: string;
  text: string;
}

export async function sendKakaoMessage(params: SendKakaoParams): Promise<void> {
  const templateObject = JSON.stringify({
    object_type: 'text',
    text: params.text,
    link: { web_url: '', mobile_web_url: '' },
  });

  try {
    await axios.post(
      'https://kapi.kakao.com/v2/api/talk/memo/default/send',
      new URLSearchParams({ template_object: templateObject }),
      {
        headers: {
          Authorization: `Bearer ${params.accessToken}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
      },
    );
  } catch (err) {
    const status = (err as AxiosError).response?.status;
    if (status === 401) throw new Error('auth_error');
    throw new Error('upstream_error');
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd apps/firehub-channel && pnpm test src/channels/kakao.test.ts
```
Expected: 3 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-channel/src/channels/kakao.ts apps/firehub-channel/src/channels/kakao.test.ts
git commit -m "feat(channel): Kakao 채널 어댑터 (나에게 보내기)"
```

---

## Task 6: Email 채널 어댑터

**Files:**
- Create: `apps/firehub-channel/src/channels/email.ts`
- Create: `apps/firehub-channel/src/channels/email.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

`src/channels/email.test.ts`:
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { sendEmail } from './email.js';

vi.mock('nodemailer', () => ({
  default: {
    createTransport: vi.fn(() => ({
      sendMail: vi.fn().mockResolvedValue({ messageId: 'test-id' }),
    })),
  },
}));

describe('sendEmail', () => {
  it('SMTP 전송 성공', async () => {
    await expect(sendEmail({
      smtpConfig: { host: 'smtp.test.com', port: 587, secure: false, user: 'u', pass: 'p' },
      to: 'dest@example.com',
      subject: '테스트',
      html: '<p>내용</p>',
    })).resolves.toBeUndefined();
  });

  it('SMTP 오류 → upstream_error throw', async () => {
    const nodemailer = await import('nodemailer');
    vi.mocked(nodemailer.default.createTransport).mockReturnValueOnce({
      sendMail: vi.fn().mockRejectedValue(new Error('ECONNREFUSED')),
    } as never);

    await expect(sendEmail({
      smtpConfig: { host: 'bad-host', port: 587, secure: false, user: 'u', pass: 'p' },
      to: 'dest@example.com',
      subject: '실패',
      html: '<p>내용</p>',
    })).rejects.toThrow('upstream_error');
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd apps/firehub-channel && pnpm test src/channels/email.test.ts
```

- [ ] **Step 3: 구현**

`src/channels/email.ts`:
```typescript
import nodemailer from 'nodemailer';

interface SmtpConfig {
  host: string;
  port: number;
  secure: boolean;
  user: string;
  pass: string;
}

interface SendEmailParams {
  smtpConfig: SmtpConfig;
  to: string;
  subject: string;
  html: string;
}

export async function sendEmail(params: SendEmailParams): Promise<void> {
  const transporter = nodemailer.createTransport({
    host: params.smtpConfig.host,
    port: params.smtpConfig.port,
    secure: params.smtpConfig.secure,
    auth: { user: params.smtpConfig.user, pass: params.smtpConfig.pass },
  });

  try {
    await transporter.sendMail({
      from: params.smtpConfig.user,
      to: params.to,
      subject: params.subject,
      html: params.html,
    });
  } catch {
    throw new Error('upstream_error');
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd apps/firehub-channel && pnpm test src/channels/email.test.ts
```
Expected: 2 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-channel/src/channels/email.ts apps/firehub-channel/src/channels/email.test.ts
git commit -m "feat(channel): Email 채널 어댑터 (Nodemailer SMTP)"
```

---

## Task 7: POST /send 라우터

**Files:**
- Create: `apps/firehub-channel/src/routes/send.ts`
- Create: `apps/firehub-channel/src/routes/send.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

`src/routes/send.test.ts`:
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import request from 'supertest';
import express from 'express';
import { sendRouter } from './send.js';

vi.mock('../channels/slack.js', () => ({
  sendSlackMessage: vi.fn().mockResolvedValue({ ok: true, ts: '123' }),
}));
vi.mock('../channels/kakao.js', () => ({
  sendKakaoMessage: vi.fn().mockResolvedValue(undefined),
}));
vi.mock('../channels/email.js', () => ({
  sendEmail: vi.fn().mockResolvedValue(undefined),
}));
vi.mock('../middleware/internal-auth.js', () => ({
  internalAuth: (_req: unknown, _res: unknown, next: () => void) => next(),
}));

const app = express();
app.use(express.json());
app.use('/send', sendRouter);

describe('POST /send', () => {
  it('SLACK 채널 발송 성공', async () => {
    const res = await request(app).post('/send').send({
      channel: 'SLACK',
      recipient: { slackBotToken: 'xoxb-test', slackChannelId: 'C123' },
      message: { text: '안녕' },
    });
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ ok: true });
  });

  it('KAKAO 채널 발송 성공', async () => {
    const res = await request(app).post('/send').send({
      channel: 'KAKAO',
      recipient: { kakaoAccessToken: 'token' },
      message: { text: '메시지' },
    });
    expect(res.status).toBe(200);
  });

  it('EMAIL 채널 발송 성공', async () => {
    const res = await request(app).post('/send').send({
      channel: 'EMAIL',
      recipient: { emailAddress: 'a@b.com', smtpConfig: { host: 'h', port: 587, secure: false, user: 'u', pass: 'p' } },
      message: { text: '제목', html: '<p>내용</p>' },
    });
    expect(res.status).toBe(200);
  });

  it('알 수 없는 channel → 400', async () => {
    const res = await request(app).post('/send').send({ channel: 'UNKNOWN', recipient: {}, message: { text: '' } });
    expect(res.status).toBe(400);
  });

  it('auth_error → 401', async () => {
    const { sendKakaoMessage } = await import('../channels/kakao.js');
    vi.mocked(sendKakaoMessage).mockRejectedValueOnce(new Error('auth_error'));

    const res = await request(app).post('/send').send({
      channel: 'KAKAO',
      recipient: { kakaoAccessToken: 'expired' },
      message: { text: '실패' },
    });
    expect(res.status).toBe(401);
  });

  it('upstream_error → 503', async () => {
    const { sendSlackMessage } = await import('../channels/slack.js');
    vi.mocked(sendSlackMessage).mockRejectedValueOnce(new Error('upstream_error'));

    const res = await request(app).post('/send').send({
      channel: 'SLACK',
      recipient: { slackBotToken: 'xoxb', slackChannelId: 'C1' },
      message: { text: '실패' },
    });
    expect(res.status).toBe(503);
  });
});
```

> `supertest` 추가 필요: `pnpm add -D supertest @types/supertest`

- [ ] **Step 2: supertest 설치 및 실패 확인**

```bash
cd apps/firehub-channel && pnpm add -D supertest @types/supertest
pnpm test src/routes/send.test.ts
```
Expected: FAIL

- [ ] **Step 3: 구현**

`src/routes/send.ts`:
```typescript
import { Router } from 'express';
import { internalAuth } from '../middleware/internal-auth.js';
import { sendSlackMessage } from '../channels/slack.js';
import { sendKakaoMessage } from '../channels/kakao.js';
import { sendEmail } from '../channels/email.js';

export const sendRouter = Router();

sendRouter.post('/', internalAuth, async (req, res) => {
  const { channel, recipient, message, threadTs } = req.body as {
    channel: string;
    recipient: Record<string, unknown>;
    message: { text?: string; html?: string; blocks?: unknown[] };
    threadTs?: string;
  };

  try {
    if (channel === 'SLACK') {
      await sendSlackMessage({
        botToken: recipient.slackBotToken as string,
        channel: recipient.slackChannelId as string,
        text: message.text ?? '',
        threadTs,
        blocks: message.blocks,
      });
    } else if (channel === 'KAKAO') {
      await sendKakaoMessage({
        accessToken: recipient.kakaoAccessToken as string,
        text: message.text ?? '',
      });
    } else if (channel === 'EMAIL') {
      await sendEmail({
        smtpConfig: recipient.smtpConfig as { host: string; port: number; secure: boolean; user: string; pass: string },
        to: recipient.emailAddress as string,
        subject: message.text ?? '',
        html: message.html ?? message.text ?? '',
      });
    } else {
      res.status(400).json({ ok: false, error: 'unknown_channel' });
      return;
    }
    res.json({ ok: true });
  } catch (err) {
    const msg = (err as Error).message;
    if (msg === 'auth_error') { res.status(401).json({ ok: false, error: msg }); return; }
    if (msg === 'upstream_error') { res.status(503).json({ ok: false, error: msg }); return; }
    res.status(503).json({ ok: false, error: 'upstream_error' });
  }
});
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd apps/firehub-channel && pnpm test src/routes/send.test.ts
```
Expected: 6 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-channel/src/routes/send.ts apps/firehub-channel/src/routes/send.test.ts apps/firehub-channel/package.json
git commit -m "feat(channel): POST /send 라우터"
```

---

## Task 8: firehub-api 포워딩 클라이언트

**Files:**
- Create: `apps/firehub-channel/src/clients/firehub-api.ts`
- Create: `apps/firehub-channel/src/clients/firehub-api.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

`src/clients/firehub-api.test.ts`:
```typescript
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { forwardSlackInbound } from './firehub-api.js';

const API_BASE = 'http://api:8080';

beforeEach(() => {
  process.env.FIREHUB_API_BASE_URL = API_BASE;
  nock.cleanAll();
});
afterEach(() => { if (!nock.isDone()) throw new Error('nock pending'); });

describe('forwardSlackInbound', () => {
  it('inbound 이벤트 포워딩 성공', async () => {
    nock(API_BASE)
      .post('/api/v1/channels/slack/inbound', { teamId: 'T123', event: { type: 'message' } })
      .reply(200);

    await expect(forwardSlackInbound('T123', { type: 'message' })).resolves.toBeUndefined();
  });

  it('firehub-api 오류 → 에러 throw', async () => {
    nock(API_BASE)
      .post('/api/v1/channels/slack/inbound')
      .reply(500);

    await expect(forwardSlackInbound('T123', { type: 'message' })).rejects.toThrow();
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd apps/firehub-channel && pnpm test src/clients/firehub-api.test.ts
```

- [ ] **Step 3: 구현**

`src/clients/firehub-api.ts`:
```typescript
import axios from 'axios';

export async function forwardSlackInbound(teamId: string, event: unknown): Promise<void> {
  const baseUrl = process.env.FIREHUB_API_BASE_URL ?? 'http://api:8080';
  const token = process.env.INTERNAL_TOKEN;
  await axios.post(
    `${baseUrl}/api/v1/channels/slack/inbound`,
    { teamId, event },
    { headers: { Authorization: `Internal ${token}` } },
  );
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd apps/firehub-channel && pnpm test src/clients/firehub-api.test.ts
```
Expected: 2 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-channel/src/clients/
git commit -m "feat(channel): firehub-api inbound 포워딩 클라이언트"
```

---

## Task 9: POST /slack/events 라우터

**Files:**
- Create: `apps/firehub-channel/src/routes/slack-events.ts`
- Create: `apps/firehub-channel/src/routes/slack-events.test.ts`
- Modify: `apps/firehub-channel/src/index.ts` (raw body 처리)

- [ ] **Step 1: 실패 테스트 작성**

`src/routes/slack-events.test.ts`:
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import request from 'supertest';
import express from 'express';
import { createHmac } from 'node:crypto';
import { slackEventsRouter } from './slack-events.js';

vi.mock('../clients/firehub-api.js', () => ({
  forwardSlackInbound: vi.fn().mockResolvedValue(undefined),
}));

const SECRET = 'test-secret';
const app = express();
app.use(express.raw({ type: '*/*' }));
app.use('/slack', slackEventsRouter);

function signedHeaders(body: string): Record<string, string> {
  const ts = String(Math.floor(Date.now() / 1000));
  const sig = `v0=${createHmac('sha256', SECRET).update(`v0:${ts}:${body}`).digest('hex')}`;
  return { 'x-slack-request-timestamp': ts, 'x-slack-signature': sig, 'content-type': 'application/json' };
}

beforeEach(() => { process.env.SLACK_SIGNING_SECRET = SECRET; });

describe('POST /slack/events', () => {
  it('url_verification → challenge 응답', async () => {
    const body = JSON.stringify({ type: 'url_verification', challenge: 'abc123' });
    const res = await request(app).post('/slack/events').set(signedHeaders(body)).send(body);
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ challenge: 'abc123' });
  });

  it('event_callback → 200 ack + 비동기 포워딩', async () => {
    const body = JSON.stringify({ type: 'event_callback', team_id: 'T123', event: { type: 'message' } });
    const res = await request(app).post('/slack/events').set(signedHeaders(body)).send(body);
    expect(res.status).toBe(200);
  });

  it('서명 없음 → 401', async () => {
    const res = await request(app).post('/slack/events').send('{}');
    expect(res.status).toBe(401);
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd apps/firehub-channel && pnpm test src/routes/slack-events.test.ts
```

- [ ] **Step 3: 구현**

`src/routes/slack-events.ts`:
```typescript
import { Router } from 'express';
import { slackSignature } from '../middleware/slack-signature.js';
import { forwardSlackInbound } from '../clients/firehub-api.js';

export const slackEventsRouter = Router();

slackEventsRouter.post(
  '/events',
  (req, _res, next) => {
    // raw body를 string으로 변환 후 JSON parse
    if (Buffer.isBuffer(req.body)) {
      (req as unknown as { rawBody: string }).rawBody = req.body.toString('utf8');
      req.body = (req as unknown as { rawBody: string }).rawBody;
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

    // 3초 내 ack
    res.json({ ok: true });

    // 비동기 포워딩
    if (payload.type === 'event_callback' && payload.team_id && payload.event) {
      forwardSlackInbound(payload.team_id, payload.event).catch((err: Error) => {
        console.error('[slack-events] inbound forward 실패:', err.message);
      });
    }
  },
);
```

- [ ] **Step 4: index.ts raw body 처리로 수정**

`src/index.ts`에서 `/slack` 경로는 `express.raw`를 사용하도록 변경:

```typescript
import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import { sendRouter } from './routes/send.js';
import { slackEventsRouter } from './routes/slack-events.js';

const app = express();
const PORT = process.env.PORT ?? '3002';

app.use(cors());

// /slack/events는 raw body가 필요 (서명 검증)
app.use('/slack', express.raw({ type: '*/*' }), slackEventsRouter);

// 나머지 경로는 JSON
app.use(express.json());
app.use('/send', sendRouter);

app.get('/health', (_req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

app.listen(Number(PORT), () => {
  console.log(`firehub-channel listening on port ${PORT}`);
});
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
cd apps/firehub-channel && pnpm test src/routes/slack-events.test.ts
```
Expected: 3 tests PASS

- [ ] **Step 6: 전체 테스트 통과 확인**

```bash
cd apps/firehub-channel && pnpm test
```
Expected: 모든 테스트 PASS

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-channel/src/routes/ apps/firehub-channel/src/index.ts
git commit -m "feat(channel): POST /slack/events 라우터 (HMAC + ack + 포워딩)"
```

---

## Task 10: firehub-api — Internal /inbound/slack 엔드포인트

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackInboundController.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/global/config/SecurityConfig.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/notification/inbound/SlackInboundControllerTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`SlackInboundControllerTest.java`:
```java
package com.smartfirehub.notification.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class SlackInboundControllerTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private SlackInboundService slackInboundService;

    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Test
    void inbound_유효한_Internal_토큰_dispatch_호출() throws Exception {
        var body = Map.of("teamId", "T123", "event", Map.of("type", "message", "channel", "C123", "user", "U123", "text", "hi", "ts", "1.0"));

        mockMvc.perform(post("/api/v1/channels/slack/inbound")
                .header("Authorization", "Internal " + INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        verify(slackInboundService).dispatch(anyString(), any());
    }

    @Test
    void inbound_Internal_토큰_없음_401() throws Exception {
        var body = Map.of("teamId", "T123", "event", Map.of("type", "message"));

        mockMvc.perform(post("/api/v1/channels/slack/inbound")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.notification.inbound.SlackInboundControllerTest"
```
Expected: FAIL (endpoint not found)

- [ ] **Step 3: SlackInboundController 구현**

```java
package com.smartfirehub.notification.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** firehub-channel로부터 Slack inbound 이벤트를 수신하는 Internal 전용 엔드포인트. */
@RestController
@RequestMapping("/api/v1/channels/slack")
public class SlackInboundController {

    private final SlackInboundService slackInboundService;

    public SlackInboundController(SlackInboundService slackInboundService) {
        this.slackInboundService = slackInboundService;
    }

    @PostMapping("/inbound")
    public ResponseEntity<Void> inbound(@RequestBody InboundRequest request) {
        slackInboundService.dispatch(request.teamId(), request.event());
        return ResponseEntity.ok().build();
    }

    public record InboundRequest(String teamId, JsonNode event) {}
}
```

- [ ] **Step 4: SecurityConfig — /inbound 경로 Internal 인증 추가**

`SecurityConfig.java`에서 `/api/v1/channels/slack/inbound`에 `InternalAuthFilter` 또는 `hasRole("INTERNAL")` 적용. 기존 Internal 인증 패턴 확인 후 동일 방식으로 추가.

> 기존 `/api/v1/channels/slack/events` permitAll 항목은 제거 (firehub-channel로 이전됨).

- [ ] **Step 5: 테스트 통과 확인**

```bash
cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.notification.inbound.SlackInboundControllerTest"
```
Expected: 2 tests PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackInboundController.java \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/inbound/SlackInboundControllerTest.java \
        apps/firehub-api/src/main/java/com/smartfirehub/global/config/SecurityConfig.java
git commit -m "feat(api): POST /api/v1/channels/slack/inbound Internal 엔드포인트"
```

---

## Task 11: firehub-api — ChannelHttpClient

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/ChannelHttpClient.java`
- Modify: `apps/firehub-api/src/main/resources/application.yml`

- [ ] **Step 1: application.yml에 채널 서비스 URL 추가**

```yaml
# application.yml
channel:
  service:
    url: ${CHANNEL_SERVICE_URL:http://firehub-channel:3002}
```

- [ ] **Step 2: ChannelHttpClient 구현**

```java
package com.smartfirehub.notification.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * firehub-channel 서비스에 메시지 발송을 위임하는 HTTP 클라이언트.
 * 실제 외부 API(Slack/Kakao/Email) 호출은 firehub-channel이 담당한다.
 */
@Component
public class ChannelHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ChannelHttpClient.class);

    private final WebClient webClient;
    private final String internalToken;

    public ChannelHttpClient(
            WebClient.Builder webClientBuilder,
            @Value("${channel.service.url}") String channelServiceUrl,
            @Value("${internal.service.token}") String internalToken) {
        this.webClient = webClientBuilder.baseUrl(channelServiceUrl).build();
        this.internalToken = internalToken;
    }

    /**
     * firehub-channel POST /send 호출.
     *
     * @param channel  채널 타입 (SLACK, KAKAO, EMAIL)
     * @param recipient 수신자 정보 (채널별 credentials 포함)
     * @param message   메시지 내용
     * @throws ChannelHttpException 발송 실패 시 (auth_error → PermanentFailure, upstream_error → 재시도 가능)
     */
    public void send(String channel, Map<String, Object> recipient, Map<String, Object> message) {
        send(channel, recipient, message, null);
    }

    public void send(String channel, Map<String, Object> recipient, Map<String, Object> message, String threadTs) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("channel", channel);
        body.put("recipient", recipient);
        body.put("message", message);
        if (threadTs != null) body.put("threadTs", threadTs);

        var response = webClient.post()
                .uri("/send")
                .header("Authorization", "Internal " + internalToken)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.value() == 401,
                        res -> res.bodyToMono(String.class)
                                .map(b -> new ChannelHttpException("auth_error", 401)))
                .onStatus(status -> status.is5xxServerError(),
                        res -> res.bodyToMono(String.class)
                                .map(b -> new ChannelHttpException("upstream_error", status.value())))
                .toBodilessEntity()
                .block();

        log.debug("channel send 완료: channel={}, status={}", channel, response != null ? response.getStatusCode() : "null");
    }
}
```

- [ ] **Step 3: ChannelHttpException 추가**

```java
package com.smartfirehub.notification.channels;

public class ChannelHttpException extends RuntimeException {
    private final int statusCode;

    public ChannelHttpException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() { return statusCode; }
    public boolean isAuthError() { return statusCode == 401; }
}
```

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/ChannelHttpClient.java \
        apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/ChannelHttpException.java \
        apps/firehub-api/src/main/resources/application.yml
git commit -m "feat(api): ChannelHttpClient — firehub-channel /send 위임 클라이언트"
```

---

## Task 12: firehub-api — Channel 어댑터 교체 + 구 코드 제거

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/KakaoChannel.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/SlackChannel.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/EmailChannel.java`
- Delete: `SlackEventsController.java`, `SlackSignatureVerifier.java`, `SlackInboundAsyncConfig.java`
- Delete: `SlackApiClient.java`, `KakaoApiClient.java` (+ Email SMTP 직접 호출 코드)

- [ ] **Step 1: SlackChannel — ChannelHttpClient 사용으로 교체**

`SlackChannel.java`의 실제 Slack API 호출 부분을 `ChannelHttpClient.send("SLACK", recipient, message, threadTs)` 호출로 교체. `replyTo(workspaceId, channel, threadTs, text)` 메서드가 SlackWorkspaceRepository에서 botToken 조회 후 recipient map 구성 → `channelHttpClient.send()` 호출하는 구조로 변경.

```java
// SlackChannel.replyTo 변경 예시
public void replyTo(long workspaceId, String channel, String threadTs, String text) {
    var workspace = workspaceRepo.findById(workspaceId)
            .orElseThrow(() -> new IllegalArgumentException("workspace not found: " + workspaceId));
    String botToken = encryption.decrypt(workspace.botTokenEnc());

    var recipient = Map.of(
            "slackBotToken", botToken,
            "slackChannelId", channel
    );
    var message = Map.of("text", text);
    channelHttpClient.send("SLACK", recipient, message, threadTs);
}
```

- [ ] **Step 2: KakaoChannel — ChannelHttpClient 사용으로 교체**

KakaoChannel의 `sendMessage(userId, text)` 메서드에서 KakaoApiClient 제거. UserChannelBindingRepository에서 accessToken 조회 후 `channelHttpClient.send("KAKAO", recipient, message)` 호출.

- [ ] **Step 3: EmailChannel — ChannelHttpClient 사용으로 교체**

EmailChannel의 SMTP 직접 호출을 `channelHttpClient.send("EMAIL", recipient, message)` 로 교체.

- [ ] **Step 4: 구 코드 삭제**

```bash
# 삭제 대상
rm apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackEventsController.java
rm apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackSignatureVerifier.java
rm apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackInboundAsyncConfig.java
rm apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/slack/SlackApiClient.java
rm apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/kakao/KakaoApiClient.java
# 관련 테스트 파일도 삭제
rm apps/firehub-api/src/test/java/com/smartfirehub/notification/inbound/SlackSignatureVerifierTest.java
```

- [ ] **Step 5: 전체 gradle 빌드 통과 확인**

```bash
cd apps/firehub-api && ./gradlew build -x test
```
Expected: BUILD SUCCESSFUL (컴파일 에러 없음)

- [ ] **Step 6: 전체 테스트 통과 확인**

```bash
cd apps/firehub-api && ./gradlew test
```
Expected: 모든 테스트 PASS

- [ ] **Step 7: 커밋**

```bash
git add -A apps/firehub-api/src/
git commit -m "feat(api): Channel 어댑터 ChannelHttpClient 교체 + 구 코드 제거"
```

---

## Task 13: 인프라 설정

**Files:**
- Modify: `docker-compose.yml` (루트 또는 `~/prod/smart-fire-hub/docker-compose.yml`)
- Modify: `nginx.conf`

- [ ] **Step 1: docker-compose.yml에 firehub-channel 추가**

```yaml
firehub-channel:
  image: ghcr.io/bluleo78/smart-fire-hub/channel:latest
  container_name: smart-fire-hub-prod-channel-1
  environment:
    - INTERNAL_TOKEN=${INTERNAL_TOKEN}
    - SLACK_SIGNING_SECRET=${SLACK_SIGNING_SECRET}
    - SLACK_PREVIOUS_SIGNING_SECRET=${SLACK_PREVIOUS_SIGNING_SECRET:-}
    - SLACK_PREVIOUS_SIGNING_SECRET_EXPIRES_AT=${SLACK_PREVIOUS_SIGNING_SECRET_EXPIRES_AT:-0}
    - FIREHUB_API_BASE_URL=http://api:8080
    - PORT=3002
  depends_on:
    api:
      condition: service_healthy
  networks:
    - app-network
  restart: unless-stopped
```

- [ ] **Step 2: nginx.conf에 Slack Events 라우팅 추가**

```nginx
# Slack Events API (firehub-channel로 직접 라우팅)
location /slack/events {
    proxy_pass http://firehub-channel:3002/slack/events;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Slack-Request-Timestamp $http_x_slack_request_timestamp;
    proxy_set_header X-Slack-Signature $http_x_slack_signature;
}
```

> 기존 `/api/v1/channels/slack/events` nginx 항목이 있으면 제거.

- [ ] **Step 3: Slack App Request URL 변경**

https://api.slack.com/apps → Event Subscriptions → Request URL을:
`https://{domain}/api/v1/channels/slack/events` → `https://{domain}/slack/events`
로 변경 후 Verified 확인.

- [ ] **Step 4: 커밋**

```bash
git add docker-compose.yml nginx.conf
git commit -m "feat(infra): firehub-channel docker-compose + nginx 설정"
```

---

## Task 14: 배포 및 검증

- [ ] **Step 1: firehub-channel 이미지 빌드 & push**

```bash
./scripts/deploy.sh channel
```
> `deploy.sh`에 `channel` 케이스가 없으면 추가:
```bash
docker build --no-cache -t ghcr.io/bluleo78/smart-fire-hub/channel:latest apps/firehub-channel/
docker push ghcr.io/bluleo78/smart-fire-hub/channel:latest
```

- [ ] **Step 2: firehub-api 배포**

```bash
./scripts/deploy.sh api
```

- [ ] **Step 3: 컨테이너 상태 확인**

```bash
cd ~/prod/smart-fire-hub && docker compose ps
```
Expected: firehub-channel, firehub-api 모두 healthy/running.

- [ ] **Step 4: Health check**

```bash
curl http://localhost:3002/health
# {"status":"ok","timestamp":"..."}
```

- [ ] **Step 5: Slack DM 테스트**

봇에게 DM → API 로그 확인:
```
slack inbound — 응답 완료 (team=T..., ts=..., sessionId=...)
```
Slack DM 스레드에 AI 응답 도착 확인.

- [ ] **Step 6: ROADMAP 업데이트**

`docs/ROADMAP.md` Phase 10 항목을 `firehub-channel` 분리 완료 내용으로 업데이트.

- [ ] **Step 7: 최종 커밋**

```bash
git add docs/ROADMAP.md
git commit -m "docs(roadmap): Phase 10 firehub-channel 분리 완료"
```
