package com.smartfirehub.notification.channels;

import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.Channel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.user.repository.UserRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 이메일 채널 구현.
 *
 * <p>firehub-channel 서비스에 이메일 발송을 위임한다 (ChannelHttpClient 경유).
 * SMTP 설정과 수신자 이메일을 recipient 맵으로 전달하여 firehub-channel이 실제 발송을 처리한다.
 *
 * <p>수신자는 outbox 행 단위로 이미 fan-out되어 있으므로, 본 deliver는 (recipientUserId → email)
 * 또는 recipientAddress 중 하나만 처리하면 된다.
 */
@Component
public class EmailChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final SettingsService settingsService;
    private final UserRepository userRepository;
    private final ChannelHttpClient channelHttpClient;

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
        if (host.isBlank()) {
            return new DeliveryResult.PermanentFailure(
                    PermanentFailureReason.UNRECOVERABLE, "SMTP 호스트 미설정");
        }

        String toAddress = resolveRecipient(ctx);
        if (toAddress == null || toAddress.isBlank()) {
            return new DeliveryResult.PermanentFailure(
                    PermanentFailureReason.RECIPIENT_INVALID,
                    "수신 이메일 주소를 확보할 수 없음 (user id=" + ctx.recipientUserId() + ")");
        }

        Payload payload = ctx.payload();
        String subject = payload.title() == null ? "Smart Fire Hub 알림" : payload.title();
        String htmlBody = buildHtmlBody(payload);

        // SMTP 설정 맵 구성 — firehub-channel이 사용하는 필드명으로 변환
        Map<String, Object> smtpConfig = Map.of(
                "host", host,
                "port", Integer.parseInt(smtp.getOrDefault("smtp.port", "587")),
                "secure", Boolean.parseBoolean(smtp.getOrDefault("smtp.starttls", "true")),
                "user", smtp.getOrDefault("smtp.username", ""),
                "pass", smtp.getOrDefault("smtp.password", ""));

        Map<String, Object> recipient = Map.of(
                "emailAddress", toAddress,
                "smtpConfig", smtpConfig);

        Map<String, Object> message = Map.of(
                "text", subject,
                "html", htmlBody);

        try {
            channelHttpClient.send("EMAIL", recipient, message);
            log.info("EmailChannel sent to {} (outboxId={})", toAddress, ctx.outboxId());
            return new DeliveryResult.Sent("email-" + ctx.outboxId());
        } catch (ChannelHttpException e) {
            log.warn("EmailChannel ChannelHttpException {} (outboxId={})", e.getStatusCode(), ctx.outboxId(), e);
            return new DeliveryResult.TransientFailure("CHANNEL_HTTP_" + e.getStatusCode(), e);
        } catch (Exception e) {
            log.warn("EmailChannel 네트워크 오류 (outboxId={})", ctx.outboxId(), e);
            return new DeliveryResult.TransientFailure(e.getClass().getSimpleName(), e);
        }
    }

    /** recipientAddress가 있으면 우선, 없으면 사용자 테이블에서 email 조회. */
    private String resolveRecipient(DeliveryContext ctx) {
        if (ctx.recipientAddress() != null && !ctx.recipientAddress().isBlank()) {
            return ctx.recipientAddress();
        }
        if (ctx.recipientUserId() != null) {
            return userRepository.findById(ctx.recipientUserId())
                    .map(u -> u.email())
                    .orElse(null);
        }
        return null;
    }

    /**
     * StandardPayload를 단순 HTML 본문으로 렌더.
     */
    private String buildHtmlBody(Payload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><body style=\"font-family: -apple-system, sans-serif; max-width: 640px;\">");
        sb.append("<h2>").append(escape(payload.title() == null ? "" : payload.title())).append("</h2>");
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
                sb.append("<li><a href=\"").append(escape(l.url())).append("\">")
                        .append(escape(l.label() == null ? l.url() : l.label())).append("</a></li>");
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
