package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 운영자 평면 사용자 검색 TC.
 *
 * <p>가장 중요한 단언은 <b>교차 테넌트 가시성</b>이다. {@code "user"} 가 만약 RLS 였다면 플랫폼
 * 토큰에는 GUC 가 없어 조용히 0행이 나오고, 화면은 그것을 "검색 결과 없음"으로 그린다 — 예외도
 * 로그도 없는 무동작이다. 그래서 서로 다른 테넌트에 소속된 두 사용자를 심고 <b>둘 다</b> 잡히는지
 * 본다. 한 명만 심으면 깨진 쿼리에서도 우연히 통과할 수 있다.
 */
@AutoConfigureMockMvc
class PlatformUserControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  /** 공유 test DB 라 검색어가 다른 테스트의 행을 긁지 않도록 나노초로 유일화한다. */
  private String marker;

  private String operatorToken;
  private final List<Long> createdUserIds = new ArrayList<>();
  private final List<Long> createdTenantIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    marker = "p7c2a" + System.nanoTime();
    operatorToken = jwtTokenProvider.generatePlatformAccessToken(createUser(marker + "-ops", true), "ops");
  }

  @AfterEach
  void cleanUp() {
    // 자기가 만든 행만 지운다. 공유 test DB 에서 남의 행을 지우면 무관한 테스트가 깨진다.
    // 순서가 중요하다: membership → user → tenant. FK 에 cascade 가 없다.
    // deleteMembership 은 **사용자 단위**로 지운다(멤버십 id 가 아니다).
    createdUserIds.forEach(id -> TenantRlsTestSupport.deleteMembership(dsl, id));
    createdUserIds.forEach(id -> TenantRlsTestSupport.deleteUser(dsl, id));
    TenantRlsTestSupport.deleteTenants(dsl, createdTenantIds.toArray(Long[]::new));
  }

  private long createUser(String username, boolean platformRole) {
    long userId = TenantRlsTestSupport.insertUserWithPassword(dsl, username, "{noop}x");
    createdUserIds.add(userId);
    if (platformRole) {
      TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, userId);
    }
    return userId;
  }

  @Test
  void searchesAcrossTenants() throws Exception {
    // 서로 다른 테넌트에 소속된 두 사용자.
    long tenantA = TenantRlsTestSupport.createActiveTenant(dsl, marker + "-a");
    long tenantB = TenantRlsTestSupport.createActiveTenant(dsl, marker + "-b");
    createdTenantIds.add(tenantA);
    createdTenantIds.add(tenantB);

    long userA = createUser(marker + "-alpha", false);
    long userB = createUser(marker + "-bravo", false);
    // 반환값이 없다(void). 정리는 @AfterEach 가 사용자 id 로 한다.
    TenantRlsTestSupport.insertActiveMembership(dsl, userA, tenantA);
    TenantRlsTestSupport.insertActiveMembership(dsl, userB, tenantB);

    String body =
        mockMvc
            .perform(get("/api/platform/users").param("q", marker)
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<Long> ids =
        objectMapper.readTree(body).findValuesAsText("id").stream().map(Long::valueOf).toList();
    assertThat(ids).contains(userA, userB);
  }

  @Test
  void returnsMinimalFields() throws Exception {
    long target = createUser(marker + "-charlie", false);

    mockMvc
        .perform(get("/api/platform/users").param("q", marker + "-charlie")
            .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(target))
        .andExpect(jsonPath("$[0].name").value(marker + "-charlie"))
        .andExpect(jsonPath("$[0].email").value(marker + "-charlie@example.com"))
        // 비밀번호·활성여부·가입일은 절대 나가지 않는다 — 열거 표면을 넓히지 않는다.
        .andExpect(jsonPath("$[0].password").doesNotExist())
        .andExpect(jsonPath("$[0].username").doesNotExist())
        .andExpect(jsonPath("$[0].isActive").doesNotExist());
  }

  @Test
  void rejectsShortQuery() throws Exception {
    mockMvc
        .perform(get("/api/platform/users").param("q", "a")
            .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsMissingQuery() throws Exception {
    mockMvc
        .perform(get("/api/platform/users").header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsBlankQuery() throws Exception {
    mockMvc
        .perform(get("/api/platform/users").param("q", "   ")
            .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void truncatesToLimit() throws Exception {
    // 상한(20) 보다 많이 심는다. 절단이 없으면 25 가 그대로 나온다.
    for (int i = 0; i < 25; i++) {
      createUser(marker + "-bulk-" + i, false);
    }

    mockMvc
        .perform(get("/api/platform/users").param("q", marker + "-bulk")
            .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(20));
  }

  @Test
  void escapesLikeWildcards() throws Exception {
    // '%' 를 이스케이프하지 않으면 이 검색어가 전 사용자를 긁는다.
    mockMvc
        .perform(get("/api/platform/users").param("q", "%")
            .header("Authorization", "Bearer " + operatorToken))
        // 2자 미만이라 하한에서 먼저 걸린다.
        .andExpect(status().isBadRequest());

    long delta = createUser(marker + "-delta", false);

    // 리터럴 대조군: 쿼리 자체는 정상 동작해서 delta 를 찾는다는 것을 먼저 확인한다.
    String literalBody =
        mockMvc
            .perform(get("/api/platform/users").param("q", marker + "-delta")
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Long> literalIds =
        objectMapper.readTree(literalBody).findValuesAsText("id").stream()
            .map(Long::valueOf)
            .toList();
    assertThat(literalIds).contains(delta);

    // 본검증: '_' 는 LIKE 에서 임의의 한 글자와 매치되는 와일드카드다. 이스케이프가 없으면
    // "<marker>-delt_" 가 "<marker>-delta" 의 밑줄 자리에 'a' 를 매치시켜 delta 를 잡는다.
    // 이스케이프가 있으면 리터럴 밑줄을 요구하므로 아무도 안 잡혀야 한다.
    //
    // ORDER BY … LIMIT 20 이 증거를 가릴 수 없는 이유: marker 가 나노초로 유일해서 이 검색어에
    // 매치되는 행은 이 테스트가 심은 것(0~1개)뿐이다 — 공유 test DB 에 다른 11,795행이 있어도
    // 그 행들은 이 marker 문자열을 갖지 않으므로 후보에 아예 오르지 않는다. 예전 버전은 "%%" 를
    // 써서 검색 결과가 전체 사용자(수천 행)가 됐고, 그중 가장 오래된 20명만 반환되는 바람에
    // 방금 만든 delta 는 이스케이프 여부와 무관하게 항상 그 20명 밖이었다(공허 테스트).
    String body =
        mockMvc
            .perform(get("/api/platform/users").param("q", marker + "-delt_")
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Long> ids =
        objectMapper.readTree(body).findValuesAsText("id").stream().map(Long::valueOf).toList();
    assertThat(ids).doesNotContain(delta);
  }

  @Test
  void excludesInactiveUsers() throws Exception {
    // 대조군(활성)과 실험군(비활성)을 같은 marker 로 심는다. 대조군이 잡혀야 "marker 검색 자체가
    // 결과를 낸다"가 고정되고, 그래야 실험군 부재가 "필터가 걸렀다"인지 "애초에 아무것도 안
    // 잡혔다"인지 구별된다.
    long active = createUser(marker + "-golf", false);
    long inactive = createUser(marker + "-hotel", false);
    dsl.update(table(name("user")))
        .set(field(name("is_active"), Boolean.class), false)
        .where(field(name("id"), Long.class).eq(inactive))
        .execute();

    String body =
        mockMvc
            .perform(get("/api/platform/users").param("q", marker)
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<Long> ids =
        objectMapper.readTree(body).findValuesAsText("id").stream().map(Long::valueOf).toList();
    assertThat(ids).contains(active).doesNotContain(inactive);
  }

  @Test
  void exactEmailMatchSortsFirstThenAlphabetical() throws Exception {
    // id 오름차순(삽입 순)으로는 [userZ, userY, userExact] 가 나온다. 이메일 정확일치 우선 +
    // 이메일 오름차순 정렬이라면 [userExact, userY, userZ] 여야 한다 — 두 정렬 규칙이 서로 다른
    // 답을 내도록 일부러 정확일치 대상을 가장 나중에(가장 큰 id 로) 심는다.
    long userZ = createUser(marker + "-userz", false);
    long userY = createUser(marker + "-usery", false);
    long userExact = createUser(marker + "-userexact", false);

    String q = marker + "@search.example";
    // 셋 다 이메일에 q 를 부분문자열로 포함시켜 같은 검색어로 모두 잡히게 하되, userExact 만
    // q 와 완전히 같게 해서 "정확일치" 조건을 인위적으로 만든다. "aaa-"/"zzz-" 접두사로 알파벳
    // tie-break 도 같이 고정한다(rank 가 같은 둘 중 "aaa-" 가 먼저 와야 한다).
    dsl.update(table(name("user")))
        .set(field(name("email"), String.class), "zzz-" + q)
        .where(field(name("id"), Long.class).eq(userZ))
        .execute();
    dsl.update(table(name("user")))
        .set(field(name("email"), String.class), "aaa-" + q)
        .where(field(name("id"), Long.class).eq(userY))
        .execute();
    dsl.update(table(name("user")))
        .set(field(name("email"), String.class), q)
        .where(field(name("id"), Long.class).eq(userExact))
        .execute();

    String body =
        mockMvc
            .perform(get("/api/platform/users").param("q", q)
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<Long> ids =
        objectMapper.readTree(body).findValuesAsText("id").stream().map(Long::valueOf).toList();
    assertThat(ids).containsExactly(userExact, userY, userZ);
  }

  @Test
  void rejectsQueryLongerThanLimit() throws Exception {
    String tooLong = marker + "a".repeat(101);

    mockMvc
        .perform(get("/api/platform/users").param("q", tooLong)
            .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void withoutPlatformPermissions_isForbidden() throws Exception {
    String weak =
        jwtTokenProvider.generatePlatformAccessToken(createUser(marker + "-nobody", false), "nobody");

    mockMvc
        .perform(get("/api/platform/users").param("q", marker)
            .header("Authorization", "Bearer " + weak))
        .andExpect(status().isForbidden());
  }

  // tenantTokenCannotReachPlatformPlane 는 이 파일에 두지 않는다. PlatformPlaneFilter 는
  // 컨트롤러보다 앞서 도는 라우트 무관 필터라 이 컨트롤러가 존재하기 전에도(404 이전에) 이미
  // 403 을 줬다 — 실제로 이 파일 작성 초기(TDD Step 2) 기록에 그 증거가 남아 있다. 같은
  // 단언이 PlatformPlaneIsolationTest.tenantTokenCannotReachPlatformPlane(다른 라우트,
  // /api/platform/tenants)에 이미 있고 그걸로 평면 격리 자체는 충분히 고정된다. 여기 다시
  // 둬도 이 컨트롤러 고유의 무엇도 검증하지 못한다.
}
