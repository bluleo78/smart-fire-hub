package com.smartfirehub.notification.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfirehub.ai.repository.AiSessionRepository;
import com.smartfirehub.ai.service.AiAgentBatchClient;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.channels.SlackChannel;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.SlackWorkspaceTenantResolver;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Slack inbound 이벤트 처리 서비스.
 *
 * <p>SlackEventsController가 3초 내 HTTP 200 ack를 반환한 후, {@code @Async("slackInboundExecutor")} 스레드
 * 풀에서 실제 처리를 수행한다.
 *
 * <p>처리 흐름:
 *
 * <ol>
 *   <li>워크스페이스 조회 + 봇 토큰 복호화
 *   <li>reactions.add(:eyes:) — 처리 중 표시 (실패 무시)
 *   <li>user 매핑 — findByExternalId로 Smart Fire Hub 사용자 특정
 *   <li>ai_session lookup/create — 동일 스레드 재진입 시 기존 세션 재사용
 *   <li>ai-agent chat 호출 (60초 blocking)
 *   <li>SlackChannel.replyTo — 동일 스레드에 AI 응답 전송
 * </ol>
 *
 * <p><b>테넌트 해석 — 이 경로의 닭과 달걀.</b> inbound 웹훅은 permitAll 이라 {@link TenantContext}
 * 가 없는데, 테넌트를 알려 줄 유일한 행({@code slack_workspace})이 RLS 대상이다. 그래서
 * {@link #dispatch} 가 가장 먼저 {@link SlackWorkspaceTenantResolver} (V106 의 SECURITY DEFINER
 * 함수)로 {@code team_id → tenant_id} 를 해석하고, 그 컨텍스트 안에서 기존 조회를 전부 수행한다.
 * 봇 토큰은 우회 함수가 아니라 <b>RLS 하의 정상 조회</b>로 읽는다. 해석에 실패하면 이벤트를
 * <b>조용히 버린다</b> — 외부에 오류를 노출하면 어떤 팀 ID 가 등록돼 있는지가 누출된다.
 *
 * <p><b>{@code @Async("slackInboundExecutor")} 가 가리키는 빈은 존재하지 않는다</b>(커밋
 * {@code 968a28c2} 에서 삭제). 즉 이 경로는 지금 죽어 있다. P2-f 는 그 빈을 되살리지 않는다 —
 * 복원은 테넌시 작업이 아니라 기능 복구다. <b>복원할 때의 계약을 여기 남긴다:</b>
 *
 * <ul>
 *   <li>테넌트 해석은 <b>이미 여기 배선돼 있다</b>. 빈을 되살리는 커밋이 함께 넣어야 할 것은 없다
 *       — {@code ai/repository/AiSessionRepository} 의 경고("복원과 같은 커밋에서 웹훅 테넌트
 *       해석을 넣어라")가 요구하던 것이 바로 이 배선이며, P2-f Task 5 가 미리 갚아 두었다.
 *   <li>{@code TenantContextTaskDecorator} 는 <b>이 경로의 안전 조건이 아니다.</b> 제출 스레드가
 *       permitAll 컨트롤러라 승계할 컨텍스트가 애초에 없기 때문이다 — 데코레이터를 달아도 전파할
 *       값이 없다. 안전을 만드는 것은 {@code dispatch} 안의 해석이다. 다른 실행기와 형태를 맞추기
 *       위해 데코레이터를 다는 것은 무해하지만, 그것을 이 경로의 방어로 오해하지 말 것.
 *   <li>해석 없이 본 처리에 진입하지 못하도록 {@link #process} 첫 문장의 가드가
 *       <b>기계적으로</b> 막는다. 가드는 {@link TenantContext#require(String)} 로 이 지점 전용 문맥
 *       설명을 넘겨 쓴다 — 자세한 내용은 {@link #process} 에 한 곳에만 서술한다.
 * </ul>
 */
@Service
public class SlackInboundService {

  private static final Logger log = LoggerFactory.getLogger(SlackInboundService.class);

  private final UserChannelBindingRepository bindingRepo;
  private final SlackWorkspaceRepository workspaceRepo;
  /** permitAll 웹훅의 테넌트 해석기 — team_id 로 컨텍스트를 세우는 유일한 수단이다. */
  private final SlackWorkspaceTenantResolver tenantResolver;
  private final AiSessionRepository aiSessionRepo;
  private final AiAgentBatchClient aiAgentClient;
  private final SlackApiClient slackApiClient;
  private final SlackChannel slackChannel;
  private final EncryptionService encryption;
  private final SlackInboundMetrics metrics;

  /** 화이트라벨링용 브랜드명. 미연동 사용자 안내 메시지에 사용한다. 배포처별 APP_BRANDING_NAME으로 주입. */
  @Value("${app.branding.name:Smart Fire Hub}")
  private String brandName;

  public SlackInboundService(
      UserChannelBindingRepository bindingRepo,
      SlackWorkspaceRepository workspaceRepo,
      SlackWorkspaceTenantResolver tenantResolver,
      AiSessionRepository aiSessionRepo,
      AiAgentBatchClient aiAgentClient,
      SlackApiClient slackApiClient,
      SlackChannel slackChannel,
      EncryptionService encryption,
      SlackInboundMetrics metrics) {
    this.bindingRepo = bindingRepo;
    this.workspaceRepo = workspaceRepo;
    this.tenantResolver = tenantResolver;
    this.aiSessionRepo = aiSessionRepo;
    this.aiAgentClient = aiAgentClient;
    this.slackApiClient = slackApiClient;
    this.slackChannel = slackChannel;
    this.encryption = encryption;
    this.metrics = metrics;
  }

  /**
   * Slack event 처리 엔트리포인트. 컨트롤러 ack 직후 비동기 실행.
   *
   * @param teamId Slack team_id (워크스페이스 식별)
   * @param event event_callback.event 노드 (type, channel, user, text, ts 포함)
   */
  @Async("slackInboundExecutor")
  public void dispatch(String teamId, JsonNode event) {
    String ts = event.path("ts").asText();

    // 메트릭: 수신 카운트 증가 + 처리 시간 측정 시작
    metrics.incrementReceived();
    long startNanos = System.nanoTime();

    // MDC에 correlationId 설정하여 로그 추적성 확보
    MDC.put("correlationId", "slack-" + teamId + "-" + ts);
    try {
      // 0. 테넌트 해석 — 다른 무엇보다 먼저다. 이 경로에는 컨텍스트가 없어서, 해석 전에는
      //    slack_workspace 조회가 RLS 아래 조용히 0행이 된다(에러가 아니라 무동작이라 더 나쁘다).
      var ref = tenantResolver.resolveByTeamId(teamId).orElse(null);
      if (ref == null) {
        // 조용히 버린다. 404/400 같은 구분되는 응답을 내면 어떤 team_id 가 등록돼 있는지를
        // 외부에서 탐색할 수 있게 된다(V106 의 "해지된 워크스페이스는 테넌트조차 알려주지 않는다"와
        // 같은 판단). 컨트롤러는 이미 200 ack 를 돌려준 뒤이므로 외부 계약도 바뀌지 않는다.
        metrics.incrementTenantUnresolved();
        log.warn("slack inbound — 테넌트 해석 실패, 이벤트 폐기 (team={})", teamId);
        return;
      }
      // 해석된 테넌트 컨텍스트 안에서 나머지 전부를 수행한다. 봇 토큰을 포함한 실제 데이터는
      // 우회 함수가 아니라 여기서 정상 RLS 조회로 읽는다.
      TenantContext.runScoped(ref.tenantId(), () -> process(teamId, event));
    } catch (MissingTenantScopeException e) {
      // 배선 결함이지 운영 오류가 아니다. 아래 일반 catch 에 뭉뚱그리면 "예상치 못한 오류" 로 찍혀
      // 운영자가 엉뚱한 곳을 뒤진다 — 원인 추적 불가를 막으려던 가드가 오히려 오도하게 된다.
      log.error(
          "slack inbound — 테넌트 스코프 없이 본 처리에 진입했다. dispatch 의 해석·runScoped 배선이"
              + " 깨졌다는 뜻이다 (team={}, ts={})",
          teamId,
          ts,
          e);
    } catch (Exception e) {
      log.error("slack inbound dispatch 예상치 못한 오류 (team={}, ts={})", teamId, ts, e);
    } finally {
      metrics.recordProcessingDuration(java.time.Duration.ofNanos(System.nanoTime() - startNanos));
      MDC.remove("correlationId");
    }
  }

  /**
   * 이벤트 본 처리. <b>반드시 테넌트 컨텍스트 안에서 호출되어야 한다.</b>
   *
   * <p>첫 문장의 {@link TenantContext#require(String)} 이 그것을 기계적으로 강제한다 — 여기서 도는
   * 모든 조회·삽입({@code slack_workspace}, {@code user_channel_binding}, {@code ai_session})이 RLS
   * 대상이라, 컨텍스트가 비면 예외가 아니라 <b>조용한 0행</b>이 되어 원인 추적이 불가능해진다.
   * 주석 대신 실행되는 가드를 두는 이유는 "경로가 죽어 있어 안전하다"는 상태에 기대지 않기
   * 위해서다.
   *
   * <p>{@code private} 이 아니라 <b>package-private</b> 인 것은 의도다 — 같은 패키지의
   * {@code SlackInboundServiceTest} 가 "컨텍스트 없이 진입하면 예외" 를 직접 단언해 이 가드에
   * 회귀 보호를 준다. 그 단언이 없으면 가드를 지워도 아무것도 빨개지지 않고, 나중에 빈이 복원되는
   * 순간 {@code ai_session} 삽입이 조용히 죽는다.
   *
   * <p>무인자 {@link TenantContext#require()} 를 쓰지 않는 이유: 그 메서드의 메시지가
   * "배경 잡을 예약할 수 없다" 라 이 지점(웹훅 본 처리)과 맞지 않는다. 가드가 실제로 발화한 날
   * 운영자가 JobRunr 를 뒤지게 만들지 않으려고, 이 지점 전용 문맥 설명을 담은
   * {@link TenantContext#require(String)} 오버로드를 쓴다.
   */
  void process(String teamId, JsonNode event) {
    TenantContext.require("Slack inbound 본 처리 (team=" + teamId + ")");

    String channel = event.path("channel").asText();
    String slackUserId = event.path("user").asText();
    String text = event.path("text").asText();
    String ts = event.path("ts").asText();
    // thread_ts가 없으면 이 메시지가 스레드 루트 — ts를 그대로 사용
    String threadTs = event.path("thread_ts").asText(ts);

    // 1. 워크스페이스 조회 + 봇 토큰 복호화
    //    해석기가 이미 revoked_at IS NULL 을 걸렀지만 이 조회를 생략하지 않는다 — 해석과 조회
    //    사이에 revoke 가 끼어들 수 있고, 봇 토큰은 RLS 하 정상 경로로만 읽는다는 원칙 때문이다.
    var workspaceOpt = workspaceRepo.findByTeamId(teamId);
    if (workspaceOpt.isEmpty()) {
      log.warn("slack inbound — unknown workspace {}", teamId);
      return;
    }
    var workspace = workspaceOpt.get();
    String botToken = encryption.decrypt(workspace.botTokenEnc());

    // 2. reactions.add(:eyes:) — 처리 중임을 사용자에게 시각적으로 알림
    // 실패해도 핵심 흐름에 영향을 주지 않으므로 예외 무시
    try {
      slackApiClient.reactionsAdd(botToken, channel, ts, "eyes");
    } catch (Exception e) {
      log.warn("slack inbound — reactions.add 실패, 계속 진행 (team={}, ts={})", teamId, ts, e);
    }

    // 3. user 매핑 — Slack userId → Smart Fire Hub userId
    var binding = bindingRepo.findByExternalId(teamId, slackUserId);
    if (binding.isEmpty()) {
      // 연동되지 않은 사용자 → ephemeral 안내 메시지
      metrics.incrementUnmappedUser();
      log.info("slack inbound — unmapped user {}/{}", teamId, slackUserId);
      slackApiClient.postEphemeral(
          botToken,
          channel,
          slackUserId,
          brandName + " 웹에서 먼저 계정 연동을 진행해주세요: "
              + "https://app.smartfirehub.com/settings/channels");
      return;
    }
    long userId = binding.get().userId();

    // 4. ai_session lookup/create — 동일 스레드의 후속 메시지는 기존 세션 재사용
    var existing = aiSessionRepo.findBySlackContext(teamId, channel, threadTs);
    String agentSessionId;
    if (existing.isPresent()) {
      // sessionId 필드: AiSessionResponse.sessionId() — ai-agent 발급 외부 세션 ID
      agentSessionId = existing.get().sessionId();
      log.debug(
          "slack inbound — 기존 세션 재사용 (sessionId={}, threadTs={})", agentSessionId, threadTs);
    } else {
      // 새 스레드 시작 — ai-agent에 세션 생성 후 DB에 기록
      agentSessionId = aiAgentClient.createSession(userId, "Slack " + threadTs);
      aiSessionRepo.createSlackSession(
          userId, agentSessionId, teamId, channel, threadTs, "Slack 대화");
      log.debug("slack inbound — 새 세션 생성 (sessionId={}, threadTs={})", agentSessionId, threadTs);
    }

    // 5. AI 호출 (최대 60초 blocking)
    String aiResponse;
    try {
      aiResponse = aiAgentClient.chat(agentSessionId, userId, text);
    } catch (Exception e) {
      log.error("slack inbound — AI chat 실패 (team={}, ts={})", teamId, ts, e);
      // 오류 reaction 추가 후 ephemeral 안내
      try {
        slackApiClient.reactionsAdd(botToken, channel, ts, "warning");
      } catch (Exception ignore) {
        /* best-effort, 실패 무시 */
      }
      slackApiClient.postEphemeral(
          botToken, channel, slackUserId, "AI 응답 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
      return;
    }

    if (aiResponse == null || aiResponse.isBlank()) {
      log.warn("slack inbound — AI 응답 비어있음 (team={}, sessionId={})", teamId, agentSessionId);
      slackApiClient.postEphemeral(botToken, channel, slackUserId, "AI 응답을 생성하지 못했습니다.");
      return;
    }

    // 6. 동일 스레드에 AI 응답 전송
    slackChannel.replyTo(workspace.id(), channel, threadTs, aiResponse);
    log.info("slack inbound — 응답 완료 (team={}, ts={}, sessionId={})", teamId, ts, agentSessionId);
  }
}
