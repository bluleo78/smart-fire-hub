package com.smartfirehub.pipeline.service.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

@Slf4j
@Component
public class AiAgentClient {

  /**
   * ai-agent 호출 상한. <b>ai-agent 쪽 상한보다 반드시 커야 한다</b> — 저쪽이 먼저 끊어야 원인이
   * 담긴 메시지가 올라오고, 순서가 뒤집히면 원인 없는 타임아웃만 남는다. 근거와 값의 유래는
   * {@code classification-service.ts} 의 {@code classifyTimeoutMs} 에 있다(그쪽 천장 300초 + 여유).
   *
   * <p>아래에서 {@code HttpClient.responseTimeout} 과 {@code Mono.timeout} <b>양쪽</b>에 쓰인다 —
   * 한쪽만 고치면 효과가 없다. {@code classify()} 가 이 WebClient 의 유일한 소비자라,
   * 이 값을 올려도 다른 ai-agent 호출의 예산은 넓어지지 않는다.
   */
  private static final Duration TIMEOUT = Duration.ofSeconds(330);

  private final WebClient webClient;
  private final ObjectMapper objectMapper;
  private final SettingsService settingsService;
  private final AiCredentialService aiCredentialService;

  @Value("${agent.internal-token}")
  private String internalToken;

  public AiAgentClient(
      @Value("${agent.url}") String agentUrl,
      ObjectMapper objectMapper,
      SettingsService settingsService,
      AiCredentialService aiCredentialService) {
    HttpClient httpClient = HttpClient.create().responseTimeout(TIMEOUT);
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    this.objectMapper = objectMapper;
    this.settingsService = settingsService;
    this.aiCredentialService = aiCredentialService;
  }

  public record ClassifyRequest(
      List<Map<String, Object>> rows,
      String prompt,
      List<Map<String, String>> outputColumns // [{name, type}]
      ) {}

  public record ClassifyRowResult(
      Map<String, Object> values // dynamic column values; includes source_id
      ) {}

  public record ClassifyResponse(List<ClassifyRowResult> results, int processed, String model) {}

  /**
   * 분류 요청 바디를 조립한다({@code classify()} 에서 추출 — Ruling #4). HTTP 호출과 분리해
   * 단위 테스트가 WireMock 없이 자격증명 주입 규칙을 검증할 수 있게 한다. 순수 리팩터링이라
   * {@code classify()} 의 외부 동작은 그대로다.
   *
   * <p><b>{@code classify()} 밖에서 호출돼야 한다.</b> {@link AiCredentialService#resolve()} 는
   * 알 수 없는 {@code agentType} 을 만나면 {@code UnknownAgentTypeException} 을 던진다(fail-closed).
   * 이 메서드를 {@code classify()} 의 {@code try} 블록 <b>안에서</b> 부르면 그 예외가 하단의
   * {@code catch (Exception e)} 에 잡혀 평범한 {@code RuntimeException}("AI agent classify request
   * failed")으로 뭉개진다 — 알 수 없는 유형이라는 신호가 사라지고, 다음 사람이 그 catch 안에서
   * 빈 자격증명으로 계속 진행하는 실수를 해도 테스트가 구분하지 못한다.
   *
   * <p><b>자격증명이 불완전하면 여기서 던진다({@link IllegalStateException}, #706).</b> AI 자격증명이
   * 테넌트 전용이 되면서 미설정 테넌트의 {@code resolve()} 는 빈 {@code sdk} 문서를 돌려준다 —
   * 그대로 ai-agent 에 넘기면 비밀 없는 요청이 ai-agent 컨테이너의 ambient 자격증명으로 조용히
   * 떨어질 여지가 생긴다(6b1c6383 의 모양). 이 메서드가 {@code classify()} 의 {@code try} 밖에서
   * 불리므로 HTTP 호출 전에 끝나고, 문구가 catch 에 뭉개지지 않은 채 그대로 올라간다.
   */
  public Map<String, Object> buildClassifyBody(ClassifyRequest request) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("rows", request.rows());
    body.put("prompt", request.prompt());
    body.put("outputColumns", request.outputColumns());
    // AI 동작 키는 테넌트 값 → 코드 기본값으로 항상 해석된다(SettingsService.getValue).
    body.put("model", settingsService.getValue("ai.model").orElseThrow());

    // 불완전(미설정 포함)이면 모델 검사보다 **먼저** 막는다 — 순서가 뒤집히면 providerId/baseUrl
    // 이 빈 opencode 테넌트에게 "모델을 다시 선택하세요"라는 엉뚱한 안내가 나간다.
    AiCredential credential = aiCredentialService.resolve().requireComplete();

    // 유형마다 실리는 키가 다르다 — 그 규칙은 AiCredential 의 유형별 applyTo() 하나에만 있다
    // (이슈 #695: 예전에는 여기·채팅·프로액티브에 각각 switch 가 있었고 이미 드리프트했다).
    // Opencode.apiKey 는 OpenAI 호환 키로 Sdk.apiKey(Anthropic)와 의미가 다르므로 "그냥 apiKey 를
    // 넘긴다"를 유형 전체에 일반화하면 안 된다 — applyTo() 가 유형별로 실제 쓰이는 필드만 담는다.
    credential.applyTo(body);

    // opencode 모델 형식 가드(전체 브랜치 리뷰 I3) — 위에서 "model" 에 넣은 기본값
    // AiBehaviorDefaults.MODEL(슬래시 없음)은 opencode 형식이 아니라, 가드 없이 그대로 보내면
    // OpenAI 호환 호스트가 이유를 알 수 없는 상류 오류로만 실패한다. 여기서 먼저 걸러 분명한
    // 설정 오류로 바꾼다 — 검사와 문구는 AiCredential.modelProblem 하나에서 온다(세 경로 공통).
    // 모델 제약이 없는 유형에서는 no-op 이라 여기서 유형을 따로 분기하지 않는다.
    credential.requireModelUsable((String) body.get("model"));
    return body;
  }

  /**
   * ai-agent 의 분류 엔드포인트를 호출한다.
   *
   * <p>자격증명(apiKey / oauthToken)과 모델은 여기서 관리자 설정(DB)에서 복호화해 요청 바디에 주입한다 —
   * "DB 에서 복호화해 바디로 넘긴다"는 골격은 AiAgentProxyService(채팅)와 같다. 이전에는 ai-agent 가
   * {@code /settings/ai-api-key} 를 역호출해 스스로 키를 가져왔는데, 그 엔드포인트는
   * {@code ai:settings}(ADMIN 전용) 권한을 요구하므로 비-ADMIN 사용자의 파이프라인이 조용히 실패했고 OAuth
   * 토큰은 아예 전달되지 않았다.
   */
  public ClassifyResponse classify(ClassifyRequest request, Long userId) {
    // buildClassifyBody() 는 try 밖에서 부른다 — 이유는 그 메서드 javadoc 참고
    // (UnknownAgentTypeException 이 아래 catch 에 삼켜지지 않게 하기 위함).
    Map<String, Object> body = buildClassifyBody(request);
    try {
      String responseBody =
          webClient
              .post()
              .uri("/agent/classify")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Authorization", "Internal " + internalToken)
              .header("X-On-Behalf-Of", String.valueOf(userId))
              .bodyValue(body)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(TIMEOUT)
              .block();

      Map<String, Object> responseMap =
          objectMapper.readValue(responseBody, new TypeReference<>() {});

      List<Map<String, Object>> rawResults =
          objectMapper.convertValue(responseMap.get("results"), new TypeReference<>() {});

      List<ClassifyRowResult> results = rawResults.stream().map(ClassifyRowResult::new).toList();

      int processed = responseMap.get("processed") instanceof Number n ? n.intValue() : 0;
      String model = (String) responseMap.getOrDefault("model", "unknown");

      return new ClassifyResponse(results, processed, model);

    } catch (WebClientResponseException e) {
      throw new RuntimeException(
          "AI agent classify failed with status "
              + e.getStatusCode()
              + ": "
              + e.getResponseBodyAsString(),
          e);
    } catch (Exception e) {
      throw new RuntimeException("AI agent classify request failed: " + e.getMessage(), e);
    }
  }
}
