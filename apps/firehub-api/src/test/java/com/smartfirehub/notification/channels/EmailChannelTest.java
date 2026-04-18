package com.smartfirehub.notification.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EmailChannel 경계 경로 단위 검증.
 *
 * <p>실제 SMTP 발송은 별도 integration(GreenMail)에서. 본 테스트는 사전 검증(설정 누락/수신자
 * 미확보) 경로와 기본 라우팅만 확인.
 */
@ExtendWith(MockitoExtension.class)
class EmailChannelTest {

    @Mock private SettingsService settingsService;
    @Mock private UserRepository userRepository;

    @InjectMocks private EmailChannel channel;

    @Test
    void deliver_smtpHostMissing_returnsPermanentFailure() {
        when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", ""));

        var result = channel.deliver(ctx(1L, "to@example.com"));

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.UNRECOVERABLE));
    }

    @Test
    void deliver_missingRecipient_returnsPermanentFailure() {
        when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", "smtp.example.com"));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        var result = channel.deliver(ctx(1L, null));

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.RECIPIENT_INVALID));
    }

    @Test
    void deliver_smtpConnectFail_returnsTransient() {
        // 실제 SMTP 호스트 연결 불가 (RFC 5737 TEST-NET-2 대역으로 지정)
        when(settingsService.getSmtpConfig()).thenReturn(Map.of(
                "smtp.host", "198.51.100.1",
                "smtp.port", "25"
        ));

        var result = channel.deliver(ctx(null, "to@example.com"));

        assertThat(result).isInstanceOf(DeliveryResult.TransientFailure.class);
    }

    private DeliveryContext ctx(Long userId, String address) {
        Payload p = new Payload(Payload.PayloadType.STANDARD, "제목", "요약",
                List.of(), List.of(), List.of(), Map.of(), Map.of());
        return new DeliveryContext(1L, UUID.randomUUID(), userId, address, Optional.empty(), p);
    }
}
