package com.smartfirehub.notification.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfirehub.ai.repository.AiSessionRepository;
import com.smartfirehub.ai.service.AiAgentBatchClient;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.channels.SlackChannel;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
 */
@Service
public class SlackInboundService {

  private static final Logger log = LoggerFactory.getLogger(SlackInboundService.class);

  private final UserChannelBindingRepository bindingRepo;
  private final SlackWorkspaceRepository workspaceRepo;
  private final AiSessionRepository aiSessionRepo;
  private final AiAgentBatchClient aiAgentClient;
  private final SlackApiClient slackApiClient;
  private final SlackChannel slackChannel;
  private final EncryptionService encryption;
  private final SlackInboundMetrics metrics;

  public SlackInboundService(
      UserChannelBindingRepository bindingRepo,
      SlackWorkspaceRepository workspaceRepo,
      AiSessionRepository aiSessionRepo,
      AiAgentBatchClient aiAgentClient,
      SlackApiClient slackApiClient,
      SlackChannel slackChannel,
      EncryptionService encryption,
      SlackInboundMetrics metrics) {
    this.bindingRepo = bindingRepo;
    this.workspaceRepo = workspaceRepo;
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
    String channel = event.path("channel").asText();
    String slackUserId = event.path("user").asText();
    String text = event.path("text").asText();
    String ts = event.path("ts").asText();
    // thread_ts가 없으면 이 메시지가 스레드 루트 — ts를 그대로 사용
    String threadTs = event.path("thread_ts").asText(ts);

    // 메트릭: 수신 카운트 증가 + 처리 시간 측정 시작
    metrics.incrementReceived();
    long startNanos = System.nanoTime();

    // MDC에 correlationId 설정하여 로그 추적성 확보
    MDC.put("correlationId", "slack-" + teamId + "-" + ts);
    try {
      // 1. 워크스페이스 조회 + 봇 토큰 복호화
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
            "Smart Fire Hub 웹에서 먼저 계정 연동을 진행해주세요: "
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

    } catch (Exception e) {
      log.error("slack inbound dispatch 예상치 못한 오류 (team={}, ts={})", teamId, ts, e);
    } finally {
      metrics.recordProcessingDuration(java.time.Duration.ofNanos(System.nanoTime() - startNanos));
      MDC.remove("correlationId");
    }
  }
}
