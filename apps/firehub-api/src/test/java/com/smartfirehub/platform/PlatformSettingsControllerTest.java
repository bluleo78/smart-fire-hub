package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 플랫폼 기본 설정 조회·쓰기(P7-b Task 6).
 *
 * <p>이 클래스는 <b>class-level {@code @Transactional} 을 쓰지 않는다</b>(밴드 전역 규칙) —
 * 쓰기 테스트가 커밋한 값은 각 테스트가 직접 원래 값을 저장해 뒀다가 {@code finally} 에서 raw SQL
 * 로 복원한다. {@code system_settings} 는 전역 단일 행 집합이고 같은 test DB 를 AI·임베딩·SMTP
 * 테스트가 함께 읽으므로, 복원하지 않으면 이 테스트 실행 이후의 다른 실행이 깨진다.
 */
@AutoConfigureMockMvc
class PlatformSettingsControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private SettingsService settingsService;
  @Autowired private DSLContext dsl;

  /** 운영자는 세 프리픽스의 키를 한 번에 읽는다. */
  @Test
  void getSettings_returnsAllPrefixes() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/platform/settings")
                    .header("Authorization", "Bearer " + operatorToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // 프리픽스별로 최소 한 키씩 — getByPrefix 만으로는 세 번 호출해야 하는 것을 한 번에 준다.
    assertThat(body).contains("ai.model", "embedding.model", "smtp.host");
  }

  /**
   * 비밀값은 마스킹된 채로 반환된다.
   *
   * <p>test DB 의 비밀 키들은 비어 있어 마스킹 결과가 빈 문자열이다. 그래서 "무엇이 아닌가"를
   * 단언한다 — 암호문({@code iv:ciphertext} 형태의 Base64)이 그대로 새는 것을 잡는다.
   */
  @Test
  void getSettings_doesNotLeakCiphertext() throws Exception {
    String ciphertext =
        dsl.fetchOne("select value from system_settings where key = 'ai.api_key'").get(0, String.class);

    String body =
        mockMvc
            .perform(
                get("/api/platform/settings")
                    .header("Authorization", "Bearer " + operatorToken()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("ai.api_key");
    if (ciphertext != null && !ciphertext.isBlank()) {
      assertThat(body).doesNotContain(ciphertext);
    }
  }

  /** platform:settings:read 가 없는 플랫폼 사용자는 403 이다. */
  @Test
  void getSettings_requiresReadPermission() throws Exception {
    String weak = jwtTokenProvider.generatePlatformAccessToken(createUser(false), "nobody");

    mockMvc
        .perform(get("/api/platform/settings").header("Authorization", "Bearer " + weak))
        .andExpect(status().isForbidden());
  }

  /**
   * 운영자는 화이트리스트에 없는(=테넌트에게 닫힌) 플랫폼 잠금 키를 쓸 수 있다.
   *
   * <p>{@code embedding.model} 은 어떤 테넌트도 오버라이드할 수 없는 키다(모델 교체가 벡터 차원을
   * 바꾼다) — 그 편집 능력이 플랫폼 평면에는 여전히 있어야 한다는 것이 이 밴드의 존재 이유다.
   */
  @Test
  void 운영자는_플랫폼_잠금_키를_쓸_수_있다() throws Exception {
    String original = rawValue("embedding.model");
    try {
      mockMvc
          .perform(
              put("/api/platform/settings")
                  .header("Authorization", "Bearer " + operatorToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapperContent(Map.of("embedding.model", "text-embedding-3-large"))))
          .andExpect(status().isNoContent());

      assertThat(settingsService.getValue("embedding.model")).contains("text-embedding-3-large");
    } finally {
      restoreRawValue("embedding.model", original);
    }
  }

  /**
   * 마스킹된 센티널({@code "****xxxx"})을 그대로 PUT 해도 살아 있는 자격증명을 덮어쓰지 않는다.
   *
   * <p>운영자 UI 의 실제 사용 패턴이다 — GET 으로 마스킹된 값을 받아 폼에 채워 두었다가 다른 필드만
   * 바꿔서 그대로 다시 PUT 한다. 이 계약이 깨지면 "아무것도 안 바꿨는데 API 키가 사라졌다"는 사고가
   * 난다.
   */
  @Test
  void 마스킹된_비밀값은_저장되지_않는다() throws Exception {
    String original = rawValue("ai.api_key");
    try {
      mockMvc
          .perform(
              put("/api/platform/settings")
                  .header("Authorization", "Bearer " + operatorToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapperContent(Map.of("ai.api_key", "sk-live-secret-value"))))
          .andExpect(status().isNoContent());

      // 운영자 UI 가 GET 으로 받는 마스킹된 값 형태를 그대로 재현한다.
      String masked = settingsService.getByPrefix("ai").stream()
          .filter(s -> "ai.api_key".equals(s.key()))
          .findFirst()
          .orElseThrow()
          .value();
      assertThat(masked).startsWith("****");

      mockMvc
          .perform(
              put("/api/platform/settings")
                  .header("Authorization", "Bearer " + operatorToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapperContent(Map.of("ai.api_key", masked))))
          .andExpect(status().isNoContent());

      // 마스크를 그대로 되돌려 보냈으니 복호화 값은 원래 실제 키와 같아야 한다.
      assertThat(settingsService.getDecryptedApiKey()).hasValue("sk-live-secret-value");
    } finally {
      restoreRawValue("ai.api_key", original);
    }
  }

  /**
   * 테넌트 토큰은 이 경로에 도달할 수 없다.
   *
   * <p>{@code PlatformPlaneFilter}(P7-a) 가 이미 막지만, 이 새 엔드포인트가 실제로 그 보호 아래
   * 등록돼 있다는 것을 여기서 단언한다 — 라우트 등록을 빠뜨리는 함정이 이 밴드의 전례다.
   */
  @Test
  void 테넌트_토큰은_이_경로를_쓸_수_없다() throws Exception {
    String tenantToken = jwtTokenProvider.generateAccessToken(1L, "tenant-user", DEFAULT_TEST_TENANT_ID);

    mockMvc
        .perform(
            put("/api/platform/settings")
                .header("Authorization", "Bearer " + tenantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapperContent(Map.of("embedding.model", "should-not-apply"))))
        .andExpect(status().isForbidden());
  }

  private String objectMapperContent(Map<String, String> settings) throws Exception {
    return new ObjectMapper().writeValueAsString(settings);
  }

  /** {@code system_settings.value} 원본(암호화된 그대로)을 읽는다. 복원용. */
  private String rawValue(String key) {
    var row = dsl.fetchOne("select value from system_settings where key = ?", key);
    return row == null ? null : row.get(0, String.class);
  }

  /** 테스트가 바꾼 값을 원복한다. 공유 test DB 라 커밋된 변경을 남기면 이후 실행이 깨진다. */
  private void restoreRawValue(String key, String original) {
    dsl.execute("update system_settings set value = ? where key = ?", original, key);
  }

  private String operatorToken() {
    return jwtTokenProvider.generatePlatformAccessToken(createUser(true), "ops");
  }

  /** 검증용 사용자. 공유 test DB 라 나노초로 유일화한다. */
  private long createUser(boolean platformRole) {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "p7a-set-" + System.nanoTime(), "{noop}x");
    if (platformRole) {
      TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, userId);
    }
    return userId;
  }
}
