package com.smartfirehub.graphreview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.global.security.DelegationHeaders;
import com.smartfirehub.global.tenant.TenantContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 검수 승인 시 Neo4j 소유자인 ai-agent에 그래프 변경을 위임한다(역방향 통신 — 기존 Internal 인증 재사용).
 * 실패는 예외로 전파해, 호출자(ReviewItemService)가 이 예외 시 DB 상태를 갱신하지 않도록 함으로써
 * "실패 시 pending 유지"를 트랜잭션 없이 보장한다. 실패는 두 종류로 나뉜다(#310):
 * 대상 노드 부재(ai-agent 409) → {@link IllegalStateException}(409 + 사유 노출),
 * 그 외 장애 → {@link ExternalServiceException}(502).
 */
@Component
public class GraphMutationClient {
  private static final Logger log = LoggerFactory.getLogger(GraphMutationClient.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final WebClient webClient;
  /** 409 응답 바디에서 사용자용 사유(message)를 뽑는 데만 쓴다. */
  private final ObjectMapper objectMapper = new ObjectMapper();

  public GraphMutationClient(
      @Value("${agent.url}") String agentUrl, @Value("${agent.internal-token}") String internalToken) {
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .defaultHeader("Authorization", "Internal " + internalToken)
            .build();
  }

  /**
   * ai-agent POST /agent/graph/merge-entities — 두 엔티티 노드를 Neo4j에서 동기 병합(동의어 승인).
   *
   * <p>datasetId는 ai-agent가 entityType 문자열→typeId 변환에 쓸 온톨로지를 고르는 데 필수다(#678,
   * "기본 온톨로지" 폴백 제거). null을 그대로 흘려보내면 ai-agent의 zod 스키마(z.number())가 400으로
   * 거부하므로, 호출측(ReviewItemService)이 null이면 이 메서드를 부르기 전에 막아야 한다.
   */
  public void mergeEntities(String entityType, String nameA, String nameB, Long datasetId) {
    Map<String, Object> body = new HashMap<>();
    body.put("entityType", entityType);
    body.put("nameA", nameA);
    body.put("nameB", nameB);
    body.put("datasetId", datasetId);
    postJson("/agent/graph/merge-entities", body, "엔티티 병합");
  }

  /**
   * ai-agent POST /agent/graph/set-property — 엔티티 노드 속성값을 정정 write(속성 정규화 승인).
   *
   * <p>datasetId는 다른 세 메서드와 같은 이유로 필수지만 쓰임이 다르다: 여기서는 typeId 변환이 아니라
   * <b>write 스코프</b>다. entityKey는 추측 가능한 문자열이고 Neo4j는 전 테넌트 공유 DB라, datasetId로
   * 해소한 온톨로지 술어가 없으면 남의 그래프 노드를 덮어쓸 수 있다.
   */
  public void setProperty(String entityKey, String propertyName, String dataType, String value,
      Long datasetId) {
    Map<String, Object> body = new HashMap<>();
    body.put("entityKey", entityKey);
    body.put("propertyName", propertyName);
    body.put("dataType", dataType);
    body.put("value", value);
    body.put("datasetId", datasetId);
    postJson("/agent/graph/set-property", body, "엔티티 속성 갱신");
  }

  /** add-entity 요청의 보류 관계 참조. */
  public record RelationRef(String relType, String direction, String otherKey) {}

  /**
   * ai-agent POST /agent/graph/add-entity — 승인된 저신뢰 엔티티를 Neo4j에 적재(as-extracted 타입/이름).
   * datasetId는 mergeEntities와 동일한 이유로 필수다(#678) — 호출측이 null이면 부르기 전에 막아야 한다.
   */
  public void addEntity(String entityType, String name, JsonNode properties,
      List<Long> sourceChunkIds, List<RelationRef> relations, Long datasetId) {
    Map<String, Object> body = new HashMap<>();
    body.put("entityType", entityType);
    body.put("name", name);
    if (properties != null && !properties.isNull()) body.put("properties", properties);
    body.put("sourceChunkIds", sourceChunkIds == null ? List.of() : sourceChunkIds);
    body.put("relations", relations == null ? List.of()
        : relations.stream().map(r -> Map.of("relType", r.relType(), "direction", r.direction(), "otherKey", r.otherKey())).toList());
    body.put("datasetId", datasetId);
    postJson("/agent/graph/add-entity", body, "엔티티 적재");
  }

  /**
   * ai-agent POST /agent/graph/add-relation — 승인된 저신뢰 관계를 Neo4j에 적재(양 끝점 존재 시 MERGE).
   * datasetId는 mergeEntities와 동일한 이유로 필수다(#678) — 호출측이 null이면 부르기 전에 막아야 한다.
   */
  public void addRelation(String subjectKey, String relType, String objectKey, List<Long> sourceChunkIds,
      Long datasetId) {
    Map<String, Object> body = new HashMap<>();
    body.put("subjectKey", subjectKey);
    body.put("relType", relType);
    body.put("objectKey", objectKey);
    body.put("sourceChunkIds", sourceChunkIds == null ? List.of() : sourceChunkIds);
    body.put("datasetId", datasetId);
    postJson("/agent/graph/add-relation", body, "관계 적재");
  }

  /**
   * 임의 JSON 바디 POST.
   *
   * <p>ai-agent가 409를 주면 "대상 노드가 그래프에 없어 반영할 수 없음"이라는 상태 충돌이다(#310). 이때는 장애(502)가
   * 아니라 사용자에게 사유를 그대로 보여줘야 하므로, 응답 바디의 message를 담은 {@link IllegalStateException}으로
   * 바꿔 던진다(GlobalExceptionHandler가 409 + 해당 메시지로 응답). 그 외 실패는 {@link ExternalServiceException}(502).
   * 어느 쪽이든 예외가 나가면 호출자(ReviewItemService)는 status를 갱신하지 않아 항목이 pending으로 남는다.
   *
   * <p>409 사유 추출은 {@code onStatus} 훅에서 바디를 명시적으로 읽는다 — {@code toBodilessEntity()}가 바디를 버리는
   * 경로에 기대면 사유가 조용히 사라져 일반 실패 문구로 퇴화할 수 있다.
   */
  private void postJson(String uri, Object body, String opLabel) {
    // 원격 호출 전에 대행 주체를 확정한다 — 없으면 원격이 400 으로 거부할 것이 확정이므로,
    // 사유가 남는 쪽(여기)에서 끊는다.
    HttpHeaders delegation = DelegationHeaders.require(opLabel);
    try {
      webClient
          .post()
          .uri(uri)
          .headers(headers -> headers.addAll(delegation))
          .bodyValue(body)
          .retrieve()
          .onStatus(
              status -> status.value() == HttpStatus.CONFLICT.value(),
              response ->
                  response
                      .bodyToMono(String.class)
                      .defaultIfEmpty("")
                      .map(raw -> new GraphConflictException(conflictMessage(raw, opLabel))))
          // 400 은 "요청 계약 불일치"다 — 바디/헤더 스펙이 어긋났다는 뜻이고, 실제로 일어나는
          // 경우는 두 서비스 이미지의 버전 스큐다(예: 구버전 api 가 대행 헤더를 안 보냄).
          // 일반 catch 로 흘리면 "응답 코드 400" 문구만 남고 원격이 돌려준 사유가 버려지므로,
          // 여기서 바디를 읽어 로그에 남긴다(#313: 사용자 문구에는 내부 정보를 싣지 않는다).
          .onStatus(
              status -> status.value() == HttpStatus.BAD_REQUEST.value(),
              response ->
                  response
                      .bodyToMono(String.class)
                      .defaultIfEmpty("")
                      .map(raw -> contractMismatch(raw, opLabel, uri)))
          .toBodilessEntity()
          .timeout(TIMEOUT)
          .block();
    } catch (GraphConflictException e) {
      // 409 사유 전달용(#310) — 이미 사용자용 한국어 문구이므로 그대로 위임한다.
      throw e;
    } catch (ExternalServiceException e) {
      // 400 매핑에서 이미 사용자용 문구를 만들고 로그도 남겼다 — 일반 문구로 덧씌우지 않는다.
      throw e;
    } catch (RuntimeException e) {
      // 전송 계층 실패(연결 거부·5xx·타임아웃). 상세는 로그에만 남긴다(#313).
      log.error("[graph-mutation] {} 호출 실패 (uri={})", opLabel, uri, e);
      throw new ExternalServiceException(userFacingFailure(opLabel, e), e);
    }
  }

  /**
   * 하위 서비스 예외 문자열을 사용자 메시지로 그대로 올리지 않는다(#313).
   *
   * <p>WebClient 예외 메시지에는 {@code 502 Bad Gateway from POST http://127.0.0.1:5020/agent/graph/set-property}
   * 처럼 내부 호스트·포트·경로가 박혀 있어, 검수자에게 무의미할 뿐 아니라 내부 구조가 브라우저로 샌다. 대신 무엇이
   * 실패했고 지금 무엇을 하면 되는지만 남기고, 원인 추적에 필요한 원문은 위 {@code log.error}가 담당한다. 사용자
   * 입력이 원인인 실패는 이 경로로 오지 않는다 — ai-agent가 409 + 한국어 사유로 돌려주고(#310, #311) 위에서 통과된다.
   */
  private String userFacingFailure(String opLabel, RuntimeException e) {
    if (e instanceof WebClientResponseException res) {
      return "AI 그래프 서비스가 " + opLabel + " 요청을 처리하지 못했습니다(응답 코드 "
          + res.getStatusCode().value() + "). 잠시 후 다시 시도해 주세요.";
    }
    if (e.getCause() instanceof TimeoutException) {
      return "AI 그래프 서비스 응답이 " + TIMEOUT.toSeconds() + "초 안에 오지 않아 " + opLabel
          + "에 실패했습니다. 잠시 후 다시 시도해 주세요.";
    }
    return "AI 그래프 서비스에 연결할 수 없어 " + opLabel + "에 실패했습니다. 잠시 후 다시 시도해 주세요.";
  }

  /**
   * ai-agent 409(상태 충돌)의 사유를 그대로 사용자에게 올리기 위한 전용 예외(#310, #313).
   *
   * <p>{@link IllegalStateException}을 상속해 GlobalExceptionHandler의 409 매핑을 그대로 쓰되, 타입을 좁혀 둔다 —
   * Reactor가 던지는 다른 IllegalStateException(블로킹 타임아웃 등)의 내부 영문 문구가 "사용자용 사유"로 오인되어
   * 그대로 노출되는 일을 막기 위함이다. 그런 예외는 아래 RuntimeException 분기에서 정제된다.
   */
  private static class GraphConflictException extends IllegalStateException {
    GraphConflictException(String message) {
      super(message);
    }
  }

  /** 400(요청 계약 불일치) — 원격이 돌려준 사유는 로그에만 남기고, 사용자에게는 행동 문구만 준다. */
  private ExternalServiceException contractMismatch(String rawBody, String opLabel, String uri) {
    log.error("[graph-mutation] {} 요청이 400 으로 거부됐다 (uri={}, body={})", opLabel, uri, rawBody);
    return new ExternalServiceException(
        "AI 그래프 서비스가 " + opLabel + " 요청 형식을 거부했습니다(서비스 버전 불일치로 보입니다)."
            + " 관리자에게 문의해 주세요.");
  }

  /** 409 바디({"error":..,"message":..})에서 사용자용 사유를 뽑는다. 파싱 실패 시 일반 문구로 폴백. */
  private String conflictMessage(String rawBody, String opLabel) {
    try {
      JsonNode node = objectMapper.readTree(rawBody);
      String message = node.path("message").asText(null);
      if (message != null && !message.isBlank()) return message;
    } catch (Exception ignored) {
      // 바디가 JSON이 아니거나 비어 있음 — 아래 폴백 문구를 쓴다.
    }
    return "그래프에 반영할 대상이 없어 " + opLabel + "에 실패했습니다.";
  }
}
