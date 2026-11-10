package com.smartfirehub.apiconnection.service;

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
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository.ApiConnectionRow;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository.HealthCheckTarget;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.exception.SerializationException;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.pipeline.service.executor.ApiCallAuth;
import com.smartfirehub.pipeline.service.executor.ApiCallErrorMessages;
import com.smartfirehub.pipeline.service.executor.RedirectFollower;
import com.smartfirehub.pipeline.service.executor.SsrfException;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.pipeline.service.executor.SsrfSafeHttpClientFactory;
import com.smartfirehub.pipeline.service.executor.UrlAuthority;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

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

  /**
   * 상태 전환(UP↔DOWN) 알림 + 감사 STATUS_CHANGE. 상태를 저장하는 유일한 곳({@link #testConnection(Long, Long)})에서 저장
   * 직후 부른다 — 주기 점검만 알리던 예전에는 「테스트」·새로고침이 먼저 바꾼 상태가 알림 없이 사라졌다(WD-189).
   */
  private final ApiConnectionNotifier notifier;

  /** 감사 행위자 username 조회용 — audit_log.username 이 NOT NULL 이라 RoleService·UserService 처럼 조회해서 넣는다. */
  private final UserRepository userRepository;

  @Qualifier("pipelineExecutor")
  private final Executor pipelineExecutor;

  /**
   * 헬스체크용 HTTP 클라이언트의 출처(WD-613) — 접속 시점에 해석한 IP 를 SSRF 정책으로 다시 검사한다. 예전에는 기본 WebClient 가 호출 직전 검사
   * 뒤에 호스트를 다시 해석해, DNS 재바인딩으로 내부 주소에 접속할 수 있었다. 리다이렉트 홉도 같은 클라이언트로 나가므로 홉마다 같은 검사를 받는다.
   */
  private final SsrfSafeHttpClientFactory ssrfSafeHttpClientFactory;

  /**
   * 쿼리 인증이 아닌 호출 URL(경로의 한글·공백 등 인코딩 전)을 URI 로 바꾸는 팩토리 — 예전 {@code webClient.get().uri(String)} 이
   * 쓰던 WebClient 기본 팩토리(템플릿·값 인코딩)와 같아 나가는 바이트가 바뀌지 않는다(WD-693).
   */
  private static final DefaultUriBuilderFactory URI_FACTORY = new DefaultUriBuilderFactory();

  /** 헬스체크 HTTP 호출 타임아웃(초). 메시지 문구(「응답 시간 초과(N초) — …」)와 값이 어긋나지 않도록 한 곳에서 관리한다. */
  private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 5;

  // ── 공개 API ────────────────────────────────────────────────────────────────

  @Transactional
  public ApiConnectionResponse create(CreateApiConnectionRequest request, Long userId) {
    validateAuthType(request.authType());
    // baseUrl 의 사용자 정보(아이디:비밀번호@) 규칙(WD-681) — BASIC 이면 인증 설정으로 옮기고 baseUrl 에서 뗀다, 그 밖의 방식이면 400.
    BaseUrlCandidate baseUrl = applyUserInfoRule(request.authType(), request.baseUrl(), false);
    // 저장값이 없으니 가린 값(****…)은 유지할 대상도 없다 — 그대로 저장되지 않게 거절한다(WD-179).
    // 비밀 필드 빈 값(정리한 뒤 기준, WD-588 → WD-623)을 먼저, 이어서 헤더·파라미터 이름과 값 줄바꿈을 저장 전에 검사한다(WD-392) —
    // 쓸 수 없는 값이 저장돼 테스트가 네트워크 오류처럼 안내되지 않게. 옮긴 사용자 정보는 가린 값 처리 뒤에 합친다(합친 값으로 필수 검사).
    Map<String, String> normalizedConfig =
        AuthConfigValidator.validateAndNormalize(
            request.authType(),
            mergeUserInfo(
                resolveMaskedSecrets(request.authConfig(), null, false), baseUrl.moved()));
    // 입력 검증(인증 정보·기준 URL, 400)은 이름 중복(409)보다 먼저 — 입력 자체가 틀린 요청은 400 으로 답한다.
    String normalizedBaseUrl = validateAndNormalizeBaseUrl(baseUrl.baseUrl());
    validateNameNotDuplicated(request.name());

    String encryptedConfig = serializeAndEncrypt(normalizedConfig);
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

  /**
   * 호출용 단건 조회(WD-681) — baseUrl 을 가리지 않은 저장 원문으로 싣는다. API_CALL 미리보기·파이프라인 실행처럼 실제 요청 URL 을 만드는 곳만
   * 쓴다(응답으로 내보내지 말 것). 구현은 {@link ApiConnectionReader#getByIdForCall}.
   */
  @Transactional(readOnly = true)
  public ApiConnectionResponse getByIdForCall(Long id) {
    return reader.getByIdForCall(id);
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
   *   <li>authConfig 를 보내면 방식이 그대로여도 정리한 값(「Bearer 」 떼기·가린 값 채우기 뒤)에 비밀 필드가 있어야 한다(400, WD-623).
   *       authConfig 는 통째로 교체되므로 {@code {}}·빈 토큰을 받으면 저장된 비밀이 빈 값으로 덮인다. 조회한 가린 값을 그대로 보내면 저장 원문으로
   *       채워져 통과한다.
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
    ApiConnectionRow existing =
        repository.findById(id).orElseThrow(() -> new ApiConnectionNotFoundException(id));
    String currentAuthType = existing.authType();

    // 입력 검증(인증 방식·인증 정보·기준 URL, 400)은 이름 중복(409)보다 먼저 — 입력 자체가 틀린 요청은 400 으로 답한다.
    boolean authTypeChanged = false;
    if (request.authType() != null) {
      validateAuthType(request.authType());
      authTypeChanged = !request.authType().equals(currentAuthType);
    }
    // 검사·저장·감사 모두 바뀐 뒤의 인증 방식 기준이다(WD-392) — 방식을 바꾸지 않는 수정이면 저장된 방식으로 본다.
    String effectiveAuthType = request.authType() != null ? request.authType() : currentAuthType;

    // baseUrl 의 사용자 정보(WD-681). 화면은 가린 baseUrl(아이디:****@)을 받아 다른 칸·경로·쿼리만 고쳐 보낼 수 있다 —
    // 옛 연결(API_KEY·BEARER + 사용자 정보)은 이관하지 않으므로 이때 저장된 비밀번호를 되살린다. 되살리는 것은 스킴·호스트·포트·아이디가 저장값과 같을
    // 때뿐이고(WD-681
    // 리뷰), 가린 값을 남긴 채 주소·아이디를 바꾸면 저장된 비밀번호를 새 주소로 보내지 않도록 400 이다. BASIC 으로 바꾸며 baseUrl 을 보내지
    // 않으면 저장된 baseUrl 의 사용자 정보를 옮긴다(BASIC 연결의 baseUrl 에는 사용자 정보를 남기지 않는다). 되살린 사용자 정보는 이번 요청에서
    // 입력한 값이 아니므로 인증 설정과 충돌로 보지 않는다(아래 putUserInfoIfAbsent).
    String requestedBaseUrl =
        request.baseUrl() != null && !request.baseUrl().isBlank() ? request.baseUrl() : null;
    boolean restoredStoredBaseUrl = false;
    if (requestedBaseUrl != null && BaseUrlUserInfo.hasUserInfo(requestedBaseUrl)) {
      java.util.Optional<String> restored =
          BaseUrlUserInfo.restoreStoredUserInfo(requestedBaseUrl, existing.baseUrl());
      if (restored.isPresent()) {
        requestedBaseUrl = restored.get();
        restoredStoredBaseUrl = true;
      } else if (BaseUrlUserInfo.hasMaskedPassword(requestedBaseUrl)
          && BaseUrlUserInfo.hasPassword(existing.baseUrl())) {
        throw new ApiConnectionException(USERINFO_MASKED_OTHER_ADDRESS_MESSAGE);
      }
    } else if (requestedBaseUrl == null
        && "BASIC".equals(effectiveAuthType)
        && BaseUrlUserInfo.hasUserInfo(existing.baseUrl())) {
      requestedBaseUrl = existing.baseUrl();
      restoredStoredBaseUrl = true;
    }
    BaseUrlCandidate baseUrl =
        requestedBaseUrl != null
            ? applyUserInfoRule(effectiveAuthType, requestedBaseUrl, restoredStoredBaseUrl)
            : new BaseUrlCandidate(null, null);
    // 저장소에서 되살린 옛 사용자 정보는 BASIC 이면 인증 설정으로 옮긴다 — 방식별 분기 전에 한 번만 다룬다. 요청 authConfig 가 아이디·비밀번호를
    // 하나라도 주면 그 값이 이기고(옛 사용자 정보는 baseUrl 에서 떼기만 한다), 아무것도 주지 않으면 옛 사용자 정보를 채운다. authConfig 없이
    // 같은 방식을 유지하는 수정은 아래에서 저장된 설정에 덮어쓴다.
    BaseUrlUserInfo.UserInfo restoredUserInfo =
        restoredStoredBaseUrl && "BASIC".equals(effectiveAuthType)
            ? BaseUrlUserInfo.extract(requestedBaseUrl).orElse(null)
            : null;
    Map<String, String> requestedConfig = request.authConfig();
    if (restoredUserInfo != null && (authTypeChanged || requestedConfig != null)) {
      requestedConfig = putUserInfoIfAbsent(requestedConfig, restoredUserInfo);
    }

    String encryptedConfig = null;
    // 인증 정보가 실제로 바뀌었는지(감사용, WD-181). 인증 방식이 바뀌면 새 방식의 비밀이 반드시 함께 오므로 바뀐 것이다.
    boolean credentialsChanged = authTypeChanged;
    if (authTypeChanged) {
      // baseUrl 에서 옮긴 사용자 정보(BASIC)는 요청 authConfig 에 합쳐서 본다(WD-681) — 옛 연결을 BASIC 으로 바꾸며 아이디·비밀번호를
      // 입력하지 않으면 baseUrl 의 사용자 정보가 새 방식의 비밀이고, 입력했으면 입력한 값을 쓴다.
      Map<String, String> requested = mergeUserInfo(requestedConfig, baseUrl.moved());
      // 방식 변경은 새 방식의 비밀이 반드시 함께 와야 한다(WD-180). 가린 값 거절·헤더 이름 등 다른 사유보다 먼저 본다 — 무엇을 빠뜨렸는지가 가장
      // 중요한 안내다(WD-623).
      requireSecretForNewAuthType(effectiveAuthType, requested);
      // authType 이 바뀌면 같은 이름의 필드라도 뜻이 달라지므로 저장값을 쓰지 않는다 — 가린 값은 모두 거절된다(WD-179).
      Map<String, String> resolved =
          AuthConfigValidator.validateAndNormalize(
              effectiveAuthType, resolveMaskedSecrets(requested, null, true));
      encryptedConfig = serializeAndEncrypt(resolved);
    } else if (request.authConfig() != null
        || baseUrl.moved() != null
        || restoredUserInfo != null) {
      Map<String, String> stored = reader.getDecryptedAuthConfig(id);
      Map<String, String> base;
      if (request.authConfig() != null) {
        // baseUrl 에 새로 입력한 사용자 정보를 먼저 합친다(WD-681 리뷰) — 가린 비밀번호(****)를 그대로 되돌려 보낸 칸은 새로 입력한 값이
        // 아니라 baseUrl 의 비밀번호가 바꾼다. 아이디·비밀번호를 함께 입력했고 서로 다르면 400.
        Map<String, String> requested = mergeUserInfo(requestedConfig, baseUrl.moved());
        // 조회 결과(maskedAuthConfig)를 그대로 되돌려 보낸 비밀 필드는 저장 원문으로 채운다(WD-179).
        rejectMaskedEchoOfEmptyStoredSecret(effectiveAuthType, requested, stored);
        base = resolveMaskedSecrets(requested, stored, true);
      } else {
        // authConfig 없이 BASIC 연결의 baseUrl 에 사용자 정보만 넣은(또는 되살린) 수정(WD-681 리뷰) — 그 사용자 정보가 저장된
        // 아이디·비밀번호를 바꾼다(저장값과 다르다고 충돌로 보지 않는다 — 이번 요청이 입력한 값은 baseUrl 하나뿐이다).
        base = new HashMap<>(stored);
        base.remove("authType");
        base.putAll(userInfoFields(baseUrl.moved() != null ? baseUrl.moved() : restoredUserInfo));
      }
      // authConfig 는 통째로 교체되므로 방식이 그대로여도 비밀 필드가 비면 거절한다(WD-623) — 예전에는 방식이 바뀔 때만 봐서 {}·빈 토큰이
      // 저장된 비밀을 빈 값으로 덮었다(200). 가린 값을 그대로 보낸 경우는 저장 원문으로 채워져 통과한다. 빈 값 검사는 validateAndNormalize 가
      // 정리한 값으로 다른 필드 검사보다 먼저 한다.
      Map<String, String> resolved =
          AuthConfigValidator.validateAndNormalize(effectiveAuthType, base);
      encryptedConfig = serializeAndEncrypt(resolved);
      // 비교는 메모리 안에서만 한다 — 값은 어디에도 남기지 않고 true/false 만 감사에 싣는다. 양쪽을 같은 규칙(authType 키 빼기·토큰 정리·
      // API_KEY 기본값)으로 맞춰 비교해 뜻이 같은 저장을 변경으로 오판하지 않는다(WD-639).
      credentialsChanged =
          !AuthConfigValidator.comparableConfig(effectiveAuthType, resolved)
              .equals(AuthConfigValidator.comparableConfig(effectiveAuthType, stored));
    }

    // baseUrl이 있으면 정규화 및 SSRF 검증 — 사용자 정보를 옮겼으면 뗀 값이다(WD-681).
    String normalizedBaseUrl = null;
    if (baseUrl.baseUrl() != null) {
      normalizedBaseUrl = validateAndNormalizeBaseUrl(baseUrl.baseUrl());
    }

    // 이름을 변경하는 경우에만 중복 검사 — 자기 자신은 제외 (#647)
    if (request.name() != null && !request.name().isBlank()) {
      if (repository.existsByNameExcludingId(request.name(), id)) {
        throw new ApiConnectionNameAlreadyExistsException(request.name());
      }
    }

    String normalizedHealthCheckPath = normalizeHealthCheckPath(request.healthCheckPath());
    repository.update(
        id,
        request.name(),
        request.description(),
        effectiveAuthType,
        encryptedConfig,
        normalizedBaseUrl,
        normalizedHealthCheckPath);

    auditUpdate(
        userId,
        id,
        existing,
        request,
        effectiveAuthType,
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
    ApiConnectionRow existing =
        repository.findById(id).orElseThrow(() -> new ApiConnectionNotFoundException(id));
    String connectionName = existing.name();
    Map<String, Object> snapshot =
        connectionSnapshot(connectionName, existing.authType(), existing.baseUrl());
    // 동시 삭제(WD-584) — 위 조회는 먼저 지운 쪽이 커밋하기 전이면 통과한다. 진 쪽 DELETE 는 그 커밋을 기다린 뒤 0 행이므로, 순차 두 번째
    // 삭제와 같은 404 로 끝내고 감사를 남기지 않는다.
    if (repository.deleteById(id) == 0) {
      throw new ApiConnectionNotFoundException(id);
    }
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
            .map(r -> new ApiConnectionReferencesResponse.ReferenceItem(r.id(), r.name()))
            .toList();

    return new ApiConnectionReferencesResponse(id, pipelines, pipelines.size());
  }

  /** 일반 사용자가 파이프라인 스텝에서 선택할 수 있는 slim 목록을 반환. 민감한 authConfig, healthCheckPath, last* 필드는 제외. */
  @Transactional(readOnly = true)
  public List<ApiConnectionSelectableResponse> findSelectable() {
    return repository.findAll().stream()
        // baseUrl 은 사용자 정보의 비밀번호를 가린다(WD-681) — 이 목록은 일반 사용자(파이프라인 편집)에게도 나간다.
        .map(
            r ->
                new ApiConnectionSelectableResponse(
                    r.id(), r.name(), r.authType(), BaseUrlUserInfo.mask(r.baseUrl())))
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
   * <p>화면의 「테스트」(AI 연결 테스트 도구도 같은 API)뿐 아니라 10분 주기 상태 점검({@link
   * ApiConnectionHealthCheckScheduler})과 일괄 새로고침({@link #refreshAllAsync})도 이 메서드를 부른다. 호출 직전 주소
   * 검사는 {@link #callHealthCheck} 가 맡는다(WD-178).
   *
   * <p><b>상태 전환 알림도 여기서 한다(WD-189).</b> last_status 를 쓰는 곳은 이 메서드뿐이라, 저장이 돌려준 직전 상태(행 잠금으로 읽음)와 새
   * 상태를 비교해 바뀌었으면 알림 + 감사 STATUS_CHANGE 를 남긴다. 예전에는 주기 점검만 순회 시작 때 읽어 둔 값과 비교해, 그 전에 「테스트」가 상태를 바꿔
   * 두면 끊김·복구가 알림·기록 없이 사라졌다.
   *
   * @param actorUserId 「테스트」·전체 새로고침을 누른 사용자. null 이면 시스템(10분 주기 점검) — 감사 기록의 행위자가 된다
   */
  public TestConnectionResponse testConnection(Long id, Long actorUserId) {
    // ── 여기까지가 트랜잭션 구간(각 호출이 reader 프록시에서 자체 트랜잭션을 연다) ──
    // 호출 URL 은 저장 원문 baseUrl 로 만든다(WD-681) — 옛 연결의 사용자 정보를 가린 값으로 부르면 안 된다. 응답 requestUrl 은
    // callHealthCheck 가 가린다.
    ApiConnectionResponse conn = reader.getByIdForCall(id);
    Map<String, String> rawConfig = reader.getDecryptedAuthConfig(id);
    // ── 아래 HTTP 호출은 트랜잭션 밖이다 ──
    // 인증은 파이프라인 API_CALL 실행·미리보기와 같은 진입점으로 푼다(WD-657) — 방식은 연결의 auth_type 컬럼, 키·대체 키·대소문자·Bearer
    // 접두 정리는 실행과 같다. 예전 규칙(소문자 query 만·paramName 필수·대체 키 없음·Bearer 정리 없음)으로는 「테스트는 실패, 실행은 성공」
    // 또는 그 반대가 났다.
    TestConnectionResponse result =
        callHealthCheck(
            UrlUtils.joinUrl(conn.baseUrl(), conn.healthCheckPath()),
            ApiCallAuth.resolve(conn, rawConfig));
    // 성공·HTTP 오류·연결 실패·주소 차단 모두 같은 규칙으로 저장한다.
    recordHealthResult(id, conn.name(), result, actorUserId);
    return result;
  }

  /** 시스템(10분 주기 점검)이 부르는 상태 점검 — 행위자 없이 {@link #testConnection(Long, Long)} 과 같다. */
  public TestConnectionResponse testConnection(Long id) {
    return testConnection(id, null);
  }

  /**
   * 점검 결과를 저장하고, 직전 상태와 달라졌으면 알림 + 감사 STATUS_CHANGE 를 남긴다 — 상태를 저장하는 모든 경로(주기 점검·「테스트」·전체 새로고침)가
   * 거치는 한 곳(WD-189).
   *
   * <p><b>원자적 비교</b>: 직전 상태는 저장과 같은 트랜잭션에서 행 잠금으로 읽은 값({@link
   * ApiConnectionRepository#updateHealthStatus} 의 반환값)이다. 점검과 테스트가 같은 연결을 동시에 바꿔도 두 번째 호출은 첫 호출이
   * 커밋한 값을 직전 상태로 받으므로 한 전환에 알림이 두 번 나거나 빠지지 않는다. 첫 점검(직전 null)·같은 상태는 기존 규칙대로 {@link
   * ApiConnectionNotifier} 가 건너뛴다.
   *
   * <p><b>커밋 후 발송</b>: 이 메서드(와 {@link #testConnection(Long, Long)})에는 트랜잭션이 없다. 저장은 리포지토리 자신의
   * 트랜잭션에서 커밋된 뒤 반환되므로, 그 다음 줄의 알림은 커밋된 상태에 대해서만 나간다 — 주기 점검이 저장 후 알리던 예전 순서와 같다.
   *
   * <p>알림·감사가 실패해도 이미 저장된 점검 결과는 그대로 돌려준다(예전에는 스케줄러가 같은 이유로 감쌌다) — 「테스트」 응답이 500 이 되지 않게 한다.
   */
  private void recordHealthResult(
      Long id, String connectionName, TestConnectionResponse result, Long actorUserId) {
    String newStatus = result.ok() ? "UP" : "DOWN";
    String previousStatus =
        repository.updateHealthStatus(id, newStatus, result.latencyMs(), result.errorMessage());
    try {
      notifier.notifyStatusChange(
          id, connectionName, previousStatus, newStatus, result.errorMessage(), actorUserId);
    } catch (RuntimeException e) {
      log.error(
          "API 연결 상태 전환 알림 실패 — connection id={}, {}→{}: {}",
          id,
          previousStatus,
          newStatus,
          e.getMessage());
    }
  }

  /**
   * (#90) 저장 전 dry-run 헬스체크. CreateApiConnectionRequest의 payload를 가지고 외부 API 호출만 수행하고 DB는 변경하지 않는다.
   * authType 검증/baseUrl SSRF 검증은 동일하게 수행하므로 잘못된 입력은 즉시 ApiConnectionException(400, WD-180)으로 차단된다.
   */
  public TestConnectionResponse testConnectionPayload(CreateApiConnectionRequest request) {
    validateAuthType(request.authType());
    // baseUrl 의 사용자 정보는 생성과 같은 규칙이다(WD-681) — BASIC 이면 인증 설정으로 옮겨 Basic 헤더로 보내고, 그 밖의 방식이면 400.
    BaseUrlCandidate baseUrl = applyUserInfoRule(request.authType(), request.baseUrl(), false);
    // 저장 전 테스트도 가린 값(****…)을 인증 값으로 보내지 않는다 — 저장값이 없으니 유지할 것도 없다(WD-179).
    // 비밀 필드가 없거나 비면(정리한 뒤 기준) 대상 서버를 부르지 않고 생성과 같은 안내로 거절한다(WD-623) — 예전에는 빈 인증으로 호출해 대상
    // 서버의 401/403 만 보여 무엇을 빠뜨렸는지 알 수 없었다. 이 검사는 validateAndNormalize 가 다른 필드 검사보다 먼저 한다.
    Map<String, String> rawConfig =
        AuthConfigValidator.validateAndNormalize(
            request.authType(),
            mergeUserInfo(
                resolveMaskedSecrets(request.authConfig(), null, false), baseUrl.moved()));
    String normalizedBaseUrl = validateAndNormalizeBaseUrl(baseUrl.baseUrl());
    String normalizedPath = normalizeHealthCheckPath(request.healthCheckPath());
    // 저장 전이라 연결 행이 없다 — 요청의 방식을 연결 컬럼처럼 보고 같은 규칙으로 푼다(WD-657).
    return callHealthCheck(
        UrlUtils.joinUrl(normalizedBaseUrl, normalizedPath),
        ApiCallAuth.resolveWithAuthType(request.authType(), rawConfig));
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
   * <p><b>리다이렉트(WD-693).</b> 미리보기·실행과 같은 {@link RedirectFollower#follow} 로 따라간다 —
   * 301·302·303·307·308 만, 홉마다 주소 검사(SSRF)·다른 출처 홉의 인증 떼기·최대 홉 수가 같다. 예전에는 따라가지 않고 3xx 를 결과로
   * 돌려줘(3xx 를 성공처럼 보이기도 했다) 같은 연결의 테스트와 실행 결과가 달랐다. 결과는 마지막 응답이고 표시 URL({@code requestUrl})은 처음 요청
   * 주소(키 가림)다. 시간 제한은 모든 홉을 합쳐 {@link #HEALTH_CHECK_TIMEOUT_SECONDS} 한 번이다. DNS: WebClient 가 접속 때
   * 다시 해석하지만, 그 해석 결과를 {@link SsrfSafeHttpClientFactory} 의 해석기가 같은 판정으로 다시 검사한다(WD-613) — 첫 요청이든
   * 리다이렉트 홉이든 검사 뒤 DNS 가 내부 주소로 바뀌어도(재바인딩) 접속하지 않고 같은 차단 문구의 DOWN 결과가 된다.
   *
   * <p><b>인증(WD-657).</b> 헤더·쿼리 키·가릴 비밀 모두 {@link ApiCallAuth#resolve} 로 푼 값 하나로 만든다 — 파이프라인
   * 실행(Java·Python 실행기)·미리보기와 같은 값을 보낸다.
   *
   * @param targetUrl 인증 쿼리를 붙이기 전 호출 URL(기준 URL + 헬스체크 경로)
   * @param auth 실행과 같은 규칙으로 푼 인증 설정
   */
  private TestConnectionResponse callHealthCheck(String targetUrl, ApiCallAuth.ResolvedAuth auth) {
    // 응답 본문·헤더·오류 문구에서 가릴 값 — 실제로 보내는 비밀 전부다(쿼리 키뿐 아니라 헤더 키·Bearer 토큰, WD-657).
    // BASIC 은 비밀번호와 Basic 헤더의 Base64 덩어리 둘 다 가린다(WD-681).
    AuthQueryMasker.SentSecrets secret = auth.sentSecrets();
    // 응답의 requestUrl 은 인증 쿼리 키를 가린 표시용 URL 이다(WD-177). URL 을 만들기 전에 실패하면 키 없는 기준 URL 을 보인다.
    // 옛 연결의 baseUrl 사용자 정보는 비밀번호를 가린다(WD-681) — 이 값은 응답·화면·AI 챗으로 나간다.
    String displayUrl = BaseUrlUserInfo.mask(targetUrl);

    long start = System.currentTimeMillis();
    // 이미 저장된 인증 값이 HTTP 요청에 쓸 수 없는 것이면(WD-392 전에 저장된 띄어쓰기 헤더 이름 등) 호출하지 않고 그 사유를 보인다 —
    // 호출하면 클라이언트 예외가 「대상 서버에 연결하지 못했습니다」로 안내돼 주소·네트워크를 의심하게 된다.
    // 검사도 보내는 값으로 한다(WD-657) — 문구는 연결 화면용(「… 입력하세요」)이다.
    String authProblem = AuthConfigValidator.describeSentValueProblem(auth);
    if (authProblem != null) {
      return new TestConnectionResponse(
          false, null, 0L, authProblem, displayUrl, null, Map.of(), null);
    }
    try {
      // 호출 직전 주소 검사(WD-178) — 내부망·로컬이면 SsrfException 으로 아래에서 DOWN 결과가 되고, 외부 호출은 한 건도 나가지 않는다.
      ssrfProtectionService.validateUrl(targetUrl);

      // placement=query 인 경우 URL 에 인증 파라미터 부착 (#113). 호출용·표시용을 함께 만든다(WD-177) — try 안에서 만들어,
      // URL 을 만들 수 없어 실패해도 400 응답에 키가 실리지 않고 DOWN 결과(고정 문구)로 돌아가게 한다. 키 값은 인코딩 키·디코딩 키
      // 모두 공용 규칙(QueryApiKeyEncoding)으로 한 번만 인코딩된다(WD-182).
      // 쿼리 인증이 아니면 URL 을 손대지 않는다(정적 파라미터 없음 → null) — 공용 빌더(ResolvedAuth.buildUrls, WD-657).
      // 표시용 URL 은 사용자 정보 비밀번호까지 가려져 나온다(WD-681, buildUrls).
      AuthQueryMasker.AuthQueryUrls urls = auth.buildUrls(targetUrl, null);
      displayUrl = urls.displayUrl();
      // 응답 본문/헤더를 함께 받아 디버깅용으로 노출 (#76).
      // 쿼리 인증이면 이미 인코딩을 마친 URI 를 쓴다(WD-182) — 다시 인코딩하면 인코딩 키의 %2B 가 %252B 로 나간다. 쿼리 인증이 아니면 예전
      // webClient.get().uri(String) 과 같은 기본 팩토리로 경로의 한글·공백을 인코딩한다(buildUrls 가 URL 을 손대지 않는다).
      URI callUri = auth.isQueryApiKey() ? urls.callUri() : URI_FACTORY.expand(urls.callUrl());
      // 리다이렉트는 미리보기·실행과 같은 RedirectFollower 로 따라간다(WD-693). Netty 가 못 부르는 호스트(밑줄 등)는 첫 홉부터 홉마다 요청
      // 전에
      // 고정 문구로 실패하고(WD-676), 리다이렉트 대상이 내부망이면 SsrfException 으로 아래에서 DOWN 결과가 된다. 4xx·5xx 도 예외 없이 마지막
      // 응답으로 받는다 — 오류 본문·헤더를 운영자가 봐야 디버깅할 수 있다.
      // 시간 제한은 모든 홉을 합쳐 한 번(timeoutPerHop=false), 4xx·5xx 는 결과로 받는다(failOnErrorStatus=false).
      ResponseEntity<String> resp =
          RedirectFollower.follow(
              RedirectFollower.Request.bodilessGet(
                  healthCheckClient(),
                  ssrfProtectionService,
                  callUri,
                  auth,
                  HEALTH_CHECK_TIMEOUT_SECONDS * 1000,
                  false,
                  false));

      long latency = System.currentTimeMillis() - start;
      int status = resp.getStatusCode().value();
      boolean ok = resp.getStatusCode().is2xxSuccessful();
      String err = ok ? null : ("HTTP " + status);
      String body = truncateBody(resp.getBody(), secret);
      Map<String, String> headers = sanitizeHeaders(resp.getHeaders(), secret);
      String contentType =
          resp.getHeaders().getContentType() != null
              ? resp.getHeaders().getContentType().toString()
              : null;
      return new TestConnectionResponse(
          ok, status, latency, err, displayUrl, body, headers, contentType);

    } catch (SsrfException e) {
      long latency = System.currentTimeMillis() - start;
      // 차단 사유는 SsrfProtectionService 의 한국어 문구를 그대로 쓴다(「내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1」) —
      // 저장 시 검사·API_CALL 실행·미리보기(ApiCallErrorMessages.describe)와 같은 문장이다(WD-150). 처음 주소든 리다이렉트 대상이든
      // (WD-693) 막힌 주소로는 호출하지 않았으므로 본문·헤더는 비운다.
      return new TestConnectionResponse(
          false, null, latency, e.getMessage(), displayUrl, null, Map.of(), null);

    } catch (Exception e) {
      long latency = System.currentTimeMillis() - start;
      // 접속 시점 주소 검사(WD-613)에 막힌 경우 — WebClientRequestException 에 감싸여 오므로 꺼내서 사전 검사와 같은 차단 문구로 보인다.
      // 접속하지 않았으므로 본문·헤더는 비운다.
      SsrfException blocked = SsrfException.findIn(e);
      if (blocked != null) {
        return new TestConnectionResponse(
            false, null, latency, blocked.getMessage(), displayUrl, null, Map.of(), null);
      }
      // 예외 메시지에 URL·키가 실릴 수 있어 가린 문구만 응답·lastErrorMessage·알림으로 보낸다(WD-177).
      String msg = toUserFacingErrorMessage(e, secret);
      return new TestConnectionResponse(
          false, null, latency, msg, displayUrl, null, Map.of(), null);
    }
  }

  /**
   * 헬스체크 WebClient — 프로젝트 관행에 따라 빌더를 직접 생성한다(별도 Bean 없음). 자동 리다이렉트를 끄고 따라가기는 미리보기·실행과 같은 {@link
   * RedirectFollower} 가 홉마다 검사하며 한다(WD-693). 커넥터는 접속 시점 주소 검사가 걸린 {@link SsrfSafeHttpClientFactory}
   * 의 HttpClient 라(WD-613) 첫 요청과 모든 리다이렉트 홉이 DNS 재바인딩에 막힌다. 주입받은 팩토리가 필요해 필드 초기화 대신 호출마다 만든다 —
   * HttpClient 는 전역 연결 풀·이벤트 루프를 공유하므로 연결을 새로 열지 않는다.
   */
  private WebClient healthCheckClient() {
    return RedirectFollower.withoutAutoRedirect(WebClient.builder(), ssrfSafeHttpClientFactory)
        .build();
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
  private static String toUserFacingErrorMessage(Exception e, AuthQueryMasker.SentSecrets secret) {
    String message;
    if (isTimeoutException(e)) {
      // Reactor block(Duration) 타임아웃(IllegalStateException)은 분류기의 타임아웃 판별에 걸리지 않을 수 있어
      // 여기서 먼저 본다. 문장은 API_CALL 실행·미리보기와 같다(WD-180).
      message = ApiCallErrorMessages.timeout(HEALTH_CHECK_TIMEOUT_SECONDS * 1000);
    } else if (e instanceof AuthQueryMasker.InvalidRequestUrlException) {
      // 이미 한국어 안내(값 원문 없음) — 분류기 4단계로 보내면 「API 호출 중 오류가 발생했습니다: 」 접두가 겹친다.
      message = e.getMessage();
    } else {
      // 연결 거부·연결 실패·호스트 없음 등은 API_CALL 실행·미리보기와 같은 분류·문장을 쓴다(WD-150 → WD-180). 대상 서버와 주고받다 난
      // 전송 오류는 RedirectFollower 가 실패한 홉의 호스트로 이미 문구를 만들어 둔다(WD-693). 분류하지 못한 원문은 분류기가 URL 의
      // 쿼리·userinfo 를 지운다(WD-177).
      message = ApiCallErrorMessages.describe(e, HEALTH_CHECK_TIMEOUT_SECONDS * 1000);
    }
    // 분류하지 못한 원문에 인증 쿼리 키(원문·인코딩형)가 실려 올 수 있어 마지막에 가린다(WD-177).
    return secret.scrubText(message);
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
  private static String truncateBody(String body, AuthQueryMasker.SentSecrets secret) {
    if (body == null || body.isEmpty()) return body;
    // JSON 이스케이프로 되울린 비밀까지 가리도록 미리보기와 같은 본문 가림을 쓴다(WD-657). 화면에 그대로 보이는 원문이라 BASIC 비밀번호도
    // 가린다(WD-681 리뷰, SentSecrets.maskRawText).
    String masked = secret.maskRawText(body);
    if (masked.length() <= MAX_BODY_PREVIEW_BYTES) return masked;
    return masked.substring(0, MAX_BODY_PREVIEW_BYTES) + "\n... (truncated)";
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
      org.springframework.http.HttpHeaders headers, AuthQueryMasker.SentSecrets secret) {
    Map<String, String> out = new HashMap<>();
    headers.forEach(
        (k, v) -> {
          String joined = String.join(", ", v);
          if (SENSITIVE_HEADER_NAMES.contains(k.toLowerCase(Locale.ROOT))) {
            out.put(k, "****");
          } else {
            out.put(k, secret.scrubText(joined));
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
            List<HealthCheckTarget> targets = repository.findHealthCheckable();
            int total = targets.size();
            log.info("refreshAllAsync job {}: {} 대상 헬스체크 시작", jobId, total);

            for (int i = 0; i < total; i++) {
              HealthCheckTarget target = targets.get(i);
              Long id = target.id();
              String connName = target.name();

              try {
                // 새로고침을 누른 사용자를 상태 전환 감사의 행위자로 남긴다(WD-189).
                testConnection(id, userId);
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
      // 호스트는 UrlAuthority 로 읽는다(WD-676) — getHost() 는 밑줄 든 호스트(my_api.internal)에서 null 이라 거부했다.
      // 모호한 authority 는 여전히 호스트 없음으로 거부된다.
      String host = UrlAuthority.hostOrNull(uri);
      if (host == null || host.isBlank()) {
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

  /** API_KEY·BEARER 인데 baseUrl 에 사용자 정보가 새로 들어왔을 때의 안내(WD-681). */
  static final String USERINFO_NEEDS_BASIC_MESSAGE =
      "baseUrl 에 사용자 정보(아이디:비밀번호@)를 넣으려면 인증 방식을 BASIC 으로 고르세요";

  /** baseUrl 의 사용자 정보와 BASIC 인증 설정의 아이디·비밀번호가 서로 다를 때의 안내(WD-681). */
  static final String USERINFO_CONFLICT_MESSAGE =
      "사용자 정보가 baseUrl 과 인증 설정에 다르게 들어 있습니다 — 한 곳에만 넣으세요";

  /** baseUrl 사용자 정보의 비밀번호가 가린 값(****)일 때의 안내(WD-681) — 가린 값이 실제 비밀번호로 저장되지 않게 한다. */
  static final String USERINFO_MASKED_PASSWORD_MESSAGE =
      "baseUrl 의 비밀번호에 가려진 값(****)은 쓸 수 없습니다 — 실제 비밀번호를 입력하거나 사용자 정보를 지우고 인증 설정의 비밀번호 칸을 쓰세요";

  /**
   * 가린 사용자 정보({@code 아이디:****@})를 남긴 채 baseUrl 의 주소(스킴·호스트·포트)나 아이디를 바꿨을 때의 안내(WD-681 리뷰). 저장된
   * 비밀번호를 다른 주소로 보내지 않으므로 되살리지 않는다 — 사용자 정보를 지우거나 BASIC 을 골라 아이디·비밀번호를 입력하게 한다.
   */
  static final String USERINFO_MASKED_OTHER_ADDRESS_MESSAGE =
      "저장된 비밀번호는 다른 주소(스킴·호스트·포트)나 다른 아이디로 옮길 수 없습니다 — baseUrl 에서 사용자 정보(아이디:****@)를 지우거나, 인증 방식을"
          + " BASIC 으로 골라 아이디·비밀번호를 입력하세요";

  /**
   * 사용자 정보 규칙을 적용한 baseUrl 과 옮길 사용자 정보.
   *
   * @param baseUrl 검증할 baseUrl(BASIC 이면 사용자 정보를 뗀 값). 요청에 없으면 null
   * @param moved 이번 요청 baseUrl 에 입력해 BASIC 인증 설정으로 옮길 사용자 정보. 없거나 저장소에서 되살린 값이면 null(되살린 값은 호출자가 따로
   *     다룬다)
   */
  private record BaseUrlCandidate(String baseUrl, BaseUrlUserInfo.UserInfo moved) {}

  /**
   * baseUrl 의 사용자 정보(아이디:비밀번호@) 규칙(WD-681, 사람 결정 Z).
   *
   * <ul>
   *   <li>사용자 정보가 없으면 그대로.
   *   <li>BASIC: baseUrl 에서는 뗀다. 이번 요청에서 입력한 사용자 정보면 퍼센트 디코딩한 아이디·비밀번호를 옮길 값으로 돌려준다(저장소에서 되살린 값이면
   *       돌려주지 않는다 — 호출자가 충돌 검사 없이 채운다). 입력한 비밀번호가 가린 값({@code ****})이면 400 — 되살릴 저장값이 없는(생성·저장 전
   *       테스트·저장값에 비밀번호 없음) 가린 값이라 실제 비밀번호로 저장하면 안 된다.
   *   <li>API_KEY·BEARER: 저장된 옛 값을 되살린 것이면 허용(옛 연결은 이관하지 않는다), 새로 들어온 사용자 정보면 400.
   * </ul>
   *
   * <p>baseUrl 형식·SSRF 검사는 여기서 하지 않는다 — 호출자가 돌려받은 baseUrl 로 {@link #validateAndNormalizeBaseUrl} 을
   * 부른다.
   *
   * @param authType 검증을 마친 (바뀐 뒤의) 인증 방식
   * @param baseUrl 요청 baseUrl(가린 값은 이미 저장 원문으로 되살린 것). null 가능
   * @param restoredStored {@code baseUrl} 의 사용자 정보가 저장된 원래 값인지(가린 값 되살리기·BASIC 전환 시 저장값 이관)
   */
  private static BaseUrlCandidate applyUserInfoRule(
      String authType, String baseUrl, boolean restoredStored) {
    if (baseUrl == null || !BaseUrlUserInfo.hasUserInfo(baseUrl)) {
      return new BaseUrlCandidate(baseUrl, null);
    }
    if ("BASIC".equals(authType)) {
      if (restoredStored) return new BaseUrlCandidate(BaseUrlUserInfo.strip(baseUrl), null);
      if (BaseUrlUserInfo.hasMaskedPassword(baseUrl)) {
        throw new ApiConnectionException(USERINFO_MASKED_PASSWORD_MESSAGE);
      }
      return new BaseUrlCandidate(
          BaseUrlUserInfo.strip(baseUrl), BaseUrlUserInfo.extract(baseUrl).orElse(null));
    }
    if (!restoredStored) {
      throw new ApiConnectionException(USERINFO_NEEDS_BASIC_MESSAGE);
    }
    return new BaseUrlCandidate(baseUrl, null);
  }

  /**
   * 이번 요청 baseUrl 에 입력한 사용자 정보를 요청의 BASIC 인증 설정에 합친다(WD-681, 리뷰 반영). 같은 요청의 authConfig 에 아이디·비밀번호가
   * 함께 있고 값이 다르면 400(어느 쪽이 맞는지 서버가 고를 수 없다). 비었거나 같으면 사용자 정보 값을 쓴다. 가린 비밀번호({@code ****…}, 조회 결과를
   * 되돌려 보낸 것)는 입력한 값이 아니라 비어 있는 것으로 본다. 비밀번호가 없는 사용자 정보({@code 아이디@})는 아이디만 합친다.
   *
   * @param config 요청 설정(null 가능 — 빈 설정으로 본다)
   * @param typed 입력한 사용자 정보. null 이면 {@code config} 를 그대로 돌려준다
   * @return 합친 새 Map(요청 Map 은 불변일 수 있어 건드리지 않는다)
   */
  private static Map<String, String> mergeUserInfo(
      Map<String, String> config, BaseUrlUserInfo.UserInfo typed) {
    if (typed == null) return config;
    Map<String, String> merged = new HashMap<>(config != null ? config : Map.of());
    userInfoFields(typed).forEach((key, value) -> putTypedUserInfoField(merged, key, value));
    return merged;
  }

  /**
   * 저장소에서 되살린 옛 사용자 정보를 설정에 채운다(옛 연결을 BASIC 으로 바꿀 때, WD-681 리뷰) — 충돌로 보지 않는다. 설정이 아이디나 비밀번호를 하나라도
   * 주면 그 값이 이기고 그대로 둔다. 아무것도 주지 않으면 옛 사용자 정보를 넣는다.
   *
   * @param config 요청 설정(null 가능 — 빈 설정으로 본다)
   * @return 새 Map(요청 Map 은 불변일 수 있어 건드리지 않는다)
   */
  private static Map<String, String> putUserInfoIfAbsent(
      Map<String, String> config, BaseUrlUserInfo.UserInfo restored) {
    Map<String, String> merged = new HashMap<>(config != null ? config : Map.of());
    String user = merged.get(AuthConfigValidator.BASIC_USERNAME_KEY);
    String password = merged.get(AuthConfigValidator.BASIC_PASSWORD_KEY);
    boolean provided =
        (user != null && !user.isBlank()) || (password != null && !password.isEmpty());
    if (!provided) merged.putAll(userInfoFields(restored));
    return merged;
  }

  /** 사용자 정보를 BASIC 인증 설정 필드(아이디·비밀번호)로 — 비밀번호가 없는 사용자 정보면 아이디만. */
  private static Map<String, String> userInfoFields(BaseUrlUserInfo.UserInfo info) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put(AuthConfigValidator.BASIC_USERNAME_KEY, info.username());
    if (info.password() != null) {
      fields.put(AuthConfigValidator.BASIC_PASSWORD_KEY, info.password());
    }
    return fields;
  }

  /**
   * 이번 요청에서 입력한 사용자 정보 필드 하나를 합친다 — 설정 값이 비어 있으면(아이디는 공백만이어도, 비밀번호는 빈 문자열·가린 값) 사용자 정보 값을 넣고, 다른
   * 값이면 충돌 400.
   */
  private static void putTypedUserInfoField(Map<String, String> config, String key, String value) {
    String existing = config.get(key);
    boolean empty =
        existing == null
            || (AuthConfigValidator.BASIC_USERNAME_KEY.equals(key)
                ? existing.isBlank()
                : existing.isEmpty() || AuthConfigValidator.isMaskedSecret(key, existing));
    if (!empty && !existing.equals(value)) {
      throw new ApiConnectionException(USERINFO_CONFLICT_MESSAGE);
    }
    config.put(key, value);
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

  /** 안내 문구에 싣는 지원 목록 — 순서를 고정해 문구가 실행마다 달라지지 않게 한다. */
  private static final String SUPPORTED_AUTH_TYPES_TEXT = "API_KEY, BEARER, BASIC";

  /**
   * 인증 방식 값 자체를 검증한다 — 생성·수정·저장 전 테스트 공통. 지원 목록 밖(OAUTH2·FOO 등)은 DB CHECK 제약(409 일반 문구)에 닿기 전에 400
   * 한국어 안내로 거절한다(WD-180).
   */
  private void validateAuthType(String authType) {
    if (authType == null || authType.isBlank()) {
      throw new ApiConnectionException(
          "인증 방식(authType)을 입력하세요 (사용 가능: " + SUPPORTED_AUTH_TYPES_TEXT + ")");
    }
    if (!AuthConfigValidator.REQUIRED_SECRET_BY_AUTH_TYPE.containsKey(authType)) {
      throw new ApiConnectionException(
          "지원하지 않는 인증 방식입니다: " + authType + " (사용 가능: " + SUPPORTED_AUTH_TYPES_TEXT + ")");
    }
  }

  /** 같은 방식 수정에서 가린 값을 되돌려 보냈는데 저장된 비밀이 비어 있을 때의 안내(WD-623). */
  private static final Map<String, String> STORED_SECRET_EMPTY_MESSAGE_BY_AUTH_TYPE =
      Map.of(
          "API_KEY", "저장된 API 키가 비어 있습니다 — 새 키를 입력하세요 (authConfig.apiKey)",
          "BEARER", "저장된 Bearer 토큰이 비어 있습니다 — 새 토큰을 입력하세요 (authConfig.token)",
          "BASIC", "저장된 비밀번호가 비어 있습니다 — 새 비밀번호를 입력하세요 (authConfig.password)");

  /**
   * 인증 방식을 바꾸는 수정에는 새 방식의 비밀 필드를 담은 authConfig 가 함께 와야 한다(WD-180). 저장된 비밀은 옛 방식의 것이라(API 키 ↔ 토큰) 새
   * 방식으로 쓸 수 없다 — 없이 저장하면 인증 헤더 없이 호출되면서도 오류가 나지 않는다.
   *
   * <p>정리한 값 기준으로 비었거나(「Bearer 」 포함, WD-623) 가린 값({@code ****…})이면 거절한다. 방식이 바뀌면 가린 값을 채울 저장값이 없어
   * 어차피 쓸 수 없으므로, 뒤이은 {@link #resolveMaskedSecrets} 의 일반 거절 문구보다 「새 방식의 인증 정보를 입력하라」는 이 안내를 먼저 보인다.
   *
   * @param newAuthType 검증을 마친(지원 목록 안의) 새 인증 방식
   * @param requestedConfig 요청 authConfig(정리 전, null 가능)
   */
  private static void requireSecretForNewAuthType(
      String newAuthType, Map<String, String> requestedConfig) {
    // 가린 값 판정은 resolveMaskedSecrets 와 같은 규칙(앞뒤 공백·「Bearer 」 무시, WD-639)이다 — 「 ****abcd」 도 새 비밀이
    // 아니다.
    if (AuthConfigValidator.isSecretMissing(newAuthType, requestedConfig)
        || AuthConfigValidator.isSecretMasked(newAuthType, requestedConfig)) {
      throw new ApiConnectionException(
          "인증 방식을 "
              + newAuthType
              + "(으)로 바꾸려면 새 방식의 인증 정보(authConfig."
              + AuthConfigValidator.REQUIRED_SECRET_BY_AUTH_TYPE.get(newAuthType)
              + ")를 함께 입력하세요");
    }
  }

  /**
   * 같은 방식 수정에서 비밀 필드에 가린 값({@code ****…})을 되돌려 보냈는데 저장된 비밀이 비어 있으면(정리한 뒤 기준) 그 사실을 알려 거절한다(WD-623).
   *
   * <p>빈 비밀은 예전(WD-588·WD-623 전)에 저장된 연결에 남아 있을 수 있다. 조회 화면에는 {@code ****} 로 보여 사용자는 키가 있다고 여기고 그대로
   * 저장하는데, 일반 문구(「… 입력하세요」·「가려진 값은 저장할 수 없습니다」)로는 왜 거절됐는지 알기 어렵다. 저장된 값이 비었다고 짚어 새 값을 받게 한다.
   *
   * @param stored 현재 저장된 복호화 authConfig
   */
  private static void rejectMaskedEchoOfEmptyStoredSecret(
      String authType, Map<String, String> requestedConfig, Map<String, String> stored) {
    if (AuthConfigValidator.isSecretMasked(authType, requestedConfig)
        && AuthConfigValidator.isSecretMissing(authType, stored)) {
      throw new ApiConnectionException(STORED_SECRET_EMPTY_MESSAGE_BY_AUTH_TYPE.get(authType));
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
      // 가린 값 판정·비교는 앞뒤 공백·「Bearer 」 접두어를 뗀 값으로 한다(WD-639) — 원값으로 보면 「Bearer ****oken」 을 놓쳐, 뒤이은 정리가
      // ****oken 을 만들어 실제 토큰을 덮었다. 저장값의 가린 형태도 같은 정리를 거쳐 비교한다(정리 전에 저장된 옛 값의 가린 형태 대비).
      if (!ApiConnectionReader.isSensitiveKey(key)
          || !AuthConfigValidator.isMaskedSecret(key, value)) {
        continue;
      }
      String comparable = AuthConfigValidator.maskComparable(key, value);
      String storedValue = stored != null ? stored.get(key) : null;
      if (storedValue != null
          // 저장값의 가린 모양은 응답과 같은 함수로 만든다(WD-681 리뷰) — 비밀번호는 고정 ****, 키·토큰은 ****+끝 4자.
          && AuthConfigValidator.maskComparable(key, reader.maskSecretValue(key, storedValue))
              .equals(comparable)) {
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
      ApiConnectionRow existing,
      UpdateApiConnectionRequest request,
      String authType,
      String normalizedBaseUrl,
      String normalizedHealthCheckPath,
      boolean credentialsChanged) {
    String beforeName = existing.name();
    String beforeDescription = existing.description();
    String beforeAuthType = existing.authType();
    String beforeBaseUrl = existing.baseUrl();
    String beforeHealthCheckPath = existing.healthCheckPath();

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
