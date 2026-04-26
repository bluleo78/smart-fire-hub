package com.smartfirehub.notification.channels;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.BoundChannel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.channels.kakao.KakaoTextFormatter;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 카카오 채널 구현 — BoundChannel SPI.
 *
 * <p>firehub-channel 서비스에 메시지 발송을 위임한다 (ChannelHttpClient 경유). firehub-channel이 카카오 "나에게 보내기" API를
 * 직접 호출하며 인증 오류(401)를 반환한다.
 *
 * <p>401 수신 시 binding을 TOKEN_EXPIRED로 마킹하고 PermanentFailure를 반환한다.
 */
@Component
public class KakaoChannel implements BoundChannel {

  private static final Logger log = LoggerFactory.getLogger(KakaoChannel.class);

  private final ChannelHttpClient channelHttpClient;
  private final KakaoTextFormatter textFormatter;
  private final UserChannelBindingRepository bindingRepo;
  private final EncryptionService encryptionService;

  public KakaoChannel(
      ChannelHttpClient channelHttpClient,
      KakaoTextFormatter textFormatter,
      UserChannelBindingRepository bindingRepo,
      EncryptionService encryptionService) {
    this.channelHttpClient = channelHttpClient;
    this.textFormatter = textFormatter;
    this.bindingRepo = bindingRepo;
    this.encryptionService = encryptionService;
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
   * <p>처리 순서: 1. 활성 binding 조회 → 없으면 BINDING_REQUIRED 2. binding status != ACTIVE → TOKEN_EXPIRED
   * 3. access_token 복호화 → 텍스트 렌더 → channelHttpClient.send("KAKAO", ...) 위임 4.
   * ChannelHttpException(401) → binding TOKEN_EXPIRED 마킹 + PermanentFailure 5.
   * ChannelHttpException(5xx) → TransientFailure
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
      return new DeliveryResult.PermanentFailure(PermanentFailureReason.UNRECOVERABLE, "토큰 복호화 실패");
    }

    String text = textFormatter.render(ctx.payload());

    // 4. firehub-channel 서비스에 발송 위임
    Map<String, Object> recipient = Map.of("kakaoAccessToken", accessToken);
    Map<String, Object> message = Map.of("text", text);

    try {
      channelHttpClient.send("KAKAO", recipient, message);
      log.info("KakaoChannel: 발송 성공 (userId={}, outboxId={})", userId, ctx.outboxId());
      return new DeliveryResult.Sent("kakao-" + ctx.outboxId());
    } catch (ChannelHttpException e) {
      if (e.isAuthError()) {
        // 401 → binding TOKEN_EXPIRED 마킹
        log.warn("KakaoChannel: 인증 오류 401, binding 만료 마킹 (userId={})", userId);
        markBindingExpired(binding);
        return new DeliveryResult.PermanentFailure(
            PermanentFailureReason.TOKEN_EXPIRED, "auth_error");
      }
      log.warn(
          "KakaoChannel: 발송 오류 {} (userId={}, outboxId={})",
          e.getStatusCode(),
          userId,
          ctx.outboxId());
      return new DeliveryResult.TransientFailure("CHANNEL_HTTP_" + e.getStatusCode(), e);
    } catch (Exception e) {
      log.warn("KakaoChannel: 네트워크 오류 (userId={}, outboxId={})", userId, ctx.outboxId(), e);
      return new DeliveryResult.TransientFailure(e.getClass().getSimpleName(), e);
    }
  }

  /**
   * binding status를 TOKEN_EXPIRED로 갱신.
   *
   * <p>firehub-channel이 401을 반환한 경우 — 사용자가 재연동해야 한다.
   */
  private void markBindingExpired(UserChannelBinding binding) {
    UserChannelBinding expired =
        new UserChannelBinding(
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
            Instant.now());
    bindingRepo.upsert(expired);
  }

  /**
   * firehub-channel이 토큰 갱신을 담당하므로 여기서는 StillValid를 반환한다.
   *
   * <p>OAuth refresh 로직은 firehub-channel 서비스 내부에서 처리된다.
   *
   * @param binding 사용자 채널 binding
   * @return 항상 StillValid
   */
  @Override
  public RefreshResult refreshIfNeeded(UserChannelBinding binding) {
    return new RefreshResult.StillValid();
  }
}
