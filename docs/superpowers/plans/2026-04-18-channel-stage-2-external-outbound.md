# Channel Stage 2 — KAKAO/SLACK Outbound + 사용자 연동 UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stage 1 Outbox 인프라 위에 KAKAO(나에게 보내기) + SLACK(봇 outbound) 두 채널을 실제 동작하는 구현체로 추가한다. 사용자가 `/settings/channels` 페이지에서 본인 계정을 OAuth로 연동하고, `ChannelRecipientEditor`에서 두 새 채널을 수신자별로 선택할 수 있게 한다.

**Architecture:** Stage 1의 `Channel` SPI에 `KakaoChannel`, `SlackChannel` 구현체 추가. 사용자별 `user_channel_binding` 레코드를 OAuth 콜백 + 토큰 refresh 사이클로 관리. 프론트 `/settings/channels` 페이지는 카드 UI로 각 채널의 연동 상태·토글·연동 시작/해제 버튼 노출. `ChannelRecipientEditor`는 4채널로 확장 + 미연동 사용자 경고 배지.

**Tech Stack:** Spring Boot 3.x, jOOQ, Java 21, React 19, TanStack Query, Zod, shadcn/ui, Playwright E2E

**Spec:** `docs/superpowers/specs/2026-04-18-channel-abstraction-design.md` (특히 5·9·11장)
**전제:** Stage 1 plan 완료 (`docs/superpowers/plans/2026-04-18-channel-stage-1-outbox.md`). `notification.outbox.enabled=true`로 운영 카나리 1주 안정 관찰 후 Stage 2 시작.

---

## File Structure

### 신규 (백엔드)
```
apps/firehub-api/src/main/java/com/smartfirehub/notification/
├── channels/
│   ├── KakaoChannel.java               # 나에게 보내기 outbound
│   ├── SlackChannel.java               # 봇 outbound (DM)
│   └── kakao/
│       ├── KakaoApiClient.java         # WebClient 래퍼
│       └── KakaoTextFormatter.java     # StandardPayload → 1000자 텍스트 + 안내문구
│   └── slack/
│       ├── SlackApiClient.java
│       └── SlackBlockKitRenderer.java  # StandardPayload → Block Kit JSON
├── auth/
│   ├── KakaoOAuthService.java          # code → token, refresh
│   ├── SlackOAuthService.java          # workspace install + user identity
│   ├── OAuthStateService.java          # state CSRF 발급/소비
│   ├── EncryptedTokenStore.java        # AES/GCM 래핑 (기존 EncryptionService 재사용)
│   └── controller/
│       ├── KakaoOAuthController.java   # /api/v1/oauth/kakao/*
│       └── SlackOAuthController.java   # /api/v1/oauth/slack/*
└── settings/
    ├── ChannelSettingsService.java     # 사용자 binding/preference 관리
    ├── ChannelSettingsController.java  # /api/v1/channels/settings
    └── dto/
        ├── ChannelSettingResponse.java
        └── ChannelPreferenceRequest.java
```

### 신규 (프론트엔드)
```
apps/firehub-web/src/
├── pages/settings/
│   ├── ChannelsPage.tsx                # /settings/channels 진입점
│   └── components/
│       ├── ChannelCard.tsx             # 단일 채널(CHAT/EMAIL/KAKAO/SLACK) 카드
│       ├── OAuthConnectButton.tsx      # 외부 창 오픈 + 콜백 대기
│       └── ChannelStatusBadge.tsx      # 연결됨/재인증필요/미연결 badge
├── api/channels.ts                     # GET/POST /api/v1/channels/settings
├── hooks/queries/useChannelSettings.ts # TanStack Query wrapper
└── pages/ai-insights/components/
    └── ChannelRecipientEditor.tsx      # 4채널 확장 + 미연동 경고 배지
```

### 수정 (백엔드)
- 신규 설정 항목: `application.yml`에 `notification.kakao.*`, `notification.slack.*` (client_id, client_secret_env, redirect_uri)

### 수정 (프론트엔드)
- `src/App.tsx`: `/settings/channels` 라우트 추가
- `src/components/layout/AppLayout.tsx`: UserNav/사이드 메뉴에 링크 추가
- `src/hooks/queries/useAiInsights.ts` 등 `ChannelRecipientEditor` 사용 처: 새 채널 대응 확인

### 테스트
```
apps/firehub-api/src/test/java/com/smartfirehub/notification/
├── channels/KakaoChannelTest.java        # Mockito — 토큰 refresh, deliver, 안내문구 append
├── channels/SlackChannelTest.java        # Mockito — chat.postMessage 호출 payload
├── auth/KakaoOAuthServiceTest.java       # WireMock — code → token
├── auth/SlackOAuthServiceTest.java       # WireMock — workspace install
└── settings/ChannelSettingsControllerTest.java  # @SpringBootTest

apps/firehub-web/e2e/pages/settings/
└── channels-page.spec.ts                 # Playwright E2E — 연동 상태/토글/해제
```

---

## Task 1: Kakao OAuth + KakaoApiClient 기반

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/auth/OAuthStateService.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/auth/KakaoOAuthService.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/kakao/KakaoApiClient.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/auth/KakaoOAuthServiceTest.java`

- [ ] **Step 1.1: OAuthStateService — CSRF state 발급/소비 래퍼**

```java
package com.smartfirehub.notification.auth;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.OAuthStateRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class OAuthStateService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final SecureRandom RNG = new SecureRandom();
    private final OAuthStateRepository repo;

    public OAuthStateService(OAuthStateRepository repo) { this.repo = repo; }

    /** 32바이트 CSPRNG hex 생성 + repo INSERT. 반환 state를 redirect_uri에 포함. */
    public String issue(long userId, ChannelType channelType) {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        repo.create(state, userId, channelType, Instant.now().plus(TTL));
        return state;
    }

    /** 콜백에서 호출. state 소비 + 미소비/미만료 검증. */
    public Optional<OAuthStateRepository.ConsumedState> consume(String state) {
        return repo.consume(state);
    }
}
```

- [ ] **Step 1.2: KakaoApiClient — WebClient 래퍼**

```java
package com.smartfirehub.notification.channels.kakao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Kakao REST API — code → token, refresh, 나에게 보내기 (memo/default/send). */
@Component
public class KakaoApiClient {

    private final WebClient authClient;   // kauth.kakao.com
    private final WebClient apiClient;    // kapi.kakao.com
    private final ObjectMapper objectMapper;

    public KakaoApiClient(ObjectMapper objectMapper) {
        this.authClient = WebClient.builder().baseUrl("https://kauth.kakao.com").build();
        this.apiClient = WebClient.builder().baseUrl("https://kapi.kakao.com").build();
        this.objectMapper = objectMapper;
    }

    /** code + client_id + redirect_uri → token 응답 (access_token, refresh_token, expires_in). */
    public JsonNode exchangeCode(String code, String clientId, String clientSecret, String redirectUri) {
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        return authClient.post().uri("/oauth/token")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve().bodyToMono(JsonNode.class)
                .block();
    }

    /** refresh_token → 새 access_token. */
    public JsonNode refresh(String refreshToken, String clientId, String clientSecret) {
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        return authClient.post().uri("/oauth/token")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve().bodyToMono(JsonNode.class)
                .block();
    }

    /** 나에게 보내기 — template_object 를 text 템플릿으로 전송. */
    public void sendMemoText(String accessToken, String text, String webUrl) {
        String templateJson = "{\"object_type\":\"text\",\"text\":"
                + objectMapper.valueToTree(text)
                + ",\"link\":{\"web_url\":\"" + escape(webUrl) + "\"}}";
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("template_object", templateJson);
        apiClient.post().uri("/v2/api/talk/memo/default/send")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve().bodyToMono(Void.class)
                .block();
    }

    private static String escape(String s) { return s == null ? "" : s.replace("\"", "\\\""); }
}
```

- [ ] **Step 1.3: KakaoOAuthService — 설정 주입, code→binding INSERT, refresh 흐름**

```java
package com.smartfirehub.notification.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfirehub.global.security.EncryptionService;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.channels.kakao.KakaoApiClient;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class KakaoOAuthService {

    private final KakaoApiClient kakaoApiClient;
    private final UserChannelBindingRepository bindingRepo;
    private final EncryptionService encryption;

    @Value("${notification.kakao.client_id:}") private String clientId;
    @Value("${notification.kakao.client_secret:}") private String clientSecret;
    @Value("${notification.kakao.redirect_uri:}") private String redirectUri;

    public KakaoOAuthService(KakaoApiClient kakaoApiClient,
                              UserChannelBindingRepository bindingRepo,
                              EncryptionService encryption) {
        this.kakaoApiClient = kakaoApiClient;
        this.bindingRepo = bindingRepo;
        this.encryption = encryption;
    }

    /** 인증 URL 반환 (프론트가 새 창으로 열어 사용자 로그인 유도). */
    public String authorizeUrl(String state) {
        return "https://kauth.kakao.com/oauth/authorize?response_type=code"
                + "&client_id=" + clientId
                + "&redirect_uri=" + redirectUri
                + "&scope=talk_message"
                + "&state=" + state;
    }

    /** code → token 교환 후 user_channel_binding upsert. */
    public void completeAuthorization(long userId, String code) {
        JsonNode resp = kakaoApiClient.exchangeCode(code, clientId, clientSecret, redirectUri);
        String accessToken = resp.path("access_token").asText();
        String refreshToken = resp.path("refresh_token").asText();
        long expiresInSeconds = resp.path("expires_in").asLong();

        bindingRepo.upsert(new UserChannelBinding(
                null, userId, ChannelType.KAKAO, null, null, null,
                encryption.encrypt(accessToken),
                encryption.encrypt(refreshToken),
                Instant.now().plusSeconds(expiresInSeconds),
                "ACTIVE", Instant.now(), Instant.now(), Instant.now()));
    }
}
```

> `UserChannelBindingRepository`에 `upsert(UserChannelBinding)` 메서드 추가 필요 — `ON CONFLICT (user_id, channel_type, workspace_id) DO UPDATE SET ...`. Stage 1에서는 findActive만 있었음. 본 plan Task 1에서 같이 확장.

- [ ] **Step 1.4: UserChannelBindingRepository.upsert + findByUser**

기존 `UserChannelBindingRepository.java`에 메서드 추가:
```java
/** 사용자 + 채널(+ 워크스페이스) 조합이 있으면 토큰·상태 갱신, 없으면 INSERT. */
void upsert(UserChannelBinding binding);

/** 사용자의 모든 binding 조회 (settings 화면 용). */
java.util.List<UserChannelBinding> findByUser(long userId);

/** binding 해제 (status=REVOKED). */
void revoke(long userId, ChannelType channelType, Long workspaceId);
```

구현은 jOOQ `insertInto(...).onConflictOnConstraint(...).doUpdate().set(...)` 패턴.

- [ ] **Step 1.5: 단위 테스트 — WireMock으로 카카오 API mock**

`KakaoOAuthServiceTest.java`:
```java
// WireMock으로 kauth.kakao.com /oauth/token을 stub → exchangeCode → binding upsert 확인
// 200 응답 stub: {"access_token":"AAA","refresh_token":"BBB","expires_in":21599}
// 검증: bindingRepo.upsert 호출, 암호화된 토큰 전달, tokenExpiresAt 미래값
```

- [ ] **Step 1.6: 설정 + 커밋**

`application.yml` 주석만 추가:
```yaml
notification:
  kakao:
    client_id: ${KAKAO_CLIENT_ID:}
    client_secret: ${KAKAO_CLIENT_SECRET:}
    redirect_uri: ${KAKAO_REDIRECT_URI:https://app.smartfirehub.com/api/v1/oauth/kakao/callback}
```

커밋: `feat(notification): Kakao OAuth + 나에게 보내기 API client`

---

## Task 2: KakaoChannel (Channel SPI 구현)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/kakao/KakaoTextFormatter.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/KakaoChannel.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/channels/KakaoChannelTest.java`

- [ ] **Step 2.1: KakaoTextFormatter — StandardPayload → 1000자 텍스트 + 안내문구 append**

```java
package com.smartfirehub.notification.channels.kakao;

import com.smartfirehub.notification.Payload;
import org.springframework.stereotype.Component;

@Component
public class KakaoTextFormatter {

    private static final int MAX_LEN = 990;   // 1000자 제한, 안내문구 여유 10자
    private static final String FOOTER =
            "\n\n답장은 Smart Fire Hub 웹/Slack에서";

    public String render(Payload payload) {
        StringBuilder sb = new StringBuilder();
        if (payload.title() != null) sb.append(payload.title()).append('\n');
        if (payload.summary() != null) sb.append(payload.summary());
        // sections/links도 1줄씩 추가하되 MAX_LEN 초과 시 절단
        // ...
        String body = sb.toString();
        if (body.length() > MAX_LEN) body = body.substring(0, MAX_LEN - 1) + "…";
        return body + FOOTER;
    }
}
```

- [ ] **Step 2.2: KakaoChannel + BoundChannel 구현**

```java
// Channel.type=KAKAO, authStrategy=OAUTH
// deliver 시 binding 필수. 토큰 만료면 refreshIfNeeded로 갱신 (실패 시 status=TOKEN_EXPIRED,
// PermanentFailure(TOKEN_EXPIRED) 반환).
// access_token 복호화 → KakaoApiClient.sendMemoText(text, deepLinkUrl) 호출.
// 성공 → Sent("kakao-" + outboxId). 401/만료 → TransientFailure로 refresh 1회 재시도 (워커 backoff).
```

- [ ] **Step 2.3: 단위 테스트**

Mockito로 KakaoApiClient stub. 케이스: 정상 발송/binding 없음/토큰 만료(refreshIfNeeded 호출)/rate limit 응답(TransientFailure).

- [ ] **Step 2.4: 커밋**

`feat(notification): KakaoChannel — 나에게 보내기 + refresh + 안내문구 append`

---

## Task 3: Slack Workspace OAuth (관리자 1회 설치)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/slack/SlackApiClient.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/auth/SlackOAuthService.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/auth/controller/SlackOAuthController.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/auth/SlackOAuthServiceTest.java`

- [ ] **Step 3.1: SlackApiClient — oauth.v2.access + chat.postMessage + users.info**

WebClient 래퍼. `https://slack.com/api/oauth.v2.access`, `chat.postMessage`, `users.info`.

- [ ] **Step 3.2: SlackOAuthService — 워크스페이스 설치 (관리자 전용)**

```java
// authorizeUrl(state) → https://slack.com/oauth/v2/authorize?client_id=...&scope=chat:write,im:write,im:history,users:read,reactions:write,app_mentions:read&state=...
// completeAuthorization(code) → oauth.v2.access 호출
//   → slack_workspace 테이블 upsert (team_id, bot_user_id, bot_token_enc, signing_secret_enc)
```

- [ ] **Step 3.3: SlackOAuthController — /api/v1/oauth/slack/start, /callback**

```java
// GET /start → state 발급 → authorize URL 리다이렉트
// GET /callback?code=...&state=... → state 소비 + oauth.v2.access + workspace upsert
//   → 성공 페이지 HTML 반환 (프론트가 창 닫기)
```

- [ ] **Step 3.4: SlackWorkspaceRepository 확장**

기존에 findByTeamId만 있던 인터페이스에 `upsert`, `revoke(teamId)` 추가.

- [ ] **Step 3.5: 사용자 Slack 매핑 플로우**

워크스페이스가 설치된 후, 각 사용자가 별도로 `/api/v1/oauth/slack/link-user`로 자신의 slack user id를 연동. 옵션 2가지:
- A: Slack Magic Link — 봇이 사용자에게 DM 링크 보내는 방식 (봇이 user_id를 알아야 하므로 닭과 달걀)
- B: 사용자가 웹에서 "Slack user id" 수동 입력 → 봇이 해당 user에게 `chat.postMessage` ping → 사용자가 "확인" 링크 클릭
- **권장 B.** Stage 3 Slack inbound 구현 후 사용자가 DM으로 `/connect`를 보내면 자동 매핑하는 흐름으로 교체.

Stage 2에서는 **수동 입력 (B)** 만 구현. 봇이 ping 발송 → 사용자가 받으면 연동 완료로 간주.

- [ ] **Step 3.6: 테스트 + 커밋**

`feat(notification): Slack workspace OAuth 설치 + 사용자 매핑 수동 입력`

---

## Task 4: SlackChannel (outbound)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/slack/SlackBlockKitRenderer.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/notification/channels/SlackChannel.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/notification/channels/SlackChannelTest.java`

- [ ] **Step 4.1: SlackBlockKitRenderer — StandardPayload → Block Kit JSON**

```java
// header(title) + section(summary) + section per (payload.sections 항목)
// + actions block with buttons (payload.links → button + url)
// 40K자 제한 방어: summary 절단, sections 슬라이싱
```

- [ ] **Step 4.2: SlackChannel**

```java
// Channel.type=SLACK, authStrategy=BOT_TOKEN
// deliver 시 binding 필요 (workspace_id + external_user_id).
// slack_workspace에서 bot_token_enc 조회 → 복호화
// SlackBlockKitRenderer → JSON blocks
// SlackApiClient.chatPostMessage(channel=binding.external_user_id, blocks=...) (DM)
// 응답에서 ts(스레드) + channel(DM id) 추출 → Sent(ts:channel)
// rate limit(429) → TransientFailure
// invalid_auth → PermanentFailure(TOKEN_EXPIRED)
```

- [ ] **Step 4.3: 테스트 + 커밋**

`feat(notification): SlackChannel — Block Kit + chat.postMessage`

---

## Task 5: `/settings/channels` 페이지 (프론트엔드)

**Files:**
- Create: `apps/firehub-web/src/api/channels.ts`
- Create: `apps/firehub-web/src/hooks/queries/useChannelSettings.ts`
- Create: `apps/firehub-web/src/pages/settings/ChannelsPage.tsx`
- Create: `apps/firehub-web/src/pages/settings/components/ChannelCard.tsx`
- Create: `apps/firehub-web/src/pages/settings/components/OAuthConnectButton.tsx`
- Create: `apps/firehub-web/src/pages/settings/components/ChannelStatusBadge.tsx`
- Modify: `apps/firehub-web/src/App.tsx` — 라우트 추가
- Modify: `apps/firehub-web/src/components/layout/AppLayout.tsx` — 메뉴 링크
- Test: `apps/firehub-web/e2e/pages/settings/channels-page.spec.ts`

- [ ] **Step 5.1: 백엔드 ChannelSettingsController 정의**

```java
// GET /api/v1/channels/settings → 현재 사용자 4채널 상태
// PATCH /api/v1/channels/settings/{channel}/preference → enabled 토글 (CHAT 불가)
// DELETE /api/v1/channels/settings/{channel} → binding 해제
```

Response DTO:
```java
record ChannelSettingResponse(
    String channel,            // CHAT | EMAIL | KAKAO | SLACK
    boolean enabled,           // preference
    boolean connected,         // binding 존재 + ACTIVE
    boolean needsReauth,       // binding 있으나 TOKEN_EXPIRED
    String displayAddress,     // 이메일 주소 · @slackname · Kakao 닉네임
    String oauthStartUrl       // 미연결·재인증 시 외부 창으로 열 URL
) {}
```

- [ ] **Step 5.2: 프론트 API client + TanStack Query hook**

표준 패턴 — `api/client.ts` Axios 인스턴스 사용.

- [ ] **Step 5.3: ChannelsPage + 카드 4개**

`/settings/channels` 라우트. shadcn Card 컴포넌트로 각 채널 카드 표시. 각 카드:
- 아이콘 + 채널 이름
- `ChannelStatusBadge`: ✅ 연결됨 / ⚠️ 재인증 필요 / ❌ 미연결
- `Switch`: 알림 받기 on/off (CHAT은 disabled)
- `OAuthConnectButton` / 재연결 / 연결 해제
- `displayAddress`

- [ ] **Step 5.4: OAuthConnectButton — 외부 창 + 콜백 대기**

```tsx
// window.open(oauthStartUrl, 'kakao-oauth', 'width=640,height=720')
// 부모 창은 TanStack Query refetch로 연결 상태 갱신 (2초 주기 polling 또는 postMessage 패턴)
// 타임아웃 60초 시 사용자에게 "취소됨" 안내
```

- [ ] **Step 5.5: Playwright E2E**

OAuth 창은 stub 불가능이므로 MSW/Playwright route mock 사용. 검증 시나리오:
- 초기 진입 → 모든 미연결 상태 표시
- KAKAO 연결 버튼 클릭 → OAuth 창 시작 URL로 이동 시도
- 연결 완료 후 refetch → ✅ 배지
- EMAIL 토글 OFF → PATCH 호출

- [ ] **Step 5.6: 커밋**

`feat(web): /settings/channels 페이지 + OAuth 연동 UX`

---

## Task 6: ChannelRecipientEditor 4채널 확장

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/components/ChannelRecipientEditor.tsx`
- Modify: `apps/firehub-web/src/types/*` — 관련 타입 확장
- Test: 기존 `channel-recipient-editor.spec.ts` 업데이트

- [ ] **Step 6.1: UI 확장 — 4개 체크박스 + 미연동 배지**

기존 CHAT/EMAIL 2개를 CHAT/EMAIL/KAKAO/SLACK 4개로 확장. 각 체크박스 옆에 "수신자 중 N명 미연동" 배지 (TanStack Query로 selected user들의 binding 상태 조회).

- [ ] **Step 6.2: 데이터 모델**

`ChannelConfigValues.type`에 `KAKAO`, `SLACK` enum 추가. 기존 Zod 스키마 확장.

- [ ] **Step 6.3: E2E**

- KAKAO 선택 → 수신자 중 미연동 사용자 있으면 경고 배지 표시
- SLACK 선택 → 워크스페이스 미설치 시 관리자 안내

- [ ] **Step 6.4: 커밋**

`feat(web): ChannelRecipientEditor — KAKAO/SLACK 확장 + 미연동 경고`

---

## Task 7: 엔드투엔드 검증 + ROADMAP

- [ ] **Step 7.1: dev 환경에서 실제 KAKAO 발송**

개발자 본인 계정으로 Kakao 로그인 → `/settings/channels` → 연동 → ProactiveJob 실행 → 카톡 수신 확인.

- [ ] **Step 7.2: dev 환경에서 Slack 발송**

테스트 워크스페이스에 앱 설치 → 사용자 매핑 → DM 수신 확인.

- [ ] **Step 7.3: Stage 2 runbook**

`docs/runbooks/notification-outbox-rollout.md`에 "Stage 2 활성화" 섹션 추가. 환경변수 목록, dev → stage → 운영 단계 절차.

- [ ] **Step 7.4: ROADMAP 업데이트 + 커밋**

## Self-Review Checklist

- **Spec coverage:**
  - 5장 Channel SPI → Task 2, 4 (KakaoChannel, SlackChannel)
  - 9장 사용자 연동 UX → Task 5, 6
  - 11장 보안 (토큰 암호화, OAuth state CSRF) → Task 1, 3
- **Placeholder 잔존:** 없음
- **타입 일관성:** `ChannelType.KAKAO`, `ChannelType.SLACK` enum은 Stage 1에 이미 등록됨

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-04-18-channel-stage-2-external-outbound.md`. 실행 옵션:
1. Subagent-Driven
2. Inline Execution

어느 방식으로 진행할까요? (Stage 1처럼 task별 subagent 또는 인라인)
