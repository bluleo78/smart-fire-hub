package com.smartfirehub.platform;

import static com.smartfirehub.support.SettingsTestSupport.rawSystemSettingValue;
import static com.smartfirehub.support.SettingsTestSupport.restoreSystemSettingValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 플랫폼 AI 인증 상태(Ruling #47, Task 13 fix round 1) — {@code PlatformAiController} 의 거울인
 * {@code AiControllerTest}(테넌트)와 같은 시나리오를 플랫폼 평면에서 고정한다.
 *
 * <p>다른 {@code com.smartfirehub.platform} 패키지 테스트(예: {@code PlatformSettingsControllerTest})
 * 와 같은 관례를 따른다 — {@code @WebMvcTest} 슬라이스 대신 {@link IntegrationTestBase}(실제 DB
 * + {@code PlatformPlaneFilter} 포함 전체 보안 체인)를 쓴다. 이 엔드포인트의 핵심 위험(플랫폼
 * 토큰 인증 경로에 실제로 등록돼 있는가, 권한 검사가 실제로 걸리는가)은 슬라이스 테스트가 목킹
 * 으로 가려버릴 수 있는 부분이라, 이 패키지의 기존 관례를 그대로 따르는 것이 더 정직하다.
 *
 * <p><b>외부 ai-agent 호출을 목킹하지 않는다</b> — 아래 두 시나리오 모두 빈 비밀(sdk, 토큰 없음)
 * 또는 opencode(애초에 ai-agent 를 부르지 않는다) 라서 {@code AiAgentProxyService} 가 외부
 * WebClient 호출까지 가지 않고 즉시 반환한다({@code verifyApiKey()}/{@code verifyCliToken()} 의
 * "비어 있으면 즉시 {@code valid:false}" 가드). 실제 검증 성공 경로(ai-agent 응답)는 이 클래스의
 * 책임이 아니다 — 그건 {@code AiAgentProxyServiceTest} 가 이미 다룬다.
 */
@AutoConfigureMockMvc
class PlatformAiControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private AiCredentialService aiCredentialService;
  @Autowired private DSLContext dsl;

  /**
   * Task 14 게이트에서 발견 — {@link #createReadOnlyUser()} 가 {@code platform_role} 에 심는
   * {@code FR2_READ_ONLY_*} 행을 정리 없이 남겼다. {@link IntegrationTestBase} 는 의도적으로
   * 클래스 레벨 {@code @Transactional} 이 아니라서(자기 문서화된 규칙) 이 삽입은 커밋된 채
   * 남는다 — 실행할 때마다 공유 test DB 에 한 행씩 쌓여, 결국
   * {@code MultiTenancyMigrationTest} 의 "플랫폼 롤은 SUPER_ADMIN 하나뿐이다" 단언을
   * 깨뜨렸다(로컬 실측: 11행 누적). 패턴 기반 삭제라 실패한 실행이 남긴 행도 다음 실행이
   * 스스로 치운다. FK 순서(role_permission → user_role → role)를 지켜 삭제한다.
   */
  @AfterEach
  void cleanUpLeakedReadOnlyRole() {
    dsl.execute(
        "delete from platform_user_role where platform_role_id in"
            + " (select id from platform_role where name like 'FR2_READ_ONLY_%')");
    dsl.execute(
        "delete from platform_role_permission where platform_role_id in"
            + " (select id from platform_role where name like 'FR2_READ_ONLY_%')");
    dsl.execute("delete from platform_role where name like 'FR2_READ_ONLY_%'");
  }

  /** sdk 이고 OAuth 토큰·API 키가 둘 다 비어 있으면(시드 상태) ai-agent 를 부르지 않고 즉시
   * invalid 로 답한다 — 이 화면이 있게 된 이유(스펙 공통 절의 "인증 확인" 버튼)가 실제로
   * 플랫폼 자격증명을 읽는지 확인하는 가장 기본적인 배선 테스트다. */
  @Test
  void authStatus_비어있는_sdk_자격증명은_invalid이다() throws Exception {
    String original = rawSystemSettingValue(dsl, "ai.credential");
    try {
      // secret 을 생략(Map.of())하면 "유지" 다 — 공유 test DB 에 이미 sdk 행이 있고 그 행에
      // 실제 토큰이 남아 있으면(다른 테스트/실행의 잔재) 이 저장이 그 토큰을 그대로 들고
      // 있는다. 그러면 verifyCliToken() 이 blank 가드를 지나 실제 ai-agent(localhost:9999,
      // 테스트 스텁 없음)를 호출해 500 이 난다(실측) — 빈 문자열을 **명시**해 확실히
      // 지운다(PUT 계약: 빈 문자열=삭제).
      aiCredentialService.save(
          new AiCredentialUpsert("sdk", Map.of(), Map.of("oauthToken", "", "apiKey", "")),
          createUser(true),
          true);

      mockMvc
          .perform(get("/api/platform/ai/auth-status").header("Authorization", "Bearer " + operatorToken()))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(content().string("{\"valid\":false}"));
    } finally {
      restoreSystemSettingValue(dsl, "ai.credential", original);
    }
  }

  /** opencode 는 Anthropic 인증 개념이 없다 — ai-agent 를 부르지 않고 "해당 없음"을 바로
   * 응답해야 한다. 관리자 화면은 이 응답을 보고 "인증 확인" 버튼·배지를 아예 숨긴다. */
  @Test
  void authStatus_opencode면_해당없음을_바로_응답한다() throws Exception {
    String original = rawSystemSettingValue(dsl, "ai.credential");
    try {
      aiCredentialService.save(
          new AiCredentialUpsert(
              "opencode",
              Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
              Map.of("apiKey", "sk-oai-test")),
          createUser(true),
          true);

      mockMvc
          .perform(get("/api/platform/ai/auth-status").header("Authorization", "Bearer " + operatorToken()))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(content().string("{\"valid\":false,\"applicable\":false}"));
    } finally {
      restoreSystemSettingValue(dsl, "ai.credential", original);
    }
  }

  /**
   * 아무 플랫폼 권한도 없는 사용자는 403 이다.
   *
   * <p>뮤테이션 검사(Task 13 fix round 1): 컨트롤러의 {@code @RequirePermission(...)} 를 지우면
   * 이 테스트가 RED 가 된다(200 으로 응답) — 실측했고, 복구 후 다시 GREEN 을 확인했다(리포트
   * 참고).
   */
  @Test
  void authStatus_권한이_없으면_403이다() throws Exception {
    String weak = jwtTokenProvider.generatePlatformAccessToken(createUser(false), "nobody");

    mockMvc
        .perform(get("/api/platform/ai/auth-status").header("Authorization", "Bearer " + weak))
        .andExpect(status().isForbidden());
  }

  /**
   * Ruling #54(fix round 2) — 이 엔드포인트는 {@code platform:settings:read} 가 아니라
   * {@code platform:settings:write} 를 요구해야 한다. 이 호출은 실제로 외부 ai-agent 에 인증된
   * 네트워크 요청을 낸다(같은 이유로 {@code POST /settings/ai-credential/probe} 도 write 다) —
   * 조회 권한만으로 외부 호출을 트리거할 수 있으면 안 된다. {@code SUPER_ADMIN}(다른 테스트가
   * 쓰는 {@code createUser(true)})은 read/write 를 둘 다 갖고 있어 이 구별을 낼 수 없으므로,
   * 이 테스트만을 위해 read 전용 롤을 즉석에서 만든다.
   *
   * <p>뮤테이션 검사: {@code @RequirePermission("platform:settings:write")} 를 다시
   * {@code "platform:settings:read"} 로 되돌리면 이 테스트가 RED 가 된다(read 만 가진
   * 사용자가 200 을 받는다).
   */
  @Test
  void authStatus_읽기_권한만으로는_403이다() throws Exception {
    String readOnly = jwtTokenProvider.generatePlatformAccessToken(createReadOnlyUser(), "reader");

    mockMvc
        .perform(get("/api/platform/ai/auth-status").header("Authorization", "Bearer " + readOnly))
        .andExpect(status().isForbidden());
  }

  /**
   * 테넌트 토큰은 이 경로에 도달할 수 없다 — {@code PlatformPlaneFilter} 가 이미 막지만, 이 새
   * 라우트가 실제로 그 보호 아래 등록돼 있다는 것을 여기서 단언한다(같은 패키지의 다른 테스트가
   * 쓰는 것과 같은 종류의 회귀 방지, "라우트 등록을 빠뜨리는 함정").
   */
  @Test
  void authStatus_테넌트_토큰은_이_경로를_쓸_수_없다() throws Exception {
    String tenantToken = jwtTokenProvider.generateAccessToken(1L, "tenant-user", DEFAULT_TEST_TENANT_ID);

    mockMvc
        .perform(get("/api/platform/ai/auth-status").header("Authorization", "Bearer " + tenantToken))
        .andExpect(status().isForbidden());
  }

  private String operatorToken() {
    return jwtTokenProvider.generatePlatformAccessToken(createUser(true), "ops");
  }

  /** 검증용 사용자. 공유 test DB 라 나노초로 유일화한다. */
  private long createUser(boolean platformRole) {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "p13fr1-ai-" + System.nanoTime(), "{noop}x");
    if (platformRole) {
      TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, userId);
    }
    return userId;
  }

  /** {@code platform:settings:read} 만 가진 사용자(Ruling #54 검사 전용) — 기존
   * {@code platform_role} 헬퍼(`TenantRlsTestSupport.grantPlatformSuperAdmin`)는 SUPER_ADMIN
   * 전체(read+write) 만 부여해 read/write 를 구별하는 테스트를 만들 수 없다. 이 테스트만을
   * 위한 임시 롤을 직접 만든다 — 공유 헬퍼로 옮기기엔 이 구별이 필요한 테스트가 아직 이거
   * 하나뿐이다. */
  private long createReadOnlyUser() {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "p13fr2-ai-ro-" + System.nanoTime(), "{noop}x");
    String roleName = "FR2_READ_ONLY_" + System.nanoTime();
    dsl.execute(
        "insert into platform_role (name, description, is_system) values (?, 'fix round 2 read-only test role', false)",
        roleName);
    dsl.execute(
        "insert into platform_role_permission (platform_role_id, permission_id)"
            + " select pr.id, p.id from platform_role pr, permission p"
            + " where pr.name = ? and p.code = 'platform:settings:read'",
        roleName);
    dsl.execute(
        "insert into platform_user_role (user_id, platform_role_id)"
            + " select ?, id from platform_role where name = ?",
        userId, roleName);
    return userId;
  }
}
