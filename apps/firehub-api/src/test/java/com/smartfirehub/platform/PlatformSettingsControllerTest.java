package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 플랫폼 기본 설정 조회.
 *
 * <p>이 테스트는 {@code system_settings} 를 <b>바꾸지 않는다</b>. 그 테이블은 전역 단일 행 집합이고
 * 같은 test DB 를 AI·임베딩·SMTP 테스트가 함께 읽는다 — 값을 바꾸면 그 테스트들이 뒤에서 깨진다.
 */
@AutoConfigureMockMvc
class PlatformSettingsControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
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
   * 쓰기 엔드포인트는 아직 없다.
   *
   * <p>P7-b 까지 두 평면이 같은 전역 18행을 경쟁적으로 쓰지 않게 하기 위한 의도된 부재다. 200 이
   * 나오면 화이트리스트 없이 쓰기가 열렸다는 뜻이므로 실패해야 한다.
   */
  @Test
  void putSettings_notExposedYet() throws Exception {
    int status =
        mockMvc
            .perform(
                put("/api/platform/settings")
                    .header("Authorization", "Bearer " + operatorToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"settings\":{}}"))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isIn(404, 405);
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
