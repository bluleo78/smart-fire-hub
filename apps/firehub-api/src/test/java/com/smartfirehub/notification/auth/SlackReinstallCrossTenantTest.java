package com.smartfirehub.notification.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.auth.exception.SlackWorkspaceInstallDeniedException;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>다른 테넌트가 이미 설치한 Slack 팀을 (재)설치할 때</b>의 실패 표면을 고정한다 (P2-g Task 5).
 *
 * <p><b>경로가 반직관적이라 한 번 오진한 적이 있다.</b> {@code slack_workspace.team_id} 유니크는
 * 전역이고 {@code upsertFromOAuth} 는 {@code ON CONFLICT (team_id) DO UPDATE} 다. 충돌 판정은 유니크
 * 인덱스가 하는데 <b>인덱스는 RLS 와 무관하게 모든 행을 본다</b> → 남의 테넌트 행과 충돌해 DO UPDATE
 * 로 내려가고, 정책이 그 행을 보여 주지 않아 {@code 42501} 로 죽는다. <b>{@code 23505} 는 이 경로에서
 * 발생하지 않는다</b> — 그것을 기대하는 단언·핸들러는 영원히 발화하지 않는다.
 *
 * <p>두 층을 본다. (1) 리포지토리 층 — 실제로 던져지는 예외 클래스와 SQLState 를 못 박는다(다음
 * 사람이 예외 타입으로 잡으려다 죽은 코드를 쓰지 않도록). (2) HTTP 층 — OAuth 콜백이 500 이 아니라
 * 일반 409 로 응답하고, <b>응답 바디가 다른 테넌트의 존재를 시사하지 않는지</b> 단언한다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 쓰지 않는다.</b> 픽스처만
 * {@code inTenantFixture} 로 감싸고 검증 대상 호출은 밖에 둔다 — 안에 넣으면 리포지토리가 테스트
 * 트랜잭션에 합류해 거부가 rollback-only 로 바뀌고, 프로덕션이 스스로 컨텍스트를 세우는지도 가려진다.
 */
@AutoConfigureMockMvc
class SlackReinstallCrossTenantTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private SlackWorkspaceRepository slackWorkspaceRepo;
  @Autowired private OAuthStateService oAuthStateService;
  @Autowired private SlackOAuthService slackOAuthService;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  /** Slack 서버 왕복은 이 테스트의 관심사가 아니다 — 토큰 교환 성공만 흉내 낸다. */
  @MockitoBean private SlackApiClient slackApiClient;

  private long tenantA;
  private long tenantB;
  private Long userA;
  private Long userB;
  private String teamId;

  @BeforeEach
  void createScratchFixtures() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "slack-xtenant-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "slack-xtenant-b");
    userA = TenantRlsTestSupport.insertUser(dsl, "slackxta");
    userB = TenantRlsTestSupport.insertUser(dsl, "slackxtb");
    TenantRlsTestSupport.insertActiveMembership(dsl, userA, tenantA);
    TenantRlsTestSupport.insertActiveMembership(dsl, userB, tenantB);
    // team_id 는 전역 유니크이고 테스트 DB 는 다른 워크트리와 공유하므로 고유 접미사가 필요하다.
    teamId = "T-XTENANT-" + TenantRlsTestSupport.nextTenantId();
  }

  @AfterEach
  void cleanupScratchFixtures() {
    // 본인이 만든 행만 지운다. 기본 테넌트(1)는 절대 건드리지 않는다 — 공유 테스트 DB 다.
    inTenantFixture(tenantA, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantA));
    inTenantFixture(tenantB, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantB));
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantRlsTestSupport.deleteMembership(dsl, userA);
    TenantRlsTestSupport.deleteMembership(dsl, userB);
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteUser(dsl, userB);
    TenantContext.clear();
  }

  /** 테넌트 A 가 이미 그 팀을 설치해 둔 상태를 만든다. {@code tenant_id} 는 GUC 파생 DEFAULT 에 맡긴다. */
  private void installForTenantA() {
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "insert into slack_workspace"
                    + " (team_id, team_name, bot_user_id, bot_token_enc, signing_secret_enc,"
                    + " installed_by_user_id)"
                    + " values (?, 'A사 워크스페이스', 'B-A', 'enc-bot', 'enc-sign', ?)",
                teamId,
                userA));
  }

  /**
   * 리포지토리 층 — 실제 예외 클래스와 SQLState 를 못 박는다.
   *
   * <p>Spring 은 이 실패를 {@code BadSqlGrammarException} 으로 감싼다(클래스 코드 42 를 문법 오류로
   * 뭉뚱그리는 SQLState 번역기 때문). {@code PermissionDeniedDataAccessException} 이 아니다 — 그것을
   * 잡는 핸들러는 죽은 코드가 된다. 그래서 프로덕션 코드는 예외 클래스가 아니라 근본 원인의
   * SQLState {@code 42501} 로 판정하며, 이 단언이 그 전제를 지킨다.
   */
  @Test
  void upsertFromOAuth_teamOwnedByAnotherTenant_failsWithSqlState42501() {
    installForTenantA();

    // 검증 대상 호출은 픽스처 트랜잭션 밖 — 리포지토리가 스스로 트랜잭션·GUC 를 세워야 한다.
    TenantContext.set(tenantB);
    Throwable thrown =
        catchThrowable(
            () -> slackWorkspaceRepo.upsertFromOAuth(teamId, "B사", "B-B", "enc-bot-b", userB));

    assertThat(thrown)
        .as("다른 테넌트 소유 팀의 upsert 는 RLS 에 막혀야 한다")
        .isInstanceOf(DataAccessException.class);
    assertThat(thrown.getClass().getName())
        .as("실측된 래핑 클래스 — 바뀌면 SQLState 판정 전제를 다시 검토하라")
        .isEqualTo("org.springframework.jdbc.BadSqlGrammarException");

    Throwable root = ((DataAccessException) thrown).getMostSpecificCause();
    assertThat(root).isInstanceOf(SQLException.class);
    assertThat(((SQLException) root).getSQLState())
        .as("23505(중복키)가 아니라 42501(insufficient_privilege)이어야 한다")
        .isEqualTo("42501");
  }

  /** 서비스 층 — 위 거부가 전용 예외로 변환된다(전역 42501 을 통째로 잡지 않는다는 경계의 앞면). */
  @Test
  void completeAuthorization_teamOwnedByAnotherTenant_throwsInstallDenied() {
    installForTenantA();
    stubSlackOAuthExchange();

    TenantContext.set(tenantB);
    Throwable thrown =
        catchThrowable(() -> slackOAuthService.completeAuthorization("code-b", userB));

    assertThat(thrown).isInstanceOf(SlackWorkspaceInstallDeniedException.class);
    // T4 의 배선 결함 계열과 섞이면 안 된다 — 그쪽은 IllegalStateException 이다.
    assertThat(thrown).isNotInstanceOf(IllegalStateException.class);
  }

  /**
   * HTTP 층 — 콜백이 500 이 아니라 일반 409 로 응답하고, 응답이 <b>오라클이 아니다</b>.
   *
   * <p>음성 단언이 이 태스크의 보안 산출물이다. 팀 ID 는 워크스페이스 관리자면 누구나 알므로, 응답이
   * "이미 다른 테넌트가 설치했다"를 시사하는 순간 남의 테넌트 설치 여부를 조회하는 오라클이 된다.
   */
  @Test
  void callback_teamOwnedByAnotherTenant_returns409WithoutLeakingOtherTenant() throws Exception {
    installForTenantA();
    stubSlackOAuthExchange();

    // state 는 테넌트 B 컨텍스트에서 발급해야 B 를 싣는다(V106 [R7]).
    TenantContext.set(tenantB);
    String state = oAuthStateService.issue(userB, ChannelType.SLACK);
    TenantContext.clear(); // 콜백은 permitAll — 실제로 컨텍스트 없이 도착한다.

    var result =
        mockMvc
            .perform(get("/api/v1/oauth/slack/callback").param("code", "code-b").param("state", state))
            .andReturn();

    assertThat(result.getResponse().getStatus())
        .as("RLS 거부는 500 이 아니라 4xx 로 분류돼야 한다")
        .isEqualTo(409);

    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    // 먼저 양성 다리 — 일반 문구가 실제로 렌더링돼야 아래 음성 단언이 공허하지 않다.
    // (바디가 비면 doesNotContain 아홉 개가 전부 통과해 가드가 조용히 죽는다.)
    assertThat(body)
        .as("일반 문구가 담긴 ErrorResponse 가 실제로 내려가야 한다")
        .contains("Slack 워크스페이스를 설치할 수 없습니다.");
    // 다른 테넌트의 존재를 시사하는 어떤 흔적도 응답에 없어야 한다.
    assertThat(body)
        .as("응답이 다른 테넌트의 설치 여부를 알려 주는 오라클이 되면 안 된다")
        .doesNotContain(teamId)
        .doesNotContain(String.valueOf(tenantA))
        .doesNotContain("이미 설치")
        .doesNotContain("다른 테넌트")
        .doesNotContain("slack_workspace")
        .doesNotContain("42501")
        .doesNotContain("permission denied")
        .doesNotContain("row-level security")
        .doesNotContain("policy");
  }

  /** Slack 토큰 교환 성공 응답 스텁 — 팀 ID 는 A 가 이미 설치한 그 팀이다. */
  private void stubSlackOAuthExchange() {
    try {
      var json =
          objectMapper.readTree(
              "{\"ok\":true,\"access_token\":\"xoxb-test\",\"bot_user_id\":\"B-B\","
                  + "\"team\":{\"id\":\""
                  + teamId
                  + "\",\"name\":\"B사 워크스페이스\"}}");
      when(slackApiClient.oauthV2Access(anyString(), anyString(), anyString(), anyString()))
          .thenReturn(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
