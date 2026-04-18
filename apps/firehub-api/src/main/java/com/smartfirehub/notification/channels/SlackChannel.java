package com.smartfirehub.notification.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.BoundChannel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.channels.slack.SlackBlockKitRenderer;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Slack 채널 구현 — BoundChannel SPI.
 *
 * <p>Slack Bot Token을 사용해 사용자에게 DM을 발송한다.
 * 발송 흐름:
 * 1. UserChannelBinding 조회 (workspaceId + externalUserId 필수)
 * 2. SlackWorkspace에서 봇 토큰 복호화
 * 3. conversations.open으로 DM 채널 ID 획득
 * 4. Block Kit JSON + fallback 텍스트 렌더링
 * 5. chat.postMessage 발송
 *
 * <p>봇 토큰은 워크스페이스 레벨 → 개별 사용자 refresh 불가 (refreshIfNeeded는 StillValid 반환).
 */
@Component
public class SlackChannel implements BoundChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);

    private final SlackApiClient slackApiClient;
    private final SlackBlockKitRenderer renderer;
    private final UserChannelBindingRepository bindingRepo;
    private final SlackWorkspaceRepository workspaceRepo;
    private final EncryptionService encryptionService;

    public SlackChannel(
            SlackApiClient slackApiClient,
            SlackBlockKitRenderer renderer,
            UserChannelBindingRepository bindingRepo,
            SlackWorkspaceRepository workspaceRepo,
            EncryptionService encryptionService) {
        this.slackApiClient = slackApiClient;
        this.renderer = renderer;
        this.bindingRepo = bindingRepo;
        this.workspaceRepo = workspaceRepo;
        this.encryptionService = encryptionService;
    }

    @Override
    public ChannelType type() {
        return ChannelType.SLACK;
    }

    @Override
    public AuthStrategy authStrategy() {
        return AuthStrategy.BOT_TOKEN;
    }

    @Override
    public boolean requiresBinding() {
        return true;
    }

    /**
     * Slack DM 발송.
     *
     * <p>처리 순서:
     * 1. 활성 binding 조회 → 없으면 BINDING_REQUIRED PermanentFailure
     * 2. binding의 workspaceId/externalUserId null 체크 → 없으면 BINDING_REQUIRED PermanentFailure
     * 3. SlackWorkspace 조회 → 없으면 UNRECOVERABLE PermanentFailure (워크스페이스 취소됨)
     * 4. 봇 토큰 복호화
     * 5. conversations.open → DM 채널 ID 획득
     * 6. Block Kit 렌더링 + chatPostMessage 발송
     * 7. ok=true → Sent, rate_limited → TransientFailure, invalid_auth/token_revoked → TOKEN_EXPIRED
     */
    @Override
    public DeliveryResult deliver(DeliveryContext ctx) {
        long userId = ctx.recipientUserId();

        // 1. 활성 binding 조회
        var bindingOpt = bindingRepo.findActive(userId, ChannelType.SLACK);
        if (bindingOpt.isEmpty()) {
            log.info("SlackChannel: binding 없음 (userId={}, outboxId={})", userId, ctx.outboxId());
            return new DeliveryResult.PermanentFailure(
                    PermanentFailureReason.BINDING_REQUIRED, "slack 연동 없음");
        }

        UserChannelBinding binding = bindingOpt.get();

        // 2. workspaceId + externalUserId 필수 체크
        if (binding.workspaceId() == null || binding.externalUserId() == null) {
            log.warn("SlackChannel: binding 필드 누락 (userId={}, workspaceId={}, externalUserId={})",
                    userId, binding.workspaceId(), binding.externalUserId());
            return new DeliveryResult.PermanentFailure(
                    PermanentFailureReason.BINDING_REQUIRED, "slack binding 정보 불완전");
        }

        // 3. SlackWorkspace 조회 (봇 토큰 원본)
        var workspaceOpt = workspaceRepo.findById(binding.workspaceId());
        if (workspaceOpt.isEmpty()) {
            log.warn("SlackChannel: workspace 없음 (userId={}, workspaceId={})", userId, binding.workspaceId());
            return new DeliveryResult.PermanentFailure(
                    PermanentFailureReason.UNRECOVERABLE, "slack workspace revoked or missing");
        }

        var workspace = workspaceOpt.get();

        // 4. 봇 토큰 복호화
        String botToken;
        try {
            botToken = encryptionService.decrypt(workspace.botTokenEnc());
        } catch (Exception e) {
            log.warn("SlackChannel: 봇 토큰 복호화 실패 (userId={}, workspaceId={})", userId, binding.workspaceId(), e);
            return new DeliveryResult.PermanentFailure(
                    PermanentFailureReason.UNRECOVERABLE, "봇 토큰 복호화 실패");
        }

        // 5. DM 채널 ID 획득 (conversations.open)
        JsonNode convResp;
        try {
            convResp = slackApiClient.openConversation(botToken, binding.externalUserId());
        } catch (Exception e) {
            log.warn("SlackChannel: conversations.open 네트워크 오류 (userId={})", userId, e);
            return new DeliveryResult.TransientFailure("CONVERSATIONS_OPEN_ERROR", e);
        }

        if (convResp == null || !convResp.path("ok").asBoolean(false)) {
            String error = convResp != null ? convResp.path("error").asText("unknown") : "null_response";
            log.warn("SlackChannel: conversations.open 실패 (userId={}, error={})", userId, error);
            if ("invalid_auth".equals(error) || "token_revoked".equals(error)) {
                return new DeliveryResult.PermanentFailure(
                        PermanentFailureReason.TOKEN_EXPIRED, error);
            }
            return new DeliveryResult.TransientFailure("CONVERSATIONS_OPEN_FAILED_" + error, null);
        }

        String dmChannelId = convResp.path("channel").path("id").asText(null);
        if (dmChannelId == null || dmChannelId.isBlank()) {
            log.warn("SlackChannel: conversations.open 응답에 channel.id 없음 (userId={})", userId);
            return new DeliveryResult.TransientFailure("CONVERSATIONS_OPEN_NO_CHANNEL_ID", null);
        }

        // 6. Block Kit 렌더링
        String blocksJson = renderer.renderBlocksJson(ctx.payload());
        String fallbackText = renderer.renderFallbackText(ctx.payload());

        // 7. chat.postMessage 발송
        JsonNode postResp;
        try {
            postResp = slackApiClient.chatPostMessage(botToken, dmChannelId, blocksJson, fallbackText);
        } catch (Exception e) {
            log.warn("SlackChannel: chatPostMessage 네트워크 오류 (userId={}, outboxId={})",
                    userId, ctx.outboxId(), e);
            return new DeliveryResult.TransientFailure("CHAT_POST_MESSAGE_ERROR", e);
        }

        return parsePostMessageResult(ctx, postResp, userId);
    }

    /**
     * chat.postMessage 응답을 DeliveryResult로 변환.
     *
     * <p>ok=true → Sent(ts:channel), rate_limited → TransientFailure,
     * invalid_auth/token_revoked → TOKEN_EXPIRED PermanentFailure, 기타 → UNRECOVERABLE.
     *
     * @param ctx      발송 컨텍스트 (outboxId 로깅용)
     * @param resp     Slack API 응답 JsonNode
     * @param userId   수신자 userId (로깅용)
     * @return 발송 결과
     */
    private DeliveryResult parsePostMessageResult(DeliveryContext ctx, JsonNode resp, long userId) {
        if (resp == null) {
            log.warn("SlackChannel: chatPostMessage 응답 null (userId={}, outboxId={})", userId, ctx.outboxId());
            return new DeliveryResult.TransientFailure("NULL_RESPONSE", null);
        }

        boolean ok = resp.path("ok").asBoolean(false);
        if (ok) {
            // ts:channel 형식으로 외부 메시지 ID 구성
            String ts = resp.path("ts").asText("");
            String channel = resp.path("channel").asText("");
            String externalId = ts.isEmpty() ? ("slack-" + ctx.outboxId()) : (ts + ":" + channel);
            log.info("SlackChannel: 발송 성공 (userId={}, outboxId={}, ts={})", userId, ctx.outboxId(), ts);
            return new DeliveryResult.Sent(externalId);
        }

        String error = resp.path("error").asText("unknown");
        log.warn("SlackChannel: chatPostMessage 실패 (userId={}, outboxId={}, error={})",
                userId, ctx.outboxId(), error);

        // rate limit → 재시도 가능
        if ("rate_limited".equals(error) || "ratelimited".equals(error)) {
            return new DeliveryResult.TransientFailure("RATE_LIMITED", null);
        }

        // 인증 오류 → 영구 실패
        if ("invalid_auth".equals(error) || "token_revoked".equals(error)) {
            return new DeliveryResult.PermanentFailure(PermanentFailureReason.TOKEN_EXPIRED, error);
        }

        // 기타 오류 → 영구 실패
        return new DeliveryResult.PermanentFailure(PermanentFailureReason.UNRECOVERABLE, error);
    }

    /**
     * 인바운드 대응용 — thread_ts로 같은 스레드에 텍스트 응답 전송.
     *
     * <p>기존 {@link #deliver}는 새 DM 채널을 열어 메시지를 보낸다.
     * 이 메서드는 이미 열려있는 채널 id를 직접 받아 스레드에 AI 응답을 post한다.
     *
     * @param workspaceId Slack 워크스페이스 PK (slack_workspace.id)
     * @param channel     대상 채널 ID (사용자가 보낸 채널)
     * @param threadTs    원본 메시지 ts (스레드 루트 타임스탬프)
     * @param text        전송할 텍스트 (AI 응답)
     */
    public void replyTo(long workspaceId, String channel, String threadTs, String text) {
        // 워크스페이스 조회 → 봇 토큰 복호화 → 스레드 회신
        var workspace = workspaceRepo.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("workspace not found: " + workspaceId));
        String botToken = encryptionService.decrypt(workspace.botTokenEnc());
        slackApiClient.chatPostMessageInThread(botToken, channel, threadTs, null, text);
    }

    /**
     * Slack 봇 토큰은 워크스페이스 레벨이므로 개별 사용자 refresh 불가.
     *
     * <p>토큰 갱신이 필요하면 워크스페이스 관리자가 OAuth 재설치 필요.
     * 여기서는 항상 StillValid를 반환하여 refresh 로직을 건너뛴다.
     *
     * @param binding 사용자 채널 binding (무시됨)
     * @return 항상 StillValid
     */
    @Override
    public RefreshResult refreshIfNeeded(UserChannelBinding binding) {
        // 봇 토큰은 사용자 레벨이 아닌 워크스페이스 레벨 — 개별 사용자 refresh 지원 안 함
        return new RefreshResult.StillValid();
    }
}
