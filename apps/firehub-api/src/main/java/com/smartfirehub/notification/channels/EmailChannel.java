package com.smartfirehub.notification.channels;

import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.Channel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.TransientFailureReason;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.settings.service.SmtpFailureLog;
import com.smartfirehub.settings.service.SmtpFailureMessages;
import com.smartfirehub.settings.service.SmtpTlsPolicy;
import com.smartfirehub.user.repository.UserRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 이메일 채널 구현.
 *
 * <p>firehub-channel 서비스에 이메일 발송을 위임한다 (ChannelHttpClient 경유). SMTP 설정과 수신자 이메일을 recipient 맵으로 전달하여
 * firehub-channel이 실제 발송을 처리한다.
 *
 * <p>수신자는 outbox 행 단위로 이미 fan-out되어 있으므로, 본 deliver는 (recipientUserId → email) 또는 recipientAddress
 * 중 하나만 처리하면 된다.
 */
@Component
public class EmailChannel implements Channel {

  private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

  /**
   * 워크스페이스 SMTP 미설정 사유(#712). 예전 문구 "SMTP 호스트 미설정"은 플랫폼 기본값이 있던 시절 "운영자가 채울 값"을 가리켰다 — 이제는 워크스페이스
   * 관리자가 직접 등록해야 하므로 위치를 안내한다. 위치는 화면에 실제로 보이는 메뉴 이름(사이드바 「관리 › 설정」의 「이메일」 탭)으로 쓴다 — 예전 「워크스페이스 설정
   * › 이메일」은 화면에 없는 이름이었다(WD-304).
   */
  static final String SMTP_NOT_CONFIGURED = "SMTP 서버가 설정되지 않았습니다 — 관리 › 설정 › 이메일 탭에서 등록하세요";

  /**
   * 저장된 SMTP 포트가 숫자가 아닐 때의 사유(WD-627). 저장 경로는 막지만 그 검증 이전의 옛 행이 있을 수 있다 — 재시도로 고쳐지지 않으므로 영구 실패이고,
   * 고칠 위치를 함께 안내한다.
   */
  static final String SMTP_PORT_INVALID = "SMTP 포트 값이 올바르지 않습니다 — 관리 › 설정 › 이메일 탭에서 포트를 확인하세요";

  private final SettingsService settingsService;
  private final UserRepository userRepository;
  private final ChannelHttpClient channelHttpClient;

  /** 화이트라벨링용 브랜드명. 이메일 제목 기본값(제목 미지정 시)에 사용한다. 배포처별 APP_BRANDING_NAME으로 주입. */
  @Value("${app.branding.name:Smart Fire Hub}")
  private String brandName;

  public EmailChannel(
      SettingsService settingsService,
      UserRepository userRepository,
      ChannelHttpClient channelHttpClient) {
    this.settingsService = settingsService;
    this.userRepository = userRepository;
    this.channelHttpClient = channelHttpClient;
  }

  @Override
  public ChannelType type() {
    return ChannelType.EMAIL;
  }

  @Override
  public AuthStrategy authStrategy() {
    return AuthStrategy.EMAIL_ADDRESS;
  }

  @Override
  public DeliveryResult deliver(DeliveryContext ctx) {
    Map<String, String> smtp = settingsService.getSmtpConfig();
    String host = smtp.getOrDefault("smtp.host", "");
    // SMTP 는 워크스페이스 전용이다(#712) — 미설정이면 플랫폼 공용 서버로 폴백하지 않고, 운영자가
    // 무엇을 해야 하는지 알 수 있게 등록 위치를 사유에 담는다(알림 전달 기록에 그대로 남는다).
    if (host.isBlank()) {
      return new DeliveryResult.PermanentFailure(
          PermanentFailureReason.UNRECOVERABLE, SMTP_NOT_CONFIGURED);
    }

    String toAddress = resolveRecipient(ctx);
    if (toAddress == null || toAddress.isBlank()) {
      return new DeliveryResult.PermanentFailure(
          PermanentFailureReason.RECIPIENT_INVALID,
          "수신 이메일 주소를 확보할 수 없음 (user id=" + ctx.recipientUserId() + ")");
    }

    Payload payload = ctx.payload();
    String subject = payload.title() == null ? brandName + " 알림" : payload.title();
    String htmlBody = buildHtmlBody(payload);

    // 포트·TLS 해석은 SmtpTlsPolicy 가 한다(WD-627). 숫자가 아닌 옛 포트는 재시도로 고쳐지지 않으므로 SMTP_PORT_INVALID 영구 실패.
    SmtpTlsPolicy tls;
    try {
      tls = SmtpTlsPolicy.from(smtp);
    } catch (NumberFormatException e) {
      // 메일 코드는 Logger 를 직접 부르지 않는다(WD-693, SmtpFailureLog).
      SmtpFailureLog.warn(log, "EmailChannel SMTP 포트 값이 숫자가 아님 (outboxId={})", ctx.outboxId());
      return new DeliveryResult.PermanentFailure(
          PermanentFailureReason.UNRECOVERABLE, SMTP_PORT_INVALID);
    }
    SmtpTlsPolicy.NodemailerFlags flags = tls.nodemailerFlags();
    // SMTP 설정 맵 구성 — firehub-channel이 사용하는 필드명으로 변환.
    Map<String, Object> smtpConfig =
        Map.of(
            "host", host,
            "port", tls.port(),
            "secure", flags.secure(),
            "requireTLS", flags.requireTls(),
            "user", smtp.getOrDefault("smtp.username", ""),
            "pass", smtp.getOrDefault("smtp.password", ""));

    Map<String, Object> recipient =
        Map.of(
            "emailAddress", toAddress,
            "smtpConfig", smtpConfig);

    Map<String, Object> message =
        Map.of(
            "text", subject,
            "html", htmlBody);

    // 실패 예외는 서버 응답 원문(AUTH 단계에서 되울린 비밀번호·자격 증명 포함 가능)을 담는다(WD-686) — 로그와 결과 cause(발송 워커
    // debug 로그가 결과를 찍는다)에는 가린 사본만 남긴다. 사본은 원래 클래스 이름·스택 위치를 지킨다.
    SmtpFailureMessages.CredentialMasker masker =
        SmtpFailureMessages.forCredentials(
            host, smtp.get("smtp.username"), smtp.get("smtp.password"));
    try {
      channelHttpClient.send("EMAIL", recipient, message);
      SmtpFailureLog.info(log, "EmailChannel sent to {} (outboxId={})", toAddress, ctx.outboxId());
      return new DeliveryResult.Sent("email-" + ctx.outboxId());
    } catch (ChannelHttpException e) {
      // 로그는 SmtpFailureLog 로만 남긴다(WD-693) — 가린 사본만 스택으로 붙고, 그 사본을 결과 cause 로도 쓴다.
      Throwable masked =
          SmtpFailureLog.warn(
              log,
              masker,
              e,
              "EmailChannel ChannelHttpException {} (outboxId={})",
              e.getStatusCode(),
              ctx.outboxId());
      return new DeliveryResult.TransientFailure(
          TransientFailureReason.CHANNEL_HTTP_PREFIX + e.getStatusCode(), masked);
    } catch (Exception e) {
      // 원본 예외(클래스명 등)는 서버 로그에만 남기고, 사용자에게는 노출하지 않는다.
      // reason()에는 안정적인 사유 코드만 담아 ChannelSettingsService가 한국어 메시지로 매핑한다.
      Throwable masked =
          SmtpFailureLog.warn(log, masker, e, "EmailChannel 네트워크 오류 (outboxId={})", ctx.outboxId());
      return new DeliveryResult.TransientFailure(TransientFailureReason.NETWORK_ERROR, masked);
    }
  }

  /** recipientAddress가 있으면 우선, 없으면 사용자 테이블에서 email 조회. */
  private String resolveRecipient(DeliveryContext ctx) {
    if (ctx.recipientAddress() != null && !ctx.recipientAddress().isBlank()) {
      return ctx.recipientAddress();
    }
    if (ctx.recipientUserId() != null) {
      return userRepository.findById(ctx.recipientUserId()).map(u -> u.email()).orElse(null);
    }
    return null;
  }

  /** StandardPayload를 단순 HTML 본문으로 렌더. */
  private String buildHtmlBody(Payload payload) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "<!doctype html><html><body style=\"font-family: -apple-system, sans-serif; max-width: 640px;\">");
    sb.append("<h2>")
        .append(escape(payload.title() == null ? "" : payload.title()))
        .append("</h2>");
    if (payload.summary() != null && !payload.summary().isBlank()) {
      sb.append("<p>").append(escape(payload.summary())).append("</p>");
    }
    if (payload.sections() != null) {
      for (Payload.Section s : payload.sections()) {
        sb.append("<h3>").append(escape(s.heading() == null ? "" : s.heading())).append("</h3>");
        sb.append("<div>").append(escape(s.bodyMd() == null ? "" : s.bodyMd())).append("</div>");
      }
    }
    if (payload.links() != null && !payload.links().isEmpty()) {
      sb.append("<hr><ul>");
      for (Payload.Link l : payload.links()) {
        sb.append("<li><a href=\"")
            .append(escape(l.url()))
            .append("\">")
            .append(escape(l.label() == null ? l.url() : l.label()))
            .append("</a></li>");
      }
      sb.append("</ul>");
    }
    sb.append("</body></html>");
    return sb.toString();
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
