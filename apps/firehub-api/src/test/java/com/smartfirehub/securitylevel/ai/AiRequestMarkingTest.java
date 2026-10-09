package com.smartfirehub.securitylevel.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.security.InternalCallHeaders;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * AI 경로 표시의 진입점(JwtAuthenticationFilter → {@link AiCallContext#markAiRequest})을 고정한다(스펙 §4.3 —
 * Internal + X-On-Behalf-Of 만 AI 경로).
 *
 * <p>왜 필요한가: 가드 코어의 AI 훅은 이 요청 속성만 읽는다. 표시가 빠지면 AI 요청이 웹 요청처럼 전부 열리고(fail-open), 반대로 웹 요청에 표시가 붙으면
 * 사용자 화면이 AI 정책으로 좁아진다. 표시는 대행 인증이 실제로 성립한 요청에만 붙어야 하므로 실패 분기마다 "표시 없음"을 단언한다. 종단 동작(표시 → 목록·상세
 * 차단)은 {@link AiChatPathEnforcementTest} 가 실제 HTTP 요청으로 덮는다.
 */
class AiRequestMarkingTest extends IntegrationTestBase {

  /** application-test.yml 의 agent.internal-token. */
  private static final String INTERNAL_TOKEN = "test-internal-token";

  @Autowired private JwtAuthenticationFilter filter;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  private SecurityFixture fx;
  private long userId;

  /** 필터 체인 안에서 캡처한 인증과 두 표시 속성. */
  private record Marks(Authentication auth, Object ai, Object share) {}

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    userId = fx.createUser("arm_user");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    fx.deleteUser(userId);
  }

  /** 필터를 통과시키며 체인 안(=컨트롤러가 도는 시점)의 인증·표시를 캡처한다. */
  private Marks runFilter(Consumer<MockHttpServletRequest> headers) throws Exception {
    SecurityContextHolder.clearContext();
    TenantContext.clear();
    MockHttpServletRequest request = new MockHttpServletRequest();
    headers.accept(request);
    AtomicReference<Marks> captured = new AtomicReference<>();
    FilterChain chain =
        (req, res) ->
            captured.set(
                new Marks(
                    SecurityContextHolder.getContext().getAuthentication(),
                    req.getAttribute(AiCallContext.AI_CALL_ATTR),
                    req.getAttribute(AiCallContext.AI_SHARE_ATTR)));
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return captured.get();
  }

  /** ai-agent 의 대행 호출 헤더(내부 토큰 + 대행 사용자·테넌트). */
  private void internalOnBehalf(MockHttpServletRequest r, String token) {
    r.addHeader("Authorization", "Internal " + token);
    r.addHeader(InternalCallHeaders.ON_BEHALF_OF, String.valueOf(userId));
    r.addHeader(InternalCallHeaders.ON_BEHALF_OF_TENANT, String.valueOf(DEFAULT_TEST_TENANT_ID));
  }

  @Test
  void webJwt_isNotMarked_evenWithSpoofedDelegationHeaders() throws Exception {
    Marks m =
        runFilter(
            r -> {
              r.addHeader(
                  "Authorization",
                  "Bearer " + jwt.generateAccessToken(userId, "arm", DEFAULT_TEST_TENANT_ID));
              r.addHeader(InternalCallHeaders.ON_BEHALF_OF, String.valueOf(userId));
              r.addHeader(InternalCallHeaders.AI_PURPOSE, AiCallContext.PURPOSE_SHARE);
            });
    assertThat(m.auth()).as("웹 인증 자체는 성립해야 한다(대조)").isNotNull();
    assertThat(m.ai()).isNull();
    assertThat(m.share()).isNull();
  }

  @Test
  void internalToken_withoutOnBehalfOf_isNotMarked() throws Exception {
    Marks m = runFilter(r -> r.addHeader("Authorization", "Internal " + INTERNAL_TOKEN));
    assertThat(m.auth()).isNull();
    assertThat(m.ai()).isNull();
  }

  @Test
  void wrongInternalToken_isNotMarked() throws Exception {
    Marks m = runFilter(r -> internalOnBehalf(r, "wrong-internal-token"));
    assertThat(m.auth()).isNull();
    assertThat(m.ai()).isNull();
  }

  @Test
  void internalOnBehalf_isMarkedAsAi() throws Exception {
    Marks m = runFilter(r -> internalOnBehalf(r, INTERNAL_TOKEN));
    assertThat(m.auth()).isNotNull();
    assertThat(m.ai()).isEqualTo(Boolean.TRUE);
    assertThat(m.share()).isNull();
  }

  @Test
  void internalOnBehalf_purposeNone_isNotMarked() throws Exception {
    Marks m =
        runFilter(
            r -> {
              internalOnBehalf(r, INTERNAL_TOKEN);
              r.addHeader(InternalCallHeaders.AI_PURPOSE, "NONE");
            });
    assertThat(m.auth()).as("대행 인증은 그대로 성립한다 — 표시만 빠진다").isNotNull();
    assertThat(m.ai()).isNull();
    assertThat(m.share()).isNull();
  }

  @Test
  void internalOnBehalf_purposeShare_isMarkedAiAndShare() throws Exception {
    Marks m =
        runFilter(
            r -> {
              internalOnBehalf(r, INTERNAL_TOKEN);
              r.addHeader(InternalCallHeaders.AI_PURPOSE, " share ");
            });
    assertThat(m.ai()).isEqualTo(Boolean.TRUE);
    assertThat(m.share()).isEqualTo(Boolean.TRUE);
  }

  @Test
  void internalOnBehalf_unknownPurpose_failsClosedToAi() throws Exception {
    Marks m =
        runFilter(
            r -> {
              internalOnBehalf(r, INTERNAL_TOKEN);
              r.addHeader(InternalCallHeaders.AI_PURPOSE, "graph-viewer");
            });
    assertThat(m.ai()).as("모르는 목적은 AI 판정에서 빠지지 않는다(fail-closed)").isEqualTo(Boolean.TRUE);
    assertThat(m.share()).isNull();
  }
}
