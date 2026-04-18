# Channel(알림 채널) 추상화 — 설계 문서

- 작성일: 2026-04-18
- 작성자: 브레인스토밍 합의(Architect+Critic 리뷰 반영)
- 상태: Draft (사용자 검토 대기)
- 영향 범위: `apps/firehub-api`, `apps/firehub-web`, `apps/firehub-ai-agent`

## 1. 동기 (Why)

현재 `DeliveryChannel` 인터페이스는 ProactiveJob 결과 발송에만 묶여 있으며, 구현체는 `EmailDeliveryChannel`/`ChatDeliveryChannel` 두 개다. 다음 요구가 누적되면서 추상화 확장이 필요해졌다.

- 사용자에게 카카오톡/Slack 같은 외부 채널로 알림을 보내고 싶다.
- AI가 능동적으로 사용자에게 말 거는 흐름(Proactive push, Anomaly 등)이 늘면서 동일한 발송 인프라를 재사용할 필요가 커졌다.
- Slack 같은 채널은 사용자 응답을 받아 AI와 양방향 대화로 이어지는 트렌드가 있다.

핵심 가치: **"양방향 AI"** — Slack에서 사용자가 답변하면 AI가 같은 스레드에서 이어 받게 한다.

## 2. 결정 요약

| 항목 | 결정 |
|---|---|
| Scope | 모든 사용자 알림 + AI 능동 푸시 통합 |
| Routing | 이벤트 생성자가 채널/수신자 결정 (사용자별 라우팅 룰 없음) |
| 외부 주소 확보 | 사용자 본인이 프로필에서 OAuth/직접 연동 |
| V1 채널 | CHAT(기존), EMAIL(기존), KAKAO(나에게 보내기, outbound only), SLACK(양방향) |
| 메시지 렌더링 | StandardPayload 자동 변환 + 채널별 raw override |
| 실패 처리 | 일시 실패 자동 재시도, 영구 실패 시 발송자/사용자 통보 |
| Opt-out | 채널별 사용자 글로벌 on/off (CHAT 제외, 안전망) |
| 발송 패턴 | Outbox + 비동기 디스패처 워커 (PG SKIP LOCKED + LISTEN/NOTIFY) |
| Inbound | Slack만 양방향, outbox 우회 전용 비동기 풀 |

## 3. 전체 아키텍처

```
┌─────────────── Outbound (도메인 트랜잭션 → outbox → 워커) ──────────────┐
│                                                                          │
│  도메인 (ProactiveJobService, Pipeline 완료 EventListener,                │
│         Anomaly EventListener, AI 능동 푸시)                              │
│        │  도메인 TX commit                                                │
│        │  ↓ AFTER_COMMIT 훅                                               │
│  NotificationDispatcher.enqueue(NotificationRequest)                      │
│        │                                                                  │
│        │  ① 수신자 × requested_channels 펼치기                             │
│        │  ② preference·binding 검사로 resolved_channels 확정               │
│        │  ③ 모두 거부되면 CHAT 강제 1행                                    │
│        │  ④ idempotency_key UNIQUE INSERT                                 │
│        │  ⑤ NOTIFY outbox_new                                             │
│        ▼                                                                  │
│  notification_outbox (PENDING)                                            │
└──────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────── NotificationDispatchWorker (각 인스턴스) ───────────────┐
│  1. LISTEN outbox_new (즉시 깨움) + 30초 보조 폴링                       │
│  2. 짧은 TX: SELECT FOR UPDATE SKIP LOCKED → status=SENDING,             │
│              claimed_at=NOW(), claimed_by=instance_id                    │
│  3. 락 풀고 Channel.deliver(ctx) 호출 (외부 I/O)                          │
│  4. 결과 분류:                                                            │
│     - Sent          → status=SENT, sent_at                                │
│     - TransientFailure → attempt_count++, next_attempt_at=backoff         │
│     - PermanentFailure → status=PERMANENT_FAILURE,                        │
│                          BINDING_REQUIRED/TOKEN_EXPIRED → 사용자 안내     │
│  5. ChatChannel.deliver는 SSE broadcast 책임도 보유 (단일 진입점)         │
└──────────────────────────────────────────────────────────────────────────┘

┌─────────────── Inbound (Slack 전용, outbox 우회) ─────────────────────┐
│  Slack DM/멘션 → POST /api/v1/channels/slack/events                     │
│      │ 서명 검증(현재+이전 secret grace 5분), 즉시 200 ack               │
│      │ reactions.add(:eyes:) 즉시 피드백                                 │
│      ▼ @Async("slackInboundExecutor") 풀 (core=3, max=5)                 │
│  SlackInboundService                                                     │
│      ├─ user_channel_binding 으로 user_id 매핑                           │
│      │   └─ 미매핑 시 ephemeral 안내                                     │
│      ├─ ai_session lookup (SLACK, channel_id, thread_ts)                 │
│      │   └─ 없으면 firehub-ai-agent에 새 세션 생성                       │
│      ├─ ai-agent /agent/chat 호출 (동기 일괄 응답)                       │
│      └─ SlackChannel.replyTo(thread_ts, payload) → chat.postMessage      │
└──────────────────────────────────────────────────────────────────────────┘

┌─────────────── 좀비 행 회복 (단일 인스턴스만 실행) ────────────────────┐
│  OutboxSweeper @Scheduled(매 5분)                                        │
│  - PG advisory lock 으로 동시 실행 방지                                  │
│  - status=SENDING && claimed_at < NOW()-5min 행을 PENDING으로 되돌림     │
│  - 메트릭: outbox_zombie_recovered_total                                 │
└──────────────────────────────────────────────────────────────────────────┘
```

### 핵심 원칙

- **도메인은 enqueue만 안다.** 채널 종류·실패·재시도·관측은 모름.
- **Outbox enqueue는 AFTER_COMMIT 훅에서.** 도메인 TX와 완전 분리, 큰 payload·외부 호출이 도메인 TX 길이에 영향 없음. 유실은 멱등성 키 + execution.notified 플래그로 복구 잡 사용.
- **수신자별 1행 fan-out.** preference/binding/실패가 모두 독립적으로 추적 가능.
- **런타임 fallback 금지.** SLACK 실패해도 EMAIL로 자동 폴백 안 함. 동적 fallback은 디버깅·UX 모두 망가짐.
- **resolved_channels는 enqueue 시점에 확정.** 워커는 결정 모름.
- **CHAT은 안전망.** opt-out 불가. 모든 외부 채널이 unavailable 또는 OFF여도 웹 인박스에는 남음.

## 4. 데이터 모델

### V48 — `notification_outbox`

```sql
CREATE TABLE notification_outbox (
    id BIGSERIAL PRIMARY KEY,

    idempotency_key VARCHAR(64) NOT NULL,        -- sha256(correlation_id||channel||recipient_user_id)
    correlation_id UUID NOT NULL,                -- 같은 원본 이벤트의 fan-out 묶음 (관측·UI에서 사용)
    event_type VARCHAR(64) NOT NULL,             -- PROACTIVE_RESULT | PIPELINE_COMPLETED | ANOMALY_DETECTED | AI_PROACTIVE_PUSH
    event_source_id BIGINT,                      -- 원본 엔티티 id (proactive_job_execution.id 등)

    channel_type VARCHAR(32) NOT NULL,           -- CHAT | EMAIL | KAKAO | SLACK
    recipient_user_id BIGINT,                    -- 내부 사용자 (NULL = 외부 주소 직접 발송)
    recipient_address TEXT,                      -- 외부 이메일 등 (사용 시)

    payload_ref_type VARCHAR(32),                -- 'PROACTIVE_EXECUTION' 등 — 발송 시 join하여 렌더 (JSONB 비대화 방지)
    payload_ref_id BIGINT,                       -- 참조 id
    payload JSONB,                               -- 참조 불가능한 즉석 메시지(능동 푸시)일 때만 사용
    rendered_subject TEXT,                       -- (선택) 검색·로그용 캐시
    payload_type VARCHAR(16) NOT NULL DEFAULT 'STANDARD',  -- STANDARD | OVERRIDE

    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',         -- PENDING | SENDING | SENT | PERMANENT_FAILURE | CANCELLED
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    claimed_by VARCHAR(64),                                -- 인스턴스 식별자
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    last_error_at TIMESTAMPTZ,
    permanent_failure_reason VARCHAR(64),                  -- BINDING_REQUIRED | TOKEN_EXPIRED | RATE_LIMIT_EXHAUSTED | UNRECOVERABLE | ...

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by_user_id BIGINT,

    CONSTRAINT uk_outbox_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_outbox_pending_due
    ON notification_outbox (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_zombie
    ON notification_outbox (claimed_at)
    WHERE status = 'SENDING';

CREATE INDEX idx_outbox_recipient
    ON notification_outbox (recipient_user_id, created_at DESC);

CREATE INDEX idx_outbox_correlation
    ON notification_outbox (correlation_id);
```

### V49 — `slack_workspace`

`user_channel_binding.workspace_id` FK 대상이므로 binding 보다 먼저 생성한다.

```sql
CREATE TABLE slack_workspace (
    id BIGSERIAL PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL UNIQUE,
    team_name VARCHAR(255),
    bot_user_id VARCHAR(64) NOT NULL,
    bot_token_enc TEXT NOT NULL,
    signing_secret_enc TEXT NOT NULL,
    previous_signing_secret_enc TEXT,
    previous_signing_secret_expires_at TIMESTAMPTZ,
    installed_by_user_id BIGINT REFERENCES app_user(id),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ
);
```

`previous_signing_secret*` 컬럼으로 secret 회전 시 5분 grace window 지원.

### V50 — `user_channel_binding`

```sql
CREATE TABLE user_channel_binding (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    channel_type VARCHAR(32) NOT NULL,          -- EMAIL | KAKAO | SLACK
    workspace_id BIGINT REFERENCES slack_workspace(id),  -- SLACK일 때만 NOT NULL (DEFERRABLE)
    external_user_id VARCHAR(255),              -- Slack U…, Kakao 사용자 id (Memo는 본인 토큰 단독으로 가능하므로 NULL 허용)
    display_address VARCHAR(255),               -- 표시용
    access_token_enc TEXT,                      -- AES/GCM (EncryptionService)
    refresh_token_enc TEXT,
    token_expires_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | TOKEN_EXPIRED | REVOKED
    last_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_user_channel UNIQUE (user_id, channel_type, workspace_id)
);

CREATE INDEX idx_binding_external_user
    ON user_channel_binding (channel_type, workspace_id, external_user_id);
```

### V51 — `user_channel_preference`

```sql
CREATE TABLE user_channel_preference (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    channel_type VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_preference UNIQUE (user_id, channel_type),
    CONSTRAINT chat_always_enabled CHECK (channel_type <> 'CHAT' OR enabled = TRUE)
);
```

CHAT은 DB CHECK 제약으로 disable 불가능을 강제. 안전망 보장.

### V52 — `oauth_state` (CSRF 방어)

```sql
CREATE TABLE oauth_state (
    id BIGSERIAL PRIMARY KEY,
    state VARCHAR(64) NOT NULL UNIQUE,           -- CSPRNG, 32 bytes hex
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    channel_type VARCHAR(32) NOT NULL,           -- KAKAO | SLACK
    expires_at TIMESTAMPTZ NOT NULL,             -- created_at + 10min
    consumed_at TIMESTAMPTZ,                     -- single-use
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_oauth_state_expires ON oauth_state (expires_at);
```

만료된 state는 일일 cleanup 잡으로 삭제.

### V53 — `ai_session` 확장

```sql
ALTER TABLE ai_session
    ADD COLUMN source_channel VARCHAR(32),                  -- WEB(default NULL) | SLACK | ...
    ADD COLUMN source_external_thread_id VARCHAR(255),      -- Slack thread_ts
    ADD COLUMN source_external_channel_id VARCHAR(255);     -- Slack channel_id

CREATE UNIQUE INDEX idx_ai_session_external_thread
    ON ai_session (source_channel, source_external_channel_id, source_external_thread_id)
    WHERE source_channel IS NOT NULL AND source_external_thread_id IS NOT NULL;
```

같은 Slack 스레드에서 들어온 메시지는 동일 ai_session_id로 라우팅. 사용자가 웹에서 같은 세션을 이어받을 수 있음.

### 페이로드 형식 (`payload` JSONB)

```json
// payload_type=STANDARD
{
  "title": "리포트 제목",
  "summary": "한 줄 요약",
  "sections": [{ "heading": "...", "body_md": "..." }],
  "links": [{ "label": "상세 보기", "url": "/proactive/jobs/123" }],
  "media": [{ "type": "image", "url": "...", "alt": "..." }],
  "metadata": { "deepLinkPath": "...", "tags": [...] },
  "rawOverrideByChannel": {                                 // 선택
    "SLACK": { "blocks": [...] }                            // 채널별 raw payload
  }
}

// payload_type=OVERRIDE (단일 채널 raw 전용일 때)
{ "raw": { "blocks": [...] } }
```

> 일반 사용 시 `payload_ref_type`/`payload_ref_id`로 원본 엔티티(`proactive_job_execution` 등)를 참조하고, 채널 발송 시점에 join하여 표준 payload로 렌더링한다. 능동 푸시처럼 영속 엔티티가 없는 즉석 메시지일 때만 `payload`에 직접 저장한다.

### 기존 테이블 영향

- `proactive_message`: 그대로 유지. CHAT 채널 발송 성공 시 ChatChannel.deliver 안에서 INSERT.
- `proactive_job.config.channels[]`: 그대로 유지. enqueue 시 NotificationRoutingSpec으로 매핑.
- `proactive_job_execution.delivered_channels`: 의미 변경 — outbox status aggregation view로 대체. 마이그레이션 시 deprecation.

## 5. Channel SPI

### 인터페이스

```java
public interface Channel {
    ChannelType type();
    AuthStrategy authStrategy();         // NONE | EMAIL_ADDRESS | OAUTH | BOT_TOKEN
    DeliveryResult deliver(DeliveryContext ctx);
}

/** 사용자별 binding이 필요한 채널만 추가 구현. */
public interface BoundChannel extends Channel {
    /** 토큰 만료 직전이면 갱신, 실패 시 binding.status=TOKEN_EXPIRED 마킹. */
    RefreshResult refreshIfNeeded(UserChannelBinding binding);
}

/** 양방향 채널만 추가 구현 (V1: SLACK만). */
public interface InboundChannel {
    ChannelType type();
    void verify(InboundRequest req);                   // 서명·timestamp·재전송 검증
    InboundMessage parse(InboundRequest req);
    Optional<UserBindingMatch> resolveUser(InboundMessage msg);
    void replyTo(InboundMessage msg, Payload reply);
}

public enum ChannelType { CHAT, EMAIL, KAKAO, SLACK }

public enum AuthStrategy {
    NONE,             // CHAT (사용자 본인)
    EMAIL_ADDRESS,    // EMAIL (display_address만)
    OAUTH,            // KAKAO (사용자 OAuth refresh token)
    BOT_TOKEN         // SLACK (워크스페이스 봇 토큰 + 사용자 매핑)
}

public record DeliveryContext(
    long outboxId,
    UUID correlationId,
    Long recipientUserId,
    String recipientAddress,
    Optional<UserChannelBinding> binding,
    Payload payload
) {}

public sealed interface DeliveryResult {
    record Sent(String externalMessageId) implements DeliveryResult {}
    record TransientFailure(String reason, Throwable cause) implements DeliveryResult {}
    record PermanentFailure(PermanentFailureReason reason, String details) implements DeliveryResult {}
}

public enum PermanentFailureReason {
    BINDING_REQUIRED, TOKEN_EXPIRED, RATE_LIMIT_EXHAUSTED, RECIPIENT_INVALID, UNRECOVERABLE
}

public record Payload(
    PayloadType type,
    String title,
    String summary,
    List<Section> sections,
    List<Link> links,
    List<Media> media,
    Map<String, Object> metadata,
    Map<ChannelType, JsonNode> rawOverrideByChannel
) {}
```

### Dispatcher API

```java
@Service
public class NotificationDispatcher {
    /** 도메인이 호출하는 단일 진입점. AFTER_COMMIT으로 outbox 적재. */
    void enqueue(NotificationRequest request);
}

public record NotificationRequest(
    String eventType,
    Long eventSourceId,
    Long createdByUserId,
    UUID correlationId,                          // 호출자가 안 주면 자동 생성
    Payload standardPayload,
    PayloadRef payloadRef,                       // payload 직접 저장 vs 참조 join 선택
    List<Recipient> recipients
) {}

public record Recipient(
    Long userId,
    String externalAddressIfAny,
    Set<ChannelType> requestedChannels
) {}
```

### 등록·라우팅

- 모든 `Channel`/`InboundChannel` 구현체는 Spring Bean으로 자동 수집.
- 새 채널 추가 = 클래스 1개 + (OAuth/봇이면) 인증 콜백 컨트롤러 1개. 도메인 코드 변경 0.

### 채널별 책임

| Channel | AuthStrategy | requiresBinding | rendering | 비고 |
|---|---|---|---|---|
| `ChatChannel` | NONE | × | StandardPayload → markdown → `proactive_message` INSERT + `SseEmitterRegistry.broadcast` | 안전망 |
| `EmailChannel` | EMAIL_ADDRESS | × | StandardPayload → Thymeleaf HTML → SMTP | 첨부 가능 |
| `KakaoChannel` | OAUTH | ✓ | StandardPayload → 1000자 텍스트 + 안내문구 자동 append → 나에게 보내기 API | outbound only |
| `SlackChannel` | BOT_TOKEN | ✓ | StandardPayload → Block Kit → `chat.postMessage` (DM) | 양방향 (`InboundChannel`도 구현) |

KAKAO 메시지 본문 마지막에 항상 `\n\n답장은 [Smart Fire Hub](https://app.smartfirehub.com/ai/chat)에서` 자동 append (코드 상수). 사용자 혼란 방지.

## 6. 라우팅 · Opt-out · Fallback 매트릭스 (BLOCKER C1 해결)

**원칙: enqueue 시점에 resolved_channels 확정, 런타임 동적 fallback 금지.**

### Enqueue 알고리즘

```
NotificationDispatcher.enqueue(request) {
    for (recipient in request.recipients) {
        resolved = []
        skipped_reasons = []
        for (channel in recipient.requestedChannels) {
            if (channel != CHAT && preference.disabled(recipient.userId, channel)) {
                skipped_reasons.add(channel + ":OPTED_OUT")
                continue
            }
            if (channel.requiresBinding()) {
                binding = bindingRepo.findActive(recipient.userId, channel)
                if (binding.isEmpty()) {
                    skipped_reasons.add(channel + ":BINDING_MISSING")
                    continue
                }
            }
            resolved.add(channel)
        }
        if (resolved.isEmpty()) {
            // CHAT 강제 (안전망)
            resolved.add(CHAT)
            // 사용자에게 1회성 안내 enqueue (CHAT 채널, "외부 채널 모두 미사용")
            enqueueAdvisoryMessage(recipient.userId, skipped_reasons)
        }
        for (channel in resolved) {
            outboxInsert(buildRow(recipient, channel, request))
            // idempotency_key UNIQUE → 중복 enqueue 차단
        }
    }
    pgNotify("outbox_new")
}
```

### 결정 매트릭스

| 시나리오 | 결과 |
|---|---|
| 생성자 [SLACK], 사용자 SLACK ON, binding 있음 | SLACK 1행 |
| 생성자 [SLACK], 사용자 SLACK OFF | CHAT 강제 + 안내 메시지 |
| 생성자 [SLACK, EMAIL], 사용자 SLACK OFF, EMAIL ON | EMAIL 1행 (SLACK skip) |
| 생성자 [SLACK], 사용자 SLACK ON, binding 없음 | CHAT 강제 + 안내 메시지 |
| 생성자 [KAKAO], 사용자 KAKAO ON, binding 없음 | CHAT 강제 + 안내 메시지 |
| 생성자 [CHAT, EMAIL], 사용자 EMAIL OFF | CHAT 1행 (EMAIL skip) |
| 생성자 [], (어떤 채널도 미지정) | CHAT 1행 (디폴트 안전망) |
| 워커 SLACK 발송 transient 실패 | 같은 SLACK 재시도, EMAIL 폴백 안 함 |
| 워커 SLACK 발송 PermanentFailure(TOKEN_EXPIRED) | SLACK 행 PERMANENT_FAILURE, 사용자에게 CHAT으로 "재인증 필요" 안내, 발송자에게 execution status로 통보 |

### 사용자 안내 메시지 (CHAT 강제 시)

> "🔔 알림 채널 안내
> 이 알림은 SLACK으로 보내려 했지만 사용자 설정 또는 미연동 상태여서 웹에서만 표시됩니다.
> [채널 연동/설정 변경](/settings/channels)"

같은 사용자에게 24시간 내 같은 사유의 안내는 1회만(중복 방지 키: `user_id + skip_reason`).

## 7. 실패 처리 · 재시도

### Backoff 정책

| attempt | next_attempt_at delay |
|---|---|
| 1 (즉시) | now |
| 2 | +10초 |
| 3 | +1분 |
| 4 | +5분 |
| 5 | +30분 |
| 6 (최종) | +2시간 |
| 6 실패 | PERMANENT_FAILURE(UNRECOVERABLE) |

### 영구 실패 분류

| 사유 | 트리거 | 후속 |
|---|---|---|
| BINDING_REQUIRED | 채널이 binding 필요한데 없음 | 사용자에게 CHAT으로 안내, 발송자 execution에 기록 |
| TOKEN_EXPIRED | refresh 실패 또는 401 | binding.status=TOKEN_EXPIRED, 사용자에게 CHAT 안내(재인증 링크) |
| RATE_LIMIT_EXHAUSTED | 채널 rate limit 6번 연속 | 발송자에게만 통보 (사용자 노출 X) |
| RECIPIENT_INVALID | 외부 주소 형식 오류 등 | 발송자에게만 통보 |
| UNRECOVERABLE | 6회 모두 실패 | 발송자에게 통보, 운영 대시보드에 노출 |

### Rate Limiting

- 채널별 in-process token bucket(`Bucket4j` 또는 Guava RateLimiter):
  - SLACK: Tier 2 = 20/min/team
  - KAKAO: 100/sec/app (예상치)
  - EMAIL: SMTP 처리량에 의존 (설정 가능)
- 토큰 미보유 시 즉시 retry 스케줄(다음 사이클로 미루기), `attempt_count`는 증가시키지 않음.

### Anomaly 경로 일관성

기존 `ProactiveJobService.onAnomalyDetected`의 직접 `SseEmitterRegistry.broadcast` 호출은 제거하고 `Dispatcher.enqueue(eventType=ANOMALY_DETECTED, channels=[CHAT, ...])`로 통일한다. ChatChannel.deliver가 `proactive_message` INSERT + SSE broadcast 모두 책임.

## 8. Slack Inbound (양방향)

### 흐름

```
Slack DM/멘션 → POST /api/v1/channels/slack/events
   1. SlackSignatureFilter (검증 실패 시 401)
   2. Type 분기:
      - url_verification → challenge 응답
      - event_callback → 즉시 200 ack + reactions.add(:eyes:)
   3. @Async("slackInboundExecutor") 디스패치
      → SlackInboundService.handle(event)
        ├─ resolveUser(team_id, user_id) → user_channel_binding
        │   └─ 미매핑 시 ephemeral message + 종료
        ├─ ai_session lookup (SLACK, channel_id, thread_ts)
        │   ├─ 없음 → ai-agent /agent/session 새로 생성, ai_session row INSERT
        │   └─ 있음 → 기존 sessionId
        ├─ ai-agent /agent/chat 호출 (timeout 60s, 동기 일괄)
        │   └─ 실패 시 reactions.add(:warning:) + ephemeral 에러
        └─ SlackChannel.replyTo(thread_ts, payload)
              └─ chat.postMessage with thread_ts (스레드 유지)
```

### Slack App 셋업 (런타임 외 1회)

- Bot Token Scopes: `chat:write`, `im:history`, `im:read`, `im:write`, `users:read`, `app_mentions:read`, `reactions:write`
- Events 구독: `message.im`, `app_mention`
- OAuth Redirect: `https://app.smartfirehub.com/api/v1/channels/slack/oauth/callback`
- 워크스페이스 설치 시 `slack_workspace` row 생성, bot_token/signing_secret 저장
- 사용자별 매핑은 첫 인바운드 메시지 수신 시 ephemeral 안내로 웹 연동 페이지 deep link 제공

### 응답 비동기성

- Slack Events 3초 응답 의무 → 컨트롤러는 즉시 200 + reaction:eyes
- AI 추론 평균 5~30초 → @Async slackInboundExecutor (core=3, max=5, queue=20)
- AI 응답 도착 후 같은 thread_ts로 chat.postMessage. 같은 스레드 유지로 대화 컨텍스트 자연스러움.
- 30초 초과 시 중간 ephemeral "분석 중..." 표시는 V1.5에서 추가 (V1은 reaction만)

### 보안

- Slack signing secret 검증: `v0=hex(hmac_sha256(secret, "v0:" + ts + ":" + body))`
- timestamp ±5분 검증 (재전송 방어)
- secret rotation grace: `signing_secret_enc` 우선, 실패 시 `previous_signing_secret_enc`로 재시도(시한 5분)
- 미매핑 사용자 메시지는 ephemeral 안내만 하고 본문은 ai-agent로 전달하지 않음 (스푸핑·정보 누출 방어)

## 9. 사용자 연동 UX

### 새 페이지: `/settings/channels`

- 각 채널 카드 4개 (CHAT/EMAIL/KAKAO/SLACK)
- 카드 내용:
  - 연동 상태 배지: ✅ 연결됨 / ⚠️ 재인증 필요 / ❌ 미연결
  - 표시 주소(이메일, @slackname, Kakao 닉네임)
  - 알림 받기 토글 (CHAT은 disabled, 항상 ON)
  - "연결" / "재연결" / "연결 해제" 버튼
- KAKAO/SLACK은 OAuth 콜백 플로우 (oauth_state 발급 → 외부 인증 → 콜백 → binding 저장)
- 연동 상태 배지가 ⚠️인 채널은 사용자 메뉴에도 작은 알림 표시

### `ChannelRecipientEditor` 확장

- 기존 CHAT/EMAIL → CHAT/EMAIL/KAKAO/SLACK 4개 체크박스로 확장
- 각 채널 옆에 "수신자 중 N명이 미연동" 경고 배지
- 사용자 검색 시 각 사용자별 채널 연동 상태 미니 아이콘 표시
- "외부 이메일 입력" 필드는 EMAIL 전용 (다른 채널은 사용자 매핑 강제)

## 10. 관측성 (V1 필수)

### Metrics (Micrometer)

```
notification_outbox_pending_count {channel}            # 게이지
notification_outbox_permanent_failure_total {channel, reason}   # 카운터
notification_outbox_zombie_recovered_total             # 카운터
channel_delivery_duration_seconds {channel, status}    # 히스토그램
channel_rate_limit_throttle_total {channel}            # 카운터
slack_inbound_received_total                           # 카운터
slack_inbound_processing_duration_seconds              # 히스토그램
slack_inbound_unmapped_user_total                      # 카운터
```

### Admin endpoints

- `GET /admin/notifications/stuck?older_than=5m` — pending 5분 초과 행 목록
- `POST /admin/notifications/{outboxId}/retry` — 영구 실패 행 수동 재투입 (status=PENDING, attempt_count=0)
- `GET /admin/channels/bindings/{userId}` — 특정 사용자 채널 연동 상태 진단

### Retention

- `SENT` 행: 90일 후 삭제
- `PERMANENT_FAILURE` 행: 180일 후 삭제 (감사·디버깅 용도로 더 길게 보관)
- `oauth_state`: 만료 후 즉시 cleanup
- 일일 cleanup 잡(`@Scheduled(cron = "0 30 4 * * *")`)

### Logging 규약

- 모든 outbox·channel·inbound 로그에 `correlation_id` MDC 주입
- 로그 레벨 정책: deliver SUCCESS=DEBUG, TRANSIENT_FAILURE=WARN, PERMANENT_FAILURE=ERROR
- payload 본문은 로그에 직접 찍지 않음 (PII 보호) — 길이·hash만

## 11. 보안

### 토큰 저장

- 모든 토큰은 기존 `EncryptionService`(AES/GCM) 재사용으로 암호화
- 키는 환경변수 `ENCRYPTION_KEY`에서 로드 (현 프로젝트 표준)

### Slack signing secret 회전

- `slack_workspace.signing_secret_enc`가 우선
- 회전 시 새 secret을 `signing_secret_enc`에 저장, 기존을 `previous_signing_secret_enc`로 이동, `previous_signing_secret_expires_at = NOW() + 5min` 설정
- 검증 실패 시 previous로 한 번 더 시도 (만료 시간 안일 때만)

### OAuth state CSRF

- 인증 시작 시 32바이트 CSPRNG state 생성, `oauth_state` INSERT (TTL 10분)
- 콜백 수신 시 state 조회 → 존재·미만료·미사용 검증 → `consumed_at` 업데이트
- 일치 안 하면 401

### Refresh token rotation

- refresh 성공 시 받은 새 refresh token으로 즉시 교체 저장 (기존 무효화)
- refresh 실패(invalid_grant)는 즉시 `binding.status=TOKEN_EXPIRED`

### Inbound 스푸핑 방어

- Slack signing secret 검증 통과한 메시지여도 user_channel_binding 매핑 없으면 본문 처리 안 함
- 봇 자체 메시지 echo loop 방지: `bot_user_id`와 동일하면 skip

### PII

- `notification_outbox.payload`에 사용자 이메일·메시지 본문 들어감
- DB 백업 정책에 포함, 운영자 접근 권한 제한
- retention 정책으로 자동 삭제

## 12. 마이그레이션 전략 (점진 전환)

### Stage 1 — 인프라 도입 (V48~V52 + Channel SPI)

- 새 outbox 테이블·디스패처·워커·관측 작성
- 기존 `EmailDeliveryChannel`/`ChatDeliveryChannel`은 새 `EmailChannel`/`ChatChannel`로 리네이밍 + outbox 진입점 변경 (구현 본문 거의 그대로)
- `ProactiveJobService.executeJob` 끝의 `List<DeliveryChannel>` 직접 호출 루프 → `Dispatcher.enqueue` 한 줄로 교체
- `delivered_channels` 컬럼은 outbox aggregation view로 대체 (애플리케이션은 view 읽기)
- 기존 단위/통합 테스트는 stub Dispatcher로 격리. ChannelTest는 그대로 유지(deliver 메서드 시그니처 동일).

### Stage 2 — 새 채널 (KAKAO + SLACK outbound)

- `KakaoChannel`, `SlackChannel`(outbound) 추가
- `/settings/channels` 페이지 + `ChannelRecipientEditor` 확장
- OAuth 콜백 컨트롤러 (KAKAO + SLACK 사용자 매핑)
- Slack workspace 설치 플로우 (관리자 1회)

### Stage 3 — Slack inbound

- `SlackInboundController` + `SlackInboundService` + `slackInboundExecutor`
- ai_session 컬럼 ALTER (V53)
- 인바운드 통합 테스트 (서명 검증, 매핑, 세션 재사용, ai-agent 호출)

각 Stage는 별도 PR + ROADMAP 항목. Stage 간 회귀 없음을 회귀 테스트로 보장.

## 12.5. 회귀 방어 (Regression Protection) — 필수 조건

**원칙: 기존에 잘 동작하던 기능은 단 하나도 깨지지 않는다. 새 인프라 도입을 위해 기존 사용자 경험을 희생하지 않는다.**

### 기능 동등성 체크리스트 (Stage 1 PR 머지 전 모두 ✅ 필수)

각 항목은 새 시스템 활성 상태 + 비활성 상태(feature flag OFF) 양쪽에서 동작 검증.

| # | 보호 대상 | 검증 방법 | 책임 채널 |
|---|---|---|---|
| 1 | ProactiveJob 결과 → 웹 인박스 표시(proactive_message + SSE) | E2E: 잡 실행 → useProactiveMessages 폴링·미읽음 카운트 갱신 | ChatChannel |
| 2 | ProactiveJob 결과 → 이메일 수신(Thymeleaf HTML + PDF 첨부) | 통합: GreenMail로 SMTP 캡처 → 본문/첨부 검증 | EmailChannel |
| 3 | ProactiveJob 결과 → 외부 이메일(recipientEmails) 발송 | 통합: 사용자 미연동 외부 주소로 발송 케이스 | EmailChannel |
| 4 | Anomaly 이벤트 → 사용자 실시간 SSE 알림(ANOMALY_DETECTED) | E2E: anomaly 트리거 → 프론트 useNotificationStream 토스트 표시 | ChatChannel + 신규 Dispatcher.enqueue 경로 |
| 5 | Pipeline 완료 → SSE 알림(PIPELINE_COMPLETED/FAILED) | E2E: 파이프라인 실행 완료 → 프론트 토스트 + 쿼리 invalidate | (기존 SSE 직접 broadcast 유지) |
| 6 | useNotificationStream 모든 이벤트 타입 호환 | 단위·E2E: PROACTIVE_MESSAGE/ANOMALY_DETECTED/PIPELINE_COMPLETED 등 기존 이벤트 모두 동일 페이로드 형태 | NotificationEvent 형식 무변경 |
| 7 | proactive_message 인박스 미읽음 카운트(useProactiveMessages) | 단위: refetchInterval 60초, mark as read mutation | 기존 API/스키마 무변경 |
| 8 | ProactiveJob.config.channels JSONB 형식(ChannelConfigValues[]) 호환성 | 통합: 기존 잡 row 그대로 새 dispatcher가 해석 | ProactiveConfigParser 확장(기존 형식 100% 호환) |
| 9 | ProactiveJobService.executeJob 호출 후 deliveredChannels 컬럼 표시 | 통합: outbox aggregation view가 동일 의미 반환 | view 정의 |
| 10 | ProactiveJob 미연동 사용자 EMAIL 발송 시도 시 에러 처리 | 통합: 기존 try/catch 격리 거동을 PERMANENT_FAILURE로 전환하되 producer 알림 보장 | Dispatcher 분류 |

### Feature Flag 운영 정책

```yaml
# application.yml
notification:
  outbox:
    enabled: ${NOTIFICATION_OUTBOX_ENABLED:false}   # 기본 OFF
  worker:
    mode: ${NOTIFICATION_WORKER_MODE:async}         # async | inline
    poll_interval_seconds: 30
    listen_notify: true
```

- **PR 머지 시**: `notification.outbox.enabled=false` 기본값. 기존 직접 호출 경로가 그대로 동작 (deprecated 경로지만 활성).
- **검증 단계**: dev/stage 환경에서만 `true`로 토글, 위 체크리스트 10개 모두 통과 확인.
- **운영 활성화**: 운영에서 `true` 토글 → 1주일 모니터링 → 이슈 없으면 다음 PR에서 flag·구 경로 제거.
- **이상 발생 시**: 운영 관리자가 즉시 `false`로 토글하여 30초 내 회귀(애플리케이션 재시작 없이 동적 reload).

### 카나리아 배포 (Stage 1 활성화 시)

1. **dev 24시간**: outbox 활성, 모든 트래픽. 메트릭 집계.
2. **stage 72시간**: 동일.
3. **운영 단일 인스턴스**: 한 인스턴스만 outbox 활성, 나머지는 OFF. 트래픽 분리는 어려우므로 metric 비교로 회귀 감지.
4. **운영 전체**: 한 주 정상 운영 후 적용.

### 호환성 보장 사항 (변경 금지)

- `useNotificationStream`이 받는 NotificationEvent JSON 형식
- `proactive_message` 테이블 스키마 (CRUD API 동일)
- `GET /api/v1/proactive/messages` 응답 형식
- `ProactiveJob.config` JSONB 내 channels 배열 형식 (구·신 형식 모두 ProactiveConfigParser가 양방향 호환)
- `proactive_job_execution.delivered_channels` 컬럼 의미 (실제 저장 위치는 view로 변경되어도 SELECT 결과 동일)
- SSE 이벤트 타입 enum 값 (`ANOMALY_DETECTED`, `PROACTIVE_MESSAGE`, `PIPELINE_COMPLETED` 등)

### 회귀 발생 시 책임

- 기존 기능 회귀가 1건이라도 발견되면 PR 머지 거부. 회귀 발견 시 즉시 feature flag OFF.
- 회귀 테스트가 누락되어 운영에서 발견될 경우, 해당 케이스의 회귀 테스트 추가가 다음 PR의 우선순위 1번.

## 13. 테스트 전략

### 회귀 테스트(필수, 12.5장 체크리스트와 1:1)

- 새 코드 추가 전, 기존 동작에 대한 통합/E2E 회귀 테스트를 먼저 작성한다.
- 모든 회귀 테스트는 feature flag ON/OFF 양쪽에서 실행 (`@ParameterizedTest` + 환경변수 토글)
- 회귀 테스트가 빠진 채로 머지된 변경은 즉시 revert.

### 단위 테스트

- 각 Channel deliver(): 외부 의존(SMTP, Slack API, Kakao API)을 stub
- DispatcherRouting 알고리즘: enqueue 매트릭스 케이스 표 그대로 단위 테스트
- Backoff 계산: attempt → next_attempt_at 매핑

### 통합 테스트 (Spring Boot Test + Testcontainers)

- Outbox 라이프사이클: enqueue → SENDING claim → SENT
- 멱등성: 같은 idempotency_key INSERT 두 번 → 한 번만 통과
- 좀비 회복: claimed_at 5분 초과 행이 sweeper로 PENDING 복귀
- LISTEN/NOTIFY 깨움: enqueue 후 워커 30초 폴링 안 기다리고 즉시 처리
- preference disabled → CHAT 강제
- Slack signature 검증 (Wiremock으로 Slack API stub)
- Slack inbound 전체 플로우 (검증 → 매핑 → ai_session → reply)

### 테스트 동기 실행 스위치

- `application-test.yml`에서 `notification.worker.mode=inline` → enqueue 즉시 deliver 동기 실행 (워커 스레드 안 띄움)
- E2E·인수 테스트 안정화

### Playwright E2E

- `/settings/channels` 페이지: 연동 상태 표시·OAuth 시작 클릭(stub)·연결 해제
- `ChannelRecipientEditor`: 새 4개 체크박스, 미연동 경고 배지
- 사용자 안내 메시지: SLACK 미연동 사용자에게 발송 시 CHAT 메시지 표시

### 커버리지 목표

- 새 코드 라인 80% 이상 (백엔드/프론트 동일 기준)
- 통합 테스트: outbox 핵심 흐름·Slack inbound·OAuth 콜백 모두 커버

## 14. 구현 순서 (제안)

| 순번 | 작업 | 의존 |
|---|---|---|
| 1 | V48(outbox)~V52(oauth_state) 마이그레이션 + jOOQ 코드 생성 | - |
| 2 | Channel/BoundChannel/InboundChannel SPI + AuthStrategy | 1 |
| 3 | NotificationDispatcher + AFTER_COMMIT 훅 + 라우팅 알고리즘 | 2 |
| 4 | NotificationDispatchWorker + LISTEN/NOTIFY + lease + sweeper | 3 |
| 5 | ChatChannel/EmailChannel 리팩토링 (기존 로직 이전) | 4 |
| 6 | ProactiveJobService 호출 지점 교체 + delivered_channels view | 5 |
| 7 | 관측성 (Micrometer + admin endpoints + retention 잡) | 4 |
| 8 | KAKAO 나에게 보내기 OAuth + KakaoChannel | 4 |
| 9 | SLACK OAuth(워크스페이스) + SlackChannel(outbound) | 4 |
| 10 | V53(ai_session ALTER) + Slack inbound 컨트롤러·서비스 | 9 |
| 11 | 프론트: `/settings/channels` 페이지 | 8, 9 |
| 12 | 프론트: ChannelRecipientEditor 확장 | 8, 9 |
| 13 | 통합 테스트 + Playwright E2E | 11, 12 |
| 14 | 운영 배포 + 모니터링 대시보드 (Grafana) | 13 |

## 15. 결정되지 않은 항목 (V2 후보)

- Slack 외 다른 inbound 채널 (Telegram, Discord, Webhook)
- 카테고리별 opt-out (`PIPELINE`, `ANOMALY` 분리)
- Quiet hours / DND
- AI 응답 스트리밍을 Slack에 `chat.update`로 progressive 노출
- Slack interactive messages (버튼/메뉴 응답을 다시 inbound로)
- 운영 대시보드 UI (현재는 admin endpoint + Grafana만)

## 16. 마이그레이션 위험 · 롤백

12.5장(회귀 방어)과 연계.

- Stage 1에서 `ProactiveJobService` 호출 지점 변경이 회귀 위험 가장 큼. PR 분리·feature flag(`notification.outbox.enabled`)로 즉시 롤백 가능하게 한다.
- Outbox stuck 시 admin endpoint로 수동 처리, 최악의 경우 `notification.outbox.enabled=false` 토글로 기존 직접 호출 경로로 일시 회귀.
- 12.5장 회귀 체크리스트 10개를 각 Stage 머지 전 모두 통과한 증거(테스트 결과·스크린샷)를 PR에 첨부.
- KAKAO/SLACK 추가는 새 코드 경로라 기존 EMAIL/CHAT 회귀 없음 — 단 ChannelRecipientEditor 확장 시 기존 CHAT/EMAIL 선택 UX가 깨지지 않는지 Playwright E2E로 보호.

## 17. 참조

- 현재 코드:
  - `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/DeliveryChannel.java`
  - `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/ChatDeliveryChannel.java`
  - `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannel.java`
  - `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java`
  - `apps/firehub-web/src/pages/ai-insights/components/ChannelRecipientEditor.tsx`
  - `apps/firehub-web/src/hooks/useNotificationStream.ts`
- 가이드:
  - `apps/firehub-api/CLAUDE.md` (Async, Encryption, Trigger 섹션)
  - `.claude/docs/architecture.md`
- 외부 API:
  - Slack Events API: <https://api.slack.com/apis/events-api>
  - Slack Block Kit: <https://api.slack.com/block-kit>
  - Kakao Memo API: <https://developers.kakao.com/docs/latest/ko/message/rest-api>
