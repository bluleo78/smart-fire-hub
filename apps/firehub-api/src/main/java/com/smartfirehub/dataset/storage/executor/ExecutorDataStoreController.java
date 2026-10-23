package com.smartfirehub.dataset.storage.executor;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.security.InternalTokens;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * executor 전용 저장소 기술자 엔드포인트(WD-75).
 *
 * <p>평문 DB 비밀번호를 내므로 {@code /api/} 밖 {@code /internal/executor/**} 에 둔다(web·admin nginx 는 {@code
 * /api/} 만 프록시한다). 인증은 {@code Authorization: Internal <token>} 을 이 컨트롤러가 직접 검증한다. 설정 토큰이 비었거나 알려진
 * 기본값이면 평문 비밀번호를 내지 않고 503 으로 거부한다(R-token) — 로컬·테스트는 {@code app.executor.allow-default-token=true}
 * 로만 허용한다.
 */
@RestController
public class ExecutorDataStoreController {

  private static final Logger log = LoggerFactory.getLogger(ExecutorDataStoreController.class);

  /** 설정 파일·compose 에 박혀 있는 알려진 기본 토큰. 이 값이면 누구나 추측할 수 있으므로 운영에서 거부한다. */
  static final Set<String> INSECURE_DEFAULT_TOKENS = Set.of("dev-executor-token", "executor-token");

  /** 토큰이 없거나 안전하지 않아 비밀번호를 내줄 수 없을 때의 503 코드 — 컨트롤러와 서비스가 함께 쓴다. */
  public static final String TOKEN_NOT_CONFIGURED_CODE = "EXECUTOR_TOKEN_NOT_CONFIGURED";

  /** {@code ?include=ro} — 정확히 이 값일 때만 읽기 전용 계정을 싣는다(WD-76). 다른 값·없음은 기존 응답이다. */
  static final String INCLUDE_RO = "ro";

  private final ExecutorDataStoreDescriptorService service;
  private final String token;
  private final boolean allowDefaultToken;

  public ExecutorDataStoreController(
      ExecutorDataStoreDescriptorService service,
      @Value("${app.executor.internal-token:}") String token,
      @Value("${app.executor.allow-default-token:false}") boolean allowDefaultToken) {
    this.service = service;
    this.token = token;
    this.allowDefaultToken = allowDefaultToken;
  }

  @GetMapping("/internal/executor/tenants/{tenantId}/data-store")
  public ResponseEntity<ExecutorDataStoreDescriptor> describe(
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @PathVariable long tenantId,
      @RequestParam(value = "include", required = false) String include) {
    // 설정 토큰이 비면 아무 요청도 인증할 수 없으므로 모든 요청을 503 으로 거부한다.
    if (token == null || token.isBlank()) {
      log.error("executor 내부 토큰이 비어 있어 저장소 기술자 제공을 거부한다");
      throw new CodedApiException(
          HttpStatus.SERVICE_UNAVAILABLE, TOKEN_NOT_CONFIGURED_CODE, "executor 내부 토큰이 설정되지 않았습니다");
    }
    if (!InternalTokens.matches(authHeader, token)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    // 기본 토큰이면 비밀이 없는 PG 기술자만 내고, 비밀번호가 담길 Tibero 는 서비스가 복호화 전에 거부한다.
    ExecutorDataStoreDescriptor descriptor =
        service.describe(tenantId, secretsAllowed(), INCLUDE_RO.equals(include));
    log.info("executor 저장소 기술자 제공: tenant={}, backend={}", tenantId, descriptor.backend());
    // 평문 비밀번호가 중간 캐시에 남지 않게 한다.
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(descriptor);
  }

  /** 평문 비밀번호를 내도 되는가: 토큰이 알려진 기본값이 아니거나, 허용 플래그(로컬·테스트)가 켜져 있어야 한다. */
  private boolean secretsAllowed() {
    return allowDefaultToken || !INSECURE_DEFAULT_TOKENS.contains(token);
  }
}
