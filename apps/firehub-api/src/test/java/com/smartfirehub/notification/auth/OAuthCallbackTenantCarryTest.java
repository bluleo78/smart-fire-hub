package com.smartfirehub.notification.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.auth.controller.KakaoOAuthController;
import com.smartfirehub.notification.auth.controller.SlackOAuthController;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * permitAll OAuth 콜백 2종이 <b>테넌트를 실제로 운반·복원하는가</b>를 검증한다(P2-f Task 5).
 *
 * <p>RLS 정책은 아직 꺼져 있으므로(Task 6) 격리로는 아무것도 증명되지 않는다. 그래서 여기서는
 * <b>값 자체</b>를 단언한다 — 콜백 안에서 보이는 {@link TenantContext} 값과, 그 안에서 삽입된
 * 행의 {@code tenant_id} 다.
 *
 * <p><b>판별력의 핵심은 컨텍스트를 비우는 것이다.</b> {@code IntegrationTestBase} 가 기본
 * 테넌트(1)를 세워 두므로, 그대로 콜백을 부르면 배선이 하나도 없어도 삽입이 성공한다. 실제 콜백은
 * Bearer 헤더 없이 도착하므로 그 조건을 재현해야 한다 — 검증 대상 호출 직전에
 * {@code TenantContext.clear()} 를 부르고, 테스트 트랜잭션으로 감싸지 않는다.
 *
 * <p>외부 OAuth 서비스 호출(Slack {@code oauth.v2.access}, Kakao 토큰 교환)만 mock 으로 대체하고
 * <b>테넌시 경로는 전부 실제</b>다 — 두 mock 모두 진짜 리포지토리로 upsert 를 수행해, 콜백이 연
 * 컨텍스트 아래에서 GUC 파생 DEFAULT 가 찍히는지까지 본다.
 *
 * <p><b>왜 {@code @MockitoBean} 이 아니라 컨트롤러를 손으로 조립하는가.</b> {@code @MockitoBean} 은
 * 이 클래스만의 Spring 컨텍스트 변종을 만든다 — 컨텍스트 캐시를 한 칸 더 쓰고, 이 클래스만 자기
 * 컨텍스트를 새로 띄운다. 컨트롤러는 생성자 주입만 하는 평범한 클래스라 직접 조립해도 검증
 * 대상(콜백 안의 {@code runScopedGet})은 그대로이고, {@code OAuthStateService}·리포지토리는 실제
 * 빈을 주입하므로 GUC 경로도 전부 실제다. 공유 컨텍스트를 재사용하게 되어 클래스 실행이
 * 0.74s → 0.18s 로 줄었다.
 *
 * <p>대신 이 조립은 <b>AOP 를 거치지 않는다</b>. 지금 두 컨트롤러 모두 프록시가 필요한 애노테이션이
 * 없어 무해하지만, 컨트롤러에 {@code @Transactional} 같은 것이 붙는 날에는 이 테스트가 그것을
 * 보지 못한다.
 */
class OAuthCallbackTenantCarryTest extends IntegrationTestBase {

  @Autowired private OAuthStateService stateService;
  @Autowired private SlackWorkspaceRepository workspaceRepo;
  @Autowired private UserChannelBindingRepository bindingRepo;
  @Autowired private DSLContext dsl;

  /** 외부 HTTP 교환만 대체한다. 테넌시 배선은 대체하지 않는다. */
  private SlackOAuthService slackOAuthService;

  private KakaoOAuthService kakaoOAuthService;

  private SlackOAuthController slackController;
  private KakaoOAuthController kakaoController;

  private long tenantId;
  private Long userId;
  private String teamId;

  @BeforeEach
  void createScratchFixtures() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "oauth-carry");
    userId = TenantRlsTestSupport.insertUser(dsl, "oauthcarry");
    teamId = "T-CARRY-" + TenantRlsTestSupport.nextTenantId();
    TenantContext.set(tenantId);

    // 컨트롤러를 손으로 조립한다(위 클래스 javadoc 참조). state 서비스는 실제 빈이다.
    slackOAuthService = mock(SlackOAuthService.class);
    kakaoOAuthService = mock(KakaoOAuthService.class);
    slackController = new SlackOAuthController(slackOAuthService, stateService);
    kakaoController = new KakaoOAuthController(kakaoOAuthService, stateService);
  }

  @AfterEach
  void cleanupScratchFixtures() {
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantId));
    // oauth_state 는 deleteTenants 가 이 테넌트 범위로 좁혀 함께 지운다.
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  /** 인증된 {@code /auth-url} 경로를 흉내 내 state 를 발급한다 — 이때의 테넌트가 운반될 값이다. */
  private String issueStateAs(ChannelType channelType) {
    return TenantContext.runScopedGet(tenantId, () -> stateService.issue(userId, channelType));
  }

  @Test
  void slackCallback_restoresTenantFromStateAndStampsInsertedRow() {
    String state = issueStateAs(ChannelType.SLACK);

    // 콜백 안에서 실제로 보이는 테넌트를 포착하면서, 진짜 리포지토리로 워크스페이스를 심는다.
    AtomicReference<Long> seenTenant = new AtomicReference<>();
    when(slackOAuthService.completeAuthorization(anyString(), anyLong()))
        .thenAnswer(
            inv -> {
              seenTenant.set(TenantContext.get());
              workspaceRepo.upsertFromOAuth(teamId, "운반 테스트", "B-CARRY", "enc-bot", userId);
              return null;
            });

    // permitAll 콜백의 실제 조건: 컨텍스트 없음.
    TenantContext.clear();
    var response = slackController.callback("auth-code", state);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(seenTenant.get()).as("콜백은 state 가 실어 온 테넌트로 스코프를 열어야 한다").isEqualTo(tenantId);
    assertThat(TenantContext.get()).as("콜백이 끝나면 진입 전 상태로 복원돼야 한다").isNull();

    Long stamped =
        inTenantFixture(
            tenantId,
            () ->
                (Long)
                    dsl.fetchValue(
                        "select tenant_id from slack_workspace where team_id = ?", teamId));
    assertThat(stamped).as("콜백이 삽입한 워크스페이스에 그 테넌트가 찍혀야 한다").isEqualTo(tenantId);
  }

  @Test
  void kakaoCallback_restoresTenantFromStateAndStampsInsertedRow() {
    String state = issueStateAs(ChannelType.KAKAO);

    // Slack 쪽과 같은 강도로 본다 — Kakao 는 대안 식별자가 없어 이 운반이 유일한 해법이므로
    // 컨텍스트 값만 보고 넘어가면 정작 가장 취약한 경로의 쓰기가 검증되지 않는다.
    AtomicReference<Long> seenTenant = new AtomicReference<>();
    org.mockito.Mockito.doAnswer(
            inv -> {
              seenTenant.set(TenantContext.get());
              bindingRepo.upsert(
                  new UserChannelBinding(
                      null,
                      userId,
                      ChannelType.KAKAO,
                      null,
                      "kakao-" + userId,
                      "운반 테스트",
                      "enc-access",
                      "enc-refresh",
                      null,
                      "ACTIVE",
                      null,
                      java.time.Instant.now(),
                      java.time.Instant.now()));
              return null;
            })
        .when(kakaoOAuthService)
        .completeAuthorization(anyLong(), anyString());

    TenantContext.clear();
    var response = kakaoController.callback("auth-code", state);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(seenTenant.get()).isEqualTo(tenantId);
    assertThat(TenantContext.get()).isNull();

    Long stamped =
        inTenantFixture(
            tenantId,
            () ->
                (Long)
                    dsl.fetchValue(
                        "select tenant_id from user_channel_binding where user_id = ?", userId));
    assertThat(stamped).as("콜백이 삽입한 바인딩에 그 테넌트가 찍혀야 한다").isEqualTo(tenantId);
  }

  /**
   * fail-closed — state 를 되찾지 못하면 400 이고 <b>쓰기가 일어나지 않는다.</b>
   *
   * <p>조용히 기본 테넌트(1)로 떨어지지 않는지를 본다. 이 경로가 열려 있으면 남의 테넌트에 워크스페이스
   * 가 심어진다.
   */
  @Test
  void slackCallback_unknownState_failsClosedWithoutWriting() {
    TenantContext.clear();

    var response = slackController.callback("auth-code", "존재하지-않는-state");

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    verify(slackOAuthService, never()).completeAuthorization(anyString(), anyLong());
  }

  /** 채널 타입이 어긋난 state 도 같은 fail-closed 경로다 — 소비는 되지만 진행하지 않는다. */
  @Test
  void slackCallback_wrongChannelTypeState_failsClosedWithoutWriting() {
    String kakaoState = issueStateAs(ChannelType.KAKAO);

    TenantContext.clear();
    var response = slackController.callback("auth-code", kakaoState);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    verify(slackOAuthService, never()).completeAuthorization(anyString(), anyLong());
  }

  /**
   * 운반 자체의 단위 검증 — 컨텍스트 없이 {@code consume} 해도 발급 시점의 테넌트가 돌아온다.
   *
   * <p>이것이 성립하는 이유는 {@code oauth_state} 에 RLS 정책이 <b>없기</b> 때문이다(V106 [R7]).
   * 누군가 "일관성" 을 이유로 이 테이블에 정책을 걸면 이 테스트가 먼저 깨진다.
   */
  @Test
  void consume_returnsIssuingTenant_withoutAnyContext() {
    String state = issueStateAs(ChannelType.SLACK);

    TenantContext.clear();
    var consumed = stateService.consume(state);

    assertThat(consumed).isPresent();
    assertThat(consumed.get().tenantId()).isEqualTo(tenantId);
    assertThat(consumed.get().userId()).isEqualTo(userId);
    // single-use — 두 번째 소비는 empty(콜백 재생 방어).
    assertThat(stateService.consume(state)).isEmpty();
  }
}
