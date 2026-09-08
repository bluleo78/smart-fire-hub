package com.smartfirehub.notification.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.Channel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import com.smartfirehub.notification.repository.UserChannelPreferenceRepository;
import com.smartfirehub.notification.service.ChannelRegistry;
import com.smartfirehub.notification.settings.dto.ChannelTestResult;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ChannelSettingsService#testChannel() 단위 검증 (이슈 #538).
 *
 * <p>사용자가 명시적으로 비활성화한 채널은 프론트 disabled 우회(직접 API 호출) 시에도 실제 테스트 발송이 되지 않아야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ChannelSettingsServiceTest {

  @Mock private UserChannelBindingRepository bindingRepo;
  @Mock private UserChannelPreferenceRepository preferenceRepo;
  @Mock private UserRepository userRepository;
  @Mock private ChannelRegistry channelRegistry;
  @Mock private SlackWorkspaceRepository slackWorkspaceRepo;
  @Mock private Channel emailChannel;

  private ChannelSettingsService service;

  private static final long USER_ID = 42L;

  @BeforeEach
  void setUp() {
    service =
        new ChannelSettingsService(
            bindingRepo, preferenceRepo, userRepository, channelRegistry, slackWorkspaceRepo);
  }

  /** 채널이 비활성화(enabled=false)면 binding/이메일 조회 이전에 즉시 실패 응답을 반환해야 한다. */
  @Test
  void testChannel_disabledChannel_returnsFailureWithoutDelivering() {
    when(preferenceRepo.isEnabled(USER_ID, ChannelType.SLACK)).thenReturn(false);

    ChannelTestResult result = service.testChannel(USER_ID, ChannelType.SLACK);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("비활성화");
    // 비활성 상태면 binding 조회조차 하지 않고 즉시 반환해야 한다 (실제 발송 경로 진입 차단)
    verify(bindingRepo, never()).findActive(eq(USER_ID), any());
  }

  /** EMAIL 채널이 비활성화된 경우에도 동일하게 차단된다. */
  @Test
  void testChannel_disabledEmailChannel_returnsFailureWithoutSendingMail() {
    when(preferenceRepo.isEnabled(USER_ID, ChannelType.EMAIL)).thenReturn(false);

    ChannelTestResult result = service.testChannel(USER_ID, ChannelType.EMAIL);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("비활성화");
    verify(userRepository, never()).findById(any());
    verify(channelRegistry, never()).get(any());
  }

  /** 채널이 활성화된 정상 케이스는 기존과 동일하게 발송이 진행되어야 한다 (회귀 방지). */
  @Test
  void testChannel_enabledEmailChannel_proceedsToDelivery() {
    when(preferenceRepo.isEnabled(USER_ID, ChannelType.EMAIL)).thenReturn(true);
    when(userRepository.findById(USER_ID))
        .thenReturn(
            Optional.of(
                new UserResponse(
                    USER_ID, "user1", "user@example.com", "User", true, null)));
    lenient().when(channelRegistry.get(ChannelType.EMAIL)).thenReturn(emailChannel);
    when(emailChannel.deliver(any())).thenReturn(new DeliveryResult.Sent("msg-1"));

    ChannelTestResult result = service.testChannel(USER_ID, ChannelType.EMAIL);

    assertThat(result.success()).isTrue();
  }
}
