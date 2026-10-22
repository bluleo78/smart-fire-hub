package com.smartfirehub.apiconnection.service;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionReferencesResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionSelectableResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.exception.ApiConnectionNameAlreadyExistsException;
import com.smartfirehub.apiconnection.exception.ApiConnectionNotFoundException;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.exception.SerializationException;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.pipeline.service.executor.ApiCallErrorMessages;
import com.smartfirehub.pipeline.service.executor.SsrfException;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * API 연결(ApiConnection) 비즈니스 로직 서비스. Phase 9: baseUrl 정규화/SSRF 검증, 헬스체크(testConnection), slim
 * 목록(findSelectable), 전체 헬스체크 비동기 Job(refreshAllAsync) 제공.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiConnectionService {

  private final ApiConnectionRepository repository;

  /**
   * 조회 전용 협력자. testConnection 이 조회를 자기호출하면 프록시가 우회돼 트랜잭션이 열리지 않으므로 별도 빈으로 분리해 두고 반드시 이 참조를 통해 호출한다.
   */
  private final ApiConnectionReader reader;

  private final EncryptionService encryptionService;
  private final ObjectMapper objectMapper;
  private final SsrfProtectionService ssrfProtectionService;
  private final AsyncJobService asyncJobService;

  /**
   * 생성·수정·삭제 감사 기록(WD-181). 상태 전환 감사({@link ApiConnectionNotifier})와 같은 resource({@code
   * api_connection})를 쓴다.
   */
  private final AuditLogService auditLogService;

  /** 감사 행위자 username 조회용 — audit_log.username 이 NOT NULL 이라 RoleService·UserService 처럼 조회해서 넣는다. */
  private final UserRepository userRepository;

  @Qualifier("pipelineExecutor")
  private final Executor pipelineExecutor;

  /** WebClient는 프로젝트 관행에 따라 빌더를 직접 생성한다 (별도 Bean 없음). */
  private final WebClient webClient = WebClient.builder().build();

  /** 헬스체크 HTTP 호출 타임아웃(초). 메시지 문구(「응답 시간 초과(N초) — …」)와 값이 어긋나지 않도록 한 곳에서 관리한다. */
  private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 5;

  // ── 공개 API ────────────────────────────────────────────────────────────────

  @Transactional
  public ApiConnectionResponse create(CreateApiConnectionRequest request, Long userId) {
    validateAuthType(request.authType());
    validateNameNotDuplicated(request.name());
    String normalizedBaseUrl = validateAndNormalizeBaseUrl(request.baseUrl());

    // 저장값이 없으니 가린 값(****…)은 유지할 대상도 없다 — 그대로 저장되지 않게 거절한다(WD-179).
    String encryptedConfig =
        serializeAndEncrypt(resolveMaskedSecrets(request.authConfig(), null, false));
    Long id =
        repository.save(
            request.name(),
            request.description(),
            request.authType(),
            encryptedConfig,
            userId,
            normalizedBaseUrl,
            normalizeHealthCheckPath(request.healthCheckPath()));

    // 감사(WD-181) — 이름·인증 방식·기준 URL(쿼리·userinfo 제거)만 남긴다. 인증 정보는 값도 키도 남기지 않는다.
    audit(
        userId,
        "CREATE",
        id,
        "API 연결 생성: " + request.name(),
        connectionSnapshot(request.name(), request.authType(), normalizedBaseUrl));

    return reader.getById(id);
  }

  @Transactional(readOnly = true)
  public List<ApiConnectionResponse> getAll() {
    return repository.findAll().stream().map(reader::toResponse).toList();
  }

  /** 단건 조회. 실제 구현은 {@link ApiConnectionReader} 에 있고 여기서는 기존 호출자를 위해 위임만 한다. */
  @Transactional(readOnly = true)
  public ApiConnectionResponse getById(Long id) {
    return reader.getById(id);
  }

  /** 복호화된 authConfig 조회. 구현은 {@link ApiConnectionReader} 에 있고 여기서는 위임만 한다. */
  @Transactional(readOnly = true)
  public Map<String, String> getDecryptedAuthConfig(Long id) {
    return reader.getDecryptedAuthConfig(id);
  }

  /**
   * 연결 수정. 모든 필드는 null 이면 바꾸지 않는다.
   *
   * <p><b>인증 방식 변경 검증(WD-180).</b> 예전에는 authConfig 가 없으면 authType 검사를 건너뛰어, authType 만 보낸 수정이 지원하지
   * 않는 방식(OAUTH2 등)도 200 으로 저장했고, API_KEY → BEARER 처럼 바꾸면 새 방식의 토큰이 없어 인증 헤더 없이 호출되면서도 오류가 없었다. 엉뚱한
   * 값(FOO)은 DB CHECK 제약에 걸려 409 일반 문구로 뭉뚱그려졌다. 이제 순서대로:
   *
   * <ol>
   *   <li>연결이 없으면 404({@link ApiConnectionNotFoundException}).
   *   <li>authType 이 오면 authConfig 유무와 상관없이 지원 목록(API_KEY·BEARER)인지 본다 — DB 제약에 기대지 않는다(400).
   *   <li>authType 이 지금 값과 다르면 새 방식의 비밀 필드(API_KEY → apiKey, BEARER → token)를 담은 authConfig 를 함께
   *       받아야 한다(400). authConfig 가 없거나 {@code {}}·옛 방식의 필드만 있으면 거절한다 — 그대로 두면 신고된 「인증 헤더 없이 호출」이
   *       재현된다.
   *   <li>authType 이 그대로(또는 생략)이고 authConfig 도 없으면 저장된 비밀을 그대로 둔다(기존 동작).
   * </ol>
   *
   * <p><b>감사(WD-181).</b> 실제로 바뀐 항목이 있으면 UPDATE 감사를 한 건 남긴다 — 바뀐 항목 목록(이름·설명·기준 URL·헬스체크 경로·인증
   * 방식·인증 정보)과 인증 정보 변경 여부(true/false)만 담고 비밀 값은 남기지 않는다. 조회한 가린 값을 그대로 되돌려 보낸 저장(WD-179)은 비밀이
   * 그대로이므로 「인증 정보 변경」이 아니다. 바뀐 것이 없으면 기록하지 않는다(역할 수정 ROLE_UPDATE 와 같은 규칙 — 화면은 저장할 때마다 같은 값을 보낼 수
   * 있어 기록하면 잡음 행만 쌓인다).
   *
   * @param userId 수정한 사용자(감사 행위자)
   */
  @Transactional
  public ApiConnectionResponse update(Long id, UpdateApiConnectionRequest request, Long userId) {
    Record existing =
        repository.findById(id).orElseThrow(() -> new ApiConnectionNotFoundException(id));
    String currentAuthType = existing.get(field(name("api_connection", "auth_type"), String.class));

    // 인증 방식 검증은 이름 중복(409)보다 먼저 — 입력 자체가 틀린 요청은 400 으로 답한다.
    boolean authTypeChanged = false;
    if (request.authType() != null) {
      validateAuthType(request.authType());
      authTypeChanged = !request.authType().equals(currentAuthType);
      if (authTypeChanged) {
        requireAuthConfigForNewAuthType(request.authType(), request.authConfig());
      }
    }

    // 이름을 변경하는 경우에만 중복 검사 — 자기 자신은 제외 (#647)
    if (request.name() != null && !request.name().isBlank()) {
      if (repository.existsByNameExcludingId(request.name(), id)) {
        throw new ApiConnectionNameAlreadyExistsException(request.name());
      }
    }

    String encryptedConfig = null;
    // 인증 정보가 실제로 바뀌었는지(감사용, WD-181). 인증 방식이 바뀌면 새 방식의 비밀이 반드시 함께 오므로 바뀐 것이다.
    boolean credentialsChanged = authTypeChanged;
    if (request.authConfig() != null) {
      // 조회 결과(maskedAuthConfig)를 그대로 되돌려 보낸 비밀 필드는 저장 원문으로 채운다(WD-179). authType 이 바뀌면 같은 이름의
      // 필드라도 뜻이 달라지므로 저장값을 쓰지 않는다 — 가린 값은 모두 거절된다.
      Map<String, String> stored = authTypeChanged ? null : reader.getDecryptedAuthConfig(id);
      Map<String, String> resolved = resolveMaskedSecrets(request.authConfig(), stored, true);
      encryptedConfig = serializeAndEncrypt(resolved);
      if (!authTypeChanged) {
        // 비교는 메모리 안에서만 한다 — 값은 어디에도 남기지 않고 true/false 만 감사에 싣는다.
        credentialsChanged = !withoutAuthTypeKey(resolved).equals(withoutAuthTypeKey(stored));
      }
    }

    // baseUrl이 있으면 정규화 및 SSRF 검증
    String normalizedBaseUrl = null;
    if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
      normalizedBaseUrl = validateAndNormalizeBaseUrl(request.baseUrl());
    }

    String authType = request.authType() != null ? request.authType() : currentAuthType;
    String normalizedHealthCheckPath = normalizeHealthCheckPath(request.healthCheckPath());
    repository.update(
        id,
        request.name(),
        request.description(),
        authType,
        encryptedConfig,
        normalizedBaseUrl,
        normalizedHealthCheckPath);

    auditUpdate(
        userId,
        id,
        existing,
        request,
        authType,
        normalizedBaseUrl,
        normalizedHealthCheckPath,
        credentialsChanged);

    return reader.getById(id);
  }

  /**
   * 연결 삭제. 성공하면 DELETE 감사를 남긴다(WD-181) — 행이 사라지므로 이름·인증 방식·기준 URL 을 지우기 전에 담아 둔다. 감사는 삭제와 같은 트랜잭션이라
   * 파이프라인이 참조해 삭제가 거부(FK, 409)되면 감사도 함께 롤백된다 — 거부 경로는 기록하지 않는다(역할 삭제와 같은 규칙).
   *
   * @param userId 삭제한 사용자(감사 행위자)
   */
  @Transactional
  public void delete(Long id, Long userId) {
    Record existing =
        repository.findById(id).orElseThrow(() -> new ApiConnectionNotFoundException(id));
    String connectionName = existing.get(field(name("api_connection", "name"), String.class));
    Map<String, Object> snapshot =
        connectionSnapshot(
            connectionName,
            existing.get(field(name("api_connection", "auth_type"), String.class)),
            existing.get(field(name("api_connection", "base_url"), String.class)));
    repository.deleteById(id);
    audit(userId, "DELETE", id, "API 연결 삭제: " + connectionName, snapshot);
  }

  /**
   * 이 API 연결을 참조하는 파이프라인을 집계한다. 삭제 전 영향 범위 확인용(#605).
   *
   * <p>{@code pipeline_step.api_connection_id}는 FK(ON DELETE 절 없음 → 기본 RESTRICT)로 실제 삭제 시 참조가 있으면
   * 409로 거부되지만, 그 전에는 확인할 방법이 없어 사전 고지가 정적 문구로만 이루어지던 문제(dataset-manager의 {@code
   * get_dataset_references}와 동일 패턴)를 해결한다.
   */
  @Transactional(readOnly = true)
  public ApiConnectionReferencesResponse getReferences(Long id) {
    repository.findById(id).orElseThrow(() -> new ApiConnectionNotFoundException(id));

    List<ApiConnectionReferencesResponse.ReferenceItem> pipelines =
        repository.findReferencingPipelines(id).stream()
            .map(
                r ->
                    new ApiConnectionReferencesResponse.ReferenceItem(
                        r.get(field(name("pipeline", "id"), Long.class)),
                        r.get(field(name("pipeline", "name"), String.class))))
            .toList();

    return new ApiConnectionReferencesResponse(id, pipelines, pipelines.size());
  }

  /** 일반 사용자가 파이프라인 스텝에서 선택할 수 있는 slim 목록을 반환. 민감한 authConfig, healthCheckPath, last* 필드는 제외. */
  @Transactional(readOnly = true)
  public List<ApiConnectionSelectableResponse> findSelectable() {
    return repository.findAll().stream()
        .map(
            r ->
                new ApiConnectionSelectableResponse(
                    r.get(field(name("api_connection", "id"), Long.class)),
                    r.get(field(name("api_connection", "name"), String.class)),
                    r.get(field(name("api_connection", "auth_type"), String.class)),
                    r.get(field(name("api_connection", "base_url"), String.class))))
        .toList();
  }

  /**
   * 저장된 API 연결의 헬스체크 경로로 GET 호출하여 상태를 반환하고 DB에 반영한다. healthCheckPath가 없으면 baseUrl 자체를 GET. 5초 타임아웃.
   * DB 쓰기(updateHealthStatus)는 내부적으로 자체 트랜잭션을 사용하므로 본 메서드는 트랜잭션 밖에서 HTTP를 수행하여 커넥션 풀 점유를 피한다.
   *
   * <p><b>이 메서드에 {@code @Transactional} 을 붙이면 안 된다.</b> 붙이는 순간 최대 5초짜리 외부 HTTP 호출이 트랜잭션(=DB 커넥션)
   * 안으로 들어와 커넥션 풀을 점유한다. 대신 조회는 별도 빈인 {@link ApiConnectionReader} 를 통해 수행해 프록시를 실제로 통과시킨다(자기호출이면
   * 프록시가 우회돼 트랜잭션이 열리지 않는다). DB 쓰기는 {@code repository.updateHealthStatus} 가 리포지토리 자신의 트랜잭션을 열어
   * 처리하므로, HTTP 구간은 어떤 트랜잭션 안에도 들어가지 않는다.
   *
   * <p>화면의 「테스트」뿐 아니라 10분 주기 상태 점검({@link ApiConnectionHealthCheckScheduler})과 일괄 새로고침({@link
   * #refreshAllAsync})도 이 메서드를 부른다. 호출 직전 주소 검사는 {@link #callHealthCheck} 가 맡는다(WD-178).
   */
  public TestConnectionResponse testConnection(Long id) {
    // ── 여기까지가 트랜잭션 구간(각 호출이 reader 프록시에서 자체 트랜잭션을 연다) ──
    ApiConnectionResponse conn = reader.getById(id);
    Map<String, String> rawConfig = reader.getDecryptedAuthConfig(id);
    // ── 아래 HTTP 호출은 트랜잭션 밖이다 ──
    TestConnectionResponse result =
        callHealthCheck(
            UrlUtils.joinUrl(conn.baseUrl(), conn.healthCheckPath()), conn.authType(), rawConfig);
    // 성공·HTTP 오류·연결 실패·주소 차단 모두 같은 규칙으로 저장한다 — 상태 전환 알림은 스케줄러가 이 결과의 ok 로 판단한다.
    repository.updateHealthStatus(
        id, result.ok() ? "UP" : "DOWN", result.latencyMs(), result.errorMessage());
    return result;
  }

  /**
   * (#90) 저장 전 dry-run 헬스체크. CreateApiConnectionRequest의 payload를 가지고 외부 API 호출만 수행하고 DB는 변경하지 않는다.
   * authType 검증/baseUrl SSRF 검증은 동일하게 수행하므로 잘못된 입력은 즉시 ApiConnectionException(400, WD-180)으로 차단된다.
   */
  public TestConnectionResponse testConnectionPayload(CreateApiConnectionRequest request) {
    validateAuthType(request.authType());
    // 저장 전 테스트도 가린 값(****…)을 인증 값으로 보내지 않는다 — 저장값이 없으니 유지할 것도 없다(WD-179).
    Map<String, String> rawConfig =
        request.authConfig() != null
            ? resolveMaskedSecrets(request.authConfig(), null, false)
            : Map.of();
    String normalizedBaseUrl = validateAndNormalizeBaseUrl(request.baseUrl());
    String normalizedPath = normalizeHealthCheckPath(request.healthCheckPath());
    return callHealthCheck(
        UrlUtils.joinUrl(normalizedBaseUrl, normalizedPath), request.authType(), rawConfig);
  }

  /**
   * 헬스체크 GET 한 번을 보내고 결과를 만든다 — 저장된 연결 테스트·저장 전 테스트·상태 점검·일괄 새로고침이 모두 여기로 모인다. DB 는 건드리지 않는다(저장은
   * 호출자 몫).
   *
   * <p><b>호출 직전 주소 검사(WD-178).</b> 저장할 때 막는 내부 주소라도, 이미 저장된 연결은 그 뒤에 내부를 가리키게 될 수 있다 — 차단 규칙이 강화되기
   * 전에 저장된 연결(WD-148 IPv6 ULA 등), DB 직접 변경, DNS 재바인딩. 예전에는 여기서 주소를 다시 보지 않아 서버가 내부 주소로 인증 헤더까지 실어
   * 요청하고 그 응답 본문을 결과로 보여 줬다. 같은 연결로 하는 API_CALL 미리보기·실행은 호출 시점에 막혀 수준이 달랐다. 그래서 API_CALL 과 같은
   * {@link SsrfProtectionService#validateUrl} 로 같은 판정을 한다.
   *
   * <p>검사 대상은 인증 쿼리 키를 붙이기 <b>전</b> URL(기준 URL + 헬스체크 경로)이다. 키는 쿼리에만 붙어 호스트를 바꾸지 못하고, API_CALL 도
   * 쿼리를 붙이기 전 URL 을 검사한다. 키 붙은 URL 을 넘기면 검사 실패 문구에 키가 실릴 위험만 커진다.
   *
   * <p>리다이렉트: 이 WebClient 는 Reactor Netty 기본값(리다이렉트를 따라가지 않음)이라 3xx 응답을 그대로 결과로 돌려줄 뿐 다른 주소로 다시
   * 요청하지 않는다. DNS: API_CALL 과 마찬가지로 검사 때 해석한 IP 로 고정하지는 않는다(검사 후 WebClient 가 다시 해석).
   *
   * @param targetUrl 인증 쿼리를 붙이기 전 호출 URL(기준 URL + 헬스체크 경로)
   */
  private TestConnectionResponse callHealthCheck(
      String targetUrl, String authType, Map<String, String> rawConfig) {
    // 응답의 requestUrl 은 인증 쿼리 키를 가린 표시용 URL 이다(WD-177). URL 을 만들기 전에 실패하면 키 없는 기준 URL 을 보인다.
    String displayUrl = targetUrl;

    long start = System.currentTimeMillis();
    try {
      // 호출 직전 주소 검사(WD-178) — 내부망·로컬이면 SsrfException 으로 아래에서 DOWN 결과가 되고, 외부 호출은 한 건도 나가지 않는다.
      ssrfProtectionService.validateUrl(targetUrl);

      // placement=query 인 경우 URL 에 인증 파라미터 부착 (#113). 호출용·표시용을 함께 만든다(WD-177) — try 안에서 만들어,
      // 키에 쓸 수 없는 문자가 있어 실패해도 400 응답에 키가 실리지 않고 DOWN 결과(고정 문구)로 돌아가게 한다.
      AuthQueryMasker.AuthQueryUrls urls =
          AuthQueryMasker.buildForAuth(targetUrl, authType, rawConfig);
      displayUrl = urls.displayUrl();
      String url = urls.callUrl();
      // 응답 본문/헤더를 함께 받아 디버깅용으로 노출 (#76).
      var resp =
          webClient
              .get()
              .uri(url)
              .headers(h -> buildAuthHeaders(authType, rawConfig).forEach(h::set))
              .retrieve()
              .toEntity(String.class)
              .block(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_SECONDS));

      long latency = System.currentTimeMillis() - start;
      Integer status = resp != null ? resp.getStatusCode().value() : null;
      boolean ok = resp != null && resp.getStatusCode().is2xxSuccessful();
      String err = ok ? null : ("HTTP " + status);
      String body = resp != null ? truncateBody(resp.getBody(), rawConfig) : null;
      Map<String, String> headers =
          resp != null ? sanitizeHeaders(resp.getHeaders(), rawConfig) : Map.of();
      String contentType =
          resp != null && resp.getHeaders().getContentType() != null
              ? resp.getHeaders().getContentType().toString()
              : null;
      return new TestConnectionResponse(
          ok, status, latency, err, displayUrl, body, headers, contentType);

    } catch (SsrfException e) {
      long latency = System.currentTimeMillis() - start;
      // 차단 사유는 SsrfProtectionService 의 한국어 문구를 그대로 쓴다(「내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1」) —
      // 저장 시 검사·API_CALL 실행·미리보기(ApiCallErrorMessages.describe)와 같은 문장이다(WD-150). 호출하지 않았으므로 본문·헤더는
      // 비운다.
      return new TestConnectionResponse(
          false, null, latency, e.getMessage(), displayUrl, null, Map.of(), null);

    } catch (WebClientResponseException e) {
      long latency = System.currentTimeMillis() - start;
      String err = "HTTP " + e.getStatusCode().value();
      // 4xx/5xx 응답 본문도 운영자가 봐야 디버깅 가능하므로 함께 노출.
      String body = truncateBody(e.getResponseBodyAsString(), rawConfig);
      Map<String, String> headers = sanitizeHeaders(e.getHeaders(), rawConfig);
      String contentType =
          e.getHeaders().getContentType() != null
              ? e.getHeaders().getContentType().toString()
              : null;
      return new TestConnectionResponse(
          false, e.getStatusCode().value(), latency, err, displayUrl, body, headers, contentType);

    } catch (Exception e) {
      long latency = System.currentTimeMillis() - start;
      // 예외 메시지에 URL·키가 실릴 수 있어 가린 문구만 응답·lastErrorMessage·알림으로 보낸다(WD-177).
      String msg = toUserFacingErrorMessage(e, targetUrl, rawConfig);
      return new TestConnectionResponse(
          false, null, latency, msg, displayUrl, null, Map.of(), null);
    }
  }

  /**
   * (#532) 헬스체크 catch-all 예외를 감사 로그·UI에 노출 가능한 메시지로 변환한다.
   *
   * <p>Reactor의 {@code Mono#block(Duration)}은 타임아웃 시 "Timeout on blocking read for 5000000000
   * NANOSECONDS" 같은 저수준 구현 디테일을 담은 {@link IllegalStateException}을 던진다. 관리자가 나노초 단위를 초로 환산해야만 의미를
   * 파악할 수 있으므로, 타임아웃으로 판별되면 사람이 읽을 수 있는 고정 문구로 치환한다.
   *
   * <p>(WD-180) 그 밖의 예외는 예전에는 원문(「Connection refused: /127.0.0.1:6030」 등 영어)을 그대로 썼다. 이제 API_CALL
   * 실행·미리보기와 같은 {@link ApiCallErrorMessages#describe} 로 분류해 같은 한국어 문장(「연결이 거부되었습니다 — …」)을 쓴다. 응답
   * errorMessage·{@code lastErrorMessage}·상태 전환 알림이 모두 이 결과를 쓴다.
   *
   * <p>(WD-177) 원문 메시지는 응답·{@code lastErrorMessage}·상태 알림으로 나가므로, 메시지 안 URL 의 쿼리·userinfo 를 지우고 인증
   * 쿼리 키(원문·인코딩형)를 {@code ****XXXX} 로 가린다. API_KEY 를 쿼리로 보내는 연결에서 예외 메시지에 호출 URL 이 실리면 키가 그대로 남기
   * 때문이다.
   */
  private static String toUserFacingErrorMessage(
      Exception e, String targetUrl, Map<String, String> authConfig) {
    String message;
    if (isTimeoutException(e)) {
      // Reactor block(Duration) 타임아웃(IllegalStateException)은 분류기의 타임아웃 판별에 걸리지 않을 수 있어
      // 여기서 먼저 본다. 문장은 API_CALL 실행·미리보기와 같다(WD-180).
      message = ApiCallErrorMessages.timeout(HEALTH_CHECK_TIMEOUT_SECONDS * 1000);
    } else if (e instanceof AuthQueryMasker.InvalidRequestUrlException) {
      // 이미 한국어 안내(값 원문 없음) — 분류기 4단계로 보내면 「API 호출 중 오류가 발생했습니다: 」 접두가 겹친다.
      message = e.getMessage();
    } else {
      // 연결 거부·연결 실패·호스트 없음 등은 API_CALL 실행·미리보기와 같은 분류·문장을 쓴다(WD-150 → WD-180). 분류하지 못한 원문은
      // 분류기가 URL 의 쿼리·userinfo 를 지운다(WD-177).
      message =
          ApiCallErrorMessages.describe(e, HEALTH_CHECK_TIMEOUT_SECONDS * 1000, hostOf(targetUrl));
    }
    // 분류하지 못한 원문에 인증 쿼리 키(원문·인코딩형)가 실려 올 수 있어 마지막에 가린다(WD-177).
    return AuthQueryMasker.scrubSecret(message, authSecretOf(authConfig));
  }

  /** 「호스트 이름을 찾을 수 없습니다: X」 문구용 호스트. 해석할 수 없으면 null(문구는 「알 수 없음」). */
  private static String hostOf(String url) {
    try {
      return url != null ? URI.create(url).getHost() : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * 예외가 {@code .block(Duration.ofSeconds(5))} 타임아웃으로 인한 것인지 판별한다. Reactor-core는 전용 예외 타입 대신 {@link
   * IllegalStateException}에 "Timeout on blocking read" 접두 메시지를 담아 던지므로 메시지 패턴으로 판별한다. Netty의 {@code
   * ReadTimeoutException}은 클래스명 기준으로 판별하되, WebClient가 이를 {@code WebClientRequestException}으로 감싸 던지는
   * 경우가 있어 원인 체인(getCause)까지 한 단계 확인한다.
   */
  private static boolean isTimeoutException(Exception e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String msg = t.getMessage();
      if (msg != null && msg.startsWith("Timeout on blocking read")) {
        return true;
      }
      if (t.getClass().getSimpleName().contains("TimeoutException")) {
        return true;
      }
      if (t == t.getCause()) break; // 자기참조 순환 방지
    }
    return false;
  }

  /** 응답 본문을 최대 4KB로 잘라 반환 (UI 노출용). null/빈값은 그대로 반환. */
  private static final int MAX_BODY_PREVIEW_BYTES = 4096;

  /**
   * 응답 본문 미리보기. (WD-177) 오류 본문이 요청 파라미터를 되울려 쿼리 인증 키가 담겨 올 수 있어 키(원문·인코딩형)를 먼저 가린 뒤 자른다 — 자른 뒤 가리면
   * 경계에 걸친 키 앞부분이 남는다.
   */
  private static String truncateBody(String body, Map<String, String> authConfig) {
    if (body == null || body.isEmpty()) return body;
    String masked = AuthQueryMasker.scrubSecret(body, authSecretOf(authConfig));
    if (masked.length() <= MAX_BODY_PREVIEW_BYTES) return masked;
    return masked.substring(0, MAX_BODY_PREVIEW_BYTES) + "\n... (truncated)";
  }

  /** 응답·오류 문구에서 가릴 인증 키(API_KEY 의 apiKey). 없으면 null. */
  private static String authSecretOf(Map<String, String> authConfig) {
    return authConfig != null ? authConfig.get("apiKey") : null;
  }

  /** 민감 헤더 마스킹 — 인증/세션/쿠키 헤더 값은 노출하지 않는다. */
  private static final Set<String> SENSITIVE_HEADER_NAMES =
      Set.of(
          "authorization",
          "proxy-authorization",
          "set-cookie",
          "cookie",
          "x-api-key",
          "x-auth-token");

  /**
   * 응답 헤더를 UI 노출용으로 옮긴다. 민감 헤더는 통째로 가리고, (WD-177) 나머지 헤더 값에서도 쿼리 인증 키를 가린다 — WebClient 가 리다이렉트를
   * 따라가지 않아 3xx 의 {@code Location: https://…?serviceKey=<키>} 가 그대로 결과에 실리기 때문이다.
   */
  private static Map<String, String> sanitizeHeaders(
      org.springframework.http.HttpHeaders headers, Map<String, String> authConfig) {
    String secret = authSecretOf(authConfig);
    Map<String, String> out = new HashMap<>();
    headers.forEach(
        (k, v) -> {
          String joined = String.join(", ", v);
          if (SENSITIVE_HEADER_NAMES.contains(k.toLowerCase())) {
            out.put(k, "****");
          } else {
            out.put(k, AuthQueryMasker.scrubSecret(joined, secret));
          }
        });
    return out;
  }

  /**
   * 헬스체크 가능한 모든 API 연결을 비동기 Job으로 점검한다.
   *
   * <p>AsyncJobService로 Job을 생성(DB 추적)하고 pipelineExecutor 스레드풀에서 실행한다. 진행률은 SSE로 실시간 스트리밍된다. 클라이언트는
   * 반환된 jobId로 GET /api/v1/jobs/{jobId}/status 폴링 또는 SSE 구독 가능.
   *
   * @return AsyncJob ID (문자열 UUID)
   */
  public String refreshAllAsync(Long userId) {
    // 요청자 userId로 AsyncJob 생성 — 0L로 생성 시 user FK 위반 (#59)
    String jobId =
        asyncJobService.createJob(
            "API_CONNECTION_REFRESH_ALL", "api_connection", "all", userId, null);

    pipelineExecutor.execute(
        () -> {
          try {
            List<Record> targets = repository.findHealthCheckable();
            int total = targets.size();
            log.info("refreshAllAsync job {}: {} 대상 헬스체크 시작", jobId, total);

            for (int i = 0; i < total; i++) {
              Record r = targets.get(i);
              Long id = r.get(field(name("api_connection", "id"), Long.class));
              String connName = r.get(field(name("api_connection", "name"), String.class));

              try {
                testConnection(id);
              } catch (Exception e) {
                log.warn(
                    "refreshAllAsync job {}: connection id={} 실패 — {}", jobId, id, e.getMessage());
              }

              // 진행률 보고: 현재 처리 수 / 전체 대상 수
              int progress = total > 0 ? (int) ((i + 1) * 100.0 / total) : 100;
              asyncJobService.updateProgress(
                  jobId,
                  "CHECKING",
                  progress,
                  String.format("처리 중: %s (%d/%d)", connName, i + 1, total),
                  null);
            }

            asyncJobService.completeJob(jobId, Map.of("total", total, "checked", total));
            log.info("refreshAllAsync job {} 완료: {} 대상 처리", jobId, total);

          } catch (Exception e) {
            log.error("refreshAllAsync job {} 실패: {}", jobId, e.getMessage());
            asyncJobService.failJob(jobId, e.getMessage());
          }
        });

    return jobId;
  }

  // ── private helpers ────────────────────────────────────────────────────────

  /**
   * Base URL을 정규화하고 SSRF 보호 정책에 맞게 검증한다. SsrfProtectionService.validateUrl()을 활용하여 스킴 및 IP 대역 차단.
   *
   * @throws ApiConnectionException URL이 유효하지 않거나 내부 네트워크로의 요청인 경우
   */
  /**
   * healthCheckPath를 정규화한다. (#115)
   *
   * <ul>
   *   <li>{@code null} → null (request 에 필드 미포함 = 미변경)
   *   <li>빈 문자열/공백 → 빈 문자열 sentinel ("") (사용자가 명시적으로 비움 = 명시적 clear)
   *   <li>그 외 → 원본 값
   * </ul>
   *
   * <p>repository.update 가 sentinel "" 을 보면 컬럼을 SQL NULL 로 설정한다.
   */
  private String normalizeHealthCheckPath(String raw) {
    if (raw == null) return null;
    if (raw.isBlank()) return "";
    return raw;
  }

  private String validateAndNormalizeBaseUrl(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ApiConnectionException("baseUrl은 필수입니다");
    }
    try {
      URI uri = URI.create(raw);
      String scheme = uri.getScheme();
      if (scheme == null
          || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
        throw new ApiConnectionException("baseUrl은 http 또는 https 스킴이어야 합니다");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new ApiConnectionException("baseUrl에 호스트가 없습니다");
      }
      // SSRF 방어: 사설 IP/예약 IP 차단 (DNS 해석 포함)
      ssrfProtectionService.validateUrl(raw);
      return UrlUtils.normalizeBaseUrl(raw);
    } catch (SsrfException e) {
      // SsrfProtectionService 문구가 이미 원인을 담은 한국어 안내다(WD-150, 「내부망 주소는 호출할 수 없습니다(루프백): …」) —
      // 예전 「SSRF 보호: 」 접두는 영어 원문을 감싸던 용도라 사용자에게는 뜻 모를 용어만 더해서 뺀다.
      throw new ApiConnectionException(e.getMessage());
    } catch (IllegalArgumentException e) {
      // URI 파서의 영어 원문(「Illegal character in authority at index …」)은 싣지 않고 고정 안내만 보낸다(WD-180).
      throw new ApiConnectionException("baseUrl 형식이 올바르지 않습니다 — http(s)://호스트[:포트]/경로 형태로 입력하세요");
    }
  }

  /**
   * authType과 authConfig를 기반으로 HTTP 인증 헤더를 생성한다. API_KEY + placement=query 인 경우는 URL query param 으로
   * 전송해야 하므로 헤더에 포함하지 않는다(AuthQueryMasker.buildForAuth 가 처리). (#113)
   */
  private Map<String, String> buildAuthHeaders(String authType, Map<String, String> config) {
    Map<String, String> headers = new HashMap<>();
    if (config == null) return headers;

    if ("API_KEY".equals(authType)) {
      String placement = config.getOrDefault("placement", "header");
      if ("query".equals(placement)) {
        // placement=query 는 URL 에 붙이므로 헤더 미생성
        return headers;
      }
      String headerName = config.getOrDefault("headerName", "X-API-Key");
      String apiKey = config.get("apiKey");
      if (apiKey != null) headers.put(headerName, apiKey);
    } else if ("BEARER".equals(authType)) {
      String token = config.get("token");
      if (token != null) headers.put("Authorization", "Bearer " + token);
    }
    return headers;
  }

  /**
   * 동일 테넌트 내 이름 중복을 막는다 (#647). {@code api_connection.name}에는 유니크 제약이 없어(V93 주석 참고) 같은 이름의 연결이 여러 개
   * 생성될 수 있었고, 목록/삭제 확인 다이얼로그가 이름만으로 항목을 구분해 오삭제 위험이 있었다. RLS 정책이 테넌트 경계를 자동으로 걸어주므로 여기서는 이름만 검사하면
   * 된다.
   */
  private void validateNameNotDuplicated(String name) {
    if (repository.existsByName(name)) {
      throw new ApiConnectionNameAlreadyExistsException(name);
    }
  }

  /** 지원하는 인증 방식과 그 방식에 반드시 있어야 하는 비밀 필드. 인증 헤더·쿼리 조립({@link #buildAuthHeaders})이 읽는 키와 같다. */
  private static final Map<String, String> REQUIRED_SECRET_BY_AUTH_TYPE =
      Map.of("API_KEY", "apiKey", "BEARER", "token");

  /** 안내 문구에 싣는 지원 목록 — 순서를 고정해 문구가 실행마다 달라지지 않게 한다. */
  private static final String SUPPORTED_AUTH_TYPES_TEXT = "API_KEY, BEARER";

  /**
   * 인증 방식 값 자체를 검증한다 — 생성·수정·저장 전 테스트 공통. 지원 목록 밖(OAUTH2·BASIC·FOO 등)은 DB CHECK 제약(409 일반 문구)에 닿기
   * 전에 400 한국어 안내로 거절한다(WD-180).
   */
  private void validateAuthType(String authType) {
    if (authType == null || authType.isBlank()) {
      throw new ApiConnectionException(
          "인증 방식(authType)을 입력하세요 (사용 가능: " + SUPPORTED_AUTH_TYPES_TEXT + ")");
    }
    if (!REQUIRED_SECRET_BY_AUTH_TYPE.containsKey(authType)) {
      throw new ApiConnectionException(
          "지원하지 않는 인증 방식입니다: " + authType + " (사용 가능: " + SUPPORTED_AUTH_TYPES_TEXT + ")");
    }
  }

  /**
   * 인증 방식을 바꾸는 수정에는 새 방식의 비밀 필드를 담은 authConfig 가 함께 와야 한다(WD-180). 저장된 비밀은 옛 방식의 것이라(API 키 ↔ 토큰) 새
   * 방식으로 쓸 수 없다 — 없이 저장하면 인증 헤더 없이 호출되면서도 오류가 나지 않는다.
   *
   * <p>가린 값({@code ****…})은 여기서는 「값이 있음」으로 보고 넘긴다 — 방식이 바뀌면 유지할 저장값이 없으므로 뒤이은 {@link
   * #resolveMaskedSecrets} 가 따로 거절한다.
   *
   * @param newAuthType 검증을 마친(지원 목록 안의) 새 인증 방식
   */
  private static void requireAuthConfigForNewAuthType(
      String newAuthType, Map<String, String> authConfig) {
    String requiredKey = REQUIRED_SECRET_BY_AUTH_TYPE.get(newAuthType);
    String value = authConfig != null ? authConfig.get(requiredKey) : null;
    if (value == null || value.isBlank()) {
      throw new ApiConnectionException(
          "인증 방식을 "
              + newAuthType
              + "(으)로 바꾸려면 새 방식의 인증 정보(authConfig."
              + requiredKey
              + ")를 함께 입력하세요");
    }
  }

  /**
   * 요청 authConfig 의 비밀 필드에 실린 가린 값({@code ****…})을 처리한다(WD-179).
   *
   * <p>조회 응답의 maskedAuthConfig 는 비밀 필드를 {@code ****} + 끝 4자로 가린다. 예전에는 수정 요청이 이 값을 그대로 실어 보내면 (AI 챗
   * {@code update_api_connection}, API 직접 사용) 통째로 암호화해 저장했다 — 저장된 토큰이 {@code ****3456} 이 돼 이후 호출이
   * 모두 인증 실패하는데, 화면에는 여전히 {@code ****3456} 으로 보여 무엇이 바뀌었는지 알 수 없었다.
   *
   * <ul>
   *   <li>비밀 필드 판정은 응답 가리기와 같은 {@link ApiConnectionReader#isSensitiveKey} 를 쓴다(기준이 어긋나면 가려 나간 필드를
   *       여기서 놓친다).
   *   <li>가린 값이 저장된 같은 필드의 가린 형태와 <b>정확히</b> 같으면 저장 원문으로 채운다 — 조회 결과를 그대로 되돌려 보내도 비밀이 바뀌지 않고, 비밀이
   *       아닌 필드(placement, paramName 등)만 바꾸는 수정이 가능하다.
   *   <li>그 밖의 가린 값(끝 4자가 다름, 저장값 없음, authType 변경, 생성·저장 전 테스트)은 400 으로 거절한다. 문구에는 필드 이름만 싣고 저장
   *       원문이나 그 가린 형태는 싣지 않는다.
   * </ul>
   *
   * <p>예외는 {@link IllegalArgumentException} 이다 — 전역 처리기가 400 으로 바꾼다(WD-179 당시 {@link
   * ApiConnectionException} 은 404 로 매핑돼 있어 쓰지 않았다. WD-180 부터 그쪽도 400 이지만 동작이 같아 그대로 둔다). {@link
   * #validateAndNormalizeBaseUrl} 의 try 블록 안에서 부르면 그쪽 catch 가 다른 예외로 감싸므로 반드시 밖에서 부른다.
   *
   * @param requested 요청 authConfig(null 이면 null 을 그대로 돌려준다)
   * @param stored 현재 저장된 복호화 authConfig. 저장값이 없거나(생성·저장 전 테스트) authType 이 바뀌면 null
   * @param updating 수정 경로인지 — 거절 문구의 「그대로 두는 방법」 안내에만 쓴다
   * @return 가린 값을 저장 원문으로 채운 새 Map(요청 Map 은 불변일 수 있어 건드리지 않는다)
   * @throws IllegalArgumentException 유지할 수 없는 가린 값이 비밀 필드에 있을 때
   */
  private Map<String, String> resolveMaskedSecrets(
      Map<String, String> requested, Map<String, String> stored, boolean updating) {
    if (requested == null) return null;
    Map<String, String> resolved = new HashMap<>(requested);
    for (Map.Entry<String, String> entry : requested.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (!ApiConnectionReader.isSensitiveKey(key)
          || value == null
          || !value.startsWith(AuthQueryMasker.MASK_PREFIX)) {
        continue;
      }
      String storedValue = stored != null ? stored.get(key) : null;
      if (storedValue != null && encryptionService.maskValue(storedValue).equals(value)) {
        // 조회 결과를 그대로 되돌려 보낸 경우 — 이 필드만 저장 원문으로 채워 비밀을 유지한다.
        resolved.put(key, storedValue);
      } else {
        throw new IllegalArgumentException(maskedValueRejectedMessage(key, updating));
      }
    }
    return resolved;
  }

  /**
   * 가린 값 거절 문구. 수정은 authConfig 를 통째로 교체하므로 「그 항목만 빼고 보내라」고 안내하면 비밀이 지워진다 — 그대로 두는 방법은 authConfig 를
   * 아예 보내지 않거나 조회한 가린 값을 바꾸지 않고 보내는 것이다.
   */
  private static String maskedValueRejectedMessage(String key, boolean updating) {
    String field = "authConfig." + key;
    return updating
        ? field
            + " 에 가려진 값(****…)은 저장할 수 없습니다. 바꾸려면 새 값을 입력하고, 그대로 두려면 authConfig 를 보내지 않거나"
            + " 조회한 가린 값을 바꾸지 말고 그대로 보내세요."
        : field + " 에 가려진 값(****…)은 인증 값으로 쓸 수 없습니다. 실제 값을 입력하세요.";
  }

  private String serializeAndEncrypt(Map<String, String> authConfig) {
    try {
      String json = objectMapper.writeValueAsString(authConfig);
      return encryptionService.encrypt(json);
    } catch (JsonProcessingException e) {
      // 문자열 Map 직렬화 실패는 서버 쪽 문제라 입력 오류(400)가 아니다 — 500 으로 보내고 원문은 원인(cause)으로만 남긴다(WD-180).
      throw new SerializationException("API 연결 인증 정보를 저장하지 못했습니다", e);
    }
  }

  // ── 감사 로그(WD-181) ─────────────────────────────────────────────────────────

  /** 감사 metadata 의 바뀐 항목 키 → description 「바뀐 항목: …」에 쓰는 화면 문구. */
  private static final Map<String, String> AUDIT_FIELD_LABELS =
      Map.of(
          "name", "이름",
          "description", "설명",
          "baseUrl", "기준 URL",
          "healthCheckPath", "헬스체크 경로",
          "authType", "인증 방식",
          "authConfig", "인증 정보");

  /**
   * 수정 감사. 저장된 값({@code existing})과 실제로 쓴 값을 비교해 바뀐 항목만 모은다 — 요청에 필드가 있어도 값이 같으면 바뀐 것이 아니다. 바뀐 항목이
   * 없으면 남기지 않는다.
   *
   * <p>metadata: {@code changedFields}(바뀐 항목 키), {@code credentialsChanged}(인증 정보 변경 여부
   * true/false), {@code before}/{@code after}(이름·인증 방식·기준 URL·헬스체크 경로 — 비밀이 아닌 항목만, URL·경로의 쿼리는
   * 지운다). 연결 설명(자유 문장)은 값을 남기지 않고 바뀐 항목 목록에만 넣는다.
   */
  private void auditUpdate(
      Long userId,
      Long id,
      Record existing,
      UpdateApiConnectionRequest request,
      String authType,
      String normalizedBaseUrl,
      String normalizedHealthCheckPath,
      boolean credentialsChanged) {
    String beforeName = existing.get(field(name("api_connection", "name"), String.class));
    String beforeDescription =
        existing.get(field(name("api_connection", "description"), String.class));
    String beforeAuthType = existing.get(field(name("api_connection", "auth_type"), String.class));
    String beforeBaseUrl = existing.get(field(name("api_connection", "base_url"), String.class));
    String beforeHealthCheckPath =
        existing.get(field(name("api_connection", "health_check_path"), String.class));

    // 리포지토리 규칙대로 실제로 저장되는 값을 구한다 — null 은 미변경, 헬스체크 경로 "" 는 NULL 로 지움(#115).
    String afterName = request.name() != null ? request.name() : beforeName;
    String afterDescription =
        request.description() != null ? request.description() : beforeDescription;
    String afterBaseUrl = normalizedBaseUrl != null ? normalizedBaseUrl : beforeBaseUrl;
    String afterHealthCheckPath =
        normalizedHealthCheckPath == null
            ? beforeHealthCheckPath
            : (normalizedHealthCheckPath.isEmpty() ? null : normalizedHealthCheckPath);

    List<String> changed = new ArrayList<>();
    if (!Objects.equals(beforeName, afterName)) changed.add("name");
    if (!Objects.equals(beforeDescription, afterDescription)) changed.add("description");
    if (!Objects.equals(beforeBaseUrl, afterBaseUrl)) changed.add("baseUrl");
    if (!Objects.equals(beforeHealthCheckPath, afterHealthCheckPath)) {
      changed.add("healthCheckPath");
    }
    if (!Objects.equals(beforeAuthType, authType)) changed.add("authType");
    if (credentialsChanged) changed.add("authConfig");
    if (changed.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략(ROLE_UPDATE 와 같은 규칙)
    }

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("changedFields", changed);
    metadata.put("credentialsChanged", credentialsChanged);
    metadata.put(
        "before", updateSnapshot(beforeName, beforeAuthType, beforeBaseUrl, beforeHealthCheckPath));
    metadata.put("after", updateSnapshot(afterName, authType, afterBaseUrl, afterHealthCheckPath));

    // changed 는 위에서 화면 순서(이름 → 인증 정보)대로 쌓였으므로 그대로 문구로 바꾼다.
    List<String> labels = changed.stream().map(AUDIT_FIELD_LABELS::get).toList();
    audit(
        userId,
        "UPDATE",
        id,
        "API 연결 수정: " + afterName + " (바뀐 항목: " + String.join("·", labels) + ")",
        metadata);
  }

  /** 생성·삭제 감사 metadata — 이름·인증 방식·기준 URL(쿼리·userinfo 제거). 인증 정보는 담지 않는다. */
  private static Map<String, Object> connectionSnapshot(
      String connectionName, String authType, String baseUrl) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("name", connectionName);
    snapshot.put("authType", authType);
    snapshot.put("baseUrl", redactBaseUrl(baseUrl));
    return snapshot;
  }

  /** 수정 감사 before/after 스냅샷. 값이 null 일 수 있어 Map.of 대신 LinkedHashMap 을 쓴다. */
  private static Map<String, Object> updateSnapshot(
      String connectionName, String authType, String baseUrl, String healthCheckPath) {
    Map<String, Object> snapshot = connectionSnapshot(connectionName, authType, baseUrl);
    snapshot.put("healthCheckPath", stripQuery(healthCheckPath));
    return snapshot;
  }

  /**
   * 기준 URL 의 쿼리·프래그먼트·userinfo 를 지운다. 저장 경로에서 쿼리 키가 붙을 일은 없지만, 혹시 붙어 있어도 감사 로그에 비밀이 남지 않게 한다 — API
   * 호출 오류 문구와 같은 {@link ApiCallErrorMessages#redactUrl} 을 쓴다.
   */
  private static String redactBaseUrl(String baseUrl) {
    return baseUrl == null ? null : ApiCallErrorMessages.redactUrl(baseUrl);
  }

  /** 헬스체크 경로의 쿼리·프래그먼트를 지운다 — 경로에 키를 쿼리로 적어 둔 연결이라도 감사 로그에 값이 남지 않게. */
  private static String stripQuery(String path) {
    if (path == null) return null;
    int cut = path.length();
    int q = path.indexOf('?');
    int h = path.indexOf('#');
    if (q >= 0) cut = Math.min(cut, q);
    if (h >= 0) cut = Math.min(cut, h);
    return path.substring(0, cut);
  }

  /**
   * 인증 정보 비교용 사본. 저장값 조회({@link ApiConnectionReader#getDecryptedAuthConfig})는 저장 JSON 에 없던 {@code
   * authType} 키를 덧붙여 돌려주므로, 양쪽에서 그 키를 빼고 비교한다(인증 방식은 컬럼으로 따로 비교한다).
   */
  private static Map<String, String> withoutAuthTypeKey(Map<String, String> config) {
    Map<String, String> copy = new HashMap<>(config == null ? Map.of() : config);
    copy.remove("authType");
    return copy;
  }

  /**
   * API 연결 감사 로그(resource = api_connection, resourceId = 연결 id). 행위자 username 은 감사 테이블 NOT NULL 이라
   * 조회해서 넣는다 — RoleService.audit 와 같은 방식이다. 호출 트랜잭션 안에서 쓰므로 audit_log.tenant_id 는 GUC 기본값, 즉 현재
   * 워크스페이스로 기록된다. 성공 경로만 기록한다(다른 도메인의 생성·수정·삭제와 같다).
   */
  private void audit(
      Long userId, String action, Long connectionId, String description, Object metadata) {
    String actorName =
        userId == null
            ? "unknown"
            : userRepository.findById(userId).map(UserResponse::username).orElse("unknown");
    auditLogService.log(
        userId,
        actorName,
        action,
        "api_connection",
        String.valueOf(connectionId),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }
}
