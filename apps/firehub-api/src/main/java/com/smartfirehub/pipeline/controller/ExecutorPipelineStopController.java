package com.smartfirehub.pipeline.controller;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.security.InternalTokens;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Python 실행기 전용 「파이프라인 실행 중지 요청 확인」 엔드포인트(WD-466).
 *
 * <p><b>왜 필요한가.</b> REPLACE SQL 스텝은 실행기 트랜잭션의 첫 문장으로 출력 잠금을 잡고, 같은 출력을 쓰는 다른 실행이 끝날 때까지 기다린다. 이 대기는
 * api 러너의 「SQL 실행 직전」 체크포인트보다 뒤라, 대기 중에 들어온 취소를 러너가 보지 못했고 실행기는 잠금을 얻은 뒤 사용자 SQL 을 끝까지 돌려 출력을 다시
 * 교체했다. 실행기는 별도 프로세스라 러너의 취소 확인을 직접 부를 수 없으므로, 잠금을 얻은 직후 이 엔드포인트로 같은 판정({@link
 * PipelineExecutionRepository#isStopRequested})을 되묻는다. 판정은 DB 를 읽으므로 어느 api 인스턴스에서 취소를 받았어도 보인다.
 *
 * <p>인증은 Python 실행기 내부 토큰({@code app.executor.internal-token}, {@code Authorization: Internal
 * <token>})을 이 컨트롤러가 직접 검증한다 — {@code /internal/executor/**} 는 nginx 가 프록시하지 않는 경로이고 SecurityConfig
 * 가 permitAll 로 둔다({@code ExecutorDataStoreController} 와 같은 방식). 테넌트는 경로의 값으로 RLS 범위를 세워 조회하므로 다른
 * 테넌트의 실행은 보이지 않는다(보이지 않는 실행은 판정 규칙상 「중지」로 답한다 — 출력을 건드리지 않는 쪽).
 */
@RestController
public class ExecutorPipelineStopController {

  private final PipelineExecutionRepository executionRepository;

  /** Python 실행기 내부 토큰 — 비어 있으면 아무 요청도 인증할 수 없어 503 으로 거부한다. */
  private final String executorToken;

  public ExecutorPipelineStopController(
      PipelineExecutionRepository executionRepository,
      @Value("${app.executor.internal-token:}") String executorToken) {
    this.executionRepository = executionRepository;
    this.executorToken = executorToken;
  }

  /**
   * 실행에 중지 요청(사용자 취소·외부 마감)이 있는지 답한다.
   *
   * @return {@code {"stopRequested": true|false}}
   */
  @GetMapping(
      "/internal/executor/tenants/{tenantId}/pipeline-executions/{executionId}/stop-requested")
  public ResponseEntity<Map<String, Boolean>> stopRequested(
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @PathVariable long tenantId,
      @PathVariable long executionId) {
    if (executorToken.isBlank()) {
      throw new CodedApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "EXECUTOR_TOKEN_NOT_CONFIGURED",
          "executor 내부 토큰이 설정되지 않았습니다");
    }
    if (!InternalTokens.matches(authHeader, executorToken)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    // 경로의 테넌트로 RLS 범위를 세워 판정한다 — 리포지토리의 짧은 트랜잭션이 이 컨텍스트로 GUC 를 공급한다.
    boolean stop =
        TenantContext.runScopedGet(
            tenantId, () -> executionRepository.isStopRequested(executionId));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Map.of("stopRequested", stop));
  }
}
