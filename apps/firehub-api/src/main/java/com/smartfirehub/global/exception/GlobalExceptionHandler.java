package com.smartfirehub.global.exception;

import com.smartfirehub.ai.exception.AiSessionNotFoundException;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.dataimport.exception.ConcurrentImportException;
import com.smartfirehub.dataimport.exception.ImportProcessingException;
import com.smartfirehub.dataimport.exception.ImportValidationException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.exception.*;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.file.exception.FileNotFoundException;
import com.smartfirehub.file.exception.FileSizeLimitExceededException;
import com.smartfirehub.file.exception.UnsupportedUploadFileTypeException;
import com.smartfirehub.global.dto.ErrorResponse;
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
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.user.exception.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  private ErrorResponse buildError(
      HttpStatus status, String message, Map<String, String> errors, HttpServletRequest request) {
    return new ErrorResponse(
        status.value(),
        status.getReasonPhrase(),
        message,
        errors,
        Instant.now().toString(),
        request.getRequestURI());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationExceptions(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> fieldErrors = new HashMap<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
    ErrorResponse response =
        buildError(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors, request);
    return ResponseEntity.badRequest().body(response);
  }

  /**
   * @Validated + @Min/@Max 등 쿼리 파라미터 제약 위반 시 400 반환 (#139)
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    Map<String, String> fieldErrors = new HashMap<>();
    ex.getConstraintViolations()
        .forEach(
            v -> {
              String path = v.getPropertyPath().toString();
              String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
              fieldErrors.put(field, v.getMessage());
            });
    ErrorResponse response =
        buildError(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors, request);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(UsernameAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleUsernameAlreadyExists(
      UsernameAlreadyExistsException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(EmailAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(
      EmailAlreadyExistsException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleInvalidCredentials(
      InvalidCredentialsException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.UNAUTHORIZED, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
  }

  @ExceptionHandler(InvalidTokenException.class)
  public ResponseEntity<ErrorResponse> handleInvalidToken(
      InvalidTokenException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.UNAUTHORIZED, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    String message = "Data integrity violation";
    if (ex.getCause() != null) {
      String causeMsg = ex.getCause().getMessage();
      if (causeMsg != null && causeMsg.contains("duplicate key")) {
        message = "Data integrity violation: duplicate entry";
      } else if (causeMsg != null && causeMsg.contains("foreign key")) {
        message = "Data integrity violation: referenced record not found";
      } else if (causeMsg != null && causeMsg.contains("check constraint")) {
        message = "Data integrity violation: constraint check failed - " + causeMsg;
      } else if (causeMsg != null) {
        message = "Data integrity violation: " + causeMsg;
      }
    }
    ErrorResponse response = buildError(HttpStatus.CONFLICT, message, null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.FORBIDDEN, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
  }

  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleUserNotFound(
      UserNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(RoleNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleRoleNotFound(
      RoleNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(SystemRoleModificationException.class)
  public ResponseEntity<ErrorResponse> handleSystemRoleModification(
      SystemRoleModificationException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(UserDeactivatedException.class)
  public ResponseEntity<ErrorResponse> handleUserDeactivated(
      UserDeactivatedException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.UNAUTHORIZED, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
  }

  @ExceptionHandler(DatasetNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleDatasetNotFound(
      DatasetNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(CategoryNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleCategoryNotFound(
      CategoryNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(DuplicateDatasetNameException.class)
  public ResponseEntity<ErrorResponse> handleDuplicateDatasetName(
      DuplicateDatasetNameException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  /** 파이프라인 스텝 등 외부 리소스가 데이터셋을 참조하고 있어 삭제 불가한 경우 409 반환 (#126) */
  @ExceptionHandler(com.smartfirehub.dataset.exception.DatasetInUseException.class)
  public ResponseEntity<ErrorResponse> handleDatasetInUse(
      com.smartfirehub.dataset.exception.DatasetInUseException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(InvalidTableNameException.class)
  public ResponseEntity<ErrorResponse> handleInvalidTableName(
      InvalidTableNameException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(ColumnModificationException.class)
  public ResponseEntity<ErrorResponse> handleColumnModification(
      ColumnModificationException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
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
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(ConcurrentImportException.class)
  public ResponseEntity<ErrorResponse> handleConcurrentImport(
      ConcurrentImportException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(UnsupportedFileTypeException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedFileType(
      UnsupportedFileTypeException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
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
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(PipelineNotFoundException.class)
  public ResponseEntity<ErrorResponse> handlePipelineNotFound(
      PipelineNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  /** 파이프라인 이름 중복 시 409 반환 (#181) */
  @ExceptionHandler(PipelineNameConflictException.class)
  public ResponseEntity<ErrorResponse> handlePipelineNameConflict(
      PipelineNameConflictException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  /** 비활성 파이프라인 수동 실행 시도 시 409 반환 (#187) */
  @ExceptionHandler(PipelineInactiveException.class)
  public ResponseEntity<ErrorResponse> handlePipelineInactive(
      PipelineInactiveException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(CyclicDependencyException.class)
  public ResponseEntity<ErrorResponse> handleCyclicDependency(
      CyclicDependencyException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(ScriptExecutionException.class)
  public ResponseEntity<ErrorResponse> handleScriptExecution(
      ScriptExecutionException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
  }

  /** 파이프라인 SQL 스텝의 안전 정책 위반 — 저장 시 거부 또는 실행 시 거부 모두 동일 매핑. (#136) */
  @ExceptionHandler(UnsafeSqlException.class)
  public ResponseEntity<ErrorResponse> handleUnsafeSql(
      UnsafeSqlException ex, HttpServletRequest request) {
    log.warn("Unsafe SQL rejected: {}", ex.getMessage());
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
  }

  /** 파이프라인 PYTHON 스텝의 escalation 코드 차단 — 저장 시·실행 시 모두 400 매핑. (#270) */
  @ExceptionHandler(com.smartfirehub.pipeline.exception.UnsafePythonScriptException.class)
  public ResponseEntity<ErrorResponse> handleUnsafePython(
      com.smartfirehub.pipeline.exception.UnsafePythonScriptException ex,
      HttpServletRequest request) {
    log.warn("Unsafe Python script rejected: {}", ex.getMessage());
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
  }

  @ExceptionHandler(com.smartfirehub.dataset.exception.SqlQueryException.class)
  public ResponseEntity<ErrorResponse> handleSqlQuery(
      com.smartfirehub.dataset.exception.SqlQueryException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(com.smartfirehub.dataset.exception.RowNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleRowNotFound(
      com.smartfirehub.dataset.exception.RowNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
  }

  /**
   * 비즈니스 규칙 위반(예: 마지막 ADMIN 비활성화 시도)에 대해 409 Conflict 반환 (#146). IllegalArgumentException(400)과
   * 구별하여 상태 충돌임을 명확히 한다.
   */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorResponse> handleIllegalState(
      IllegalStateException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(AiSessionNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleAiSessionNotFound(
      AiSessionNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(TriggerNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleTriggerNotFound(
      TriggerNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(CyclicTriggerDependencyException.class)
  public ResponseEntity<ErrorResponse> handleCyclicTriggerDependency(
      CyclicTriggerDependencyException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(CryptoException.class)
  public ResponseEntity<ErrorResponse> handleCrypto(
      CryptoException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
  }

  @ExceptionHandler(SerializationException.class)
  public ResponseEntity<ErrorResponse> handleSerialization(
      SerializationException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
  }

  @ExceptionHandler(ExternalServiceException.class)
  public ResponseEntity<ErrorResponse> handleExternalService(
      ExternalServiceException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_GATEWAY, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
  }

  /** 임베딩 서비스(Ollama 등) 장애·미지원 provider 설정 시 외부 서비스 실패로 보고 502 반환. */
  @ExceptionHandler(EmbeddingException.class)
  public ResponseEntity<ErrorResponse> handleEmbedding(
      EmbeddingException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_GATEWAY, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
  }

  @ExceptionHandler(ImportProcessingException.class)
  public ResponseEntity<ErrorResponse> handleImportProcessing(
      ImportProcessingException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
  }

  @ExceptionHandler(ApiConnectionException.class)
  public ResponseEntity<ErrorResponse> handleApiConnectionNotFound(
      ApiConnectionException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(SavedQueryNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleSavedQueryNotFound(
      SavedQueryNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(ChartNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleChartNotFound(
      ChartNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(DashboardNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleDashboardNotFound(
      DashboardNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(FileNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleFileNotFound(
      FileNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(UnsupportedUploadFileTypeException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedUploadFileType(
      UnsupportedUploadFileTypeException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(FileSizeLimitExceededException.class)
  public ResponseEntity<ErrorResponse> handleFileSizeLimitExceeded(
      FileSizeLimitExceededException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(ProactiveJobNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleProactiveJobNotFound(
      ProactiveJobNotFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  /** Job 중복 실행 시도 — 409 Conflict 반환 (#149). ProactiveJobException보다 먼저 등록되어야 서브클래스 우선 매핑이 적용된다. */
  @ExceptionHandler(ProactiveJobAlreadyRunningException.class)
  public ResponseEntity<ErrorResponse> handleProactiveJobAlreadyRunning(
      ProactiveJobAlreadyRunningException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.CONFLICT, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(ProactiveJobException.class)
  public ResponseEntity<ErrorResponse> handleProactiveJob(
      ProactiveJobException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<ErrorResponse> handleAccountLocked(
      AccountLockedException ex, HttpServletRequest request) {
    ErrorResponse response =
        buildError(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), null, request);
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
  }

  @ExceptionHandler(IOException.class)
  public void handleIOException(IOException ex, HttpServletResponse response) {
    // SSE/streaming connections throw IOException (Broken pipe) when clients disconnect.
    // If the response is already committed, we cannot write a JSON error body — just log and
    // return.
    if (response.isCommitted()) {
      log.debug("IOException on committed response (client disconnect): {}", ex.getMessage());
      return;
    }
    log.error("Unhandled IOException", ex);
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
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
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
        buildError(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors, request);
    return ResponseEntity.badRequest().body(response);
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
    return ResponseEntity.badRequest().body(response);
  }

  /** 존재하지 않는 API 경로 요청 시 500 대신 404 반환 (#98) */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResourceFound(
      NoResourceFoundException ex, HttpServletRequest request) {
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.", null, request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception", ex);
    ErrorResponse response =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", null, request);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
  }
}
