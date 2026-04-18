# Channel Stage 3 — Slack Inbound (양방향 AI) Implementation Plan

> **For agentic workers:** Subagent-driven 또는 inline 실행. 각 Task는 체크박스(`- [ ]`) 단위로 TDD 진행.

**Goal:** Slack DM/멘션 이벤트를 받아 AI와 양방향 대화를 구현한다. 사용자가 Slack에서 메시지를 보내면 → 서명 검증 → user 매핑 → ai_session 조회/생성 → ai-agent 호출 → 같은 스레드로 응답.

**Architecture:** `POST /api/v1/channels/slack/events` 컨트롤러가 Slack 서명 검증 후 즉시 200 ack + reaction:eyes 반환. @Async slackInboundExecutor에서 user 매핑·세션 lookup·AI 호출·reply 처리. `ai_session`에 SLACK 컨텍스트(team_id, channel_id, thread_ts) 컬럼 추가로 스레드별 세션 분리.

**Tech Stack:** Spring Boot 3.x, jOOQ, Java 21, `@Async` + ThreadPoolTaskExecutor, HMAC-SHA256 서명, Flyway V55, WebClient for ai-agent 호출(non-SSE batch).

**Spec:** `docs/superpowers/specs/2026-04-18-channel-abstraction-design.md` (특히 8장, 10장 메트릭, 11장 보안)
**전제:** Stage 2 완료 (KAKAO/SLACK outbound + `/settings/channels`). `notification.outbox.enabled=true`로 운영 카나리 안정 관찰 후 시작. Slack App에 Event Subscriptions 추가 설정 필요.

---

## File Structure

### 신규
```
apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/
├── SlackEventsController.java          # POST /api/v1/channels/slack/events
├── SlackSignatureVerifier.java         # v0=hex(hmac_sha256) + timestamp ±5분 검증
├── SlackInboundService.java            # @Async 디스패치 → user 매핑 → ai-agent → reply
├── SlackInboundAsyncConfig.java        # slackInboundExecutor bean
├── SlackInboundMetrics.java            # received/processing_duration/unmapped_user 카운터·히스토그램
└── dto/
    ├── SlackEventRequest.java          # url_verification + event_callback 공통
    └── SlackEventCallback.java         # type=message.im | app_mention 파싱

apps/firehub-api/src/main/resources/db/migration/
└── V55__alter_ai_session_for_slack.sql # channel_source (ENUM-like varchar), slack_team_id, slack_channel_id, slack_thread_ts, UNIQUE 제약

apps/firehub-api/src/test/java/com/smartfirehub/notification/inbound/
├── SlackSignatureVerifierTest.java     # 유효/만료/조작 시그니처
├── SlackEventsControllerTest.java      # url_verification challenge + 서명 실패 401
└── SlackInboundServiceIntegrationTest.java  # 전체 플로우 (Mockito: ai-agent, SlackChannel)
```

### 수정
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/SlackChannel.java` — `replyTo(thread_ts, payload)` 메서드 추가 (thread_ts 파라미터로 chat.postMessage)
- `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/slack/SlackApiClient.java` — `reactionsAdd(botToken, channel, timestamp, name)`, `postEphemeral(botToken, channel, user, text)` 추가
- `apps/firehub-api/src/main/java/com/smartfirehub/ai/repository/AiSessionRepository.java` — `findBySlackContext(teamId, channelId, threadTs)`, `insertWithSlackContext(...)` 메서드 추가
- `apps/firehub-api/src/main/java/com/smartfirehub/ai/service/AiAgentProxyService.java` — 또는 신규 `AiAgentBatchClient` — non-SSE 호출 메서드 추가 (단일 응답 대기)
- `apps/firehub-api/src/main/java/com/smartfirehub/global/SecurityConfig.java` — `/api/v1/channels/slack/events`를 public 엔드포인트로 추가 (서명 검증이 인증 대체)
- `apps/firehub-api/src/main/resources/application.yml` — `notification.slack.signing_secret_env`, `slack_inbound_executor` 설정
- `docs/runbooks/notification-outbox-rollout.md` — 9장 추가: Slack Event Subscriptions 활성화 + 테스트 절차
- `docs/ROADMAP.md` — 변경 이력 항목 추가

### 테스트
35+ 테스트 신규: signature 경계값, controller 분기, inbound service 전체 흐름, SlackChannel.replyTo, ai_session slack context 조회/생성.

---

## Task 1: V55 마이그레이션 + AiSessionRepository 확장

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V55__alter_ai_session_for_slack.sql`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/ai/repository/AiSessionRepository.java` + `AiSessionRepositoryImpl.java`(존재 시)
- Modify: `apps/firehub-api/build.gradle.kts` 또는 `application.yml`의 `flyway.baseline-version` → 55

- [ ] **Step 1.1: V55 SQL**

```sql
-- Slack inbound 대화 컨텍스트 컬럼 추가. 기존 web 세션과 구분하기 위한 channel_source.
ALTER TABLE ai_session
    ADD COLUMN IF NOT EXISTS channel_source VARCHAR(16) NOT NULL DEFAULT 'WEB',
    ADD COLUMN IF NOT EXISTS slack_team_id VARCHAR(32),
    ADD COLUMN IF NOT EXISTS slack_channel_id VARCHAR(32),
    ADD COLUMN IF NOT EXISTS slack_thread_ts VARCHAR(32);

ALTER TABLE ai_session
    ADD CONSTRAINT chk_ai_session_channel_source CHECK (channel_source IN ('WEB','SLACK','KAKAO'));

-- Slack 세션은 (team,channel,thread)로 스레드 단위 UNIQUE → 같은 스레드의 메시지는 동일 세션 재사용
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_session_slack_thread
    ON ai_session(slack_team_id, slack_channel_id, slack_thread_ts)
    WHERE channel_source='SLACK';

CREATE INDEX IF NOT EXISTS idx_ai_session_slack_lookup
    ON ai_session(slack_team_id, slack_channel_id);
```

- [ ] **Step 1.2: AiSessionRepository 확장**

```java
/** SLACK 스레드 기준 세션 조회. 없으면 Optional.empty(). */
Optional<AiSession> findBySlackContext(String teamId, String channelId, String threadTs);

/** 새 SLACK 세션 INSERT 후 id·aiAgentSessionId 반환. */
long createSlackSession(long userId, String aiAgentSessionId, String teamId, String channelId, String threadTs, String title);
```

Impl은 jOOQ insertInto(AI_SESSION).set(...).onConflict... 또는 일반 INSERT + findBySlackContext 조합.

- [ ] **Step 1.3: AiSession 도메인 record 확장**

`channelSource`, `slackTeamId`, `slackChannelId`, `slackThreadTs` 필드 추가. 기존 WEB 세션은 channelSource='WEB' 기본값.

- [ ] **Step 1.4: 단위 테스트 — AiSessionRepository 통합 테스트**

Testcontainers 또는 @SpringBootTest(IntegrationTestBase). 케이스:
- createSlackSession → findBySlackContext 로 동일 row 복구
- 중복 (team,channel,thread) INSERT 시 제약 위반 검증 (또는 upsert로 조용히 무시)

- [ ] **Step 1.5: 커밋**

```
feat(ai): V55 ai_session SLACK 컨텍스트 컬럼 + 스레드 단위 UNIQUE

- channel_source ('WEB'|'SLACK'|'KAKAO', DEFAULT 'WEB') + slack_team_id/channel_id/thread_ts
- uk_ai_session_slack_thread partial UNIQUE로 스레드당 1세션 보장
- AiSessionRepository.findBySlackContext/createSlackSession 추가
```

---

## Task 2: SlackSignatureVerifier

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackSignatureVerifier.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/inbound/SlackSignatureVerifierTest.java`

- [ ] **Step 2.1: 구현**

```java
/**
 * Slack 요청 서명 검증. v0=hmac_sha256(signing_secret, "v0:{ts}:{body}") hex.
 * ±5분 timestamp, rotation grace(previous_signing_secret) 지원.
 */
@Component
public class SlackSignatureVerifier {
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);
    private final SlackWorkspaceRepository workspaceRepo;
    private final EncryptionService encryption;

    public boolean verify(String teamId, String timestamp, String body, String signature) {
        // 1. timestamp ±5분 체크 → skew 초과 시 false
        // 2. workspace 조회, signing_secret 복호화
        // 3. computed = "v0=" + hex(hmacSHA256(signing_secret, "v0:" + ts + ":" + body))
        // 4. MessageDigest.isEqual(signature.getBytes(), computed.getBytes())
        // 5. 실패 시 previous_signing_secret + previous_signing_secret_expires_at 확인 후 재시도
    }
}
```

- [ ] **Step 2.2: 테스트 (Mockito + 고정된 body/ts/secret)**

- 유효 서명 → true
- timestamp skew +10분 → false (replay 방어)
- 서명 조작 (1비트 변경) → false
- primary 실패 + previous 유효(만료 전) → true
- 둘 다 실패 → false

- [ ] **Step 2.3: 커밋**

`feat(slack): SlackSignatureVerifier — HMAC-SHA256 + timestamp skew + rotation grace`

---

## Task 3: SlackEventsController + url_verification

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackEventsController.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/dto/SlackEventRequest.java` (Jackson)
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/global/SecurityConfig.java` — `/api/v1/channels/slack/events` public
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/inbound/SlackEventsControllerTest.java`

- [ ] **Step 3.1: Controller**

```java
@RestController
@RequestMapping("/api/v1/channels/slack")
public class SlackEventsController {
    private final SlackSignatureVerifier verifier;
    private final SlackInboundService inboundService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> events(
            @RequestHeader("X-Slack-Signature") String signature,
            @RequestHeader("X-Slack-Request-Timestamp") String timestamp,
            @RequestBody String rawBody) throws IOException {

        JsonNode node = objectMapper.readTree(rawBody);

        // url_verification 은 team_id 없이도 오므로 바로 challenge 응답 (서명 검증 skip은 위험 → 검증하되 team_id 없을 때도 동작하게 전역 secret 사용)
        if ("url_verification".equals(node.path("type").asText())) {
            return ResponseEntity.ok(Map.of("challenge", node.path("challenge").asText()));
        }

        String teamId = node.path("team_id").asText();
        if (!verifier.verify(teamId, timestamp, rawBody, signature)) {
            return ResponseEntity.status(401).build();
        }

        // event_callback → 즉시 200 ack + async 처리
        JsonNode event = node.path("event");
        String eventType = event.path("type").asText();
        if ("message".equals(eventType) || "app_mention".equals(eventType)) {
            // subtype 필터: bot_message·message_changed·message_deleted 제외
            if (event.has("subtype")) return ResponseEntity.ok().build();
            inboundService.dispatch(teamId, event);  // @Async
        }

        return ResponseEntity.ok().build();  // 3초 내 ack
    }
}
```

- [ ] **Step 3.2: SecurityConfig 공개**

```java
.requestMatchers("/api/v1/channels/slack/events").permitAll()
```

- [ ] **Step 3.3: 테스트**

- url_verification request → challenge 응답
- 서명 실패 → 401
- subtype=bot_message → 200 + dispatch 호출 없음
- 정상 message.im → 200 + dispatch 호출 (Mockito verify)

- [ ] **Step 3.4: 커밋**

`feat(slack): SlackEventsController — 3초 ack + 서명 검증 + url_verification`

---

## Task 4: SlackInboundAsyncConfig + SlackApiClient 확장

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackInboundAsyncConfig.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/slack/SlackApiClient.java` — reactionsAdd, postEphemeral 추가

- [ ] **Step 4.1: Executor**

```java
@Configuration
public class SlackInboundAsyncConfig {
    @Bean("slackInboundExecutor")
    public ThreadPoolTaskExecutor slackInboundExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(3);
        ex.setMaxPoolSize(5);
        ex.setQueueCapacity(20);
        ex.setThreadNamePrefix("slack-inbound-");
        ex.setRejectedExecutionHandler(new CallerRunsPolicy()); // 큐 초과 시 동기 실행 (fail-fast 보다 나음)
        ex.initialize();
        return ex;
    }
}
```

- [ ] **Step 4.2: SlackApiClient 메서드 추가**

```java
public JsonNode reactionsAdd(String botToken, String channel, String timestamp, String name)
public JsonNode postEphemeral(String botToken, String channel, String user, String text)
public JsonNode chatPostMessageInThread(String botToken, String channel, String threadTs, String blocksJson, String text)
```

기존 `chatPostMessage`는 `thread_ts` 옵션 파라미터 추가로 확장하거나 새 메서드 분리. `SlackChannel.replyTo`에서 이 메서드 호출.

- [ ] **Step 4.3: 단위 테스트 — SlackApiClient (WireMock)**

POST /reactions.add 호출 → 200 응답 파싱. thread_ts 포함 JSON body 검증.

- [ ] **Step 4.4: 커밋**

`feat(slack): async executor + reactions.add/postEphemeral/chat.postMessage(thread) API`

---

## Task 5: SlackInboundService (핵심)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackInboundService.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/SlackChannel.java` — `replyTo(long outboxId, String threadTs, Payload payload)` 추가
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/inbound/SlackInboundServiceIntegrationTest.java`

- [ ] **Step 5.1: Service 스켈레톤**

```java
@Service
public class SlackInboundService {
    private final UserChannelBindingRepository bindingRepo;
    private final SlackWorkspaceRepository workspaceRepo;
    private final AiSessionRepository aiSessionRepo;
    private final AiAgentBatchClient aiAgentClient;  // 신규
    private final SlackApiClient slackApiClient;
    private final SlackChannel slackChannel;
    private final EncryptionService encryption;
    private final SlackInboundMetrics metrics;

    @Async("slackInboundExecutor")
    public void dispatch(String teamId, JsonNode event) {
        metrics.incrementReceived();
        long start = System.nanoTime();
        try {
            String channel = event.path("channel").asText();
            String user = event.path("user").asText();
            String text = event.path("text").asText();
            String ts = event.path("ts").asText();
            String threadTs = event.path("thread_ts").asText(ts); // 없으면 top-level = ts

            // 1. 워크스페이스 + 봇 토큰 확보
            var workspace = workspaceRepo.findByTeamId(teamId)
                    .orElseThrow(() -> new IllegalStateException("unknown team: " + teamId));
            String botToken = encryption.decrypt(workspace.botTokenEnc());

            // 2. reaction:eyes (처리 중 표시)
            slackApiClient.reactionsAdd(botToken, channel, ts, "eyes");

            // 3. user 매핑
            var binding = bindingRepo.findByExternalId(teamId, user); // 신규 메서드
            if (binding.isEmpty()) {
                metrics.incrementUnmappedUser();
                slackApiClient.postEphemeral(botToken, channel, user,
                    "Smart Fire Hub 웹에서 먼저 계정 연동을 진행해주세요: https://app.smartfirehub.com/settings/channels");
                return;
            }
            long userId = binding.get().userId();

            // 4. ai_session lookup
            var existing = aiSessionRepo.findBySlackContext(teamId, channel, threadTs);
            String aiAgentSessionId;
            if (existing.isPresent()) {
                aiAgentSessionId = existing.get().aiAgentSessionId();
            } else {
                aiAgentSessionId = aiAgentClient.createSession(userId, "Slack " + threadTs);
                aiSessionRepo.createSlackSession(userId, aiAgentSessionId, teamId, channel, threadTs, "Slack 대화");
            }

            // 5. ai-agent chat
            String aiResponse;
            try {
                aiResponse = aiAgentClient.chat(aiAgentSessionId, userId, text);
            } catch (Exception e) {
                slackApiClient.reactionsAdd(botToken, channel, ts, "warning");
                slackApiClient.postEphemeral(botToken, channel, user, "AI 응답 중 오류가 발생했습니다.");
                return;
            }

            // 6. SlackChannel.replyTo — 같은 스레드로 응답
            slackChannel.replyTo(channel, threadTs, aiResponse, binding.get());
        } finally {
            metrics.recordProcessingDuration(Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
```

- [ ] **Step 5.2: SlackChannel.replyTo**

```java
/** 인바운드 대응용 — thread_ts로 같은 스레드에 텍스트 응답. */
public void replyTo(String channel, String threadTs, String text, UserChannelBinding binding) {
    var workspace = workspaceRepo.findById(binding.workspaceId()).orElseThrow();
    String botToken = encryption.decrypt(workspace.botTokenEnc());
    slackApiClient.chatPostMessageInThread(botToken, channel, threadTs, null, text);
}
```

- [ ] **Step 5.3: UserChannelBindingRepository — findByExternalId**

```java
Optional<UserChannelBinding> findByExternalId(String teamId, String slackUserId);
// jOOQ: JOIN slack_workspace on team_id, WHERE channel_type='SLACK' AND external_user_id=?
```

- [ ] **Step 5.4: AiAgentBatchClient (신규)**

기존 `AiAgentProxyService`는 SSE 스트리밍. non-streaming batch 호출 클라이언트 분리:

```java
@Component
public class AiAgentBatchClient {
    private final WebClient webClient;
    @Value("${ai.agent.base-url:http://localhost:3001}") private String baseUrl;

    public String createSession(long userId, String title) { /* POST /agent/session */ }
    public String chat(String sessionId, long userId, String text) {
        // POST /agent/chat?stream=false { sessionId, userId, message }
        // 동기 blocking, timeout 60s. 응답 JSON의 content 추출.
    }
}
```

ai-agent 앱이 non-streaming 모드 지원 여부 확인 필요 — 없으면 SSE 스트림을 서버사이드에서 concat하여 최종 content 반환하는 어댑터 추가.

- [ ] **Step 5.5: 통합 테스트 — dispatch 전체 흐름**

- 정상: 매핑된 사용자 + 세션 새로 생성 + ai-agent stub → SlackChannel.replyTo 호출
- 미매핑: postEphemeral 호출, chat/reply 미호출
- ai-agent 오류: reaction:warning + postEphemeral 오류 메시지
- 기존 스레드 재사용: findBySlackContext hit → createSession 미호출

- [ ] **Step 5.6: 커밋**

`feat(slack): SlackInboundService — user 매핑 + ai_session + ai-agent batch + 스레드 reply`

---

## Task 6: SlackInboundMetrics + 관측성

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/inbound/SlackInboundMetrics.java`

- [ ] **Step 6.1: 메트릭**

```java
@Component
public class SlackInboundMetrics {
    private final Counter received;
    private final Timer processingDuration;
    private final Counter unmappedUser;

    public SlackInboundMetrics(MeterRegistry registry) {
        this.received = Counter.builder("slack_inbound_received_total").register(registry);
        this.processingDuration = Timer.builder("slack_inbound_processing_duration_seconds").register(registry);
        this.unmappedUser = Counter.builder("slack_inbound_unmapped_user_total").register(registry);
    }
    // incrementReceived/recordProcessingDuration/incrementUnmappedUser
}
```

- [ ] **Step 6.2: 로깅 — correlation_id MDC**

SlackInboundService.dispatch 진입 시 `MDC.put("correlationId", "slack-" + teamId + "-" + ts)`. finally에서 remove.

- [ ] **Step 6.3: 커밋**

`feat(slack): inbound 메트릭 + correlation_id MDC`

---

## Task 7: 런북 + ROADMAP + 통합 검증

- [ ] **Step 7.1: 런북 9장 추가** — `docs/runbooks/notification-outbox-rollout.md`

```
## 9. Stage 3 활성화 (Slack inbound 양방향)

### 9.1 Slack App Event Subscriptions 추가 설정

1. api.slack.com/apps → 앱 선택 → Event Subscriptions → Enable Events
2. Request URL: https://{domain}/api/v1/channels/slack/events (자동 url_verification 성공 확인)
3. Subscribe to bot events: message.im, app_mention
4. Bot Token Scopes 추가: im:history, app_mentions:read (Stage 2에서 이미 포함됐으면 skip)
5. 변경 사항 저장 후 "Reinstall to Workspace" 필수 (권한 변경 반영)
6. signing_secret 재확인(변경 없음) 또는 rotation 시 slack_workspace.signing_secret_enc 업데이트

### 9.2 기능 검증

- 테스트 사용자 Slack 계정으로 봇에게 DM 전송 → 3초 내 :eyes: 리액션 → 30초 내 같은 스레드로 AI 응답
- 미매핑 사용자가 DM → ephemeral 안내 메시지 표시 + ai-agent 미호출
- 같은 스레드 연속 메시지 → ai_session.findBySlackContext hit → 동일 session_id 사용

### 9.3 메트릭 모니터링

- slack_inbound_received_total 증가율
- slack_inbound_processing_duration_seconds p95 < 30s
- slack_inbound_unmapped_user_total 급증 → 안내 문구 개선 필요

### 9.4 장애 회귀

- signing_secret rotation 필요 시: slack_workspace 업데이트 + previous_signing_secret_expires_at으로 5분 grace
- ai-agent 장애 시: reaction:warning + ephemeral 오류 메시지로 사용자에게 알림, 재시도는 사용자 몫 (Stage 3.5에서 자동 재시도 도입 고려)
```

- [ ] **Step 7.2: ROADMAP.md — 변경 이력 추가**

- [ ] **Step 7.3: 커밋**

`docs(notification): Stage 3 runbook 9장 + ROADMAP — Slack inbound 양방향`

---

## Self-Review Checklist

- **Spec coverage:**
  - 8장 Slack inbound → Task 2~5
  - 10장 메트릭 → Task 6
  - 11장 보안 (signature, rotation grace, unmapped user ephemeral-only) → Task 2, 5
- **3초 ack 보장:** Task 3 컨트롤러에서 @Async dispatch 이후 즉시 200 반환 — blocking 작업 없음
- **Thread 컨텍스트:** V55 UNIQUE INDEX로 스레드당 1세션 강제, 재사용 동작 검증
- **회귀 방어:** 기존 `AiAgentProxyService` SSE 경로 미변경. Stage 3는 batch 전용 클라이언트 신설.

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-04-19-channel-stage-3-slack-inbound.md`. 실행 옵션:
1. Subagent-Driven (Task 단위 위임, 병렬 가능한 Task 2/4 분리 가능)
2. Inline (메인에서 직접 Task 1→7 순차)

어느 방식으로 진행할까요?
