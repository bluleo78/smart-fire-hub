package com.smartfirehub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 플랫폼 평면이 기본적으로 닫혀 있는지 검증한다.
 *
 * <p>왜 이 테스트가 필요한가: SecurityConfig 의 체인은 {@code /api/v1/**} 만 authenticated 로
 * 잡고 {@code anyRequest().permitAll()} 로 끝난다. {@code /api/platform/**} 는 v1 이 아니므로
 * 명시 매처가 사라지는 순간 조용히 무인증 전면 개방이 된다 — 회귀하면 즉시 알아야 한다.
 */
@AutoConfigureMockMvc
class PlatformPlaneSecurityTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;

  /** 존재하지 않는 경로여도 401 이어야 한다. 404 가 나오면 인증 필터를 통과했다는 뜻이다. */
  @Test
  void platformPathsRequireAuthentication() throws Exception {
    mockMvc.perform(get("/api/platform/tenants")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/platform/settings")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/platform/does-not-exist")).andExpect(status().isUnauthorized());
  }
}
