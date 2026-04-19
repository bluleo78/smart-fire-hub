# firehub-channel 마이크로서비스 설계

> **작성일**: 2026-04-19
> **상태**: 승인됨
> **관련 Phase**: Phase 10 (Channel 추상화)

---

## 1. 개요

`firehub-channel`은 외부 채널(Slack, Kakao, Email)로의 메시지 발송과 Slack Event 수신만 담당하는 얇은 HTTP 어댑터 서비스다. 비즈니스 로직, DB, 재시도 정책은 모두 `firehub-api`가 담당하고, `firehub-channel`은 외부 API 호출 결과만 반환한다.

**핵심 원칙:**
- DB 없음, 비즈니스 로직 없음, 상태 없음
- 발송에 필요한 credentials는 `firehub-api`가 요청 시 전달
- retry 판단은 `firehub-api` Outbox BackoffPolicy가 담당

---

## 2. 아키텍처

```
┌─────────────────────────────────────────────────────┐
│                   firehub-api                       │
│  ProactiveJobService → notification_outbox (DB)     │
│  OutboxWorker → POST /send → firehub-channel        │
│                                                     │
│  SlackInboundService ← POST /api/v1/channels/slack/inbound (Internal)
│  (user 매핑, ai_session, AI 호출, 응답)              │
└─────────────────────────────────────────────────────┘
           ↕  Authorization: Internal <token>
┌─────────────────────────────────────────────────────┐
│              firehub-channel (Node.js/TypeScript)   │
│                                                     │
│  POST /send      → Kakao / Slack / Email API        │
│  POST /slack/events ← Slack Events API              │
│    (HMAC 검증 + 3초 ack → /inbound/slack 포워딩)    │
└─────────────────────────────────────────────────────┘
           ↕  External APIs
    Kakao API  |  Slack API  |  SMTP Server
```

**Chat(SSE) 채널**은 `firehub-api` 내부 채널이므로 이전 대상에서 제외.

---

## 3. 기술 스택

| 항목 | 선택 | 이유 |
|------|------|------|
| 런타임 | Node.js/TypeScript | `firehub-ai-agent`와 동일 스택, 얇은 HTTP 어댑터에 적합 |
| HTTP 서버 | Express | `firehub-ai-agent` 패턴 일관성 |
| HTTP 클라이언트 | axios | Kakao/Slack API 호출 |
| 이메일 | Nodemailer | SMTP 발송 |
| 테스트 | Vitest + Supertest | 단위 + 통합 |

---

## 4. API 계약

### 4.1 Outbound — `POST /send`

**요청:**
```json
{
  "channel": "SLACK" | "KAKAO" | "EMAIL",
  "recipient": {
    "slackBotToken": "xoxb-...",
    "slackUserId": "U123",
    "slackChannelId": "C123",
    "kakaoAccessToken": "...",
    "emailAddress": "user@example.com",
    "smtpConfig": {
      "host": "smtp.example.com",
      "port": 587,
      "secure": false,
      "user": "...",
      "pass": "..."
    }
  },
  "message": {
    "text": "안녕하세요",
    "blocks": [...]
  },
  "threadTs": "123.456"
}
```

**응답:**
- `200 { "ok": true }`
- `400 { "ok": false, "error": "invalid_request" }`
- `401 { "ok": false, "error": "auth_error" }`
- `503 { "ok": false, "error": "upstream_error", "detail": "..." }`

### 4.2 Slack Inbound — `POST /slack/events`

Slack Events API 엔드포인트. `firehub-channel`이 외부로 공개.

- `url_verification`: 즉시 `{ "challenge": "..." }` 응답
- `event_callback`:
  1. HMAC-SHA256 서명 검증 (`X-Slack-Signature`, `X-Slack-Request-Timestamp`)
  2. 타임스탬프 ±5분 검증
  3. HTTP 200 즉시 반환 (3초 내 ack)
  4. 비동기로 `POST /api/v1/channels/slack/inbound` → `firehub-api` 포워딩

### 4.3 firehub-api 신규 Internal 엔드포인트

```
POST /api/v1/channels/slack/inbound
Authorization: Internal <token>
Content-Type: application/json

{
  "teamId": "T123",
  "event": {
    "type": "message",
    "channel": "C123",
    "user": "U123",
    "text": "안녕",
    "ts": "123.456",
    "thread_ts": "123.456"
  }
}
```

---

## 5. 내부 구조

```
apps/firehub-channel/
├── src/
│   ├── index.ts                  # Express 서버 진입점 (포트 3002)
│   ├── routes/
│   │   ├── send.ts               # POST /send 라우터
│   │   └── slack-events.ts       # POST /slack/events 라우터
│   ├── channels/
│   │   ├── slack.ts              # chat.postMessage / reactionsAdd
│   │   ├── kakao.ts              # sendMemoText
│   │   └── email.ts              # Nodemailer SMTP
│   ├── middleware/
│   │   ├── internal-auth.ts      # Authorization: Internal <token> 검증
│   │   └── slack-signature.ts    # HMAC-SHA256 서명 검증
│   └── clients/
│       └── firehub-api.ts        # POST /inbound/slack 호출
├── Dockerfile
├── package.json
└── tsconfig.json
```

**파일 크기 원칙:** `channels/` 하위 파일은 각 50~80줄 수준 유지. 외부 API 호출 로직만 포함.

---

## 6. firehub-api 변경 사항 (빅뱅 이전)

### 6.1 제거 대상

| 파일 | 위치 |
|------|------|
| `SlackEventsController` | `notification/inbound/` |
| `SlackSignatureVerifier` + 테스트 | `notification/inbound/` |
| `SlackInboundAsyncConfig` | `notification/inbound/` |
| `SlackApiClient` (reactionsAdd, postEphemeral, chatPostMessageInThread) | `notification/channels/slack/` |
| `KakaoApiClient` | `notification/channels/kakao/` |
| Email SMTP 호출 코드 | `notification/channels/email/` |

### 6.2 유지 대상

| 파일 | 이유 |
|------|------|
| `SlackInboundService` | 비즈니스 로직 (user 매핑, ai_session, AI 호출) |
| `SlackInboundMetrics` | 메트릭 유지 |
| `OutboxWorker` | `firehub-channel` POST /send 호출로 변경 |
| `KakaoChannel`, `SlackChannel`, `EmailChannel` | `firehub-channel` HTTP 클라이언트로 교체 |
| `notification_outbox`, `user_channel_binding` 등 DB 테이블 | `firehub-api` DB 유지 |

### 6.3 신규 추가

- `SlackInboundController`: `POST /api/v1/channels/slack/inbound` (Internal 전용)
- `ChannelHttpClient`: `firehub-channel` POST /send 호출 WebClient
- SecurityConfig: `/api/v1/channels/slack/inbound` Internal 인증 허용

---

## 7. 에러 처리

`firehub-channel`은 외부 API 오류 코드를 분류하여 반환. `firehub-api` Outbox가 이에 따라 재시도 여부를 결정.

| 상황 | HTTP 응답 | `firehub-api` 처리 |
|------|-----------|-------------------|
| 외부 API 5xx / timeout | `503 upstream_error` | BackoffPolicy 재시도 |
| 인증 오류 (token 만료 등) | `401 auth_error` | PermanentFailure 기록 |
| 잘못된 요청 | `400 invalid_request` | PermanentFailure 기록 |
| `firehub-channel` 다운 | connection refused | Outbox 재시도 |
| Internal auth 실패 | `401` | 즉시 오류 로그 |

---

## 8. 테스트 전략

### firehub-channel (Vitest + Supertest)

| 테스트 | 내용 |
|--------|------|
| `slack-signature.ts` | 유효/만료/조작 시그니처 경계값 (기존 9개 TC 이식) |
| `internal-auth.ts` | 유효 토큰 / 없음 / 잘못된 토큰 |
| `POST /send` (SLACK) | nock으로 Slack API mock → 성공/실패 |
| `POST /send` (KAKAO) | nock으로 Kakao API mock → 성공/token 만료 |
| `POST /send` (EMAIL) | Nodemailer mock → 성공/SMTP 오류 |
| `POST /slack/events` | url_verification / event_callback 정상 / 서명 실패 |

### firehub-api 변경분 (JUnit + WireMock)

| 테스트 | 내용 |
|--------|------|
| `SlackInboundController` | Internal 토큰 인증 + dispatch 위임 |
| `OutboxWorker` | WireMock으로 `firehub-channel` mock → 성공/503 재시도/401 PermanentFailure |
| `SlackInboundService` (기존) | 진입점 교체 후 기존 5개 TC 유지 |

---

## 9. 인프라 변경

### docker-compose.yml 추가

```yaml
firehub-channel:
  image: ghcr.io/bluleo78/smart-fire-hub/channel:latest
  ports:
    - "3002:3002"
  environment:
    - INTERNAL_TOKEN=${INTERNAL_TOKEN}
    - SLACK_SIGNING_SECRET=${SLACK_SIGNING_SECRET}
    - SLACK_PREVIOUS_SIGNING_SECRET=${SLACK_PREVIOUS_SIGNING_SECRET:-}
    - SLACK_PREVIOUS_SIGNING_SECRET_EXPIRES_AT=${SLACK_PREVIOUS_SIGNING_SECRET_EXPIRES_AT:-0}
    - FIREHUB_API_BASE_URL=http://api:8080
    - PORT=3002
  depends_on:
    - api
```

### nginx 라우팅 추가

```nginx
location /slack/events {
    proxy_pass http://firehub-channel:3002/slack/events;
}
```

> Slack의 Request URL은 `https://{domain}/slack/events`로 변경 필요.

---

## 10. 마이그레이션 순서 (빅뱅)

1. `firehub-channel` 서비스 구현 + 테스트
2. `firehub-api` Internal `/inbound/slack` 엔드포인트 추가
3. `firehub-api` `OutboxWorker`/`KakaoChannel`/`SlackChannel`/`EmailChannel` → `ChannelHttpClient` 교체
4. `firehub-api`에서 제거 대상 코드 삭제
5. docker-compose + nginx 변경
6. Slack App Request URL 변경
7. 통합 테스트 후 운영 배포

---

## 관련 문서

- Phase 10 스펙: `docs/superpowers/specs/2026-04-18-channel-abstraction-design.md`
- Phase 10 런북: `docs/runbooks/notification-outbox-rollout.md`
- 현재 구현: `apps/firehub-api/src/main/java/com/smartfirehub/notification/`
