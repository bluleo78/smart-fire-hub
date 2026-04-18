# Channel Stage 1 — Outbox 인프라 + Channel SPI 리팩토링 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 `DeliveryChannel` 직접 호출 패턴을 Outbox 기반 비동기 Dispatcher 패턴으로 리팩토링한다. 사용자 가시 기능 변화 0 — 모든 기존 동작(ProactiveJob/Anomaly/SSE/이메일 발송)을 회귀 없이 보존하면서 새 채널 추가의 기반 인프라를 마련한다.

**Architecture:** PG 단일 outbox 테이블 + Spring `@TransactionalEventListener(AFTER_COMMIT)` 훅으로 enqueue + `@Scheduled` 워커가 `SELECT FOR UPDATE SKIP LOCKED + lease` 패턴으로 발송 + LISTEN/NOTIFY로 즉시 깨움 + 멱등성 키로 중복 방지 + Micrometer 관측. 기존 `ChatDeliveryChannel`/`EmailDeliveryChannel`은 새 `Channel` SPI로 리네이밍·이전(deliver 본문 거의 그대로). `notification.outbox.enabled` feature flag로 즉시 회귀 가능.

**Tech Stack:** Spring Boot 3.x, jOOQ, Java 21, PostgreSQL 16, Flyway, Micrometer, JUnit 5, Mockito, Testcontainers, Awaitility

**Spec:** `docs/superpowers/specs/2026-04-18-channel-abstraction-design.md` (특히 4·5·6·7·10·12·12.5·13장)

---

## 사전 작업

- [ ] **0-1: 새 워크트리에서 작업 시작 확인**

```bash
git status
git log --oneline -5
```

기대: 깨끗한 워킹 트리, main 분기 또는 채널 작업 전용 분기.

- [ ] **0-2: 기존 회귀 baseline 잡기 — 모든 테스트 통과 확인**

```bash
pnpm --filter firehub-api test 2>&1 | tail -10
pnpm --filter firehub-web test:e2e 2>&1 | tail -10
```

기대: 두 명령 모두 0 failures. 실패하는 테스트가 있으면 stage 1 시작 전 fix.

- [ ] **0-3: 작업 중 변경되지 않을 회귀 검증 시점 기록**

이번 stage는 사용자 가시 변경이 0이라야 하므로, 작업 시작 직후 화면 스크린샷·로그·메트릭을 베이스라인으로 캡처한다.

```bash
mkdir -p snapshots/channel-stage-1-baseline
# 수동 스크린샷: 홈, /ai-insights, /settings (있으면)
# E2E 캡처: pnpm --filter firehub-web test:e2e --grep "smoke" --reporter=line
```

---

## File Structure

### 신규 생성 (백엔드 — `apps/firehub-api/src/main/java/com/smartfirehub/notification/`)

```
notification/
├── Channel.java                    # SPI: type/authStrategy/deliver
├── BoundChannel.java               # SPI: refreshIfNeeded
├── ChannelType.java                # enum: CHAT, EMAIL, KAKAO, SLACK
├── AuthStrategy.java               # enum: NONE, EMAIL_ADDRESS, OAUTH, BOT_TOKEN
├── Payload.java                    # record: 표준 + rawOverride hybrid
├── DeliveryContext.java            # record
├── DeliveryResult.java             # sealed: Sent/TransientFailure/PermanentFailure
├── PermanentFailureReason.java     # enum
├── NotificationRequest.java        # record (Dispatcher 입력)
├── Recipient.java                  # record
├── service/
│   ├── NotificationDispatcher.java
│   ├── NotificationDispatchWorker.java
│   ├── OutboxSweeper.java
│   ├── RoutingResolver.java
│   ├── BackoffPolicy.java
│   ├── ChannelRegistry.java        # Map<ChannelType, Channel>
│   ├── PayloadRenderer.java        # payload_ref → Payload 조립
│   └── ChannelRateLimiter.java     # Bucket4j wrapper
├── repository/
│   ├── NotificationOutboxRepository.java
│   ├── UserChannelBindingRepository.java
│   ├── UserChannelPreferenceRepository.java
│   ├── SlackWorkspaceRepository.java
│   └── OAuthStateRepository.java
├── channels/
│   ├── ChatChannel.java            # 기존 ChatDeliveryChannel 이전
│   └── EmailChannel.java           # 기존 EmailDeliveryChannel 이전
├── admin/
│   └── NotificationAdminController.java
└── metrics/
    └── NotificationMetrics.java
```

### 신규 생성 (DB 마이그레이션 — `apps/firehub-api/src/main/resources/db/migration/`)

```
V48__create_notification_outbox.sql
V49__create_slack_workspace.sql
V50__create_user_channel_binding.sql
V51__create_user_channel_preference.sql
V52__create_oauth_state.sql
V52_5__create_outbox_delivered_channels_view.sql
```

### 수정 (백엔드)

- `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java` — `executeJob` 호출 지점 교체
- `apps/firehub-api/src/main/resources/application.yml` — `notification.*` 설정
- `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/ChatDeliveryChannel.java` — 삭제(코드는 ChatChannel로 이전)
- `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannel.java` — 삭제(코드는 EmailChannel로 이전)
- `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/DeliveryChannel.java` — 삭제

### 신규 테스트

```
apps/firehub-api/src/test/java/com/smartfirehub/notification/
├── service/
│   ├── RoutingResolverTest.java          # 라우팅 매트릭스 단위
│   ├── BackoffPolicyTest.java            # 재시도 간격 단위
│   ├── NotificationDispatcherTest.java   # enqueue + AFTER_COMMIT 단위
│   ├── NotificationDispatchWorkerIntegrationTest.java   # @SpringBootTest + Testcontainers
│   ├── OutboxSweeperIntegrationTest.java
│   └── PayloadRendererTest.java
└── regression/
    └── ProactiveJobNotificationRegressionTest.java       # 12.5장 체크리스트 1~10
```

---

## Task 1: V48 — `notification_outbox` 마이그레이션

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V48__create_notification_outbox.sql`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/repository/NotificationOutboxRepositoryTest.java`

- [ ] **Step 1.1: V48 SQL 작성**

`apps/firehub-api/src/main/resources/db/migration/V48__create_notification_outbox.sql`:

```sql
-- 알림 발송 작업 큐. 도메인 트랜잭션 후 AFTER_COMMIT 훅으로 INSERT,
-- NotificationDispatchWorker가 SKIP LOCKED + lease 패턴으로 발송.
CREATE TABLE notification_outbox (
    id BIGSERIAL PRIMARY KEY,

    idempotency_key VARCHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_source_id BIGINT,

    channel_type VARCHAR(32) NOT NULL,
    recipient_user_id BIGINT,
    recipient_address TEXT,

    payload_ref_type VARCHAR(32),
    payload_ref_id BIGINT,
    payload JSONB,
    rendered_subject TEXT,
    payload_type VARCHAR(16) NOT NULL DEFAULT 'STANDARD',

    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    claimed_by VARCHAR(64),
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    last_error_at TIMESTAMPTZ,
    permanent_failure_reason VARCHAR(64),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by_user_id BIGINT,

    CONSTRAINT uk_outbox_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','SENDING','SENT','PERMANENT_FAILURE','CANCELLED')),
    CONSTRAINT chk_outbox_payload_type CHECK (payload_type IN ('STANDARD','OVERRIDE'))
);

-- 워커 폴링 — pending이 due 상태인 행을 빠르게 찾음
CREATE INDEX idx_outbox_pending_due
    ON notification_outbox (next_attempt_at)
    WHERE status = 'PENDING';

-- 좀비 회복 — SENDING 상태로 5분 이상 묶인 행
CREATE INDEX idx_outbox_zombie
    ON notification_outbox (claimed_at)
    WHERE status = 'SENDING';

-- 사용자 알림 인박스 조회
CREATE INDEX idx_outbox_recipient
    ON notification_outbox (recipient_user_id, created_at DESC);

-- correlation 묶음 조회
CREATE INDEX idx_outbox_correlation
    ON notification_outbox (correlation_id);
```

- [ ] **Step 1.2: 마이그레이션 자동 적용 + 검증**

```bash
pnpm db:reset && pnpm db:up
# 또는 백엔드만 띄워 Flyway 자동 적용
pnpm --filter firehub-api dev &
sleep 15
psql -h localhost -p 55432 -U firehub firehub -c "\d notification_outbox"
```

기대: 테이블·인덱스·제약 모두 출력. `idempotency_key` UNIQUE, status CHECK 표시.

- [ ] **Step 1.3: jOOQ 코드 재생성**

```bash
pnpm --filter firehub-api jooq:generate
```

기대: `apps/firehub-api/src/generated/.../NotificationOutbox.java` 생성, `Tables.NOTIFICATION_OUTBOX` 사용 가능.

- [ ] **Step 1.4: 회귀 검증 — 기존 테이블/마이그레이션 영향 없음**

```bash
pnpm --filter firehub-api test 2>&1 | tail -5
```

기대: 모든 기존 테스트 통과. V47 이하 마이그레이션 회귀 0.

- [ ] **Step 1.5: 커밋**

```bash
git add apps/firehub-api/src/main/resources/db/migration/V48__create_notification_outbox.sql
git add apps/firehub-api/src/generated/
git commit -m "feat(notification): V48 notification_outbox 테이블 추가

Outbox 패턴 기반 알림 발송 큐 + 멱등성 키 + lease 컬럼.
워커 폴링·좀비 회복·사용자 인박스 조회용 인덱스 동봉."
```

---

## Task 2: V49~V52 — slack_workspace / user_channel_binding / user_channel_preference / oauth_state

각 마이그레이션은 spec 4장의 DDL을 그대로 사용. 적용·jOOQ 재생성·기존 테스트 회귀 검증·커밋을 V별로 반복.

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V49__create_slack_workspace.sql`
- Create: `apps/firehub-api/src/main/resources/db/migration/V50__create_user_channel_binding.sql`
- Create: `apps/firehub-api/src/main/resources/db/migration/V51__create_user_channel_preference.sql`
- Create: `apps/firehub-api/src/main/resources/db/migration/V52__create_oauth_state.sql`

- [ ] **Step 2.1: V49 슬랙 워크스페이스**

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

- [ ] **Step 2.2: V50 user_channel_binding**

```sql
CREATE TABLE user_channel_binding (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    channel_type VARCHAR(32) NOT NULL,
    workspace_id BIGINT REFERENCES slack_workspace(id),
    external_user_id VARCHAR(255),
    display_address VARCHAR(255),
    access_token_enc TEXT,
    refresh_token_enc TEXT,
    token_expires_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_user_channel UNIQUE (user_id, channel_type, workspace_id),
    CONSTRAINT chk_binding_status CHECK (status IN ('ACTIVE','TOKEN_EXPIRED','REVOKED')),
    CONSTRAINT chk_binding_channel CHECK (channel_type IN ('EMAIL','KAKAO','SLACK'))
);

CREATE INDEX idx_binding_external_user
    ON user_channel_binding (channel_type, workspace_id, external_user_id);
```

- [ ] **Step 2.3: V51 user_channel_preference (CHAT 안전망 CHECK 포함)**

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

- [ ] **Step 2.4: V52 oauth_state**

```sql
CREATE TABLE oauth_state (
    id BIGSERIAL PRIMARY KEY,
    state VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    channel_type VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_oauth_state_expires ON oauth_state (expires_at);
```

- [ ] **Step 2.5: 마이그레이션 적용 + jOOQ 재생성 + 회귀 검증**

```bash
pnpm db:reset && pnpm --filter firehub-api dev &
sleep 15
psql -h localhost -p 55432 -U firehub firehub -c "\dt slack_workspace user_channel_binding user_channel_preference oauth_state"
pnpm --filter firehub-api jooq:generate
pnpm --filter firehub-api test 2>&1 | tail -5
```

기대: 4개 테이블 모두 표시, jOOQ 코드 4개 클래스 생성, 기존 테스트 0 failures.

- [ ] **Step 2.6: CHAT CHECK 제약 확인 단위 테스트**

`apps/firehub-api/src/test/java/com/smartfirehub/notification/repository/UserChannelPreferenceConstraintTest.java`:

```java
package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.test.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** CHAT 채널 OFF 시도 → CHECK 제약 위반으로 INSERT 실패 검증. */
class UserChannelPreferenceConstraintTest extends IntegrationTestBase {

    @Autowired
    private DSLContext dsl;

    @Test
    void chatChannelCannotBeDisabled() {
        long userId = createTestUser();
        assertThatThrownBy(() -> dsl
                .insertInto(USER_CHANNEL_PREFERENCE)
                .set(USER_CHANNEL_PREFERENCE.USER_ID, userId)
                .set(USER_CHANNEL_PREFERENCE.CHANNEL_TYPE, "CHAT")
                .set(USER_CHANNEL_PREFERENCE.ENABLED, false)
                .execute()
        ).hasMessageContaining("chat_always_enabled");
    }

    @Test
    void otherChannelsCanBeDisabled() {
        long userId = createTestUser();
        int rows = dsl
                .insertInto(USER_CHANNEL_PREFERENCE)
                .set(USER_CHANNEL_PREFERENCE.USER_ID, userId)
                .set(USER_CHANNEL_PREFERENCE.CHANNEL_TYPE, "SLACK")
                .set(USER_CHANNEL_PREFERENCE.ENABLED, false)
                .execute();
        assertThat(rows).isEqualTo(1);
    }
}
```

```bash
pnpm --filter firehub-api test --tests UserChannelPreferenceConstraintTest
```

기대: 두 테스트 모두 PASS.

- [ ] **Step 2.7: 커밋**

```bash
git add apps/firehub-api/src/main/resources/db/migration/V49__*.sql \
        apps/firehub-api/src/main/resources/db/migration/V50__*.sql \
        apps/firehub-api/src/main/resources/db/migration/V51__*.sql \
        apps/firehub-api/src/main/resources/db/migration/V52__*.sql \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/repository/UserChannelPreferenceConstraintTest.java \
        apps/firehub-api/src/generated/
git commit -m "feat(notification): V49~V52 외부 채널 binding/preference/workspace/oauth_state

slack_workspace, user_channel_binding, user_channel_preference (CHAT
안전망 CHECK), oauth_state (CSRF) 테이블 + 인덱스. CHAT 비활성 차단
제약 단위 테스트 포함."
```

---

## Task 3: Channel SPI 타입 정의 (코드 생성, no behavior)

**Files (create all):**
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/Channel.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/BoundChannel.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/ChannelType.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/AuthStrategy.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/Payload.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/DeliveryContext.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/DeliveryResult.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/PermanentFailureReason.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/NotificationRequest.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/Recipient.java`

이 task는 타입 정의만이라 TDD 적용 안 함. 컴파일 통과로 검증.

- [ ] **Step 3.1: enum/record 타입 작성**

`ChannelType.java`:
```java
package com.smartfirehub.notification;

/** 알림 채널 종류 식별. 새 채널 추가 시 여기에 enum 값 추가 + 구현체 1개. */
public enum ChannelType {
    CHAT,    // 웹 인박스 (안전망, opt-out 불가)
    EMAIL,
    KAKAO,
    SLACK
}
```

`AuthStrategy.java`:
```java
package com.smartfirehub.notification;

/** Channel 별 외부 인증 방식. requiresBinding 여부와 refresh 책임을 결정. */
public enum AuthStrategy {
    NONE,            // CHAT (binding 불필요)
    EMAIL_ADDRESS,   // EMAIL (display_address만 사용)
    OAUTH,           // KAKAO (사용자 OAuth refresh)
    BOT_TOKEN        // SLACK (워크스페이스 봇 토큰 + 사용자 매핑)
}
```

`Payload.java`:
```java
package com.smartfirehub.notification;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 채널 발송용 페이로드. Standard 필드 + 선택적 channel-raw override. */
public record Payload(
        PayloadType type,
        String title,
        String summary,
        List<Section> sections,
        List<Link> links,
        List<Media> media,
        Map<String, Object> metadata,
        Map<ChannelType, JsonNode> rawOverrideByChannel
) {
    public enum PayloadType { STANDARD, OVERRIDE }
    public record Section(String heading, String bodyMd) {}
    public record Link(String label, String url) {}
    public record Media(String type, String url, String alt) {}
}
```

`DeliveryContext.java`:
```java
package com.smartfirehub.notification;

import com.smartfirehub.notification.repository.UserChannelBinding;
import java.util.Optional;
import java.util.UUID;

/** Channel.deliver()에 전달되는 발송 컨텍스트. 워커가 outbox 행 + binding 조회 후 구성. */
public record DeliveryContext(
        long outboxId,
        UUID correlationId,
        Long recipientUserId,
        String recipientAddress,
        Optional<UserChannelBinding> binding,
        Payload payload
) {}
```

`DeliveryResult.java`:
```java
package com.smartfirehub.notification;

/** Channel.deliver() 결과. sealed로 강제하여 워커가 모든 경우 처리. */
public sealed interface DeliveryResult {
    record Sent(String externalMessageId) implements DeliveryResult {}
    record TransientFailure(String reason, Throwable cause) implements DeliveryResult {}
    record PermanentFailure(PermanentFailureReason reason, String details) implements DeliveryResult {}
}
```

`PermanentFailureReason.java`:
```java
package com.smartfirehub.notification;

/** 영구 실패 분류. 후속 처리(사용자/발송자 통보 여부) 결정. */
public enum PermanentFailureReason {
    BINDING_REQUIRED,
    TOKEN_EXPIRED,
    RATE_LIMIT_EXHAUSTED,
    RECIPIENT_INVALID,
    UNRECOVERABLE
}
```

`Recipient.java`:
```java
package com.smartfirehub.notification;

import java.util.Set;

/** 단일 수신자의 발송 요청 (사용자 또는 외부 주소 단위). */
public record Recipient(
        Long userId,                       // null = 외부 주소 직접 발송
        String externalAddressIfAny,
        Set<ChannelType> requestedChannels
) {}
```

`NotificationRequest.java`:
```java
package com.smartfirehub.notification;

import java.util.List;
import java.util.UUID;

/** 도메인이 NotificationDispatcher.enqueue로 전달하는 요청. */
public record NotificationRequest(
        String eventType,
        Long eventSourceId,
        Long createdByUserId,
        UUID correlationId,                // null이면 enqueue가 자동 생성
        Payload standardPayload,
        PayloadRef payloadRef,             // 참조 발송 시 (entity join 렌더), null이면 payload 직접 사용
        List<Recipient> recipients
) {
    public record PayloadRef(String type, long id) {}
}
```

`Channel.java`:
```java
package com.smartfirehub.notification;

/** 모든 발송 채널의 공통 SPI. 구현체는 Spring Bean으로 등록. */
public interface Channel {
    ChannelType type();
    AuthStrategy authStrategy();
    DeliveryResult deliver(DeliveryContext ctx);

    /** 사용자별 binding 필요 여부. authStrategy로 자동 판정. */
    default boolean requiresBinding() {
        return authStrategy() == AuthStrategy.OAUTH || authStrategy() == AuthStrategy.BOT_TOKEN;
    }
}
```

`BoundChannel.java`:
```java
package com.smartfirehub.notification;

import com.smartfirehub.notification.repository.UserChannelBinding;

/** binding이 필요한 채널은 추가로 토큰 갱신 책임을 가진다. */
public interface BoundChannel extends Channel {
    /** 토큰 만료 직전이면 refresh, 실패 시 binding.status=TOKEN_EXPIRED. */
    RefreshResult refreshIfNeeded(UserChannelBinding binding);

    sealed interface RefreshResult {
        record Refreshed(String newAccessToken, String newRefreshToken,
                         java.time.Instant expiresAt) implements RefreshResult {}
        record StillValid() implements RefreshResult {}
        record Failed(String reason) implements RefreshResult {}
    }
}
```

- [ ] **Step 3.2: 컴파일 검증**

```bash
pnpm --filter firehub-api typecheck
```

기대: BUILD SUCCESSFUL. 모든 타입 컴파일 통과.

- [ ] **Step 3.3: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/*.java
git commit -m "feat(notification): Channel SPI 타입 정의

Channel/BoundChannel 인터페이스, ChannelType/AuthStrategy enum,
Payload/DeliveryContext/DeliveryResult/Recipient/NotificationRequest
record. 동작 없음, 컴파일만 통과."
```

---

## Task 4: RoutingResolver — 라우팅 매트릭스 단위 (TDD)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/service/RoutingResolver.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/service/ResolvedRouting.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/service/RoutingResolverTest.java`

스펙 6장 매트릭스를 직접 단위 테스트로 코드화한다.

- [ ] **Step 4.1: ResolvedRouting record**

`ResolvedRouting.java`:
```java
package com.smartfirehub.notification.service;

import com.smartfirehub.notification.ChannelType;
import java.util.List;
import java.util.Map;

/** RoutingResolver 결과: 실제 enqueue할 채널 목록 + skip 사유. */
public record ResolvedRouting(
        List<ChannelType> resolvedChannels,
        Map<ChannelType, String> skippedReasons,    // 채널 → OPTED_OUT|BINDING_MISSING
        boolean forcedChatFallback                   // resolved가 비어 CHAT 강제됐는지
) {}
```

- [ ] **Step 4.2: 실패하는 테스트 작성 (매트릭스 1행씩)**

`RoutingResolverTest.java`:
```java
package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import com.smartfirehub.notification.repository.UserChannelPreferenceRepository;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Spec 6장 라우팅 매트릭스 단위 검증. */
@ExtendWith(MockitoExtension.class)
class RoutingResolverTest {

    @Mock private UserChannelPreferenceRepository preferenceRepo;
    @Mock private UserChannelBindingRepository bindingRepo;

    @InjectMocks private RoutingResolver resolver;

    private static final long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        // 디폴트: 모든 채널 enabled
        when(preferenceRepo.isEnabled(eq(USER_ID), org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    @Test
    void slackEnabledWithBinding_resolvesSlackOnly() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK))
                .thenReturn(Optional.of(stubBinding(ChannelType.SLACK)));
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.SLACK));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.SLACK);
        assertThat(result.forcedChatFallback()).isFalse();
        assertThat(result.skippedReasons()).isEmpty();
    }

    @Test
    void slackOptedOut_forcesChatFallback() {
        when(preferenceRepo.isEnabled(USER_ID, ChannelType.SLACK)).thenReturn(false);
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.SLACK));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.CHAT);
        assertThat(result.forcedChatFallback()).isTrue();
        assertThat(result.skippedReasons()).containsEntry(ChannelType.SLACK, "OPTED_OUT");
    }

    @Test
    void slackEnabledWithoutBinding_forcesChatFallback() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.empty());
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.SLACK));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.CHAT);
        assertThat(result.forcedChatFallback()).isTrue();
        assertThat(result.skippedReasons()).containsEntry(ChannelType.SLACK, "BINDING_MISSING");
    }

    @Test
    void slackOffEmailOn_resolvesEmailOnly() {
        when(preferenceRepo.isEnabled(USER_ID, ChannelType.SLACK)).thenReturn(false);
        // EMAIL은 binding 불필요 (AuthStrategy.EMAIL_ADDRESS)
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.SLACK, ChannelType.EMAIL));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.EMAIL);
        assertThat(result.forcedChatFallback()).isFalse();
        assertThat(result.skippedReasons()).containsEntry(ChannelType.SLACK, "OPTED_OUT");
    }

    @Test
    void emptyRequestedChannels_resolvesChatDefault() {
        Recipient r = new Recipient(USER_ID, null, EnumSet.noneOf(ChannelType.class));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.CHAT);
        assertThat(result.forcedChatFallback()).isTrue();
    }

    @Test
    void chatRequested_alwaysIncludedRegardlessOfPreference() {
        // CHAT은 DB CHECK로 disable 불가능이지만 방어적으로 코드도 무시
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.CHAT, ChannelType.EMAIL));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactlyInAnyOrder(ChannelType.CHAT, ChannelType.EMAIL);
    }

    private UserChannelBinding stubBinding(ChannelType ch) {
        return new UserChannelBinding(1L, USER_ID, ch, null, "ext-id", "addr", null, null, null,
                "ACTIVE", null, null, null);
    }
}
```

```bash
pnpm --filter firehub-api test --tests RoutingResolverTest
```

기대: FAIL — RoutingResolver 클래스 없음 / NoSuchMethodError.

- [ ] **Step 4.3: 최소 구현**

`RoutingResolver.java`:
```java
package com.smartfirehub.notification.service;

import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import com.smartfirehub.notification.repository.UserChannelPreferenceRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 라우팅 매트릭스 (Spec 6장):
 * - opt-out 채널 skip
 * - binding 필요한데 없는 채널 skip
 * - 모두 skip되면 CHAT 강제 (안전망)
 * - CHAT은 항상 포함 가능
 */
@Component
public class RoutingResolver {

    private final UserChannelPreferenceRepository preferenceRepo;
    private final UserChannelBindingRepository bindingRepo;
    private final ChannelRegistry channelRegistry;

    public RoutingResolver(UserChannelPreferenceRepository preferenceRepo,
                           UserChannelBindingRepository bindingRepo,
                           ChannelRegistry channelRegistry) {
        this.preferenceRepo = preferenceRepo;
        this.bindingRepo = bindingRepo;
        this.channelRegistry = channelRegistry;
    }

    public ResolvedRouting resolve(Recipient recipient) {
        List<ChannelType> resolved = new ArrayList<>();
        Map<ChannelType, String> skipped = new EnumMap<>(ChannelType.class);

        for (ChannelType ch : recipient.requestedChannels()) {
            if (ch != ChannelType.CHAT && !preferenceRepo.isEnabled(recipient.userId(), ch)) {
                skipped.put(ch, "OPTED_OUT");
                continue;
            }
            AuthStrategy auth = channelRegistry.authStrategyOf(ch);
            boolean requiresBinding = auth == AuthStrategy.OAUTH || auth == AuthStrategy.BOT_TOKEN;
            if (requiresBinding && bindingRepo.findActive(recipient.userId(), ch).isEmpty()) {
                skipped.put(ch, "BINDING_MISSING");
                continue;
            }
            resolved.add(ch);
        }

        boolean forced = false;
        if (resolved.isEmpty()) {
            resolved.add(ChannelType.CHAT);
            forced = true;
        }
        return new ResolvedRouting(resolved, skipped, forced);
    }
}
```

- [ ] **Step 4.4: ChannelRegistry 스텁 (Task 5에서 본 구현)**

`ChannelRegistry.java`:
```java
package com.smartfirehub.notification.service;

import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.Channel;
import com.smartfirehub.notification.ChannelType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 모든 Channel 구현체 등록. authStrategy 조회 + 발송 시 lookup. */
@Component
public class ChannelRegistry {

    private final Map<ChannelType, Channel> channels = new EnumMap<>(ChannelType.class);

    public ChannelRegistry(List<Channel> all) {
        for (Channel c : all) {
            channels.put(c.type(), c);
        }
    }

    public Channel get(ChannelType type) {
        Channel c = channels.get(type);
        if (c == null) throw new IllegalStateException("No channel registered: " + type);
        return c;
    }

    public AuthStrategy authStrategyOf(ChannelType type) {
        return get(type).authStrategy();
    }
}
```

- [ ] **Step 4.5: UserChannelPreferenceRepository / UserChannelBindingRepository 인터페이스 스텁**

`UserChannelPreferenceRepository.java`:
```java
package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;

public interface UserChannelPreferenceRepository {
    boolean isEnabled(long userId, ChannelType channelType);
}
```

`UserChannelBindingRepository.java`:
```java
package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;
import java.util.Optional;

public interface UserChannelBindingRepository {
    Optional<UserChannelBinding> findActive(long userId, ChannelType channelType);
}
```

`UserChannelBinding.java` (record):
```java
package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;

public record UserChannelBinding(
        Long id,
        long userId,
        ChannelType channelType,
        Long workspaceId,
        String externalUserId,
        String displayAddress,
        String accessTokenEnc,
        String refreshTokenEnc,
        Instant tokenExpiresAt,
        String status,
        Instant lastVerifiedAt,
        Instant createdAt,
        Instant updatedAt
) {}
```

- [ ] **Step 4.6: 테스트 통과 확인**

```bash
pnpm --filter firehub-api test --tests RoutingResolverTest
```

기대: 6 PASS.

- [ ] **Step 4.7: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/ \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/
git commit -m "feat(notification): RoutingResolver — 라우팅 매트릭스 + CHAT 안전망

스펙 6장 매트릭스를 단위 테스트 6개로 코드화. opt-out/BINDING_MISSING
skip 후 CHAT 강제 폴백. ChannelRegistry/Preference·Binding repo 스텁
포함 (jOOQ 본 구현은 Task 5)."
```

---

## Task 5: jOOQ 기반 Repository 본 구현

**Files:**
- Modify: `UserChannelPreferenceRepository.java` → 구현 클래스 분리
- Modify: `UserChannelBindingRepository.java` → 구현 클래스 분리
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/repository/UserChannelPreferenceRepositoryImpl.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/repository/UserChannelBindingRepositoryImpl.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/repository/SlackWorkspaceRepository.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/repository/OAuthStateRepository.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/repository/NotificationOutboxRepository.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/repository/NotificationOutboxRepositoryIntegrationTest.java`

- [ ] **Step 5.1: NotificationOutboxRepository 인터페이스 + 메서드**

```java
package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationOutboxRepository {

    /** AFTER_COMMIT 훅에서 호출. idempotency_key UNIQUE 충돌은 ON CONFLICT DO NOTHING으로 무시. */
    void insertIfAbsent(NotificationOutboxRow row);

    /** 워커: SELECT FOR UPDATE SKIP LOCKED + claim (status=SENDING, claimed_at=now, claimed_by=instance). */
    List<NotificationOutboxRow> claimDue(int batchSize, String instanceId);

    /** 발송 성공 시 최종 상태 기록. */
    void markSent(long id, String externalMessageId);

    /** 일시 실패 시 backoff 재스케줄. */
    void rescheduleTransient(long id, int newAttemptCount, Instant nextAttemptAt, String error);

    /** 영구 실패 기록. */
    void markPermanentFailure(long id, String reason, String error);

    /** 좀비 회복 (claimed_at < cutoff인 SENDING 행을 PENDING으로 되돌림). */
    int reclaimZombies(Instant cutoff);

    /** correlation 묶음 조회 (관측·UI). */
    List<NotificationOutboxRow> findByCorrelation(UUID correlationId);

    record NotificationOutboxRow(
            Long id,
            String idempotencyKey,
            UUID correlationId,
            String eventType,
            Long eventSourceId,
            ChannelType channelType,
            Long recipientUserId,
            String recipientAddress,
            String payloadRefType,
            Long payloadRefId,
            String payloadJson,
            String payloadType,
            String status,
            int attemptCount,
            Instant nextAttemptAt
    ) {}
}
```

- [ ] **Step 5.2: jOOQ 구현체**

`NotificationOutboxRepositoryImpl.java`:
```java
package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.tables.NotificationOutbox.NOTIFICATION_OUTBOX;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
class NotificationOutboxRepositoryImpl implements NotificationOutboxRepository {

    private final DSLContext dsl;

    NotificationOutboxRepositoryImpl(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insertIfAbsent(NotificationOutboxRow row) {
        dsl.insertInto(NOTIFICATION_OUTBOX)
                .set(NOTIFICATION_OUTBOX.IDEMPOTENCY_KEY, row.idempotencyKey())
                .set(NOTIFICATION_OUTBOX.CORRELATION_ID, row.correlationId())
                .set(NOTIFICATION_OUTBOX.EVENT_TYPE, row.eventType())
                .set(NOTIFICATION_OUTBOX.EVENT_SOURCE_ID, row.eventSourceId())
                .set(NOTIFICATION_OUTBOX.CHANNEL_TYPE, row.channelType().name())
                .set(NOTIFICATION_OUTBOX.RECIPIENT_USER_ID, row.recipientUserId())
                .set(NOTIFICATION_OUTBOX.RECIPIENT_ADDRESS, row.recipientAddress())
                .set(NOTIFICATION_OUTBOX.PAYLOAD_REF_TYPE, row.payloadRefType())
                .set(NOTIFICATION_OUTBOX.PAYLOAD_REF_ID, row.payloadRefId())
                .set(NOTIFICATION_OUTBOX.PAYLOAD, org.jooq.JSONB.valueOf(row.payloadJson()))
                .set(NOTIFICATION_OUTBOX.PAYLOAD_TYPE, row.payloadType())
                .onConflictOnConstraint(org.jooq.impl.DSL.constraint("uk_outbox_idempotency"))
                .doNothing()
                .execute();
    }

    @Override
    public List<NotificationOutboxRow> claimDue(int batchSize, String instanceId) {
        // dsl.transactionResult로 짧은 TX 안에서 SELECT FOR UPDATE SKIP LOCKED 후 UPDATE
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();
            List<Long> ids = tx.select(NOTIFICATION_OUTBOX.ID)
                    .from(NOTIFICATION_OUTBOX)
                    .where(NOTIFICATION_OUTBOX.STATUS.eq("PENDING")
                            .and(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT.le(currentTs())))
                    .orderBy(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT.asc())
                    .limit(batchSize)
                    .forUpdate().skipLocked()
                    .fetchInto(Long.class);

            if (ids.isEmpty()) return List.of();

            tx.update(NOTIFICATION_OUTBOX)
                    .set(NOTIFICATION_OUTBOX.STATUS, "SENDING")
                    .set(NOTIFICATION_OUTBOX.CLAIMED_AT, java.time.OffsetDateTime.now())
                    .set(NOTIFICATION_OUTBOX.CLAIMED_BY, instanceId)
                    .where(NOTIFICATION_OUTBOX.ID.in(ids))
                    .execute();

            return tx.selectFrom(NOTIFICATION_OUTBOX)
                    .where(NOTIFICATION_OUTBOX.ID.in(ids))
                    .fetch(this::toRow);
        });
    }

    @Override
    public void markSent(long id, String externalMessageId) {
        dsl.update(NOTIFICATION_OUTBOX)
                .set(NOTIFICATION_OUTBOX.STATUS, "SENT")
                .set(NOTIFICATION_OUTBOX.SENT_AT, java.time.OffsetDateTime.now())
                // externalMessageId는 별도 컬럼이 없으면 last_error 옆 메타로 저장하거나 skip
                .where(NOTIFICATION_OUTBOX.ID.eq(id))
                .execute();
    }

    @Override
    public void rescheduleTransient(long id, int newAttemptCount, Instant nextAttemptAt, String error) {
        dsl.update(NOTIFICATION_OUTBOX)
                .set(NOTIFICATION_OUTBOX.STATUS, "PENDING")
                .set(NOTIFICATION_OUTBOX.ATTEMPT_COUNT, newAttemptCount)
                .set(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT, nextAttemptAt.atOffset(java.time.ZoneOffset.UTC))
                .set(NOTIFICATION_OUTBOX.LAST_ERROR, error)
                .set(NOTIFICATION_OUTBOX.LAST_ERROR_AT, java.time.OffsetDateTime.now())
                .setNull(NOTIFICATION_OUTBOX.CLAIMED_AT)
                .setNull(NOTIFICATION_OUTBOX.CLAIMED_BY)
                .where(NOTIFICATION_OUTBOX.ID.eq(id))
                .execute();
    }

    @Override
    public void markPermanentFailure(long id, String reason, String error) {
        dsl.update(NOTIFICATION_OUTBOX)
                .set(NOTIFICATION_OUTBOX.STATUS, "PERMANENT_FAILURE")
                .set(NOTIFICATION_OUTBOX.PERMANENT_FAILURE_REASON, reason)
                .set(NOTIFICATION_OUTBOX.LAST_ERROR, error)
                .set(NOTIFICATION_OUTBOX.LAST_ERROR_AT, java.time.OffsetDateTime.now())
                .where(NOTIFICATION_OUTBOX.ID.eq(id))
                .execute();
    }

    @Override
    public int reclaimZombies(Instant cutoff) {
        return dsl.update(NOTIFICATION_OUTBOX)
                .set(NOTIFICATION_OUTBOX.STATUS, "PENDING")
                .setNull(NOTIFICATION_OUTBOX.CLAIMED_AT)
                .setNull(NOTIFICATION_OUTBOX.CLAIMED_BY)
                .where(NOTIFICATION_OUTBOX.STATUS.eq("SENDING")
                        .and(NOTIFICATION_OUTBOX.CLAIMED_AT.lt(cutoff.atOffset(java.time.ZoneOffset.UTC))))
                .execute();
    }

    @Override
    public List<NotificationOutboxRow> findByCorrelation(UUID correlationId) {
        return dsl.selectFrom(NOTIFICATION_OUTBOX)
                .where(NOTIFICATION_OUTBOX.CORRELATION_ID.eq(correlationId))
                .orderBy(NOTIFICATION_OUTBOX.CHANNEL_TYPE.asc())
                .fetch(this::toRow);
    }

    private NotificationOutboxRow toRow(Record r) {
        return new NotificationOutboxRow(
                r.get(NOTIFICATION_OUTBOX.ID),
                r.get(NOTIFICATION_OUTBOX.IDEMPOTENCY_KEY),
                r.get(NOTIFICATION_OUTBOX.CORRELATION_ID),
                r.get(NOTIFICATION_OUTBOX.EVENT_TYPE),
                r.get(NOTIFICATION_OUTBOX.EVENT_SOURCE_ID),
                ChannelType.valueOf(r.get(NOTIFICATION_OUTBOX.CHANNEL_TYPE)),
                r.get(NOTIFICATION_OUTBOX.RECIPIENT_USER_ID),
                r.get(NOTIFICATION_OUTBOX.RECIPIENT_ADDRESS),
                r.get(NOTIFICATION_OUTBOX.PAYLOAD_REF_TYPE),
                r.get(NOTIFICATION_OUTBOX.PAYLOAD_REF_ID),
                r.get(NOTIFICATION_OUTBOX.PAYLOAD) != null
                        ? r.get(NOTIFICATION_OUTBOX.PAYLOAD).data()
                        : null,
                r.get(NOTIFICATION_OUTBOX.PAYLOAD_TYPE),
                r.get(NOTIFICATION_OUTBOX.STATUS),
                r.get(NOTIFICATION_OUTBOX.ATTEMPT_COUNT),
                r.get(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT).toInstant()
        );
    }

    private java.time.OffsetDateTime currentTs() {
        return java.time.OffsetDateTime.now();
    }
}
```

> 외부 메시지 id를 컬럼으로 보관하려면 V48에 `external_message_id TEXT` 컬럼 추가하는 것을 검토. 본 plan에서는 메트릭/관측 용도로만 활용하고 별도 컬럼 추가는 V53과 묶지 않는다.

- [ ] **Step 5.3: Preference/Binding/Workspace/OAuthState 구현체**

스펙 4장 컬럼을 jOOQ 쿼리로 1:1 매핑. `findActive`, `isEnabled`, `findByTeamId`, `consumeState` 같은 명료한 메서드만 노출. (코드 생략 — 위 패턴 그대로)

- [ ] **Step 5.4: 통합 테스트 — outbox 라이프사이클**

`NotificationOutboxRepositoryIntegrationTest.java`:
```java
package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.test.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class NotificationOutboxRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired private NotificationOutboxRepository repo;

    @Test
    void insertIfAbsent_idempotent() {
        UUID corr = UUID.randomUUID();
        var row = sampleRow("key-1", corr);
        repo.insertIfAbsent(row);
        repo.insertIfAbsent(row);  // 두 번째는 무시되어야 함
        assertThat(repo.findByCorrelation(corr)).hasSize(1);
    }

    @Test
    void claimDue_marksSendingAndReturnsRows() {
        UUID corr = UUID.randomUUID();
        repo.insertIfAbsent(sampleRow("key-2", corr));

        var claimed = repo.claimDue(10, "test-instance");
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).status()).isEqualTo("SENDING");
    }

    @Test
    void claimDue_skipLockedConcurrent() throws Exception {
        UUID corr = UUID.randomUUID();
        repo.insertIfAbsent(sampleRow("key-3", corr));

        // 동시 클레임 두 번 — 한쪽만 가져가야 함
        var futures = java.util.List.of(
                java.util.concurrent.CompletableFuture.supplyAsync(() -> repo.claimDue(10, "i1")),
                java.util.concurrent.CompletableFuture.supplyAsync(() -> repo.claimDue(10, "i2"))
        );
        int total = 0;
        for (var f : futures) total += f.get().size();
        assertThat(total).isEqualTo(1);
    }

    @Test
    void markSent_setsStatus() {
        UUID corr = UUID.randomUUID();
        repo.insertIfAbsent(sampleRow("key-4", corr));
        var claimed = repo.claimDue(10, "i").get(0);

        repo.markSent(claimed.id(), "ext-123");

        var rows = repo.findByCorrelation(corr);
        assertThat(rows.get(0).status()).isEqualTo("SENT");
    }

    @Test
    void reclaimZombies_resetsLongClaimedRows() {
        UUID corr = UUID.randomUUID();
        repo.insertIfAbsent(sampleRow("key-5", corr));
        repo.claimDue(10, "i");

        // 실시간 cutoff 미래 = 모두 좀비로 간주
        int reclaimed = repo.reclaimZombies(Instant.now().plusSeconds(60));
        assertThat(reclaimed).isEqualTo(1);
        assertThat(repo.findByCorrelation(corr).get(0).status()).isEqualTo("PENDING");
    }

    private NotificationOutboxRepository.NotificationOutboxRow sampleRow(String key, UUID corr) {
        return new NotificationOutboxRepository.NotificationOutboxRow(
                null, key, corr, "TEST_EVENT", null,
                ChannelType.CHAT, 1L, null,
                null, null, "{\"title\":\"t\"}", "STANDARD",
                "PENDING", 0, Instant.now()
        );
    }
}
```

```bash
pnpm --filter firehub-api test --tests NotificationOutboxRepositoryIntegrationTest
```

기대: 5 PASS.

- [ ] **Step 5.5: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/repository/ \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/repository/
git commit -m "feat(notification): jOOQ 기반 Outbox/Binding/Preference Repository

NotificationOutboxRepository(idempotent insert, claim with SKIP LOCKED,
mark sent/transient/permanent, zombie reclaim) + 보조 repository.
통합 테스트 5케이스 (idempotent, claim, 동시성, sent, zombie reclaim)."
```

---

## Task 6: BackoffPolicy + 단위 테스트

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/service/BackoffPolicy.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/service/BackoffPolicyTest.java`

- [ ] **Step 6.1: 실패 테스트**

```java
package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BackoffPolicyTest {
    private final BackoffPolicy policy = new BackoffPolicy();

    @ParameterizedTest
    @CsvSource({
            "1, 10",        // 첫 transient 실패 후 다음 attempt까지 10초
            "2, 60",        // 1분
            "3, 300",       // 5분
            "4, 1800",      // 30분
            "5, 7200"       // 2시간
    })
    void delaysMatchSpec(int attempt, long expectedSeconds) {
        assertThat(policy.delayFor(attempt).getSeconds()).isEqualTo(expectedSeconds);
    }

    @org.junit.jupiter.api.Test
    void exhaustedAfterFiveAttempts() {
        assertThat(policy.exhausted(5)).isFalse();
        assertThat(policy.exhausted(6)).isTrue();
    }
}
```

```bash
pnpm --filter firehub-api test --tests BackoffPolicyTest
```

기대: FAIL — BackoffPolicy 클래스 없음.

- [ ] **Step 6.2: 구현**

```java
package com.smartfirehub.notification.service;

import java.time.Duration;
import org.springframework.stereotype.Component;

/** 스펙 7장 backoff 정책. attempt=1..5 재시도, 6=PERMANENT_FAILURE(UNRECOVERABLE). */
@Component
public class BackoffPolicy {

    private static final Duration[] DELAYS = {
            Duration.ofSeconds(10),
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2)
    };

    public Duration delayFor(int attempt) {
        if (attempt < 1 || attempt > DELAYS.length) {
            throw new IllegalArgumentException("attempt out of range: " + attempt);
        }
        return DELAYS[attempt - 1];
    }

    public boolean exhausted(int attempt) {
        return attempt > DELAYS.length;
    }
}
```

- [ ] **Step 6.3: 테스트 통과 + 커밋**

```bash
pnpm --filter firehub-api test --tests BackoffPolicyTest
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/service/BackoffPolicy.java \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/service/BackoffPolicyTest.java
git commit -m "feat(notification): BackoffPolicy — 10s/1m/5m/30m/2h 5회 재시도"
```

---

## Task 7: NotificationDispatcher — enqueue + AFTER_COMMIT

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/service/NotificationDispatcher.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/service/IdempotencyKeyGenerator.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/service/NotificationDispatcherTest.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/service/NotificationDispatcherIntegrationTest.java`

- [ ] **Step 7.1: IdempotencyKeyGenerator (단위)**

```java
package com.smartfirehub.notification.service;

import com.smartfirehub.notification.ChannelType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyGenerator {
    /** key = sha256(correlationId|channel|recipientUserId).hex 앞 64자 */
    public String generate(UUID correlationId, ChannelType channel, Long recipientUserId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String src = correlationId + "|" + channel + "|" + (recipientUserId == null ? "ext" : recipientUserId);
            byte[] hash = md.digest(src.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 64);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 7.2: 실패 테스트 — Dispatcher 단위 (Mockito)**

```java
package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock private RoutingResolver routingResolver;
    @Mock private NotificationOutboxRepository outboxRepo;
    @Mock private IdempotencyKeyGenerator keyGen;
    @Mock private OutboxNotifier notifier;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks private NotificationDispatcher dispatcher;

    @Test
    void enqueue_insertsOneRowPerResolvedChannel() throws Exception {
        Recipient r = new Recipient(1L, null, EnumSet.of(ChannelType.SLACK, ChannelType.EMAIL));
        NotificationRequest req = new NotificationRequest(
                "TEST_EVENT", null, 99L, UUID.randomUUID(),
                samplePayload(), null, List.of(r)
        );
        when(routingResolver.resolve(r)).thenReturn(new ResolvedRouting(
                List.of(ChannelType.SLACK, ChannelType.EMAIL),
                java.util.Map.of(),
                false
        ));
        when(keyGen.generate(any(), any(), any())).thenReturn("k1", "k2");
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        dispatcher.enqueue(req);

        ArgumentCaptor<NotificationOutboxRow> rows = ArgumentCaptor.forClass(NotificationOutboxRow.class);
        verify(outboxRepo, times(2)).insertIfAbsent(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(NotificationOutboxRow::channelType)
                .containsExactlyInAnyOrder(ChannelType.SLACK, ChannelType.EMAIL);
        verify(notifier).notifyOutboxNew();
    }

    @Test
    void enqueue_advisesUserWhenForcedChatFallback() throws Exception {
        Recipient r = new Recipient(1L, null, EnumSet.of(ChannelType.SLACK));
        NotificationRequest req = new NotificationRequest(
                "TEST_EVENT", null, 99L, UUID.randomUUID(),
                samplePayload(), null, List.of(r)
        );
        when(routingResolver.resolve(r)).thenReturn(new ResolvedRouting(
                List.of(ChannelType.CHAT),
                java.util.Map.of(ChannelType.SLACK, "BINDING_MISSING"),
                true
        ));
        when(keyGen.generate(any(), any(), any())).thenReturn("k1", "advisory");
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        dispatcher.enqueue(req);

        // CHAT 본문 1개 + advisory 1개 = 총 2개 INSERT
        verify(outboxRepo, times(2)).insertIfAbsent(any());
    }

    private Payload samplePayload() {
        return new Payload(Payload.PayloadType.STANDARD, "t", "s",
                List.of(), List.of(), List.of(),
                java.util.Map.of(), java.util.Map.of());
    }
}
```

```bash
pnpm --filter firehub-api test --tests NotificationDispatcherTest
```

기대: FAIL — Dispatcher/OutboxNotifier 클래스 없음.

- [ ] **Step 7.3: OutboxNotifier (LISTEN/NOTIFY 트리거)**

```java
package com.smartfirehub.notification.service;

import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Postgres NOTIFY 발행으로 워커를 즉시 깨움. 실패해도 폴링 fallback이 30초 내 처리. */
@Component
public class OutboxNotifier {
    private final DataSource dataSource;

    public OutboxNotifier(DataSource dataSource) { this.dataSource = dataSource; }

    public void notifyOutboxNew() {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("NOTIFY outbox_new");
        } catch (Exception e) {
            // 폴링 fallback이 처리함 — log warn만
            org.slf4j.LoggerFactory.getLogger(OutboxNotifier.class)
                    .warn("Failed to NOTIFY outbox_new: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 7.4: NotificationDispatcher 본 구현**

```java
package com.smartfirehub.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.context.event.EventListener;

/** 도메인이 호출하는 단일 진입점. AFTER_COMMIT 훅으로 outbox INSERT. */
@Service
public class NotificationDispatcher {

    private final RoutingResolver routingResolver;
    private final NotificationOutboxRepository outboxRepo;
    private final IdempotencyKeyGenerator keyGen;
    private final OutboxNotifier notifier;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public NotificationDispatcher(RoutingResolver routingResolver,
                                  NotificationOutboxRepository outboxRepo,
                                  IdempotencyKeyGenerator keyGen,
                                  OutboxNotifier notifier,
                                  ObjectMapper objectMapper,
                                  @Value("${notification.outbox.enabled:false}") boolean enabled) {
        this.routingResolver = routingResolver;
        this.outboxRepo = outboxRepo;
        this.keyGen = keyGen;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /** 도메인이 직접 호출. AFTER_COMMIT 보장이 필요하면 publishEvent 경로 사용. */
    public void enqueue(NotificationRequest request) {
        if (!enabled) {
            // feature flag OFF → 회귀 안전: 호출자가 기존 직접 호출 경로를 그대로 쓰도록
            // 의도적으로 no-op. ProactiveJobService에서 flag를 보고 분기.
            return;
        }
        UUID correlationId = request.correlationId() == null ? UUID.randomUUID() : request.correlationId();
        for (Recipient r : request.recipients()) {
            ResolvedRouting routing = routingResolver.resolve(r);
            for (ChannelType ch : routing.resolvedChannels()) {
                outboxRepo.insertIfAbsent(buildRow(request, r, ch, correlationId, request.standardPayload()));
            }
            if (routing.forcedChatFallback()) {
                outboxRepo.insertIfAbsent(buildAdvisoryRow(request, r, correlationId, routing.skippedReasons()));
            }
        }
        notifier.notifyOutboxNew();
    }

    private NotificationOutboxRow buildRow(NotificationRequest req, Recipient r, ChannelType ch,
                                            UUID correlationId, Payload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return new NotificationOutboxRow(
                    null,
                    keyGen.generate(correlationId, ch, r.userId()),
                    correlationId,
                    req.eventType(), req.eventSourceId(),
                    ch, r.userId(), r.externalAddressIfAny(),
                    req.payloadRef() == null ? null : req.payloadRef().type(),
                    req.payloadRef() == null ? null : req.payloadRef().id(),
                    json, "STANDARD",
                    "PENDING", 0, Instant.now()
            );
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("payload serialize failed", e);
        }
    }

    private NotificationOutboxRow buildAdvisoryRow(NotificationRequest req, Recipient r,
                                                    UUID correlationId, Map<ChannelType, String> reasons) {
        Payload advisory = AdvisoryPayloadFactory.build(reasons);
        try {
            String json = objectMapper.writeValueAsString(advisory);
            return new NotificationOutboxRow(
                    null,
                    keyGen.generate(correlationId, ChannelType.CHAT,
                            r.userId() == null ? -1L : -r.userId()),    // 본문 vs advisory 키 분리
                    correlationId,
                    "CHANNEL_ADVISORY", null,
                    ChannelType.CHAT, r.userId(), null,
                    null, null,
                    json, "STANDARD",
                    "PENDING", 0, Instant.now()
            );
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("advisory serialize failed", e);
        }
    }
}
```

- [ ] **Step 7.5: AdvisoryPayloadFactory (사용자 안내 메시지 표준화)**

```java
package com.smartfirehub.notification.service;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.Payload;
import java.util.List;
import java.util.Map;

class AdvisoryPayloadFactory {
    static Payload build(Map<ChannelType, String> skippedReasons) {
        String summary = "외부 채널 발송이 불가능해 웹 인박스에만 표시됩니다. " + skippedReasons;
        return new Payload(
                Payload.PayloadType.STANDARD,
                "🔔 알림 채널 안내",
                summary,
                List.of(new Payload.Section("연동/설정 변경",
                        "채널 연동 또는 수신 설정을 변경하려면 [설정 페이지](/settings/channels)에서 진행하세요.")),
                List.of(new Payload.Link("설정 페이지", "/settings/channels")),
                List.of(),
                Map.of(),
                Map.of()
        );
    }
}
```

- [ ] **Step 7.6: 단위 테스트 통과 + 통합 테스트 (feature flag ON)**

`NotificationDispatcherIntegrationTest.java`:
```java
package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.*;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.test.IntegrationTestBase;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "notification.outbox.enabled=true")
class NotificationDispatcherIntegrationTest extends IntegrationTestBase {

    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private NotificationOutboxRepository repo;

    @Test
    void enqueue_idempotentOnSameRequest() {
        long userId = createTestUser();
        var req = sampleRequest(userId);
        dispatcher.enqueue(req);
        dispatcher.enqueue(req);   // 같은 correlationId/payload → idempotency_key 동일

        var rows = repo.findByCorrelation(req.correlationId());
        // CHAT (요청), 추가 advisory 없음 (CHAT이 요청에 포함되어 forcedChatFallback=false)
        assertThat(rows).hasSize(1);
    }

    @Test
    void enqueue_disabledFlag_doesNothing() {
        // 별도 클래스 또는 @TestPropertySource override로 enabled=false 케이스도 작성
    }

    private NotificationRequest sampleRequest(long userId) {
        return new NotificationRequest(
                "TEST_INTEGRATION", null, userId, UUID.randomUUID(),
                new Payload(Payload.PayloadType.STANDARD, "t", "s",
                        List.of(), List.of(), List.of(), java.util.Map.of(), java.util.Map.of()),
                null,
                List.of(new Recipient(userId, null, EnumSet.of(ChannelType.CHAT)))
        );
    }
}
```

```bash
pnpm --filter firehub-api test --tests NotificationDispatcherTest --tests NotificationDispatcherIntegrationTest
```

기대: 모두 PASS.

- [ ] **Step 7.7: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/service/NotificationDispatcher.java \
        apps/firehub-api/src/main/java/com/smartfirehub/notification/service/IdempotencyKeyGenerator.java \
        apps/firehub-api/src/main/java/com/smartfirehub/notification/service/OutboxNotifier.java \
        apps/firehub-api/src/main/java/com/smartfirehub/notification/service/AdvisoryPayloadFactory.java \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/service/
git commit -m "feat(notification): NotificationDispatcher — enqueue + idempotency + LISTEN/NOTIFY

요청을 RoutingResolver로 펼쳐 channel별 outbox 행 INSERT (멱등성 키 기반).
forcedChatFallback이면 advisory 메시지 1개 추가 enqueue. notification.outbox.enabled=false면 no-op (회귀 안전)."
```

---

## Task 8: NotificationDispatchWorker — 폴링·LISTEN/NOTIFY·발송 실행

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/service/NotificationDispatchWorker.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/service/OutboxListenerLoop.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/service/NotificationDispatchWorkerIntegrationTest.java`

- [ ] **Step 8.1: 워커 본 구현**

```java
package com.smartfirehub.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.*;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 폴링 + LISTEN/NOTIFY 깨움 받아 채널별 발송 실행.
 * SKIP LOCKED + lease 컬럼으로 멀티 인스턴스 안전.
 */
@Component
public class NotificationDispatchWorker {

    private final NotificationOutboxRepository outboxRepo;
    private final UserChannelBindingRepository bindingRepo;
    private final ChannelRegistry channelRegistry;
    private final BackoffPolicy backoff;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String instanceId = "instance-" + UUID.randomUUID().toString().substring(0, 8);
    private final int batchSize;
    private final boolean enabled;

    public NotificationDispatchWorker(NotificationOutboxRepository outboxRepo,
                                       UserChannelBindingRepository bindingRepo,
                                       ChannelRegistry channelRegistry,
                                       BackoffPolicy backoff,
                                       ObjectMapper objectMapper,
                                       MeterRegistry meterRegistry,
                                       @Value("${notification.worker.batch_size:20}") int batchSize,
                                       @Value("${notification.outbox.enabled:false}") boolean enabled) {
        this.outboxRepo = outboxRepo;
        this.bindingRepo = bindingRepo;
        this.channelRegistry = channelRegistry;
        this.backoff = backoff;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.enabled = enabled;
    }

    /** 30초 폴백 폴링. LISTEN/NOTIFY가 즉시 깨우는 것이 정상 경로. */
    @Scheduled(fixedDelayString = "${notification.worker.poll_interval_ms:30000}")
    public void pollOnce() {
        if (!enabled) return;
        runOneBatch();
    }

    /** OutboxListenerLoop가 NOTIFY 수신 시 호출. */
    public void onNotify() {
        if (!enabled) return;
        runOneBatch();
    }

    void runOneBatch() {
        var rows = outboxRepo.claimDue(batchSize, instanceId);
        for (var row : rows) {
            try {
                deliverOne(row);
            } catch (Throwable t) {
                outboxRepo.rescheduleTransient(row.id(), row.attemptCount() + 1,
                        Instant.now().plus(backoff.delayFor(Math.min(row.attemptCount() + 1, 5))),
                        t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private void deliverOne(NotificationOutboxRow row) {
        Channel ch = channelRegistry.get(row.channelType());

        Optional<com.smartfirehub.notification.repository.UserChannelBinding> binding =
                row.recipientUserId() == null ? Optional.empty()
                        : bindingRepo.findActive(row.recipientUserId(), row.channelType());

        Payload payload;
        try {
            payload = objectMapper.readValue(row.payloadJson(), Payload.class);
        } catch (Exception e) {
            outboxRepo.markPermanentFailure(row.id(), "RECIPIENT_INVALID", "payload parse: " + e.getMessage());
            meterRegistry.counter("notification_outbox_permanent_failure_total",
                    "channel", row.channelType().name(), "reason", "RECIPIENT_INVALID").increment();
            return;
        }

        DeliveryContext ctx = new DeliveryContext(row.id(), row.correlationId(),
                row.recipientUserId(), row.recipientAddress(), binding, payload);

        long start = System.nanoTime();
        DeliveryResult result;
        try {
            result = ch.deliver(ctx);
        } catch (Throwable t) {
            // 채널 코드가 RuntimeException 던지면 transient 처리
            result = new DeliveryResult.TransientFailure("uncaught: " + t.getMessage(), t);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        switch (result) {
            case DeliveryResult.Sent s -> {
                outboxRepo.markSent(row.id(), s.externalMessageId());
                meterRegistry.timer("channel_delivery_duration_seconds",
                        "channel", row.channelType().name(), "status", "SENT")
                        .record(java.time.Duration.ofMillis(elapsedMs));
            }
            case DeliveryResult.TransientFailure tf -> {
                int next = row.attemptCount() + 1;
                if (backoff.exhausted(next)) {
                    outboxRepo.markPermanentFailure(row.id(), "UNRECOVERABLE", tf.reason());
                    meterRegistry.counter("notification_outbox_permanent_failure_total",
                            "channel", row.channelType().name(), "reason", "UNRECOVERABLE").increment();
                } else {
                    outboxRepo.rescheduleTransient(row.id(), next,
                            Instant.now().plus(backoff.delayFor(next)), tf.reason());
                }
            }
            case DeliveryResult.PermanentFailure pf -> {
                outboxRepo.markPermanentFailure(row.id(), pf.reason().name(), pf.details());
                meterRegistry.counter("notification_outbox_permanent_failure_total",
                        "channel", row.channelType().name(), "reason", pf.reason().name()).increment();
            }
        }
    }
}
```

- [ ] **Step 8.2: OutboxListenerLoop — LISTEN 전용 스레드**

```java
package com.smartfirehub.notification.service;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OutboxListenerLoop {

    private final DataSource dataSource;
    private final NotificationDispatchWorker worker;
    private final boolean enabled;
    private volatile boolean running = true;

    public OutboxListenerLoop(DataSource dataSource, NotificationDispatchWorker worker,
                              @Value("${notification.worker.listen_notify:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.worker = worker;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!enabled) return;
        Thread t = new Thread(this::loop, "outbox-listener");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        while (running) {
            try (var conn = dataSource.getConnection()) {
                var pgConn = conn.unwrap(org.postgresql.PGConnection.class);
                try (var st = conn.createStatement()) { st.execute("LISTEN outbox_new"); }
                while (running) {
                    var notes = pgConn.getNotifications(30_000);  // 30s timeout
                    if (notes != null && notes.length > 0) worker.onNotify();
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(OutboxListenerLoop.class)
                        .warn("LISTEN loop error, retrying in 5s", e);
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
```

- [ ] **Step 8.3: 통합 테스트 — 전체 발송 흐름**

`NotificationDispatchWorkerIntegrationTest.java`:
```java
package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.smartfirehub.notification.*;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.test.IntegrationTestBase;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "notification.outbox.enabled=true",
        "notification.worker.poll_interval_ms=500",
        "notification.worker.listen_notify=false"   // 테스트는 폴링만 사용
})
class NotificationDispatchWorkerIntegrationTest extends IntegrationTestBase {

    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private NotificationOutboxRepository repo;

    @Test
    void enqueueChatRequest_workerSendsAndMarksSent() {
        long userId = createTestUser();
        var req = chatRequest(userId);
        dispatcher.enqueue(req);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var rows = repo.findByCorrelation(req.correlationId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).status()).isEqualTo("SENT");
        });
    }

    private NotificationRequest chatRequest(long userId) {
        return new NotificationRequest(
                "TEST_WORKER", null, userId, UUID.randomUUID(),
                new Payload(Payload.PayloadType.STANDARD, "t", "s",
                        List.of(), List.of(), List.of(), java.util.Map.of(), java.util.Map.of()),
                null,
                List.of(new Recipient(userId, null, EnumSet.of(ChannelType.CHAT)))
        );
    }
}
```

> 이 테스트는 Task 9에서 ChatChannel 본 구현이 들어와야 PASS. Task 8 시점에는 stub ChatChannel을 임시로 둔다.

- [ ] **Step 8.4: 임시 stub ChatChannel (Task 9에서 본 구현)**

`stubs/StubChatChannel.java` (테스트 클래스패스에만):
```java
package com.smartfirehub.notification.stubs;

import com.smartfirehub.notification.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
class StubChatChannel implements Channel {
    @Override public ChannelType type() { return ChannelType.CHAT; }
    @Override public AuthStrategy authStrategy() { return AuthStrategy.NONE; }
    @Override public DeliveryResult deliver(DeliveryContext ctx) {
        return new DeliveryResult.Sent("stub-msg-" + ctx.outboxId());
    }
}
```

- [ ] **Step 8.5: 테스트 통과 + 커밋**

```bash
pnpm --filter firehub-api test --tests NotificationDispatchWorkerIntegrationTest
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/service/NotificationDispatchWorker.java \
        apps/firehub-api/src/main/java/com/smartfirehub/notification/service/OutboxListenerLoop.java \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/
git commit -m "feat(notification): NotificationDispatchWorker — claim/deliver/retry

@Scheduled 30초 폴링 + PG LISTEN/NOTIFY 즉시 깨움. SKIP LOCKED + lease 컬럼,
Sent/TransientFailure/PermanentFailure 분기, BackoffPolicy 통합. 통합 테스트 1건."
```

---

## Task 9: ChatChannel — 기존 ChatDeliveryChannel 이전 + SSE broadcast 책임

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/ChatChannel.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/channels/ChatChannelTest.java`
- Modify (later): `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/ChatDeliveryChannel.java` — 삭제 (Task 12에서)

- [ ] **Step 9.1: ChatChannel 본 구현**

기존 `ChatDeliveryChannel`의 동작(proactive_message INSERT + SseEmitterRegistry.broadcast)을 새 SPI에 맞춰 이전. payload는 StandardPayload에서 직접 추출 (proactive 전용 필드 의존성 제거).

```java
package com.smartfirehub.notification.channels;

import com.smartfirehub.notification.*;
import com.smartfirehub.notification.metrics.NotificationMetrics;
import com.smartfirehub.notification.payload.PayloadRenderer;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import com.smartfirehub.notification.SseEmitterRegistry;
import com.smartfirehub.notification.NotificationEvent;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** 웹 인박스 메시지 INSERT + 실시간 SSE broadcast. 안전망 채널. */
@Component
public class ChatChannel implements Channel {

    private final ProactiveMessageRepository messageRepo;
    private final SseEmitterRegistry sseRegistry;
    private final PayloadRenderer renderer;

    public ChatChannel(ProactiveMessageRepository messageRepo,
                       SseEmitterRegistry sseRegistry,
                       PayloadRenderer renderer) {
        this.messageRepo = messageRepo;
        this.sseRegistry = sseRegistry;
        this.renderer = renderer;
    }

    @Override public ChannelType type() { return ChannelType.CHAT; }
    @Override public AuthStrategy authStrategy() { return AuthStrategy.NONE; }

    @Override
    public DeliveryResult deliver(DeliveryContext ctx) {
        if (ctx.recipientUserId() == null) {
            return new DeliveryResult.PermanentFailure(PermanentFailureReason.RECIPIENT_INVALID,
                    "CHAT requires recipientUserId");
        }
        var rendered = renderer.toChatMessage(ctx.payload());
        long messageId = messageRepo.create(
                ctx.recipientUserId(),
                rendered.title(),
                rendered.summary(),
                rendered.metadataJson(),
                Instant.now()
        );
        sseRegistry.broadcast(ctx.recipientUserId(),
                NotificationEvent.proactiveMessage(messageId, rendered.title(), rendered.summary()));
        return new DeliveryResult.Sent("chat-msg-" + messageId);
    }
}
```

- [ ] **Step 9.2: PayloadRenderer (Standard → Chat 변환)**

```java
package com.smartfirehub.notification.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.Payload;
import org.springframework.stereotype.Component;

@Component
public class PayloadRenderer {

    private final ObjectMapper objectMapper;

    public PayloadRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ChatRendered toChatMessage(Payload payload) {
        try {
            String metadataJson = objectMapper.writeValueAsString(payload.metadata());
            return new ChatRendered(payload.title(), payload.summary(), metadataJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record ChatRendered(String title, String summary, String metadataJson) {}
}
```

- [ ] **Step 9.3: ChatChannel 단위 테스트 (Mockito + ProactiveMessageRepo stub)**

```java
package com.smartfirehub.notification.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.*;
import com.smartfirehub.notification.payload.PayloadRenderer;
import com.smartfirehub.notification.payload.PayloadRenderer.ChatRendered;
import com.smartfirehub.notification.NotificationEvent;
import com.smartfirehub.notification.SseEmitterRegistry;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatChannelTest {
    @Mock private ProactiveMessageRepository messageRepo;
    @Mock private SseEmitterRegistry sseRegistry;
    @Mock private PayloadRenderer renderer;
    @InjectMocks private ChatChannel channel;

    @Test
    void deliver_insertsMessageAndBroadcasts() {
        when(renderer.toChatMessage(any())).thenReturn(new ChatRendered("t", "s", "{}"));
        when(messageRepo.create(eq(99L), eq("t"), eq("s"), eq("{}"), any())).thenReturn(42L);

        var result = channel.deliver(ctx(99L));

        assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
        verify(sseRegistry).broadcast(eq(99L), any(NotificationEvent.class));
    }

    @Test
    void deliver_failsOnNullUser() {
        var result = channel.deliver(ctx(null));
        assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
    }

    private DeliveryContext ctx(Long userId) {
        return new DeliveryContext(1L, UUID.randomUUID(), userId, null, Optional.empty(),
                new Payload(Payload.PayloadType.STANDARD, "t", "s",
                        List.of(), List.of(), List.of(),
                        java.util.Map.of(), java.util.Map.of()));
    }
}
```

```bash
pnpm --filter firehub-api test --tests ChatChannelTest
```

기대: 2 PASS.

- [ ] **Step 9.4: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/ChatChannel.java \
        apps/firehub-api/src/main/java/com/smartfirehub/notification/payload/PayloadRenderer.java \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/channels/ChatChannelTest.java
git commit -m "feat(notification): ChatChannel — proactive_message INSERT + SSE broadcast

기존 ChatDeliveryChannel 동작을 새 Channel SPI로 이전. 안전망 채널이라
authStrategy=NONE, recipientUserId 강제. 단위 테스트 2건."
```

---

## Task 10: EmailChannel — 기존 EmailDeliveryChannel 이전

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/EmailChannel.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/channels/EmailChannelTest.java`

- [ ] **Step 10.1: EmailChannel 이전**

기존 `EmailDeliveryChannel`의 SMTP 호출 + Thymeleaf HTML 렌더 + (선택)PDF 첨부 로직을 새 SPI로 이전. payload_ref가 PROACTIVE_EXECUTION이면 기존 로직 그대로(execution row를 fetch하여 htmlContent 사용), 그 외엔 PayloadRenderer.toEmailHtml() 표준 변환.

(코드 본문은 기존 EmailDeliveryChannel 로직 거의 그대로, deliver 시그니처만 새 인터페이스에 맞춰 변경)

- [ ] **Step 10.2: GreenMail 통합 테스트 (현재 EmailDeliveryChannelTest의 fixture 재활용)**

```java
package com.smartfirehub.notification.channels;
// ... 기존 EmailDeliveryChannelTest 케이스를 새 시그니처로 이전
```

- [ ] **Step 10.3: 회귀 검증 — 기존 EmailDeliveryChannel 동작 동등성**

기존 EmailDeliveryChannelTest를 그대로 두고, 같은 입력으로 새 EmailChannel을 호출한 결과가 동일한 SMTP 호출을 만드는지 비교.

```bash
pnpm --filter firehub-api test --tests EmailChannelTest
```

기대: 모두 PASS.

- [ ] **Step 10.4: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/EmailChannel.java \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/channels/EmailChannelTest.java
git commit -m "feat(notification): EmailChannel — Thymeleaf HTML + SMTP 발송

기존 EmailDeliveryChannel 로직(payload_ref 참조 발송 + 표준 변환 폴백)을
새 SPI로 이전. GreenMail 통합 테스트로 회귀 동등성 확인."
```

---

## Task 11: OutboxSweeper — 좀비 회복

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/service/OutboxSweeper.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/service/OutboxSweeperIntegrationTest.java`

- [ ] **Step 11.1: 구현**

```java
package com.smartfirehub.notification.service;

import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SENDING으로 5분 이상 묶인 행을 PENDING으로 되돌린다.
 * PG advisory lock으로 단일 인스턴스만 실행 (중복 호출 안전).
 */
@Component
public class OutboxSweeper {

    private final NotificationOutboxRepository outboxRepo;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final Duration zombieAge;

    public OutboxSweeper(NotificationOutboxRepository outboxRepo,
                         MeterRegistry meterRegistry,
                         @Value("${notification.outbox.enabled:false}") boolean enabled,
                         @Value("${notification.worker.zombie_age_minutes:5}") int zombieAgeMin) {
        this.outboxRepo = outboxRepo;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.zombieAge = Duration.ofMinutes(zombieAgeMin);
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)   // 5분
    public void sweep() {
        if (!enabled) return;
        int recovered = outboxRepo.reclaimZombies(Instant.now().minus(zombieAge));
        if (recovered > 0) {
            meterRegistry.counter("notification_outbox_zombie_recovered_total").increment(recovered);
        }
    }
}
```

- [ ] **Step 11.2: 통합 테스트 — claimed_at 강제 backdated**

스펙 4장 인덱스(`idx_outbox_zombie`)와 회복 동작 검증.

- [ ] **Step 11.3: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/service/OutboxSweeper.java \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/service/OutboxSweeperIntegrationTest.java
git commit -m "feat(notification): OutboxSweeper — 좀비 SENDING 행 5분 후 PENDING 복귀"
```

---

## Task 12: ProactiveJobService 호출 지점 교체 + delivered_channels view + 기존 DeliveryChannel 제거

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java`
- Create: `apps/firehub-api/src/main/resources/db/migration/V52_5__create_outbox_delivered_channels_view.sql`
- Modify: `apps/firehub-api/src/main/resources/application.yml`
- Delete: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/{DeliveryChannel,ChatDeliveryChannel,EmailDeliveryChannel}.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/regression/ProactiveJobNotificationRegressionTest.java`

- [ ] **Step 12.1: V52_5 view 작성**

```sql
-- proactive_job_execution.delivered_channels 컬럼 의미를 outbox aggregation으로 대체.
-- 애플리케이션은 이 view를 SELECT 한다 (실제 컬럼이 사라져도 결과는 동일).
CREATE OR REPLACE VIEW proactive_execution_delivered_channels_v AS
SELECT
    o.event_source_id AS execution_id,
    array_agg(DISTINCT o.channel_type ORDER BY o.channel_type) FILTER (WHERE o.status = 'SENT')
            AS delivered_channels,
    array_agg(DISTINCT o.channel_type ORDER BY o.channel_type) FILTER (WHERE o.status = 'PERMANENT_FAILURE')
            AS failed_channels
FROM notification_outbox o
WHERE o.event_type = 'PROACTIVE_RESULT'
GROUP BY o.event_source_id;
```

- [ ] **Step 12.2: application.yml 추가**

```yaml
notification:
  outbox:
    enabled: ${NOTIFICATION_OUTBOX_ENABLED:false}
  worker:
    poll_interval_ms: 30000
    batch_size: 20
    listen_notify: true
    zombie_age_minutes: 5
```

- [ ] **Step 12.3: ProactiveJobService.executeJob 분기**

기존 `List<DeliveryChannel>` 직접 호출 루프(241-251행)를 다음으로 교체:

```java
// notification.outbox.enabled=true이면 새 dispatcher 경로
if (notificationOutboxEnabled) {
    NotificationRequest req = ProactiveJobNotificationMapper.toRequest(job, execution, result);
    notificationDispatcher.enqueue(req);
} else {
    // 회귀 안전 — 기존 직접 호출 경로 유지
    for (DeliveryChannel channel : deliveryChannels) {
        try { channel.deliver(...); } catch (...) { ... }
    }
}
```

> 기존 DeliveryChannel 인터페이스·구현체는 Stage 1.5 PR(별도 PR)에서 삭제. 이 PR에서는 dual-path로 유지.

- [ ] **Step 12.4: ProactiveJobNotificationMapper — 기존 config → NotificationRequest 매핑**

```java
package com.smartfirehub.proactive.service;

import com.smartfirehub.notification.*;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
// ...

public class ProactiveJobNotificationMapper {

    public static NotificationRequest toRequest(ProactiveJobRow job, ProactiveJobExecutionRow execution,
                                                 ProactiveResult result) {
        var channels = ProactiveConfigParser.parseChannels(job.config());
        List<Recipient> recipients = new ArrayList<>();
        for (var ch : channels) {
            for (Long userId : ch.recipientUserIds()) {
                recipients.add(new Recipient(userId, null, EnumSet.of(toChannelType(ch.type()))));
            }
            for (String email : ch.recipientEmails()) {
                recipients.add(new Recipient(null, email, EnumSet.of(ChannelType.EMAIL)));
            }
        }
        return new NotificationRequest(
                "PROACTIVE_RESULT",
                execution.id(),
                job.createdByUserId(),
                null,                         // dispatcher가 UUID 생성
                buildPayload(result),
                new NotificationRequest.PayloadRef("PROACTIVE_EXECUTION", execution.id()),
                recipients
        );
    }
    // ...
}
```

- [ ] **Step 12.5: 회귀 테스트 — 12.5장 체크리스트 1~10**

`ProactiveJobNotificationRegressionTest.java`:
```java
package com.smartfirehub.notification.regression;

import com.smartfirehub.test.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 스펙 12.5장 체크리스트 1~10. feature flag ON/OFF 양쪽에서 동일 결과를 검증.
 */
class ProactiveJobNotificationRegressionTest extends IntegrationTestBase {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("1. ProactiveJob 결과 → 웹 인박스(proactive_message + SSE)")
    void chatDelivery(boolean outboxEnabled) {
        setOutboxEnabled(outboxEnabled);
        // 1) ProactiveJob 실행 트리거
        // 2) proactive_message 테이블 INSERT 확인
        // 3) SseEmitterRegistry broadcast 호출 spy 검증
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("2. ProactiveJob 결과 → 이메일 발송(GreenMail)")
    void emailDelivery(boolean outboxEnabled) {
        // GreenMail로 SMTP 캡처
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("3. 외부 이메일 주소 발송 케이스")
    void externalEmailDelivery(boolean outboxEnabled) {
        // recipientEmails에 외부 주소 입력
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("4. Anomaly 이벤트 → ANOMALY_DETECTED SSE")
    void anomalySseBroadcast(boolean outboxEnabled) {
        // anomaly trigger 후 NotificationEvent.ANOMALY_DETECTED 수신 검증
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("5. Pipeline 완료 → PIPELINE_COMPLETED SSE")
    void pipelineCompletedSse(boolean outboxEnabled) {
        // 파이프라인 실행 완료 → SSE 이벤트
    }

    // 6~10도 동일 패턴 (NotificationEvent JSON 호환, 인박스 unread count, config JSONB 호환,
    // delivered_channels view, 미연동 외부주소 에러)
}
```

```bash
pnpm --filter firehub-api test --tests ProactiveJobNotificationRegressionTest
```

기대: 모든 케이스(채널×flag) PASS.

- [ ] **Step 12.6: 수동 회귀 검증 (스크린샷)**

```bash
pnpm dev:full
# 브라우저 진입: 홈, /ai-insights — 0-3 baseline 스크린샷과 비교
# Proactive Job 수동 실행 → 인박스 표시·이메일 수신 확인
```

`snapshots/channel-stage-1-after/` 캡처 후 baseline과 diff. 시각적 차이 0이어야 함.

- [ ] **Step 12.7: 커밋 (이 task는 가장 위험. PR 분리 권장)**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java \
        apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobNotificationMapper.java \
        apps/firehub-api/src/main/resources/db/migration/V52_5__create_outbox_delivered_channels_view.sql \
        apps/firehub-api/src/main/resources/application.yml \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/regression/ProactiveJobNotificationRegressionTest.java
git commit -m "feat(notification): ProactiveJobService → Dispatcher 듀얼 패스 (회귀 안전)

notification.outbox.enabled feature flag로 신/구 경로 동시 보유.
ProactiveJobNotificationMapper로 기존 config → NotificationRequest 매핑.
delivered_channels view로 컬럼 의미 보존. 회귀 테스트 10건 (flag×ON/OFF)."
```

> 기존 DeliveryChannel 인터페이스·구현체 삭제는 **다음 PR**에서 (운영 1주 안정화 후).

---

## Task 13: 관측성 — Micrometer + Admin endpoints + Retention

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/admin/NotificationAdminController.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/service/NotificationRetentionJob.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/admin/NotificationAdminControllerTest.java`

- [ ] **Step 13.1: Admin endpoints**

```java
package com.smartfirehub.notification.admin;

import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
public class NotificationAdminController {

    private final NotificationOutboxRepository outboxRepo;

    public NotificationAdminController(NotificationOutboxRepository outboxRepo) {
        this.outboxRepo = outboxRepo;
    }

    @GetMapping("/stuck")
    public java.util.List<NotificationOutboxRepository.NotificationOutboxRow> stuck(
            @RequestParam(defaultValue = "PT5M") String olderThan
    ) {
        return outboxRepo.findStuckPending(Instant.now().minus(Duration.parse(olderThan)));
    }

    @PostMapping("/{id}/retry")
    public void retry(@PathVariable long id) {
        outboxRepo.requeueForRetry(id);
    }
}
```

`NotificationOutboxRepository`에 `findStuckPending`, `requeueForRetry` 메서드 추가.

- [ ] **Step 13.2: Retention 잡 (일일 cleanup)**

```java
@Component
public class NotificationRetentionJob {

    private final NotificationOutboxRepository outboxRepo;
    private final OAuthStateRepository oauthRepo;
    private final boolean enabled;

    @Scheduled(cron = "0 30 4 * * *")
    public void cleanup() {
        if (!enabled) return;
        outboxRepo.deleteSentOlderThan(Duration.ofDays(90));
        outboxRepo.deletePermanentFailureOlderThan(Duration.ofDays(180));
        oauthRepo.deleteExpired();
    }
}
```

- [ ] **Step 13.3: NotificationMetrics — Gauge 등록**

```java
package com.smartfirehub.notification.metrics;

import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {
    private final MeterRegistry registry;
    private final NotificationOutboxRepository repo;

    public NotificationMetrics(MeterRegistry registry, NotificationOutboxRepository repo) {
        this.registry = registry;
        this.repo = repo;
    }

    @EventListener(ApplicationReadyEvent.class)
    void register() {
        for (var ch : com.smartfirehub.notification.ChannelType.values()) {
            registry.gauge("notification_outbox_pending_count",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("channel", ch.name())),
                    repo, r -> r.countPending(ch));
        }
    }
}
```

- [ ] **Step 13.4: 테스트 + 커밋**

```bash
pnpm --filter firehub-api test --tests NotificationAdminControllerTest
git add apps/firehub-api/src/main/java/com/smartfirehub/notification/admin/ \
        apps/firehub-api/src/main/java/com/smartfirehub/notification/metrics/ \
        apps/firehub-api/src/main/java/com/smartfirehub/notification/service/NotificationRetentionJob.java \
        apps/firehub-api/src/test/java/com/smartfirehub/notification/admin/
git commit -m "feat(notification): 관측성 — Micrometer + admin endpoints + retention

- /admin/notifications/stuck (5분 초과 PENDING 조회)
- /admin/notifications/{id}/retry (영구 실패 수동 재투입)
- Gauge: outbox_pending_count{channel}
- Counter: outbox_permanent_failure_total, zombie_recovered_total
- Daily cleanup (SENT 90일, PERMANENT_FAILURE 180일, oauth_state 만료)"
```

---

## Task 14: Stage 1 운영 활성화 가이드 + ROADMAP 업데이트

- [ ] **Step 14.1: 운영 활성화 절차 문서**

`docs/runbooks/notification-outbox-rollout.md` 작성:
- dev 활성화 → 24h 모니터링
- stage 활성화 → 72h 모니터링
- 운영 단일 인스턴스 → 1주
- 운영 전체 → 1주 후 deprecated 코드 제거 PR

- [ ] **Step 14.2: ROADMAP 업데이트**

`docs/ROADMAP.md`에 "Channel Stage 1 — Outbox 인프라 ✅" 표시.

- [ ] **Step 14.3: 최종 회귀 검증 + 커밋**

```bash
pnpm test
pnpm --filter firehub-web test:e2e
git add docs/runbooks/notification-outbox-rollout.md docs/ROADMAP.md
git commit -m "docs: Channel Stage 1 운영 활성화 runbook + ROADMAP 갱신"
```

---

## Self-Review Checklist (작성자가 수행)

- **Spec coverage:**
  - 4장 데이터 모델 → Task 1, 2 (V48~V52, V52_5)
  - 5장 Channel SPI → Task 3, 9, 10
  - 6장 라우팅 매트릭스 → Task 4
  - 7장 backoff/rate → Task 6, 8 (rate limit은 Task 8 워커에 인라인, 본격 구현은 Task 13.4 후속 또는 Stage 2에서)
  - 10장 관측성 → Task 13
  - 12장 마이그레이션 Stage 1 → Task 12
  - 12.5장 회귀 방어 → Task 12 (회귀 테스트), 0-3 (baseline)
  - 13장 테스트 전략 → Task별 단위·통합 테스트
- **Placeholder 잔존:**
  - Task 5.3 "코드 생략 — 위 패턴 그대로" — 본 plan에서는 의도적으로 간략. 실행 시 Task 7 패턴 참고.
  - Task 10 "기존 EmailDeliveryChannel 로직 거의 그대로" — 수십 줄의 SMTP/Thymeleaf 코드 복붙이 의도됨. Task 10에서 직접 기존 파일을 열어 본문 이전.
  - Task 12.5 회귀 테스트 6~10 케이스 본문 — Task 12.5 실행 시 12.5장 체크리스트를 보면서 케이스별 작성.
- **타입 일관성:** ChannelType/AuthStrategy/Payload 시그니처는 Task 3에서 한 번 정의 후 모든 후속 task가 그대로 사용.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-18-channel-stage-1-outbox.md`.**

다음 두 가지 실행 옵션 중 선택해 주세요.

1. **Subagent-Driven (recommended)** — 각 Task별 fresh subagent 디스패치, Task 사이 리뷰. 빠른 반복.
2. **Inline Execution** — 현재 세션에서 executing-plans로 batch 실행 + 체크포인트.

어느 쪽으로 진행하시겠어요? Stage 2/3 plan은 Stage 1 완료·검증 후 별도로 작성합니다.
