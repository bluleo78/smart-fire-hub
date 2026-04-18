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
import jakarta.mail.MessagingException;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 이메일 채널 구현.
 *
 * <p>StandardPayload(title + summary)를 간단한 HTML 본문으로 렌더해 SMTP로 발송.
 * 풍부한 Thymeleaf 템플릿·차트 이미지·PDF 첨부는 Task 12에서 ProactiveJobNotificationMapper가
 * payload_ref를 통해 원본 execution을 join해 렌더하도록 확장한다.
 *
 * <p>수신자는 outbox 행 단위로 이미 fan-out되어 있으므로, 본 deliver는 (recipientUserId → email)
 * 또는 recipientAddress 중 하나만 처리하면 된다.
 */
@Component
public class EmailChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);
    private static final String DEFAULT_FROM = "noreply@smartfirehub.io";

    private final SettingsService settingsService;
    private final UserRepository userRepository;

    public EmailChannel(SettingsService settingsService, UserRepository userRepository) {
        this.settingsService = settingsService;
        this.userRepository = userRepository;
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

        try {
            JavaMailSenderImpl sender = buildSender(smtp);
            var message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(smtp.getOrDefault("smtp.from", DEFAULT_FROM));
            helper.setTo(toAddress);

            Payload payload = ctx.payload();
            helper.setSubject(payload.title() == null ? "Smart Fire Hub 알림" : payload.title());
            helper.setText(buildHtmlBody(payload), true);

            sender.send(message);
            log.info("EmailChannel sent to {} (outboxId={})", toAddress, ctx.outboxId());
            return new DeliveryResult.Sent("email-" + ctx.outboxId());
        } catch (MessagingException e) {
            log.warn("EmailChannel MessagingException: outboxId={}", ctx.outboxId(), e);
            return new DeliveryResult.TransientFailure("MessagingException", e);
        } catch (RuntimeException e) {
            log.warn("EmailChannel RuntimeException: outboxId={}", ctx.outboxId(), e);
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

    private JavaMailSenderImpl buildSender(Map<String, String> smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.get("smtp.host"));
        sender.setPort(Integer.parseInt(smtp.getOrDefault("smtp.port", "587")));
        sender.setUsername(smtp.getOrDefault("smtp.username", ""));
        sender.setPassword(smtp.getOrDefault("smtp.password", ""));
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable",
                smtp.getOrDefault("smtp.starttls", "true"));
        return sender;
    }

    /**
     * StandardPayload를 단순 HTML 본문으로 렌더.
     * Thymeleaf 풍부 템플릿은 Task 12에서 ProactiveJob-specific mapper가 제공.
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
