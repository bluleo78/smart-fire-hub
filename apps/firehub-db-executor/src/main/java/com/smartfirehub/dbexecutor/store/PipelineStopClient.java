package com.smartfirehub.dbexecutor.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * api 의 파이프라인 실행 중지 요청 확인 클라이언트(WD-482, Python 실행기 {@code pipeline_stop_client} 와 같은 동작).
 *
 * <p><b>왜 필요한가.</b> Tibero 테넌트의 REPLACE SQL 스텝은 선행 문장마다 {@code LOCK TABLE … IN EXCLUSIVE MODE} 로 출력
 * 테이블을 잠그고, 같은 출력을 쓰는 다른 실행이 끝날 때까지 기다린다. 이 대기는 api 러너의 「SQL 실행 직전」 체크포인트보다 뒤라 그 사이 들어온 취소를 api 가 볼
 * 수 없었고, 잠금을 얻은 뒤 사용자 SQL 이 끝까지 돌아 출력을 다시 교체했다. 그래서 잠금을 얻은 직후 api 의 {@code GET
 * /internal/executor/tenants/{tenant}/pipeline-executions/{id}/stop-requested} 로 되묻는다 — 판정은 api 가
 * DB 에서 하므로 어느 api 인스턴스에서 취소를 받았어도 보인다.
 *
 * <p>인증은 기술자 조회와 같은 db-executor 내부 토큰({@code Authorization: Internal <token>})이다. 실패(연결 실패·200
 * 이외·형식 오류)는 예외로 올린다 — 호출부({@code SqlRunner})가 「중지 아님」으로 보고 그대로 실행한다(fail-open: 확인 실패로 정상 실행을 실패시키지
 * 않는다).
 */
@Component
public class PipelineStopClient {

  /**
   * 한 번 묻는 데 기다리는 최대 시간(접속·응답 포함). 출력 테이블 잠금을 쥔 채 기다리므로 짧게 둔다 — api 가 멈춰도 같은 출력을 기다리는 다른 실행을 오래 묶지
   * 않는다.
   */
  static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final String baseUrl;
  private final String token;

  @Autowired
  public PipelineStopClient(DbExecutorProperties props, ObjectMapper mapper) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), props, mapper);
  }

  /** 테스트용: HTTP 클라이언트를 주입한다. */
  PipelineStopClient(HttpClient http, DbExecutorProperties props, ObjectMapper mapper) {
    this.http = http;
    this.mapper = mapper;
    this.baseUrl = props.apiBaseUrl().replaceAll("/+$", "");
    this.token = props.internalToken();
  }

  /** 이 실행의 중지 요청을 api 에 묻는 함수를 돌려준다(부를 때마다 새로 묻는다 — 캐시하지 않는다). */
  public BooleanSupplier check(long tenantId, long executionId) {
    return () -> isStopRequested(tenantId, executionId);
  }

  /**
   * api 에 중지 요청 여부를 묻는다.
   *
   * @throws IllegalStateException 연결 실패·시간 초과·200 이외·형식 오류(응답 본문·토큰은 메시지에 싣지 않는다)
   */
  boolean isStopRequested(long tenantId, long executionId) {
    HttpRequest req =
        HttpRequest.newBuilder(
                URI.create(
                    baseUrl
                        + "/internal/executor/tenants/"
                        + tenantId
                        + "/pipeline-executions/"
                        + executionId
                        + "/stop-requested"))
            .timeout(TIMEOUT)
            .header("Authorization", "Internal " + token)
            .GET()
            .build();
    HttpResponse<String> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      // 인터럽트 표시를 되살린다 — 이 스레드를 멈추려는 쪽이 알 수 있게.
      Thread.currentThread().interrupt();
      throw new IllegalStateException("중지 요청 확인이 인터럽트됐습니다");
    } catch (IOException e) {
      throw new IllegalStateException(
          "중지 요청 확인 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    if (res.statusCode() != 200) {
      throw new IllegalStateException("중지 요청 확인 응답 상태=" + res.statusCode());
    }
    JsonNode value;
    try {
      value = mapper.readTree(res.body()).path("stopRequested");
    } catch (IOException e) {
      throw new IllegalStateException("중지 요청 확인 응답 형식 오류");
    }
    if (!value.isBoolean()) {
      throw new IllegalStateException("stopRequested 값이 올바르지 않습니다");
    }
    return value.booleanValue();
  }
}
