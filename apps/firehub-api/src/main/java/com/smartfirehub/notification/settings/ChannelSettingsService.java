package com.smartfirehub.notification.settings;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import com.smartfirehub.notification.repository.UserChannelPreferenceRepository;
import com.smartfirehub.notification.service.ChannelRegistry;
import com.smartfirehub.notification.settings.dto.ChannelSettingResponse;
import com.smartfirehub.notification.settings.dto.ChannelTestResult;
import com.smartfirehub.user.repository.UserRepository;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 사용자 채널 설정 조회·변경 서비스.
 *
 * <p>채널별 preference(opt-out) 및 binding(외부 OAuth 연동 상태)을 통합하여 프론트엔드 /settings/channels 페이지에 필요한 데이터를
 * 제공한다.
 */
@Service
public class ChannelSettingsService {

  /** Kakao OAuth URL 반환 엔드포인트 — 인증 후 실제 Kakao 인증 URL을 JSON으로 반환 */
  private static final String KAKAO_OAUTH_AUTH_URL = "/api/v1/oauth/kakao/auth-url";

  /** Slack OAuth URL 반환 엔드포인트 — 인증 후 실제 Slack 인증 URL을 JSON으로 반환 */
  private static final String SLACK_OAUTH_AUTH_URL = "/api/v1/oauth/slack/auth-url";

  private final UserChannelBindingRepository bindingRepo;
  private final UserChannelPreferenceRepository preferenceRepo;
  private final UserRepository userRepository;
  private final ChannelRegistry channelRegistry;

  public ChannelSettingsService(
      UserChannelBindingRepository bindingRepo,
      UserChannelPreferenceRepository preferenceRepo,
      UserRepository userRepository,
      ChannelRegistry channelRegistry) {
    this.bindingRepo = bindingRepo;
    this.preferenceRepo = preferenceRepo;
    this.userRepository = userRepository;
    this.channelRegistry = channelRegistry;
  }

  /**
   * 현재 사용자의 모든 채널 설정 조회.
   *
   * <p>ChannelType.values() 순서대로 4개 row를 반환한다. binding이 없어도 row는 항상 포함된다.
   *
   * @param userId 조회할 사용자 ID
   * @return 채널별 설정 목록 (CHAT, EMAIL, KAKAO, SLACK 순서)
   */
  public List<ChannelSettingResponse> getSettings(long userId) {
    // 사용자 이메일 — EMAIL 채널 connected 여부와 displayAddress에 사용
    String email = userRepository.findById(userId).map(u -> u.email()).orElse(null);

    return Arrays.stream(ChannelType.values())
        .map(channelType -> buildResponse(userId, channelType, email))
        .toList();
  }

  /**
   * 채널별 ChannelSettingResponse 생성.
   *
   * <p>CHAT/EMAIL은 binding 개념이 없으므로 connected 판단 방식이 다르다.
   */
  private ChannelSettingResponse buildResponse(long userId, ChannelType channelType, String email) {
    return switch (channelType) {
      case CHAT ->
          new ChannelSettingResponse(
              "CHAT",
              true, // CHAT은 opt-out 불가 — 항상 활성
              true, // 항상 연결됨 (웹 인박스)
              false,
              "웹 인박스",
              null // OAuth 불필요
              );

      case EMAIL ->
          new ChannelSettingResponse(
              "EMAIL",
              preferenceRepo.isEnabled(userId, ChannelType.EMAIL),
              email != null && !email.isBlank(), // 이메일이 등록되어 있으면 connected
              false,
              email, // null이어도 프론트엔드가 처리
              null // OAuth 불필요
              );

      case KAKAO -> {
        Optional<UserChannelBinding> binding = bindingRepo.findActive(userId, ChannelType.KAKAO);
        // ACTIVE binding 없을 때도 REVOKED/TOKEN_EXPIRED 바인딩 존재 여부를 확인
        boolean connected = binding.isPresent();
        boolean needsReauth = !connected && hasExpiredOrRevokedBinding(userId, ChannelType.KAKAO);
        // TOKEN_EXPIRED인 active 바인딩도 needsReauth 처리
        if (connected && "TOKEN_EXPIRED".equals(binding.get().status())) {
          needsReauth = true;
        }
        String displayAddress =
            binding
                .map(UserChannelBinding::displayAddress)
                .filter(s -> s != null && !s.isBlank())
                .orElse("카카오톡");
        yield new ChannelSettingResponse(
            "KAKAO",
            preferenceRepo.isEnabled(userId, ChannelType.KAKAO),
            connected,
            needsReauth,
            displayAddress,
            KAKAO_OAUTH_AUTH_URL);
      }

      case SLACK -> {
        Optional<UserChannelBinding> binding = bindingRepo.findActive(userId, ChannelType.SLACK);
        boolean connected = binding.isPresent();
        boolean needsReauth = !connected && hasExpiredOrRevokedBinding(userId, ChannelType.SLACK);
        if (connected && "TOKEN_EXPIRED".equals(binding.get().status())) {
          needsReauth = true;
        }
        String displayAddress =
            binding
                .map(UserChannelBinding::displayAddress)
                .filter(s -> s != null && !s.isBlank())
                .orElse("Slack");
        yield new ChannelSettingResponse(
            "SLACK",
            preferenceRepo.isEnabled(userId, ChannelType.SLACK),
            connected,
            needsReauth,
            displayAddress,
            SLACK_OAUTH_AUTH_URL);
      }
    };
  }

  /**
   * ACTIVE가 아닌 이력 바인딩(REVOKED·TOKEN_EXPIRED)이 존재하는지 확인.
   *
   * <p>재인증 유도 배지(needsReauth)를 표시하기 위해 사용한다.
   */
  private boolean hasExpiredOrRevokedBinding(long userId, ChannelType channelType) {
    return bindingRepo.findByUser(userId).stream()
        .anyMatch(
            b ->
                b.channelType() == channelType
                    && ("TOKEN_EXPIRED".equals(b.status()) || "REVOKED".equals(b.status())));
  }

  /**
   * 채널 알림 수신 여부 변경.
   *
   * @param userId 대상 사용자 ID
   * @param channelType 변경할 채널
   * @param enabled 활성화 여부
   * @throws IllegalArgumentException CHAT 채널은 변경 불가
   */
  public void updatePreference(long userId, ChannelType channelType, boolean enabled) {
    // CHAT은 안전망 채널 — DB CHECK 제약으로도 막히지만 명시적 400 반환
    if (channelType == ChannelType.CHAT) {
      throw new IllegalArgumentException("CHAT 채널은 항상 활성 상태이며 변경할 수 없습니다.");
    }
    preferenceRepo.setEnabled(userId, channelType, enabled);
  }

  /**
   * 채널 외부 binding 해제.
   *
   * <p>CHAT·EMAIL은 binding 개념이 없으므로 해제 불가. KAKAO/SLACK은 ACTIVE binding을 찾아 REVOKED로 변경한다.
   *
   * @param userId 대상 사용자 ID
   * @param channelType 해제할 채널
   * @throws IllegalArgumentException CHAT 또는 EMAIL은 disconnect 불가
   */
  public void disconnectBinding(long userId, ChannelType channelType) {
    if (channelType == ChannelType.CHAT || channelType == ChannelType.EMAIL) {
      throw new IllegalArgumentException(channelType.name() + " 채널은 연결 해제를 지원하지 않습니다.");
    }

    if (channelType == ChannelType.KAKAO) {
      // KAKAO는 workspace 개념 없음 — workspaceId=null
      bindingRepo.revoke(userId, ChannelType.KAKAO, null);
    } else if (channelType == ChannelType.SLACK) {
      // SLACK은 active binding에서 workspaceId를 가져와 해제
      Optional<UserChannelBinding> active = bindingRepo.findActive(userId, ChannelType.SLACK);
      if (active.isPresent()) {
        bindingRepo.revoke(userId, ChannelType.SLACK, active.get().workspaceId());
      }
      // binding이 없으면 no-op (이미 해제된 상태)
    }
  }

  /**
   * 채널 테스트 발송 — 사용자가 /settings/channels 페이지에서 "테스트 발송" 버튼을 눌렀을 때 호출.
   *
   * <p>outbox 큐를 거치지 않고 ChannelRegistry에서 해당 채널을 직접 lookup하여 {@code Channel.deliver()}로
   * 동기 발송한다. 결과는 success/message 형태로 즉시 반환되어 토스트로 표시된다.
   *
   * <ul>
   *   <li>CHAT: 항상 활성 — 별도 테스트 불필요. IllegalArgumentException으로 거부 (400).
   *   <li>SLACK/KAKAO: 활성 binding이 없으면 success=false 반환 (사용자에게 "연동이 필요합니다" 안내).
   *   <li>EMAIL: 사용자 계정 이메일로 실제 테스트 메일 1통 발송.
   * </ul>
   *
   * @param userId 테스트 발송을 요청한 사용자 ID
   * @param channelType 테스트 대상 채널
   * @return 발송 결과 (성공/실패 + 사유 메시지)
   * @throws IllegalArgumentException CHAT 채널은 테스트 발송 불가
   */
  public ChannelTestResult testChannel(long userId, ChannelType channelType) {
    if (channelType == ChannelType.CHAT) {
      throw new IllegalArgumentException("CHAT 채널은 항상 활성 상태이며 테스트 발송이 필요하지 않습니다.");
    }

    // OAuth 채널은 binding 미연결 시 즉시 실패 응답 (Channel.deliver()까지 가지 않고 사용자에게 명확한 안내)
    Optional<UserChannelBinding> binding = bindingRepo.findActive(userId, channelType);
    if (channelType == ChannelType.KAKAO || channelType == ChannelType.SLACK) {
      if (binding.isEmpty()) {
        return new ChannelTestResult(false, "채널 연동이 필요합니다. 먼저 '연동하기' 버튼으로 계정을 연결하세요.");
      }
    }

    // 테스트 페이로드 — 사용자에게 발송됨이 명확하게 보이도록 시간 정보 포함
    String now = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    Payload testPayload =
        new Payload(
            Payload.PayloadType.STANDARD,
            "🔔 Smart Fire Hub 테스트 알림",
            "이 메시지는 채널 연결 상태를 확인하기 위한 테스트 발송입니다. (" + now + ")",
            List.of(),
            List.of(),
            List.of(),
            Map.of("test", true),
            Map.of());

    // 수신 주소 결정 — EMAIL은 계정 이메일, 그 외는 binding이 처리
    String recipientAddress = null;
    if (channelType == ChannelType.EMAIL) {
      recipientAddress = userRepository.findById(userId).map(u -> u.email()).orElse(null);
      if (recipientAddress == null || recipientAddress.isBlank()) {
        return new ChannelTestResult(false, "수신 이메일 주소를 확인할 수 없습니다.");
      }
    }

    DeliveryContext ctx =
        new DeliveryContext(
            // outboxId — 테스트 발송은 outbox에 행을 만들지 않으므로 음수 sentinel 사용 (로그 추적용)
            -System.currentTimeMillis(),
            UUID.randomUUID(),
            userId,
            recipientAddress,
            binding,
            testPayload);

    DeliveryResult result;
    try {
      result = channelRegistry.get(channelType).deliver(ctx);
    } catch (RuntimeException e) {
      return new ChannelTestResult(false, "테스트 발송 중 오류: " + e.getMessage());
    }

    return switch (result) {
      case DeliveryResult.Sent ignored -> new ChannelTestResult(true, "테스트 메시지가 발송되었습니다.");
      case DeliveryResult.TransientFailure tf ->
          new ChannelTestResult(false, "발송 실패 (재시도 가능): " + tf.reason());
      case DeliveryResult.PermanentFailure pf ->
          new ChannelTestResult(false, "발송 실패: " + pf.details());
    };
  }
}
