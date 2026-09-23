package com.smartfirehub.platform;

import static com.smartfirehub.support.SettingsTestSupport.deleteSystemSetting;
import static com.smartfirehub.support.SettingsTestSupport.rawSystemSettingValue;
import static com.smartfirehub.support.SettingsTestSupport.restoreSystemSettingValue;
import static com.smartfirehub.support.SettingsTestSupport.upsertSystemSetting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.EncryptionService;
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
 * 로 복원한다. {@code system_settings} 는 전역 단일 행 집합이고 같은 test DB 를 여러 설정
 * 테스트가 함께 읽으므로, 복원하지 않으면 이 테스트 실행 이후의 다른 실행이 깨진다.
 */
@AutoConfigureMockMvc
class PlatformSettingsControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private SettingsService settingsService;
  @Autowired private EncryptionService encryptionService;
  @Autowired private DSLContext dsl;

  /**
   * 운영자는 임베딩 키만 읽는다 — AI(#706)·SMTP(#712) 설정은 테넌트 전용이다.
   *
   * <p>V127·V128 이 {@code ai.*}·{@code smtp.*} 플랫폼 행을 지웠으므로, 행을 <b>직접 심어 둔 채</b>
   * 응답에서 빠지는지 본다(심지 않으면 필터가 사라져도 통과하는 공허한 단언이 된다).
   */
  @Test
  void getSettings_returnsEmbeddingKeysButNoAiOrSmtpKeys() throws Exception {
    plantPlatformRow("ai.model", "planted-platform-model");
    plantPlatformRow("ai.max_turns", "42");
    plantPlatformRow("smtp.host", "planted-platform-smtp.example.com");
    plantPlatformRow("smtp.password", "planted-platform-password");
    try {
      String body =
          mockMvc
              .perform(
                  get("/api/platform/settings")
                      .header("Authorization", "Bearer " + operatorToken()))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(body).contains("embedding.model");
      assertThat(body).doesNotContain("\"ai.").doesNotContain("planted-platform-model");
      assertThat(body)
          .doesNotContain("\"smtp.")
          .doesNotContain("planted-platform-smtp.example.com")
          .doesNotContain("planted-platform-password");
    } finally {
      deleteSystemSetting(dsl, "ai.model");
      deleteSystemSetting(dsl, "ai.max_turns");
      deleteSystemSetting(dsl, "smtp.host");
      deleteSystemSetting(dsl, "smtp.password");
    }
  }

  /** 플랫폼 PUT 은 {@code ai.*} 를 거부하고 아무것도 저장하지 않는다. */
  @Test
  void 운영자도_AI_키는_쓸_수_없다() throws Exception {
    try {
      mockMvc
          .perform(
              put("/api/platform/settings")
                  .header("Authorization", "Bearer " + operatorToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapperContent(Map.of("ai.model", "operator-model"))))
          .andExpect(status().isBadRequest());

      assertThat(rawSystemSettingValue(dsl, "ai.model")).isNull();
    } finally {
      deleteSystemSetting(dsl, "ai.model");
    }
  }

  /**
   * 플랫폼 PUT 은 {@code smtp.*} 를 400 으로 거부하고 아무것도 저장하지 않는다(#712). 옛 관리자 앱이
   * SMTP 탭 값을 보내면 이 응답을 받는다 — 임베딩 키와 섞어 보내도 통째로 거부된다(부분 저장 없음).
   */
  @Test
  void 운영자도_SMTP_키는_쓸_수_없다() throws Exception {
    String modelBefore = rawSystemSettingValue(dsl, "embedding.model");
    try {
      mockMvc
          .perform(
              put("/api/platform/settings")
                  .header("Authorization", "Bearer " + operatorToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      objectMapperContent(
                          Map.of(
                              "smtp.host", "operator-smtp.example.com",
                              "embedding.model", "should-not-save"))))
          .andExpect(status().isBadRequest());

      assertThat(rawSystemSettingValue(dsl, "smtp.host")).isNull();
      assertThat(rawSystemSettingValue(dsl, "embedding.model")).isEqualTo(modelBefore);
    } finally {
      deleteSystemSetting(dsl, "smtp.host");
      restoreSystemSettingValue(dsl, "embedding.model", modelBefore);
    }
  }

  /** V127·V128 이후 없는 {@code ai.*}·{@code smtp.*} 플랫폼 행을 직접 심는다(쓰기 API 가 없다). */
  private void plantPlatformRow(String key, String value) {
    upsertSystemSetting(dsl, key, value);
  }

  /**
   * 비밀값은 마스킹된 채로 반환된다.
   *
   * <p><b>먼저 진짜 키를 저장한다.</b> test DB 의 {@code embedding.api_key} 시드 값은 빈 문자열이라
   * (예전 검증 키 {@code ai.api_key} 는 #706 으로 사라졌다),
   * "값이 있을 때만 단언한다"는 형태로 두면 조건이 항상 거짓이 되어 <b>유출 단언이 한 번도
   * 실행되지 않는다</b>(실제로 그렇게 쓰여 있었다). 그 상태에서는 {@code SettingsService.getAll}
   * 의 {@code maskSecret} 을 지워도 이 테스트가 녹색으로 남아, 막으려던 암호문 유출을 전혀
   * 막지 못한다. 값을 만들어 두고 <b>무조건</b> 단언한다.
   *
   * <p>공유 test DB 이므로 원래 값을 {@code finally} 에서 그대로 되돌린다.
   */
  @Test
  void getSettings_doesNotLeakCiphertext() throws Exception {
    String original = rawSystemSettingValue(dsl, "embedding.api_key");
    try {
      settingsService.updatePlatformSettings(Map.of("embedding.api_key", "sk-platform-secret"), null);
      String ciphertext = rawSystemSettingValue(dsl, "embedding.api_key");
      // 전제 확인: 저장된 원본이 실제 암호문이어야 이 단언이 의미를 갖는다.
      assertThat(ciphertext).contains(":");

      String body =
          mockMvc
              .perform(
                  get("/api/platform/settings")
                      .header("Authorization", "Bearer " + operatorToken()))
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(body).contains("embedding.api_key");
      assertThat(body).doesNotContain(ciphertext);
      assertThat(body).doesNotContain("sk-platform-secret");
    } finally {
      restoreSystemSettingValue(dsl, "embedding.api_key", original);
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
    String original = rawSystemSettingValue(dsl, "embedding.model");
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
      restoreSystemSettingValue(dsl, "embedding.model", original);
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
    String original = rawSystemSettingValue(dsl, "embedding.api_key");
    try {
      mockMvc
          .perform(
              put("/api/platform/settings")
                  .header("Authorization", "Bearer " + operatorToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapperContent(Map.of("embedding.api_key", "sk-live-secret-value"))))
          .andExpect(status().isNoContent());

      // 운영자 UI 가 GET 으로 받는 마스킹된 값 형태를 그대로 재현한다 — 그 라우트가 부르는
      // 메서드(getAll)를 그대로 쓴다. 예전에는 getByPrefix 를 썼는데 그 메서드는 운영자 UI 가
      // 부르지 않는 경로였고(P7-c1 에서 호출자 0으로 삭제됐다), 재현이라면서 다른 문을 열고 있었다.
      String masked = settingsService.getAll().stream()
          .filter(s -> "embedding.api_key".equals(s.key()))
          .findFirst()
          .orElseThrow()
          .value();
      assertThat(masked).startsWith("****");

      mockMvc
          .perform(
              put("/api/platform/settings")
                  .header("Authorization", "Bearer " + operatorToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapperContent(Map.of("embedding.api_key", masked))))
          .andExpect(status().isNoContent());

      // 마스크를 그대로 되돌려 보냈으니 복호화 값은 원래 실제 키와 같아야 한다.
      // DB 원문을 직접 복호화해 계약을 검증한다.
      assertThat(encryptionService.decrypt(rawSystemSettingValue(dsl, "embedding.api_key")))
          .isEqualTo("sk-live-secret-value");
    } finally {
      restoreSystemSettingValue(dsl, "embedding.api_key", original);
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
