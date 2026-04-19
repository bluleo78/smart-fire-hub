package com.smartfirehub.notification.channels;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.BoundChannel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.channels.slack.SlackBlockKitRenderer;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Slack 채널 구현 — BoundChannel SPI.
 *
 * <p>firehub-channel 서비스에 메시지 발송을 위임한다 (ChannelHttpClient 경유).
 * 발송 흐름:
 * 1. UserChannelBinding 조회 (workspaceId + externalUserId 필수)
 * 2. SlackWorkspace에서 봇 토큰 복호화
 * 3. Block Kit JSON + fallback 텍스트 렌더링
 * 4. channelHttpClient.send("SLACK", recipient, message) 위임
 *
 * <p>봇 토큰은 워크스페이스 레벨 → 개별 사용자 refresh 불가 (refreshIfNeeded는 StillValid 반환).
 */
@Component
public class SlackChannel implements BoundChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);

    private final ChannelHttpClient channelHttpClient;
    private final SlackBlockKitRenderer renderer;
    private final UserChannelBindingRepository bindingRepo;
    private final SlackWorkspaceRepository workspaceRepo;
    private final EncryptionService encryptionService;

    public SlackChannel(
            ChannelHttpClient channelHttpClient,
            SlackBlockKitRenderer renderer,
            UserChannelBindingRepository bindingRepo,
            SlackWorkspaceRepository workspaceRepo,
            EncryptionService encryptionService) {
        this.channelHttpClient = channelHttpClient;
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
     * 5. Block Kit 렌더링
     * 6. channelHttpClient.send("SLACK", recipient, message) 위임
     * 7. ChannelHttpException(401) → TOKEN_EXPIRED, 기타 예외 → TransientFailure
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

        // 5. Block Kit 렌더링
        String blocksJson = renderer.renderBlocksJson(ctx.payload());
        String fallbackText = renderer.renderFallbackText(ctx.payload());

        // 6. firehub-channel 서비스에 발송 위임
        // externalUserId를 slackChannelId로 사용 (DM 채널 오픈은 firehub-channel에서 처리)
        Map<String, Object> recipient = Map.of(
                "slackBotToken", botToken,
                "slackChannelId", binding.externalUserId());
        Map<String, Object> message = Map.of(
                "blocks", blocksJson,
                "text", fallbackText);

        try {
            channelHttpClient.send("SLACK", recipient, message, null);
            log.info("SlackChannel: 발송 성공 (userId={}, outboxId={})", userId, ctx.outboxId());
            return new DeliveryResult.Sent("slack-" + ctx.outboxId());
        } catch (ChannelHttpException e) {
            if (e.isAuthError()) {
                log.warn("SlackChannel: 인증 오류 401 (userId={}, outboxId={})", userId, ctx.outboxId());
                return new DeliveryResult.PermanentFailure(PermanentFailureReason.TOKEN_EXPIRED, "auth_error");
            }
            log.warn("SlackChannel: 발송 오류 {} (userId={}, outboxId={})", e.getStatusCode(), userId, ctx.outboxId());
            return new DeliveryResult.TransientFailure("CHANNEL_HTTP_" + e.getStatusCode(), e);
        } catch (Exception e) {
            log.warn("SlackChannel: 네트워크 오류 (userId={}, outboxId={})", userId, ctx.outboxId(), e);
            return new DeliveryResult.TransientFailure(e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 인바운드 대응용 — thread_ts로 같은 스레드에 텍스트 응답 전송.
     *
     * <p>firehub-channel 서비스에 SLACK send 요청을 위임한다.
     * threadTs를 포함하여 스레드 내 회신으로 처리된다.
     *
     * @param workspaceId Slack 워크스페이스 PK (slack_workspace.id)
     * @param channel     대상 채널 ID (사용자가 보낸 채널)
     * @param threadTs    원본 메시지 ts (스레드 루트 타임스탬프)
     * @param text        전송할 텍스트 (AI 응답)
     */
    public void replyTo(long workspaceId, String channel, String threadTs, String text) {
        var workspace = workspaceRepo.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("workspace not found: " + workspaceId));
        String botToken = encryptionService.decrypt(workspace.botTokenEnc());

        Map<String, Object> recipient = Map.of(
                "slackBotToken", botToken,
                "slackChannelId", channel);
        Map<String, Object> message = Map.of("text", text);

        channelHttpClient.send("SLACK", recipient, message, threadTs);
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
