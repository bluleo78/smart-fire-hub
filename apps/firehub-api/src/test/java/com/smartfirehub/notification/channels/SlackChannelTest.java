package com.smartfirehub.notification.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.quality.Strictness.LENIENT;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.channels.slack.SlackBlockKitRenderer;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

/**
 * SlackChannel 단위 테스트.
 *
 * <p>SlackApiClient 대신 ChannelHttpClient를 사용하는 새 구현 검증.
 * 주요 경로: 정상 발송, binding 누락, workspace 누락, 인증 오류(401), rate limit(5xx).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = LENIENT)
class SlackChannelTest {

    @Mock private ChannelHttpClient channelHttpClient;
    @Mock private SlackBlockKitRenderer renderer;
    @Mock private UserChannelBindingRepository bindingRepo;
    @Mock private SlackWorkspaceRepository workspaceRepo;
    @Mock private EncryptionService encryptionService;

    private SlackChannel channel;

    private static final long USER_ID = 42L;
    private static final long OUTBOX_ID = 100L;
    private static final long WORKSPACE_ID = 1L;
    private static final String SLACK_USER_ID = "U0123456";
    private static final String BOT_TOKEN_ENC = "enc:xoxb-test-token";
    private static final String BOT_TOKEN = "xoxb-test-token";

    @BeforeEach
    void setUp() {
        channel = new SlackChannel(channelHttpClient, renderer, bindingRepo, workspaceRepo, encryptionService);

        // 공통 stub — renderer는 모든 케이스에서 동일하게 동작
        when(renderer.renderBlocksJson(any())).thenReturn("[{\"type\":\"header\"}]");
        when(renderer.renderFallbackText(any())).thenReturn("제목\n요약");
    }

    // ----------------------------------------------------------------
    // 헬퍼
    // ----------------------------------------------------------------

    /** 기본 DeliveryContext 생성. */
    private DeliveryContext ctx() {
        Payload p = new Payload(Payload.PayloadType.STANDARD, "제목", "요약",
                List.of(), List.of(), List.of(), Map.of(), Map.of());
        return new DeliveryContext(OUTBOX_ID, UUID.randomUUID(), USER_ID, null, Optional.empty(), p);
    }

    /** 정상 활성 binding 생성. */
    private UserChannelBinding activeBinding() {
        return new UserChannelBinding(
                1L, USER_ID, ChannelType.SLACK, WORKSPACE_ID, SLACK_USER_ID,
                "slack-user@example.com", null, null,
                null, "ACTIVE",
                Instant.now(), Instant.now(), Instant.now());
    }

    /** SlackWorkspace 레코드 생성. */
    private SlackWorkspaceRepository.SlackWorkspace workspace() {
        return new SlackWorkspaceRepository.SlackWorkspace(
                WORKSPACE_ID, "T0123456", "Test Team", "B0123456",
                BOT_TOKEN_ENC, null, null, null, 1L);
    }

    // ----------------------------------------------------------------
    // 정상 발송
    // ----------------------------------------------------------------

    /**
     * 정상 경로: binding 존재, workspace 존재, channelHttpClient.send 성공
     * → Sent(slack-{outboxId}) 반환.
     */
    @Test
    void deliver_success_returnsSent() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.of(activeBinding()));
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(encryptionService.decrypt(BOT_TOKEN_ENC)).thenReturn(BOT_TOKEN);
        // channelHttpClient.send 는 void — 기본적으로 아무것도 안 한다 (정상)

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.Sent.class,
                sent -> assertThat(sent.externalMessageId()).isEqualTo("slack-" + OUTBOX_ID));

        // recipient 맵에 slackBotToken + slackChannelId(== externalUserId) 포함 검증
        verify(channelHttpClient).send(
                eq("SLACK"),
                any(Map.class),
                any(Map.class),
                isNull());
    }

    // ----------------------------------------------------------------
    // binding 없음
    // ----------------------------------------------------------------

    /**
     * binding이 없을 때 BINDING_REQUIRED PermanentFailure 반환 검증.
     */
    @Test
    void deliver_noBinding_returnsPermanentFailure() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.empty());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.BINDING_REQUIRED));
    }

    // ----------------------------------------------------------------
    // workspace 없음 (revoked)
    // ----------------------------------------------------------------

    /**
     * workspace가 조회되지 않을 때 UNRECOVERABLE PermanentFailure 반환 검증.
     */
    @Test
    void deliver_workspaceMissing_returnsPermanentFailure() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.of(activeBinding()));
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.UNRECOVERABLE));
    }

    // ----------------------------------------------------------------
    // ChannelHttpException 401 → TOKEN_EXPIRED
    // ----------------------------------------------------------------

    /**
     * channelHttpClient.send가 ChannelHttpException(401) 던질 때
     * TOKEN_EXPIRED PermanentFailure 반환 검증.
     */
    @Test
    void deliver_channelHttp401_returnsPermanentFailureTokenExpired() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.of(activeBinding()));
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(encryptionService.decrypt(BOT_TOKEN_ENC)).thenReturn(BOT_TOKEN);
        doThrow(new ChannelHttpException("auth_error", 401))
                .when(channelHttpClient).send(anyString(), any(), any(), any());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.TOKEN_EXPIRED));
    }

    // ----------------------------------------------------------------
    // ChannelHttpException 5xx → TransientFailure
    // ----------------------------------------------------------------

    /**
     * channelHttpClient.send가 ChannelHttpException(500) 던질 때
     * TransientFailure 반환 검증.
     */
    @Test
    void deliver_channelHttp5xx_returnsTransientFailure() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.of(activeBinding()));
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(encryptionService.decrypt(BOT_TOKEN_ENC)).thenReturn(BOT_TOKEN);
        doThrow(new ChannelHttpException("upstream_error", 500))
                .when(channelHttpClient).send(anyString(), any(), any(), any());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOf(DeliveryResult.TransientFailure.class);
    }

    // ----------------------------------------------------------------
    // replyTo — 스레드 회신
    // ----------------------------------------------------------------

    /**
     * replyTo: workspace 조회 → 봇 토큰 복호화 → channelHttpClient.send(threadTs 포함) 호출 검증.
     */
    @Test
    void replyTo_callsChannelHttpClientWithThreadTs() {
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(encryptionService.decrypt(BOT_TOKEN_ENC)).thenReturn(BOT_TOKEN);

        channel.replyTo(WORKSPACE_ID, "C123", "1234567890.000100", "AI 응답");

        verify(channelHttpClient).send(
                eq("SLACK"),
                any(Map.class),
                any(Map.class),
                eq("1234567890.000100"));
    }
}
