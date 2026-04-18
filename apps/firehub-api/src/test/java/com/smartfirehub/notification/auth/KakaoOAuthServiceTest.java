package com.smartfirehub.notification.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.channels.kakao.KakaoApiClient;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * KakaoOAuthService 단위 테스트.
 *
 * <p>KakaoApiClient·EncryptionService 모두 Mockito로 모킹하여 외부 호출 없이 비즈니스 로직만 검증한다. EncryptionService는
 * package-private 생성자 제약으로 @Mock을 사용하고, encrypt()가 "enc:{input}" 형태로 stub된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KakaoOAuthServiceTest {

  @Mock private KakaoApiClient kakaoApiClient;
  @Mock private UserChannelBindingRepository bindingRepo;

  /** EncryptionService는 생성자가 package-private이므로 Mock으로 stub */
  @Mock private EncryptionService encryption;

  @InjectMocks private KakaoOAuthService kakaoOAuthService;

  private static final String TEST_CLIENT_ID = "test-client-id";
  private static final String TEST_CLIENT_SECRET = "test-secret";
  private static final String TEST_REDIRECT_URI =
      "https://app.example.com/api/v1/oauth/kakao/callback";

  @BeforeEach
  void setUp() {
    // encrypt()가 호출되면 "enc:{원문}" 형태로 반환 — 암호화 여부(원문과 다름) 검증 가능
    org.mockito.Mockito.when(encryption.encrypt(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(inv -> "enc:" + inv.getArgument(0));

    // @Value 필드를 직접 주입 (Spring 컨텍스트 없이 단위 테스트)
    ReflectionTestUtils.setField(kakaoOAuthService, "clientId", TEST_CLIENT_ID);
    ReflectionTestUtils.setField(kakaoOAuthService, "clientSecret", TEST_CLIENT_SECRET);
    ReflectionTestUtils.setField(kakaoOAuthService, "redirectUri", TEST_REDIRECT_URI);
  }

  // =========================================================================
  // completeAuthorization — 성공 케이스
  // =========================================================================

  /**
   * 정상: code → token 교환 성공 시 bindingRepo.upsert 호출, access_token·refresh_token이 암호화된 값으로 전달되어야 한다.
   */
  @Test
  void completeAuthorization_success_upsertBindingWithEncryptedTokens() {
    // given
    long userId = 42L;
    String code = "test-auth-code";
    String rawAccessToken = "raw-access-token-AAA";
    String rawRefreshToken = "raw-refresh-token-BBB";
    long expiresIn = 21599L;

    ObjectNode tokenResp = new ObjectMapper().createObjectNode();
    tokenResp.put("access_token", rawAccessToken);
    tokenResp.put("refresh_token", rawRefreshToken);
    tokenResp.put("expires_in", expiresIn);

    when(kakaoApiClient.exchangeCode(
            eq(code), eq(TEST_CLIENT_ID), eq(TEST_CLIENT_SECRET), eq(TEST_REDIRECT_URI)))
        .thenReturn(tokenResp);

    // when
    Instant before = Instant.now();
    kakaoOAuthService.completeAuthorization(userId, code);

    // then: upsert 호출 확인
    ArgumentCaptor<UserChannelBinding> captor = forClass(UserChannelBinding.class);
    verify(bindingRepo).upsert(captor.capture());

    UserChannelBinding saved = captor.getValue();
    assertThat(saved.userId()).isEqualTo(userId);
    assertThat(saved.channelType()).isEqualTo(ChannelType.KAKAO);
    assertThat(saved.workspaceId()).isNull();
    assertThat(saved.status()).isEqualTo("ACTIVE");

    // 토큰이 암호화되어 있어야 한다 — 원문과 달라야 함
    assertThat(saved.accessTokenEnc()).isNotEqualTo(rawAccessToken);
    assertThat(saved.refreshTokenEnc()).isNotEqualTo(rawRefreshToken);
    // 암호화 형식: base64(iv):base64(ciphertext)
    assertThat(saved.accessTokenEnc()).contains(":");
    assertThat(saved.refreshTokenEnc()).contains(":");
  }

  /** 정상: expires_in이 tokenExpiresAt에 반영되어 미래 시각이어야 한다. */
  @Test
  void completeAuthorization_expiresInReflectedToFutureTokenExpiresAt() {
    // given
    long expiresIn = 21599L; // 약 6시간
    ObjectNode tokenResp = new ObjectMapper().createObjectNode();
    tokenResp.put("access_token", "AAA");
    tokenResp.put("refresh_token", "BBB");
    tokenResp.put("expires_in", expiresIn);

    when(kakaoApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(tokenResp);

    // when
    Instant before = Instant.now();
    kakaoOAuthService.completeAuthorization(1L, "code");

    // then
    ArgumentCaptor<UserChannelBinding> captor = forClass(UserChannelBinding.class);
    verify(bindingRepo).upsert(captor.capture());

    Instant tokenExpiresAt = captor.getValue().tokenExpiresAt();
    assertThat(tokenExpiresAt).isNotNull();
    // tokenExpiresAt는 현재 시각 + expiresIn 초 이후여야 함
    assertThat(tokenExpiresAt).isAfter(before.plusSeconds(expiresIn - 5));
    assertThat(tokenExpiresAt).isBefore(before.plusSeconds(expiresIn + 5));
  }

  // =========================================================================
  // authorizeUrl — URL 구성 검증
  // =========================================================================

  /** authorizeUrl이 client_id, redirect_uri, scope=talk_message, state를 포함해야 한다. */
  @Test
  void authorizeUrl_containsRequiredParams() {
    String state = "test-state-value";

    String url = kakaoOAuthService.authorizeUrl(state);

    assertThat(url).startsWith("https://kauth.kakao.com/oauth/authorize");
    assertThat(url).contains("client_id=" + TEST_CLIENT_ID);
    assertThat(url).contains("redirect_uri=" + TEST_REDIRECT_URI);
    assertThat(url).contains("scope=talk_message");
    assertThat(url).contains("state=" + state);
    assertThat(url).contains("response_type=code");
  }
}
