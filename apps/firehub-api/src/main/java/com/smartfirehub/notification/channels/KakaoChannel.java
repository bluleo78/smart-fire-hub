package com.smartfirehub.notification.channels;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.BoundChannel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.channels.kakao.KakaoApiClient;
import com.smartfirehub.notification.channels.kakao.KakaoTextFormatter;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 카카오 채널 구현 — BoundChannel SPI.
 *
 * <p>카카오 "나에게 보내기" API를 통해 사용자 카카오톡으로 메시지를 전송한다.
 * 토큰 만료(HTTP 401) 시 refresh 1회 시도 후 재발송. rate limit(HTTP 429) 시 TransientFailure 반환.
 */
@Component
public class KakaoChannel implements BoundChannel {

    private static final Logger log = LoggerFactory.getLogger(KakaoChannel.class);

    /**
     * Stage 3에서 deep link 치환 예정.
     * 현재는 웹 메인 페이지 URL을 하드코딩하여 카카오 메시지 링크로 사용.
     */
    private static final String WEB_URL_PLACEHOLDER = "https://app.smartfirehub.com/chat";

    private final KakaoApiClient kakaoApiClient;
    private final KakaoTextFormatter textFormatter;
    private final UserChannelBindingRepository bindingRepo;
    private final EncryptionService encryptionService;
    private final String clientId;
    private final String clientSecret;

    public KakaoChannel(
            KakaoApiClient kakaoApiClient,
            KakaoTextFormatter textFormatter,
            UserChannelBindingRepository bindingRepo,
            EncryptionService encryptionService,
            @Value("${notification.kakao.client_id:}") String clientId,
            @Value("${notification.kakao.client_secret:}") String clientSecret) {
        this.kakaoApiClient = kakaoApiClient;
        this.textFormatter = textFormatter;
        this.bindingRepo = bindingRepo;
        this.encryptionService = encryptionService;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public ChannelType type() {
        return ChannelType.KAKAO;
    }

    @Override
    public AuthStrategy authStrategy() {
        return AuthStrategy.OAUTH;
    }

    @Override
    public boolean requiresBinding() {
        return true;
    }

    /**
     * 카카오톡 나에게 보내기로 메시지 발송.
     *
     * <p>처리 순서:
     * 1. 활성 binding 조회 → 없으면 BINDING_MISSING
     * 2. binding status != ACTIVE → TOKEN_EXPIRED
     * 3. access_token 복호화 → 텍스트 렌더 → 카카오 API 호출
     * 4. HTTP 401 → refresh 1회 시도 후 재호출
     * 5. HTTP 429 → TransientFailure (rate limit)
     * 6. 기타 네트워크 오류 → TransientFailure
     */
    @Override
    public DeliveryResult deliver(DeliveryContext ctx) {
        long userId = ctx.recipientUserId();

        // 1. 활성 binding 조회
        var bindingOpt = bindingRepo.findActive(userId, ChannelType.KAKAO);
        if (bindingOpt.isEmpty()) {
            log.info("KakaoChannel: binding 없음 (userId={}, outboxId={})", userId, ctx.outboxId());
            return new DeliveryResult.PermanentFailure(
                    PermanentFailureReason.BINDING_REQUIRED, "카카오 연동 없음");
        }

        UserChannelBinding binding = bindingOpt.get();

        // 2. binding 상태 확인 — ACTIVE가 아니면 토큰 만료로 처리
        if (!"ACTIVE".equals(binding.status())) {
            log.info("KakaoChannel: 토큰 만료 (userId={}, status={})", userId, binding.status());
            return new DeliveryResult.PermanentFailure(
                    PermanentFailureReason.TOKEN_EXPIRED, binding.status());
        }

        // 3. access_token 복호화 + 텍스트 렌더링
        String accessToken;
        try {
            accessToken = encryptionService.decrypt(binding.accessTokenEnc());
        } catch (Exception e) {
            log.warn("KakaoChannel: access_token 복호화 실패 (userId={})", userId, e);
            return new DeliveryResult.PermanentFailure(
                    PermanentFailureReason.UNRECOVERABLE, "토큰 복호화 실패");
        }

        String text = textFormatter.render(ctx.payload());

        // 4. 카카오 API 호출 (토큰 만료 시 refresh 1회 재시도)
        return doSend(ctx, binding, accessToken, text, true);
    }

    /**
     * 실제 카카오 API 호출. 토큰 만료(401) 시 allowRefresh=true면 refresh 후 1회 재시도.
     *
     * @param allowRefresh 토큰 만료 시 refresh를 허용할지 여부 (재귀 방지용)
     */
    private DeliveryResult doSend(
            DeliveryContext ctx,
            UserChannelBinding binding,
            String accessToken,
            String text,
            boolean allowRefresh) {
        try {
            kakaoApiClient.sendMemoText(accessToken, text, WEB_URL_PLACEHOLDER);
            log.info("KakaoChannel: 발송 성공 (userId={}, outboxId={})",
                    ctx.recipientUserId(), ctx.outboxId());
            return new DeliveryResult.Sent("kakao-" + ctx.outboxId());

        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();

            // 토큰 만료 — refresh 1회 시도
            if (status == 401 && allowRefresh) {
                log.info("KakaoChannel: 401 감지, refresh 시도 (userId={})", ctx.recipientUserId());
                RefreshResult refreshResult = refreshIfNeeded(binding);
                if (refreshResult instanceof RefreshResult.Refreshed refreshed) {
                    // 갱신된 access_token으로 재시도 (allowRefresh=false로 재귀 방지)
                    return doSend(ctx, binding, refreshed.newAccessToken(), text, false);
                } else {
                    return new DeliveryResult.PermanentFailure(
                            PermanentFailureReason.TOKEN_EXPIRED, "토큰 갱신 실패");
                }
            }

            // rate limit
            if (status == 429) {
                log.warn("KakaoChannel: rate limit (userId={}, outboxId={})",
                        ctx.recipientUserId(), ctx.outboxId());
                return new DeliveryResult.TransientFailure("KAKAO_RATE_LIMIT", e);
            }

            // 그 외 HTTP 오류 — 재시도 가능한 일시 실패로 처리
            log.warn("KakaoChannel: HTTP {} 오류 (userId={}, outboxId={})",
                    status, ctx.recipientUserId(), ctx.outboxId(), e);
            return new DeliveryResult.TransientFailure("HTTP_" + status, e);

        } catch (Exception e) {
            // 네트워크 오류 등 — TransientFailure로 재시도 허용
            log.warn("KakaoChannel: 네트워크 오류 (userId={}, outboxId={})",
                    ctx.recipientUserId(), ctx.outboxId(), e);
            return new DeliveryResult.TransientFailure(e.getClass().getSimpleName(), e);
        }
    }

    /**
     * refresh_token으로 access_token 갱신 후 binding upsert.
     *
     * <p>갱신 성공 시 새 access_token/refresh_token을 암호화해서 DB에 저장하고 Refreshed 반환.
     * 실패 시 binding status를 TOKEN_EXPIRED로 갱신하고 Failed 반환.
     */
    @Override
    public RefreshResult refreshIfNeeded(UserChannelBinding binding) {
        if (binding.refreshTokenEnc() == null) {
            return new RefreshResult.Failed("refresh_token 없음");
        }

        String refreshToken;
        try {
            refreshToken = encryptionService.decrypt(binding.refreshTokenEnc());
        } catch (Exception e) {
            log.warn("KakaoChannel: refresh_token 복호화 실패 (userId={})", binding.userId(), e);
            return new RefreshResult.Failed("refresh_token 복호화 실패");
        }

        try {
            var resp = kakaoApiClient.refresh(refreshToken, clientId, clientSecret);

            String newAccessToken = resp.get("access_token").asText();
            // 카카오는 refresh_token이 갱신될 수도 있으므로 응답에 포함 시 사용, 없으면 기존 유지
            String newRefreshToken = resp.has("refresh_token")
                    ? resp.get("refresh_token").asText()
                    : refreshToken;
            int expiresIn = resp.has("expires_in") ? resp.get("expires_in").asInt(3600) : 3600;
            Instant expiresAt = Instant.now().plusSeconds(expiresIn);

            // 암호화 후 binding upsert
            String newAccessTokenEnc = encryptionService.encrypt(newAccessToken);
            String newRefreshTokenEnc = encryptionService.encrypt(newRefreshToken);

            UserChannelBinding updated = new UserChannelBinding(
                    binding.id(),
                    binding.userId(),
                    binding.channelType(),
                    binding.workspaceId(),
                    binding.externalUserId(),
                    binding.displayAddress(),
                    newAccessTokenEnc,
                    newRefreshTokenEnc,
                    expiresAt,
                    "ACTIVE",
                    Instant.now(),
                    binding.createdAt(),
                    Instant.now()
            );
            bindingRepo.upsert(updated);

            log.info("KakaoChannel: 토큰 갱신 성공 (userId={})", binding.userId());
            return new RefreshResult.Refreshed(newAccessToken, newRefreshToken, expiresAt);

        } catch (Exception e) {
            log.warn("KakaoChannel: refresh 실패 (userId={})", binding.userId(), e);

            // 갱신 실패 시 binding status를 TOKEN_EXPIRED로 마킹
            UserChannelBinding expired = new UserChannelBinding(
                    binding.id(),
                    binding.userId(),
                    binding.channelType(),
                    binding.workspaceId(),
                    binding.externalUserId(),
                    binding.displayAddress(),
                    binding.accessTokenEnc(),
                    binding.refreshTokenEnc(),
                    binding.tokenExpiresAt(),
                    "TOKEN_EXPIRED",
                    binding.lastVerifiedAt(),
                    binding.createdAt(),
                    Instant.now()
            );
            bindingRepo.upsert(expired);

            return new RefreshResult.Failed(e.getMessage());
        }
    }
}
