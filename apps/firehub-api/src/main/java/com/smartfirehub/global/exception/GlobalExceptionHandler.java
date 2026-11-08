package com.smartfirehub.global.exception;

import com.smartfirehub.ai.exception.AiSessionNotFoundException;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.analytics.exception.WidgetNotFoundException;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.exception.ApiConnectionNameAlreadyExistsException;
import com.smartfirehub.apiconnection.exception.ApiConnectionNotFoundException;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.TenantAccessDeniedException;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.dataimport.exception.ConcurrentImportException;
import com.smartfirehub.dataimport.exception.ImportProcessingException;
import com.smartfirehub.dataimport.exception.ImportValidationException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.exception.*;
import com.smartfirehub.dataset.rowsearch.SearchIndexNotConfiguredException;
import com.smartfirehub.dataset.service.DataExceptionTranslator;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.file.exception.FileNotFoundException;
import com.smartfirehub.file.exception.FileSizeLimitExceededException;
import com.smartfirehub.file.exception.UnsupportedUploadFileTypeException;
import com.smartfirehub.global.dto.ErrorResponse;
import com.smartfirehub.global.platformdb.DbErrorDetail;
import com.smartfirehub.global.platformdb.PlatformDbErrors;
import com.smartfirehub.notification.auth.exception.SlackWorkspaceInstallDeniedException;
import com.smartfirehub.ontology.exception.OntologyElementNotFoundException;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.pipeline.exception.PipelineInactiveException;
import com.smartfirehub.pipeline.exception.PipelineNameConflictException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.proactive.exception.ProactiveJobAlreadyRunningException;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {

  /** DB 오류 판정 창구 — 벤더별 메시지 문구·드라이버 타입은 구현 안에 둔다(WD-486). */
  private final PlatformDbErrors platformDbErrors;

  /**
   * 입력 검증 실패 400 의 최상위 message(WD-570). 예전에는 영어 "Validation failed" 가 그대로 나갔다 — 필드별 문구는 errors 맵에
   * (ValidationMessages.properties 의 한국어 기본 문구 또는 DTO 의 명시 문구로) 실리고, 최상위 문구도 한국어로 맞춘다. errors 구조는
   * 그대로다.
   */
  public static final String VALIDATION_FAILED_MESSAGE = "입력값이 올바르지 않습니다.";

  /** 커넥션 풀 대기 초과(WD-553) 안내 — 일시적 과부하이므로 재시도를 권한다. */
  static final String POOL_EXHAUSTED_MESSAGE = "요청이 많아 지금은 처리할 수 없습니다. 잠시 후 다시 시도하세요.";

  private ErrorResponse buildError(
      HttpStatus status, String message, Map<String, String> errors, HttpServletRequest request) {
    return buildError(status, message, errors, request, null);
  }

  /** 기계용 코드까지 싣는 버전 — 코드가 null 이면 JSON 에서 생략된다(ErrorResponse 참고). */
  private ErrorResponse buildError(
      HttpStatus status,
      String message,
      Map<String, String> errors,
      HttpServletRequest request,
      String code) {
    return new ErrorResponse(
        status.value(),
        status.getReasonPhrase(),
        message,
        errors,
        Instant.now().toString(),
        request.getRequestURI(),
        code);
  }

  /**
   * 오류 응답을 항상 JSON 으로 내보낸다(WD-548).
   *
   * <p>Content-Type 을 명시하면 Spring 이 Accept 협상을 건너뛴다. 명시하지 않으면 클라이언트 Accept 가 JSON 을 받지 않을 때(예:
   * image/png, text/html) 오류 본문을 쓰다 협상에 실패하고, 그 실패를 처리할 곳이 없어 빈 본문 500·Whitelabel HTML 이 나갔다. 모든
   * 핸들러가 이 메서드를 거쳐 같은 형식을 보장한다.
   */
  private static ResponseEntity<ErrorResponse> json(HttpStatus status, ErrorResponse body) {
    return json(status, HttpHeaders.EMPTY, body);
  }

  /** 추가 응답 헤더(예: 415 의 Accept)를 함께 싣는 버전. */
  private static ResponseEntity<ErrorResponse> json(
      HttpStatus status, HttpHeaders headers, ErrorResponse body) {
    return ResponseEntity.status(status)
        .headers(headers)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationExceptions(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> fieldErrors = new HashMap<>();
    Set<String> requiredFields = new HashSet<>();
    // FieldError 의 code 는 제약 애너테이션의 단순 이름(예: "NotBlank")이다.
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(
            error ->
                putFieldError(
                    fieldErrors,
                    requiredFields,
                    error.getField(),
                    error.getDefaultMessage(),
                    REQUIRED_CONSTRAINTS.contains(error.getCode())));
    ErrorResponse response =
        buildError(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_MESSAGE, fieldErrors, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /**
   * @Validated + @Min/@Max 등 쿼리 파라미터 제약 위반 시 400 반환 (#139)
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    Map<String, String> fieldErrors = new HashMap<>();
    Set<String> requiredFields = new HashSet<>();
    ex.getConstraintViolations()
        .forEach(
            v -> {
              String path = v.getPropertyPath().toString();
              String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
              String constraint =
                  v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
              putFieldError(
                  fieldErrors,
                  requiredFields,
                  field,
                  v.getMessage(),
                  REQUIRED_CONSTRAINTS.contains(constraint));
            });
    ErrorResponse response =
        buildError(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_MESSAGE, fieldErrors, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /** 「필수값 누락」 계열 제약 — 한 필드에 여러 위반이 겹치면 이 문구를 우선한다(WD-570). */
  private static final Set<String> REQUIRED_CONSTRAINTS = Set.of("NotBlank", "NotNull", "NotEmpty");

  /**
   * 필드별 오류 문구를 하나만 고른다(WD-570).
   *
   * <p>한 필드가 여러 제약을 동시에 어기면(예: slug="" → @NotBlank + @Pattern) 위반 목록 순서가 정해져 있지 않아, 단순 put 이면 응답
   * 문구가 요청마다 달라졌다. 규칙: 필수값 누락(NotBlank·NotNull·NotEmpty) 문구가 이기고, 그 밖에는 처음 만난 문구를 유지한다 — 빈 값에 「형식이
   * 올바르지 않습니다」보다 「필수 입력값입니다」가 맞는 안내다.
   */
  private static void putFieldError(
      Map<String, String> fieldErrors,
      Set<String> requiredFields,
      String field,
      String message,
      boolean required) {
    if (!fieldErrors.containsKey(field)) {
      fieldErrors.put(field, message);
      if (required) {
        requiredFields.add(field);
      }
    } else if (required && !requiredFields.contains(field)) {
      // 먼저 들어온 것이 필수값 문구가 아니면 필수값 문구로 바꾼다.
      fieldErrors.put(field, message);
      requiredFields.add(field);
    }
  }

  @ExceptionHandler(UsernameAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleUsernameAlreadyExists(
      UsernameAlreadyExistsException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleInvalidCredentials(
      InvalidCredentialsException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.UNAUTHORIZED, ex.getMessage(), null, request);
    return json(HttpStatus.UNAUTHORIZED, response);
  }

  @ExceptionHandler(InvalidTokenException.class)
  public ResponseEntity<ErrorResponse> handleInvalidToken(
      InvalidTokenException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.UNAUTHORIZED, ex.getMessage(), null, request);
    return json(HttpStatus.UNAUTHORIZED, response);
  }

  /**
   * DB 제약조건 위반을 변환한다 (#320) — 데이터 오류(SQLState 22xxx, Tibero 벤더 코드 포함)는 400 + 한국어 사유, 그 밖의 제약 위반은
   * 409.
   *
   * <p>원문 DB 메시지에는 테이블·컬럼·제약조건 이름과 때로는 값 일부가 들어있으므로 <b>사용자 응답에는 절대 넣지 않는다</b>. 원인 분류(중복/FK/체크)만 응답에
   * 담고, 진단에 필요한 원문은 서버 로그에만 남긴다 — ai-agent 호출 실패에 적용한 것과 같은 원칙(#313)이다.
   *
   * <p>이 분기는 예외 번역기가 등록되기 전(#312 이전)까지 도달 불가한 죽은 코드였으나, 번역기 등록 이후 해당 DSLContext를 쓰는 모든 jOOQ 경로에서 도달
   * 가능해졌다.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    // 클라이언트 입력이 유발하는 409이므로 error가 아닌 warn. 원문 메시지는 여기서만 노출된다.
    log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMessage(), ex);

    // DB 데이터 오류(SQLState 22xxx — 글자 수·숫자 범위·날짜·NUL 등)는 입력 오류라 원문 없는 한국어 사유로 400. 그 밖의 제약 위반은
    // 아래 기존 분류(409)를 그대로 탄다.
    String dataErrorReason = DataExceptionTranslator.describe(ex);
    if (dataErrorReason != null) {
      ErrorResponse response =
          buildError(
              HttpStatus.BAD_REQUEST,
              "저장할 수 없는 값이 있어 처리하지 못했습니다: " + dataErrorReason,
              null,
              request);
      return json(HttpStatus.BAD_REQUEST, response);
    }

    // 분류(중복/외래 키/체크)는 원인 메시지로 판정하고, 사용자 문구는 원문 없이 고정한다. 외래 키는 방향(삭제·연결)을 서버 구조화
    // 오류까지 보고 가린다.
    // 위 분류 어디에도 들지 않는 원인(UNCLASSIFIED)은 기본 문구 그대로다 — 원문을 붙이면 스키마 정보가 샌다.
    String message =
        isMissingDefaultSecurityLevel(platformDbErrors.findError(ex).orElse(null))
            ? "기본 보안 등급이 없어 저장할 수 없습니다. 설정 › 데이터 보안에서 기본 등급을 지정하세요"
            : switch (platformDbErrors.classifyIntegrityViolation(ex)) {
              case DUPLICATE_KEY -> "이미 같은 값이 있어 저장할 수 없습니다";
              case FOREIGN_KEY_IN_USE -> "다른 곳에서 사용 중이라 삭제할 수 없습니다";
              case FOREIGN_KEY_MISSING_TARGET -> "연결할 항목을 찾을 수 없습니다";
              case FOREIGN_KEY_OTHER -> "연결된 항목 때문에 처리할 수 없습니다";
              case CHECK_CONSTRAINT -> "입력값이 제약 조건을 만족하지 않습니다";
              case UNCLASSIFIED -> "데이터 무결성 오류로 저장할 수 없습니다";
            };
    ErrorResponse response = buildError(HttpStatus.CONFLICT, message, null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  /**
   * 등급 열 NOT NULL 위반(23502)인지 — 데이터셋·역할의 등급 열 기본값은 테넌트의 기본 보안 등급이라, 기본 등급이 없으면 NULL 이 들어가 여기에
   * 걸린다(WD-400). 일반 무결성 문구로는 원인을 알 수 없어 고칠 곳을 알려 준다. 원문 메시지가 아니라 서버 구조화 오류의 SQLState·열 이름으로 가린다 —
   * 문구 대조는 서버 언어 설정에 따라 빗나가고, 메시지에 실린 SQL·행 값에 열 이름이 섞여 다른 위반까지 잡을 수 있다.
   */
  private static boolean isMissingDefaultSecurityLevel(DbErrorDetail serverError) {
    return serverError != null
        && "23502".equals(serverError.sqlState())
        && SECURITY_LEVEL_COLUMNS.contains(serverError.column());
  }

  /** 기본값이 테넌트 기본 보안 등급인 열(V133 — dataset.security_level_id, role.max_security_level_id). */
  private static final Set<String> SECURITY_LEVEL_COLUMNS =
      Set.of("security_level_id", "max_security_level_id");

  /**
   * Slack 앱 설치가 RLS 에 막힌 경우 500 이 아니라 409 로 응답한다 (멀티 테넌시 P2-g).
   *
   * <p><b>왜 응답 문구가 일반적이어야 하는가 — 지우지 말 것.</b> "이미 다른 테넌트가 이 Slack 팀을 설치했다"고 알려 주면, 아무나 팀 ID 만 넣어 보고
   * <b>다른 테넌트의 설치 여부를 조회하는 오라클</b>이 된다(팀 ID 는 워크스페이스 관리자면 누구나 안다). 그래서 응답에는 원인·team_id· 상대 테넌트를 일절
   * 싣지 않고, 진단에 필요한 정보는 아래 WARN 로그에만 남긴다 — {@link #handleDataIntegrityViolation} 이 DB 원문을 응답에서 걷어낸
   * 것과 같은 원칙(#313)이다. 뒤에 오는 사람이 "메시지가 불친절하다"며 구체화하지 않도록 여기 근거를 박아 둔다.
   *
   * <p>{@code 42501} 전반이 아니라 <b>OAuth 설치 경로에서 변환된 전용 예외</b>만 받는다. RLS 거부를 통째로 4xx 로 바꾸면 컨텍스트 없는 배경
   * 경로의 쓰기 같은 배선 결함까지 조용히 묻히는데, 그건 500 으로 시끄럽게 터져야 한다.
   */
  @ExceptionHandler(SlackWorkspaceInstallDeniedException.class)
  public ResponseEntity<ErrorResponse> handleSlackWorkspaceInstallDenied(
      SlackWorkspaceInstallDeniedException ex, HttpServletRequest request) {
    // 서버측 로그는 구체적으로 — team_id·SQLState·요청 URI 를 남겨야 운영자가 원인을 짚는다.
    // correlationId 는 MDC 로만 나른다(SlackInboundService.dispatch 선례). 응답 바디에는 넣지
    // 않는다 — ErrorResponse 계약을 흔들고, 이 저장소에 그런 선례가 없다.
    MDC.put("correlationId", "slack-install-denied-" + ex.getTeamId());
    try {
      log.warn(
          "Slack workspace install denied by RLS (sqlstate=42501) on {}: teamId={}",
          request.getRequestURI(),
          ex.getTeamId(),
          ex);
    } finally {
      MDC.remove("correlationId");
    }

    ErrorResponse response =
        buildError(HttpStatus.CONFLICT, "Slack 워크스페이스를 설치할 수 없습니다.", null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.FORBIDDEN, ex.getMessage(), null, request);
    return json(HttpStatus.FORBIDDEN, response);
  }

  /**
   * 남이 시작한 비동기 작업 조회 — 403(WD-203). 진행 SSE 엔드포인트({@code Accept: text/event-stream})에서도 메시지 본문이 나가도록
   * Content-Type 을 JSON 으로 미리 정한다. 정하지 않으면 내용 협상에 실패해 본문 없는 403 이 되어 웹이 사유를 보여 줄 수 없다. 전역 403 핸들러는
   * 다른 엔드포인트 응답을 바꾸지 않도록 그대로 둔다.
   */
  @ExceptionHandler(com.smartfirehub.job.exception.JobAccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleJobAccessDenied(
      com.smartfirehub.job.exception.JobAccessDeniedException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.FORBIDDEN, ex.getMessage(), null, request);
    return json(HttpStatus.FORBIDDEN, response);
  }

  /** 소속되지 않았거나 정지된 테넌트 선택 시도 — 403 반환. */
  @ExceptionHandler(TenantAccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleTenantAccessDenied(
      TenantAccessDeniedException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.FORBIDDEN, ex.getMessage(), null, request);
    return json(HttpStatus.FORBIDDEN, response);
  }

  @ExceptionHandler(RoleNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleRoleNotFound(
      RoleNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /**
   * 비동기 작업(async_job)이 없거나 다른 테넌트의 것이라 보이지 않을 때 — 404 반환(WD-203).
   *
   * <p>진행 SSE 엔드포인트({@code produces=text/event-stream})에서도 던져지므로 Content-Type 을 JSON 으로 미리 정한다. 정하지
   * 않으면 요청의 {@code Accept: text/event-stream} 과 협상하다 오류 본문을 쓸 변환기를 못 찾아 404 대신 처리 실패(500)로 바뀌고, 웹
   * 훅이 이를 일시 오류로 보고 재시도를 이어 간다.
   */
  @ExceptionHandler(com.smartfirehub.job.exception.JobNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleJobNotFound(
      com.smartfirehub.job.exception.JobNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /** 요소 단위 온톨로지 편집 — 이 온톨로지 안에 존재하지 않는 타입/속성/관계 id를 지목하면 404 반환. */
  @ExceptionHandler(OntologyElementNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleOntologyElementNotFound(
      OntologyElementNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  @ExceptionHandler(SystemRoleModificationException.class)
  public ResponseEntity<ErrorResponse> handleSystemRoleModification(
      SystemRoleModificationException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(DatasetNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleDatasetNotFound(
      DatasetNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  @ExceptionHandler(CategoryNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleCategoryNotFound(
      CategoryNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /** 데이터셋에 지우려는 태그가 없을 때 404(WD-242) — 예전에는 204 로 지운 것처럼 보였다. */
  @ExceptionHandler(DatasetTagNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleDatasetTagNotFound(
      DatasetTagNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  @ExceptionHandler(DuplicateDatasetNameException.class)
  public ResponseEntity<ErrorResponse> handleDuplicateDatasetName(
      DuplicateDatasetNameException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  /** 파이프라인 스텝 등 외부 리소스가 데이터셋을 참조하고 있어 삭제 불가한 경우 409 반환 (#126) */
  @ExceptionHandler(com.smartfirehub.dataset.exception.DatasetInUseException.class)
  public ResponseEntity<ErrorResponse> handleDatasetInUse(
      com.smartfirehub.dataset.exception.DatasetInUseException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  /** 카테고리에 연결된 데이터셋이 남아있어 삭제 불가한 경우 409 반환 (#518) */
  @ExceptionHandler(com.smartfirehub.dataset.exception.CategoryInUseException.class)
  public ResponseEntity<ErrorResponse> handleCategoryInUse(
      com.smartfirehub.dataset.exception.CategoryInUseException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  @ExceptionHandler(InvalidTableNameException.class)
  public ResponseEntity<ErrorResponse> handleInvalidTableName(
      InvalidTableNameException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(ColumnModificationException.class)
  public ResponseEntity<ErrorResponse> handleColumnModification(
      ColumnModificationException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  // 행 검색: 검색 대상 필드 미지정 데이터셋에 검색·재색인 요청 → 400
  @ExceptionHandler(SearchIndexNotConfiguredException.class)
  public ResponseEntity<ErrorResponse> handleSearchIndexNotConfigured(
      SearchIndexNotConfiguredException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(ImportValidationException.class)
  public ResponseEntity<ErrorResponse> handleImportValidation(
      ImportValidationException ex, HttpServletRequest request) {
    Map<String, String> errorMap = new HashMap<>();
    List<String> errors = ex.getErrors();
    for (int i = 0; i < errors.size(); i++) {
      errorMap.put("error_" + i, errors.get(i));
    }
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), errorMap, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(ConcurrentImportException.class)
  public ResponseEntity<ErrorResponse> handleConcurrentImport(
      ConcurrentImportException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  /**
   * DB 교착(deadlock)·잠금 대기 실패 — 409 로 다시 시도를 안내한다(WD-531).
   *
   * <p>같은 대상을 동시에 바꾸는 요청끼리 DB 가 한쪽을 교착 피해자로 골라 되돌린 경우다. 데이터는 되돌려져 일관되고 다시 보내면 대개 성공하는 일시적 충돌이라,
   * 「예기치 않은 오류」 500 이 아니라 409 로 알린다. 교착이 알려진 경로(데이터셋 필드 변경)는 행 잠금으로 직렬화해 두었고, 이 처리는 그 밖에서 남는 교착을 위한
   * 안전망이다. 원문(잠금 대기 프로세스·SQL)은 응답에 싣지 않고 로그에만 남긴다.
   */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  public ResponseEntity<ErrorResponse> handlePessimisticLockingFailure(
      PessimisticLockingFailureException ex, HttpServletRequest request) {
    log.warn("Lock conflict on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
    ErrorResponse response =
        buildError(HttpStatus.CONFLICT, "다른 변경과 겹쳐 처리하지 못했습니다. 잠시 뒤 다시 시도하세요", null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  /** 가져오기 이력이 없거나 이 데이터셋의 것이 아닐 때 — 404(WD-250). */
  @ExceptionHandler(com.smartfirehub.dataimport.exception.ImportNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleImportNotFound(
      com.smartfirehub.dataimport.exception.ImportNotFoundException ex,
      HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /**
   * 비동기 내보내기 파일을 내려받을 수 없을 때 — 상태는 예외가 정한다(404 없음·403 남의 작업·409 진행 중/실패/정리됨, WD-250).
   *
   * <p>다운로드 요청은 파일을 기대해 {@code Accept} 가 JSON 을 포함하지 않을 수 있으므로 Content-Type 을 JSON 으로 미리 정해 오류 본문이
   * 내용 협상 실패로 사라지지 않게 한다(작업 상태 404·403 핸들러와 같은 이유).
   */
  @ExceptionHandler(com.smartfirehub.dataimport.exception.ExportDownloadException.class)
  public ResponseEntity<ErrorResponse> handleExportDownload(
      com.smartfirehub.dataimport.exception.ExportDownloadException ex,
      HttpServletRequest request) {
    ErrorResponse response = buildError(ex.status(), ex.getMessage(), null, request);
    return json(ex.status(), response);
  }

  @ExceptionHandler(UnsupportedFileTypeException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedFileType(
      UnsupportedFileTypeException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /**
   * 업로드 파일 크기가 제한(256MB)을 초과했을 때 400 대신 명확한 메시지를 반환한다. Spring Boot 기본 동작은 500 또는 비구조화된 에러이므로 여기서
   * 통일한다 (#137).
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(
            HttpStatus.BAD_REQUEST,
            "파일 크기가 허용 한도(256MB)를 초과했습니다. 더 작은 파일을 업로드해주세요.",
            null,
            request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(PipelineNotFoundException.class)
  public ResponseEntity<ErrorResponse> handlePipelineNotFound(
      PipelineNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /** 파이프라인 이름 중복 시 409 반환 (#181) */
  @ExceptionHandler(PipelineNameConflictException.class)
  public ResponseEntity<ErrorResponse> handlePipelineNameConflict(
      PipelineNameConflictException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  /** 비활성 파이프라인 수동 실행 시도 시 409 반환 (#187) */
  @ExceptionHandler(PipelineInactiveException.class)
  public ResponseEntity<ErrorResponse> handlePipelineInactive(
      PipelineInactiveException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  /** 이미 끝난 파이프라인 실행을 취소하려 할 때 409 반환 (WD-153) */
  @ExceptionHandler(com.smartfirehub.pipeline.exception.ExecutionAlreadyFinishedException.class)
  public ResponseEntity<ErrorResponse> handleExecutionAlreadyFinished(
      com.smartfirehub.pipeline.exception.ExecutionAlreadyFinishedException ex,
      HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  @ExceptionHandler(CyclicDependencyException.class)
  public ResponseEntity<ErrorResponse> handleCyclicDependency(
      CyclicDependencyException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(ScriptExecutionException.class)
  public ResponseEntity<ErrorResponse> handleScriptExecution(
      ScriptExecutionException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null, request);
    return json(HttpStatus.INTERNAL_SERVER_ERROR, response);
  }

  /** 파이프라인 SQL 스텝의 안전 정책 위반 — 저장 시 거부 또는 실행 시 거부 모두 동일 매핑. (#136) */
  @ExceptionHandler(UnsafeSqlException.class)
  public ResponseEntity<ErrorResponse> handleUnsafeSql(
      UnsafeSqlException ex, HttpServletRequest request) {
    log.warn("Unsafe SQL rejected: {}", ex.getMessage());
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /** 파이프라인 PYTHON 스텝의 escalation 코드 차단 — 저장 시·실행 시 모두 400 매핑. (#270) */
  @ExceptionHandler(com.smartfirehub.pipeline.exception.UnsafePythonScriptException.class)
  public ResponseEntity<ErrorResponse> handleUnsafePython(
      com.smartfirehub.pipeline.exception.UnsafePythonScriptException ex,
      HttpServletRequest request) {
    log.warn("Unsafe Python script rejected: {}", ex.getMessage());
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(com.smartfirehub.dataset.exception.SqlQueryException.class)
  public ResponseEntity<ErrorResponse> handleSqlQuery(
      com.smartfirehub.dataset.exception.SqlQueryException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(com.smartfirehub.dataset.exception.RowNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleRowNotFound(
      com.smartfirehub.dataset.exception.RowNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /** 운영자 평면에서 대상 테넌트가 없을 때 404. */
  @ExceptionHandler(com.smartfirehub.platform.exception.TenantNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleTenantNotFound(
      com.smartfirehub.platform.exception.TenantNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /**
   * 기계용 코드가 있는 예외. 상태는 예외가 정하고 본문에 {@code code} 를 싣는다.
   *
   * <p>인터셉터({@code PasswordChangeInterceptor}) 의 preHandle 에서 던져도 DispatcherServlet 의 예외 해석을 거쳐 여기로
   * 온다 — PermissionInterceptor 의 AccessDeniedException 과 같은 경로.
   */
  @ExceptionHandler(CodedApiException.class)
  public ResponseEntity<ErrorResponse> handleCodedApiException(
      CodedApiException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(ex.status(), ex.getMessage(), ex.details(), request, ex.code());
    return json(ex.status(), response);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /**
   * 비즈니스 규칙 위반(예: 마지막 ADMIN 비활성화 시도)에 대해 409 Conflict 반환 (#146). IllegalArgumentException(400)과
   * 구별하여 상태 충돌임을 명확히 한다.
   */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorResponse> handleIllegalState(
      IllegalStateException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  @ExceptionHandler(AiSessionNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleAiSessionNotFound(
      AiSessionNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  @ExceptionHandler(TriggerNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleTriggerNotFound(
      TriggerNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  @ExceptionHandler(CyclicTriggerDependencyException.class)
  public ResponseEntity<ErrorResponse> handleCyclicTriggerDependency(
      CyclicTriggerDependencyException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  @ExceptionHandler(CryptoException.class)
  public ResponseEntity<ErrorResponse> handleCrypto(
      CryptoException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null, request);
    return json(HttpStatus.INTERNAL_SERVER_ERROR, response);
  }

  @ExceptionHandler(SerializationException.class)
  public ResponseEntity<ErrorResponse> handleSerialization(
      SerializationException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null, request);
    return json(HttpStatus.INTERNAL_SERVER_ERROR, response);
  }

  @ExceptionHandler(ExternalServiceException.class)
  public ResponseEntity<ErrorResponse> handleExternalService(
      ExternalServiceException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_GATEWAY, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_GATEWAY, response);
  }

  /** 임베딩 미설정(#713) — 공급자 장애(502)가 아니라 설정 부재라 409 로 알린다. */
  @ExceptionHandler(com.smartfirehub.embedding.EmbeddingNotConfiguredException.class)
  public ResponseEntity<ErrorResponse> handleEmbeddingNotConfigured(
      com.smartfirehub.embedding.EmbeddingNotConfiguredException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  /** 임베딩 서비스(Ollama 등) 장애·미지원 provider 설정 시 외부 서비스 실패로 보고 502 반환. */
  @ExceptionHandler(EmbeddingException.class)
  public ResponseEntity<ErrorResponse> handleEmbedding(
      EmbeddingException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_GATEWAY, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_GATEWAY, response);
  }

  @ExceptionHandler(ImportProcessingException.class)
  public ResponseEntity<ErrorResponse> handleImportProcessing(
      ImportProcessingException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null, request);
    return json(HttpStatus.INTERNAL_SERVER_ERROR, response);
  }

  /**
   * API 연결이 없거나 보이지 않음 — 404 (WD-180). {@link ApiConnectionException} 의 하위 클래스라 Spring 이 더 구체적인 이
   * 처리기를 고른다.
   */
  @ExceptionHandler(ApiConnectionNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleApiConnectionNotFound(
      ApiConnectionNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /**
   * API 연결 입력·검증 오류(지원하지 않는 인증 방식, 잘못된 기준 URL, 내부 주소 거절 등) — 400 (WD-180). 예전에는 이 예외 전체를 404 로 보내
   * 입력 오류가 「찾을 수 없음」으로 나갔다.
   */
  @ExceptionHandler(ApiConnectionException.class)
  public ResponseEntity<ErrorResponse> handleApiConnectionInvalid(
      ApiConnectionException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /** 동일 테넌트 내 API 연결 이름 중복 시 409 반환 (#647). */
  @ExceptionHandler(ApiConnectionNameAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleApiConnectionNameAlreadyExists(
      ApiConnectionNameAlreadyExistsException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return json(HttpStatus.CONFLICT, response);
  }

  @ExceptionHandler(SavedQueryNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleSavedQueryNotFound(
      SavedQueryNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  @ExceptionHandler(ChartNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleChartNotFound(
      ChartNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  @ExceptionHandler(DashboardNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleDashboardNotFound(
      DashboardNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /** WD-367: 이 대시보드에 없는(다른 곳에서 먼저 지운) 위젯의 단건 수정·삭제 — 대시보드·차트와 같이 404. */
  @ExceptionHandler(WidgetNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleWidgetNotFound(
      WidgetNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  @ExceptionHandler(FileNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleFileNotFound(
      FileNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  @ExceptionHandler(UnsupportedUploadFileTypeException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedUploadFileType(
      UnsupportedUploadFileTypeException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(FileSizeLimitExceededException.class)
  public ResponseEntity<ErrorResponse> handleFileSizeLimitExceeded(
      FileSizeLimitExceededException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(ProactiveJobNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleProactiveJobNotFound(
      ProactiveJobNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /** Job 중복 실행 시도 — 409 Conflict 반환 (#149). ProactiveJobException보다 먼저 등록되어야 서브클래스 우선 매핑이 적용된다. */
  @ExceptionHandler(ProactiveJobAlreadyRunningException.class)
  public ResponseEntity<ErrorResponse> handleProactiveJobAlreadyRunning(
      ProactiveJobAlreadyRunningException ex, HttpServletRequest request) {
    // 코드를 실어 화면이 같은 409 인 다른 충돌과 구분해 「실행 이력」 바로가기를 붙일 수 있게 한다(WD-368)
    ErrorResponse response =
        buildError(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            null,
            request,
            ProactiveJobAlreadyRunningException.CODE);
    return json(HttpStatus.CONFLICT, response);
  }

  @ExceptionHandler(ProactiveJobException.class)
  public ResponseEntity<ErrorResponse> handleProactiveJob(
      ProactiveJobException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<ErrorResponse> handleAccountLocked(
      AccountLockedException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), null, request);
    return json(HttpStatus.TOO_MANY_REQUESTS, response);
  }

  /**
   * IOException 처리.
   *
   * <p>SSE/스트리밍 응답 중 클라이언트가 끊으면 Broken pipe 류 IOException 이 난다(bfd5c1fc). 이미 커밋된 응답에는 JSON 을 쓸 수
   * 없고, 끊긴 클라이언트에게는 쓸 곳도 없으므로 이 두 경우는 조용히 끝낸다(null = 처리 완료·본문 없음).
   *
   * <p>그 밖의(커밋 전·클라이언트 연결 정상) IOException 은 서버 처리 실패다. 예전에는 여기서도 로그만 남기고 void 로 끝내 HTTP 200 + 빈 본문이
   * 나갔고, 화면은 이를 성공으로 오인했다(WD-204: 엑셀 아닌 .xlsx 미리보기가 빈 대화상자). 일반 예외와 같은 형식·문구의 500 JSON 을 돌려준다.
   */
  @ExceptionHandler(IOException.class)
  public ResponseEntity<ErrorResponse> handleIOException(
      IOException ex, HttpServletRequest request, HttpServletResponse response) {
    if (response.isCommitted()) {
      log.debug("IOException on committed response (client disconnect): {}", ex.getMessage());
      return null;
    }
    if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
      log.debug("IOException from disconnected client: {}", ex.getMessage());
      return null;
    }
    log.error("Unhandled IOException", ex);
    ErrorResponse body =
        buildError(
            HttpStatus.INTERNAL_SERVER_ERROR, "예기치 않은 오류가 발생했습니다. 잠시 후 다시 시도하세요.", null, request);
    // SSE 요청(Accept: text/event-stream)에서도 협상 실패 없이 JSON 이 나가도록 json() 이 형식을 명시한다.
    return json(HttpStatus.INTERNAL_SERVER_ERROR, body);
  }

  @ExceptionHandler(AsyncRequestTimeoutException.class)
  public void handleAsyncTimeout(
      AsyncRequestTimeoutException ex, HttpServletRequest request, HttpServletResponse response) {
    // SSE/streaming endpoints: response is already committed with text/event-stream,
    // so we cannot write a JSON ErrorResponse. Just complete silently.
    if (response.isCommitted()) {
      log.debug(
          "Async timeout on committed response (SSE disconnect): {}", request.getRequestURI());
      return;
    }
    // Non-SSE async timeout: return 503
    log.warn("Async request timeout: {}", request.getRequestURI());
    response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    response.setContentType("application/json");
    try {
      PrintWriter writer = response.getWriter();
      writer.write(
          "{\"status\":503,\"error\":\"Service Unavailable\",\"message\":\"Request timed out\"}");
      writer.flush();
    } catch (IOException e) {
      log.debug("Failed to write timeout response: {}", e.getMessage());
    }
  }

  /** 지원하지 않는 HTTP 메서드로 요청 시 500 대신 405 반환 (#197) */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다.", null, request);
    return json(HttpStatus.METHOD_NOT_ALLOWED, response);
  }

  /**
   * 요청 본문 형식(Content-Type)이 엔드포인트가 받는 형식이 아니면 500 대신 415 로 답한다(WD-548).
   *
   * <p>text/plain·application/xml·form-urlencoded·헤더 없음(application/octet-stream 으로 간주)뿐 아니라, 형식이
   * 틀린 Content-Type({@code json}, {@code application/json; charset=bogus} 등)도 Spring 이 "Invalid
   * mime type" 사유로 같은 예외에 담아 올리므로 여기서 함께 처리된다. 예전에는 전용 처리가 없어 일반 예외로 떨어져 500 + ERROR 스택이 남았고, 공개
   * 로그인 엔드포인트로 누구나 오류 로그·알림을 채울 수 있었다. 클라이언트 잘못이고 요청 헤더 원문이 실리므로 DEBUG 로만 남긴다.
   *
   * <p>안내 문구는 지원 형식에서 고른다({@link #expectedRequestType}). 표준 {@code Accept}(PATCH 면 {@code
   * Accept-Patch}) 응답 헤더는 Spring 이 예외에 담아 둔 {@link HttpMediaTypeNotSupportedException#getHeaders()}
   * 를 그대로 싣는다 — 지원 목록이 비어 있으면(형식이 틀린 Content-Type) 헤더도 비어 온다.
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    log.debug("Unsupported media type on {}: {}", request.getRequestURI(), ex.getMessage());
    String expected = expectedRequestType(ex.getSupportedMediaTypes());
    ErrorResponse response =
        buildError(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "지원하지 않는 요청 형식입니다 — 요청 본문은 " + expected + " 이어야 합니다.",
            null,
            request);
    return json(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getHeaders(), response);
  }

  /**
   * 415 안내에 쓸 요청 형식을 지원 목록에서 고른다. JSON 계열이 있으면 JSON, 없고 multipart/form-data 가 있으면 multipart(예: POST
   * /api/v1/files), 그 밖에는 첫 지원 형식이다. 목록이 비면(형식이 틀린 Content-Type) 이 API 의 기본인 JSON 으로 안내한다. 원시 목록
   * (application/*+json 등)을 그대로 보이면 사용자에게 혼란스러워 대표 형식 하나만 고른다.
   */
  private static String expectedRequestType(List<MediaType> supported) {
    if (supported.isEmpty()
        || supported.stream().anyMatch(MediaType.APPLICATION_JSON::equalsTypeAndSubtype)) {
      return MediaType.APPLICATION_JSON_VALUE;
    }
    if (supported.stream().anyMatch(MediaType.MULTIPART_FORM_DATA::equalsTypeAndSubtype)) {
      return MediaType.MULTIPART_FORM_DATA_VALUE;
    }
    MediaType first = supported.get(0);
    return first.getType() + "/" + first.getSubtype();
  }

  /**
   * 클라이언트 Accept 가 요구하는 응답 형식을 만들 수 없으면 406 한국어 JSON 으로 답한다(WD-548).
   *
   * <p>예전에는 Accept: text/html 이면 영어 Whitelabel HTML, application/xml·image/png 면 빈 본문 406 이 나갔다. 오류
   * 본문은 항상 JSON 이어야 화면·클라이언트가 같은 형식으로 읽을 수 있으므로 {@link #json} 으로 협상을 건너뛴다(협상을 다시 하면 같은 Accept 로 또
   * 실패한다). 클라이언트 잘못이고 요청 헤더 원문이 실리므로 DEBUG 로만 남긴다.
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(
      HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
    log.debug("Not acceptable media type on {}: {}", request.getRequestURI(), ex.getMessage());
    ErrorResponse response =
        buildError(
            HttpStatus.NOT_ACCEPTABLE,
            "요청한 응답 형식을 지원하지 않습니다 — 응답은 application/json 으로만 제공합니다.",
            null,
            request);
    return json(HttpStatus.NOT_ACCEPTABLE, response);
  }

  /**
   * 경로/쿼리 파라미터 타입 불일치 시 500 대신 400 반환 (#219). 예: {@code Long id} 파라미터에 "abc" 같은 비-숫자 문자열이 전달되거나,
   * {@code Boolean} 쿼리 파라미터에 "notbool" 같은 값이 전달되는 경우. 클라이언트 입력 오류이므로 400으로 분류하여 모니터링 SLO 오염과 5xx 알람
   * 노이즈를 방지한다.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    String typeName = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "올바른";
    Map<String, String> fieldErrors =
        Map.of(ex.getName(), String.format("'%s'는 %s 타입이어야 합니다.", ex.getValue(), typeName));
    ErrorResponse response =
        buildError(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_MESSAGE, fieldErrors, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /**
   * 필수 쿼리 파라미터가 빠진 요청은 클라이언트 입력 오류라 500 대신 400 으로 답한다(WD-405). 예전에는 전용 처리가 없어 일반 예외로 떨어져 500 「예기치
   * 않은 오류」가 났다(예: GET /api/v1/settings 에서 prefix 를 빼면 500).
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingRequestParameter(
      MissingServletRequestParameterException ex, HttpServletRequest request) {
    Map<String, String> fieldErrors =
        Map.of(ex.getParameterName(), String.format("'%s' 값이 필요합니다.", ex.getParameterName()));
    ErrorResponse response =
        buildError(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_MESSAGE, fieldErrors, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /**
   * 필수 multipart part(예: POST /api/v1/files 의 {@code files})가 빠진 요청은 클라이언트 입력 오류라 400 으로 답한다(WD-521).
   * 예전에는 전용 처리가 없어 일반 예외로 떨어져 500 「예기치 않은 오류」가 났다 — 쿼리 파라미터 누락(WD-405)과 같은 모양으로 맞춘다.
   */
  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ErrorResponse> handleMissingRequestPart(
      MissingServletRequestPartException ex, HttpServletRequest request) {
    Map<String, String> fieldErrors =
        Map.of(
            ex.getRequestPartName(),
            String.format("'%s' 파일이 필요합니다.", ex.getRequestPartName()));
    ErrorResponse response =
        buildError(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_MESSAGE, fieldErrors, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /**
   * multipart 본문을 해석하지 못한 요청(본문 없음·경계 없음 등)도 클라이언트 입력 오류라 400 으로 답한다(WD-521). 크기 초과
   * ({@link MaxUploadSizeExceededException})는 더 구체적인 전용 처리가 먼저 잡는다.
   */
  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ErrorResponse> handleMultipart(
      MultipartException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.BAD_REQUEST, "파일 업로드 요청 본문을 해석할 수 없습니다.", null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /**
   * 요청 본문이 malformed JSON이거나 역직렬화에 실패한 경우 500 대신 400 반환 (#219). 같은 카테고리(클라이언트 입력 오류 → 4xx)이며 본문을 읽지
   * 못한 케이스이므로 400 Bad Request로 매핑한다.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMessageNotReadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.BAD_REQUEST, "요청 본문을 해석할 수 없습니다.", null, request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /** 존재하지 않는 API 경로 요청 시 500 대신 404 반환 (#98) */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResourceFound(
      NoResourceFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.", null, request);
    return json(HttpStatus.NOT_FOUND, response);
  }

  /**
   * 커넥션 풀 대기 초과(WD-553) → 503 + 재시도 안내. 예전에는 일반 500 으로 떨어져 "예기치 않은 오류" 로 보였다.
   *
   * <p>트랜잭션 시작({@link CannotCreateTransactionException})이나 트랜잭션 없는 커넥션 획득({@link
   * DataAccessResourceFailureException} — {@code CannotGetJdbcConnectionException} 등, {@link
   * TransientDataAccessResourceException})에서 Hikari 가 {@link SQLTransientConnectionException} 을
   * 원인으로 실어 올린 경우만 503 이다. 같은 타입이라도 원인이 다르면(예: 테넌트 GUC 설정 실패) 기존 일반 500 처리에 그대로 넘긴다.
   */
  @ExceptionHandler({
    CannotCreateTransactionException.class,
    DataAccessResourceFailureException.class,
    TransientDataAccessResourceException.class
  })
  public ResponseEntity<ErrorResponse> handleConnectionAcquireFailure(
      NestedRuntimeException ex, HttpServletRequest request) {
    if (!ex.contains(SQLTransientConnectionException.class)) {
      return handleException(ex, request);
    }
    // 풀 과부하뿐 아니라 DB 장애(접속 거부)로도 같은 예외가 올 수 있어 원인을 찾을 수 있게 스택을 남긴다.
    log.warn("DB 커넥션 획득 실패(풀 대기 초과): {}", ex.getMessage(), ex);
    ErrorResponse response =
        buildError(HttpStatus.SERVICE_UNAVAILABLE, POOL_EXHAUSTED_MESSAGE, null, request);
    return json(HttpStatus.SERVICE_UNAVAILABLE, response);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception", ex);
    // 화면이 서버 message 를 그대로 토스트로 보여 주므로(handleApiError) 사용자 안내 문장으로 쓴다(WD-110).
    // 예전 영어 문구("An unexpected error occurred")가 실행 실패 등에서 그대로 노출됐다.
    ErrorResponse response =
        buildError(
            HttpStatus.INTERNAL_SERVER_ERROR, "예기치 않은 오류가 발생했습니다. 잠시 후 다시 시도하세요.", null, request);
    return json(HttpStatus.INTERNAL_SERVER_ERROR, response);
  }
}
