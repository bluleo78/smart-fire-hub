package com.smartfirehub.notification.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.channels.kakao.KakaoTextFormatter;
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

/**
 * KakaoChannel 단위 테스트.
 *
 * <p>KakaoApiClient 대신 ChannelHttpClient를 사용하는 새 구현 검증.
 * 주요 경로: 정상 발송, binding 누락, 토큰 만료 status, 401(TOKEN_EXPIRED), 5xx(TransientFailure).
 */
@ExtendWith(MockitoExtension.class)
class KakaoChannelTest {

    @Mock private ChannelHttpClient channelHttpClient;
    @Mock private KakaoTextFormatter textFormatter;
    @Mock private UserChannelBindingRepository bindingRepo;
    @Mock private EncryptionService encryptionService;

    private KakaoChannel channel;

    private static final long USER_ID = 42L;
    private static final long OUTBOX_ID = 100L;
    private static final String ACCESS_TOKEN_ENC = "enc-access";
    private static final String ACCESS_TOKEN = "raw-access-token";

    @BeforeEach
    void setUp() {
        channel = new KakaoChannel(channelHttpClient, textFormatter, bindingRepo, encryptionService);
    }

    // ----------------------------------------------------------------
    // 헬퍼
    // ----------------------------------------------------------------

    private DeliveryContext ctx() {
        Payload p = new Payload(Payload.PayloadType.STANDARD, "제목", "요약",
                List.of(), List.of(), List.of(), Map.of(), Map.of());
        return new DeliveryContext(OUTBOX_ID, UUID.randomUUID(), USER_ID, null, Optional.empty(), p);
    }

    private UserChannelBinding activeBinding() {
        return new UserChannelBinding(
                1L, USER_ID, ChannelType.KAKAO, null, "kakao-user-id",
                "kakao@example.com", ACCESS_TOKEN_ENC, null,
                Instant.now().plusSeconds(3600), "ACTIVE",
                Instant.now(), Instant.now(), Instant.now());
    }

    // ----------------------------------------------------------------
    // 정상 발송
    // ----------------------------------------------------------------

    /**
     * 정상 경로: binding 존재, 토큰 유효 → channelHttpClient.send 호출 → Sent 반환.
     */
    @Test
    void deliver_success_returnsSent() {
        when(bindingRepo.findActive(USER_ID, ChannelType.KAKAO)).thenReturn(Optional.of(activeBinding()));
        when(encryptionService.decrypt(ACCESS_TOKEN_ENC)).thenReturn(ACCESS_TOKEN);
        when(textFormatter.render(any())).thenReturn("렌더된 텍스트");

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.Sent.class,
                sent -> assertThat(sent.externalMessageId()).isEqualTo("kakao-" + OUTBOX_ID));
        verify(channelHttpClient).send(
                eq("KAKAO"),
                any(Map.class),
                any(Map.class));
    }

    // ----------------------------------------------------------------
    // binding 없음
    // ----------------------------------------------------------------

    /**
     * binding이 없을 때 BINDING_REQUIRED PermanentFailure 반환 검증.
     */
    @Test
    void deliver_noBinding_returnsPermanentFailureBindingRequired() {
        when(bindingRepo.findActive(USER_ID, ChannelType.KAKAO)).thenReturn(Optional.empty());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.BINDING_REQUIRED));
        verify(channelHttpClient, never()).send(anyString(), any(), any());
    }

    // ----------------------------------------------------------------
    // binding status != ACTIVE
    // ----------------------------------------------------------------

    /**
     * binding status가 ACTIVE가 아닐 때 TOKEN_EXPIRED PermanentFailure 반환 검증.
     */
    @Test
    void deliver_bindingStatusNotActive_returnsPermanentFailureTokenExpired() {
        UserChannelBinding expiredBinding = new UserChannelBinding(
                1L, USER_ID, ChannelType.KAKAO, null, "kakao-user-id",
                "kakao@example.com", ACCESS_TOKEN_ENC, null,
                Instant.now().minusSeconds(100), "TOKEN_EXPIRED",
                Instant.now(), Instant.now(), Instant.now());
        when(bindingRepo.findActive(USER_ID, ChannelType.KAKAO)).thenReturn(Optional.of(expiredBinding));

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.TOKEN_EXPIRED));
        verify(channelHttpClient, never()).send(anyString(), any(), any());
    }

    // ----------------------------------------------------------------
    // ChannelHttpException 401 → TOKEN_EXPIRED + binding 만료 마킹
    // ----------------------------------------------------------------

    /**
     * channelHttpClient.send 401 → binding TOKEN_EXPIRED 마킹 + PermanentFailure(TOKEN_EXPIRED) 반환.
     */
    @Test
    void deliver_channelHttp401_returnsTokenExpiredAndMarksBinding() {
        when(bindingRepo.findActive(USER_ID, ChannelType.KAKAO)).thenReturn(Optional.of(activeBinding()));
        when(encryptionService.decrypt(ACCESS_TOKEN_ENC)).thenReturn(ACCESS_TOKEN);
        when(textFormatter.render(any())).thenReturn("텍스트");
        doThrow(new ChannelHttpException("auth_error", 401))
                .when(channelHttpClient).send(anyString(), any(), any());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.TOKEN_EXPIRED));
        // binding을 TOKEN_EXPIRED 상태로 upsert 해야 한다
        verify(bindingRepo).upsert(any(UserChannelBinding.class));
    }

    // ----------------------------------------------------------------
    // ChannelHttpException 5xx → TransientFailure
    // ----------------------------------------------------------------

    /**
     * channelHttpClient.send 5xx → TransientFailure 반환 검증.
     */
    @Test
    void deliver_channelHttp5xx_returnsTransientFailure() {
        when(bindingRepo.findActive(USER_ID, ChannelType.KAKAO)).thenReturn(Optional.of(activeBinding()));
        when(encryptionService.decrypt(ACCESS_TOKEN_ENC)).thenReturn(ACCESS_TOKEN);
        when(textFormatter.render(any())).thenReturn("텍스트");
        doThrow(new ChannelHttpException("upstream_error", 500))
                .when(channelHttpClient).send(anyString(), any(), any());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOf(DeliveryResult.TransientFailure.class);
    }
}
