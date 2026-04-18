package com.smartfirehub.notification.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.BoundChannel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.channels.kakao.KakaoApiClient;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * KakaoChannel 단위 테스트.
 *
 * <p>실제 카카오 API 호출 없이 주요 경로(정상 발송, binding 누락, 토큰 만료, 401 refresh 재시도, 429 rate limit)를 검증.
 */
@ExtendWith(MockitoExtension.class)
class KakaoChannelTest {

    @Mock private KakaoApiClient kakaoApiClient;
    @Mock private KakaoTextFormatter textFormatter;
    @Mock private UserChannelBindingRepository bindingRepo;
    @Mock private EncryptionService encryptionService;

    // @InjectMocks는 @Value 필드를 주입하지 못하므로 직접 생성
    private KakaoChannel channel;

    private static final long USER_ID = 42L;
    private static final long OUTBOX_ID = 100L;
    private static final String ACCESS_TOKEN_ENC = "enc-access";
    private static final String ACCESS_TOKEN = "raw-access-token";
    private static final String REFRESH_TOKEN_ENC = "enc-refresh";
    private static final String REFRESH_TOKEN = "raw-refresh-token";

    @BeforeEach
    void setUp() {
        // @Value 필드가 있어 직접 생성 — mock은 @Mock으로 선언된 것 그대로 사용
        channel = new KakaoChannel(
                kakaoApiClient, textFormatter, bindingRepo, encryptionService,
                "test-client-id", "test-client-secret");
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
                "kakao@example.com", ACCESS_TOKEN_ENC, REFRESH_TOKEN_ENC,
                Instant.now().plusSeconds(3600), "ACTIVE",
                Instant.now(), Instant.now(), Instant.now());
    }

    // ----------------------------------------------------------------
    // 정상 발송
    // ----------------------------------------------------------------

    /**
     * 정상 경로: binding 존재, 토큰 유효 → sendMemoText 호출 → Sent 반환.
     */
    @Test
    void deliver_success_returnsSent() throws Exception {
        when(bindingRepo.findActive(USER_ID, ChannelType.KAKAO)).thenReturn(Optional.of(activeBinding()));
        when(encryptionService.decrypt(ACCESS_TOKEN_ENC)).thenReturn(ACCESS_TOKEN);
        when(textFormatter.render(any())).thenReturn("렌더된 텍스트");

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.Sent.class,
                sent -> assertThat(sent.externalMessageId()).isEqualTo("kakao-" + OUTBOX_ID));
        verify(kakaoApiClient).sendMemoText(eq(ACCESS_TOKEN), eq("렌더된 텍스트"), anyString());
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
        verify(kakaoApiClient, never()).sendMemoText(anyString(), anyString(), anyString());
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
                "kakao@example.com", ACCESS_TOKEN_ENC, REFRESH_TOKEN_ENC,
                Instant.now().minusSeconds(100), "TOKEN_EXPIRED",
                Instant.now(), Instant.now(), Instant.now());
        when(bindingRepo.findActive(USER_ID, ChannelType.KAKAO)).thenReturn(Optional.of(expiredBinding));

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.TOKEN_EXPIRED));
        verify(kakaoApiClient, never()).sendMemoText(anyString(), anyString(), anyString());
    }

    // ----------------------------------------------------------------
    // HTTP 401 → refresh 후 재시도 (Stage 2 MVP: refresh 실패 → TOKEN_EXPIRED)
    // ----------------------------------------------------------------

    /**
     * sendMemoText 호출 시 401 → refreshIfNeeded 호출 후 refresh 실패 → TOKEN_EXPIRED 반환.
     *
     * <p>Stage 2 MVP: refresh 자체가 실패하는 경우를 검증. refresh 성공 후 재발송 성공은
     * 실제 카카오 응답 JsonNode 구성이 필요하므로 통합 테스트 수준에서 검증 예정.
     */
    @Test
    void deliver_http401_refreshFails_returnsPermanentFailureTokenExpired() throws Exception {
        when(bindingRepo.findActive(USER_ID, ChannelType.KAKAO)).thenReturn(Optional.of(activeBinding()));
        when(encryptionService.decrypt(ACCESS_TOKEN_ENC)).thenReturn(ACCESS_TOKEN);
        when(encryptionService.decrypt(REFRESH_TOKEN_ENC)).thenReturn(REFRESH_TOKEN);
        when(textFormatter.render(any())).thenReturn("텍스트");

        // sendMemoText → 401
        WebClientResponseException ex401 = WebClientResponseException.create(
                HttpStatus.UNAUTHORIZED.value(), "Unauthorized", null, null, null);
        org.mockito.Mockito.doThrow(ex401)
                .when(kakaoApiClient).sendMemoText(anyString(), anyString(), anyString());

        // refresh → 실패 (kakao API 오류)
        when(kakaoApiClient.refresh(eq(REFRESH_TOKEN), anyString(), anyString()))
                .thenThrow(new RuntimeException("refresh server error"));

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.TOKEN_EXPIRED));
    }

    // ----------------------------------------------------------------
    // HTTP 429 rate limit
    // ----------------------------------------------------------------

    /**
     * sendMemoText 호출 시 429 → TransientFailure 반환 검증.
     */
    @Test
    void deliver_http429_returnsTransientFailure() throws Exception {
        when(bindingRepo.findActive(USER_ID, ChannelType.KAKAO)).thenReturn(Optional.of(activeBinding()));
        when(encryptionService.decrypt(ACCESS_TOKEN_ENC)).thenReturn(ACCESS_TOKEN);
        when(textFormatter.render(any())).thenReturn("텍스트");

        WebClientResponseException ex429 = WebClientResponseException.create(
                HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", null, null, null);
        org.mockito.Mockito.doThrow(ex429)
                .when(kakaoApiClient).sendMemoText(anyString(), anyString(), anyString());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOf(DeliveryResult.TransientFailure.class);
    }
}
