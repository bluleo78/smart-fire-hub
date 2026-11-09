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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.BeanUtils;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.NestedRuntimeException;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.support.MultipartResolutionDelegate;
import org.springframework.web.server.ResponseStatusException;
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

  /**
   * Bean Validation 400 의 기계용 코드(WD-640) — 화면·ai-agent 가 「필드별 사람용 문구가 담긴 검증 실패」를 문구·모양 추측 없이 가려내게
   * 한다. web 의 errors 해석(pickBestMessage·applyFieldErrors)과 admin 폼은 이 코드를 code 없음과 똑같이(필드 문구로) 다룬다.
   */
  public static final String VALIDATION_FAILED_CODE = "VALIDATION_FAILED";

  /** 커넥션 풀 대기 초과(WD-553) 안내 — 일시적 과부하이므로 재시도를 권한다. */
  static final String POOL_EXHAUSTED_MESSAGE = "요청이 많아 지금은 처리할 수 없습니다. 잠시 후 다시 시도하세요.";

  /** 404 공통 안내 — 없는 경로(NoResourceFound)와 4xx 표준 예외의 404 가 같은 문장을 쓴다(WD-626). */
  static final String NOT_FOUND_MESSAGE = "요청한 리소스를 찾을 수 없습니다.";

  /** 500 공통 안내 — 일반 예외·서버 쪽 IOException 이 같은 문장을 쓴다(WD-110, WD-626). */
  static final String UNEXPECTED_ERROR_MESSAGE = "예기치 않은 오류가 발생했습니다. 잠시 후 다시 시도하세요.";

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

  /**
   * 추가 응답 헤더(예: 415 의 Accept)를 함께 싣는 버전.
   *
   * <p>응답이 이미 커밋됐으면(SSE·다운로드가 흐르는 중) 본문을 쓰지 않고 null(처리 완료·본문 없음)을 돌려준다(WD-626). 예전에는 일반 처리만 이를 확인해,
   * 전용 처리가 있는 예외(IllegalStateException 409 등)는 이미 나간 {@code data:} 줄 뒤에 JSON 을 덧붙여 스트림을 깨뜨렸다. 모든
   * 처리가 이 메서드를 거치므로 여기 한곳에서 막는다. 로그는 각 처리가 이미 남겼으므로(서버 결함이면 ERROR) 여기서는 본문 생략만 DEBUG 로 남긴다.
   */
  private static ResponseEntity<ErrorResponse> json(
      HttpStatus status, HttpHeaders headers, ErrorResponse body) {
    if (isCurrentResponseCommitted()) {
      log.debug("Skip error body {} on committed response: {}", status.value(), body.path());
      return null;
    }
    return ResponseEntity.status(status)
        .headers(headers)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }

  /** 지금 처리 중인 요청의 응답이 이미 커밋됐는지 본다(WD-626). 요청 문맥이 없으면(단위 테스트에서 처리를 직접 부르는 경우 등) 커밋되지 않은 것으로 본다. */
  private static boolean isCurrentResponseCommitted() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      return isCommitted(attrs.getResponse());
    }
    return false;
  }

  /**
   * 응답이 이미 커밋됐는지(SSE·다운로드가 흐르는 중)의 단일 판정(WD-649). {@link #json} 과, 응답을 인자로 받아 json() 을 거치기 전에 먼저
   * 확인해야 하는 처리(handleIOException·handleAsyncTimeout — 커밋 시 ERROR·WARN 로그 없이 조용히 끝낸다)가 모두 이 메서드를 쓴다.
   */
  private static boolean isCommitted(HttpServletResponse response) {
    return response != null && response.isCommitted();
  }

  /**
   * 요청 본문(@Valid @RequestBody)·폼 바인딩(@ModelAttribute) 검증 실패 → 400(WD-570, WD-640).
   *
   * <p>{@link MethodArgumentNotValidException} 은 {@link BindException} 의 하위 타입이라 이 처리기 하나가 둘 다 받는다.
   * Spring 6 의 @ModelAttribute 검증 실패는 MethodArgumentNotValidException 으로 오지만, 직접 만든 데이터 바인더 등에서 순수
   * BindException 이 올라오면 예전에는 일반 500 으로 떨어졌다. 예전 처리의 실제 결함은 둘이었다: getFieldErrors() 만 읽어 클래스 단위 합성
   * 제약(global error)이 응답에서 사라졌고, 바인딩 타입 변환 실패(typeMismatch)는 Spring 영어 기본 문구 「Failed to convert
   * property value…」가 그대로 나갔다 — 이제 각각 {@link ValidationErrorCollector#GLOBAL_KEY} 와 한국어 문구로 싣는다.
   */
  @ExceptionHandler(BindException.class)
  public ResponseEntity<ErrorResponse> handleValidationExceptions(
      BindException ex, HttpServletRequest request) {
    ValidationErrorCollector collector = new ValidationErrorCollector();
    collectErrors(collector, ex.getBindingResult(), "");
    return validationFailed(collector, request);
  }

  /**
   * Spring 6.1+ 내장 메서드 검증 실패 → 400(WD-640, WD-654, WD-665).
   *
   * <p>컨트롤러 메서드 파라미터에 제약을 직접 붙이면(예: {@code @RequestParam @Min(1) int size},
   * {@code @PathVariable @Size(max = 3)}) 클래스에 @Validated 가 없어도 Spring MVC 가 검증하고 이 예외를 던진다. 처리기가
   * 없어 일반 예외로 떨어져 500 「예기치 않은 오류」가 났다. 같은 메서드에 @Valid 본문이 함께 있거나 본문이 목록(List&lt;@Valid Dto&gt;)이면
   * 본문 오류도 이 예외로 온다.
   *
   * <p>키 규칙은 {@link #handleConstraintViolation}(@Validated 서비스)과 같다 — 같은 위반은 어느 검증 층에서 나도 같은
   * 키다(WD-665). 파라미터 종류는 Spring 이 넘겨준 결과 타입으로 가른다:
   *
   * <ul>
   *   <li>{@link ParameterErrors}(@Valid 객체의 속성 위반): 객체 파라미터. 키는 객체 안 경로(「name」·「a.b」), 목록·맵 원소면
   *       「[3].name」, 원소의 클래스 단위 오류는 「[3]」, 객체 자체의 클래스 단위 오류는 {@link
   *       ValidationErrorCollector#GLOBAL_KEY}.
   *   <li>그 밖의 결과(파라미터·원소 값 자체의 위반, 클래스 단위 위반): 검증된 값이 빈(단순 타입이 아닌 객체)이거나 본문이면 객체 — 「[3]」/_global.
   *       아니면 값 파라미터 — 키는 클라이언트 이름(바인딩 애너테이션의 name, 없으면 자바 파라미터 이름) + 원소 위치(「ids[1]」).
   *       MultipartFile·Part(목록·배열 포함)와 Optional 안의 단순 타입은 값이다 — Spring MVC 도 애너테이션 없는 이런 파라미터를 요청
   *       파라미터로 바인딩한다.
   * </ul>
   *
   * <p>Spring 의 {@code visitResults}(Visitor)는 쓰지 않는다 — 6.2.1 은 본문·폼·파트 파라미터의 값 위반(ParameterErrors
   * 가 아닌 결과, 예: {@code @RequestBody @NotEmpty List})에서 IllegalStateException 으로 깨지고, 순회 중간에 깨지면 나머지
   * 결과를 이어 돌 수 없다. 대신 Visitor 가 기대는 것과 같은 정보(결과 타입·파라미터)를 직접 읽는다.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest request) {
    // 반환값 검증 실패(isForReturnValue — Spring 이 500 으로 둔다)는 클라이언트 입력 오류가 아니라 서버가 계약을 어긴 것이다(WD-649).
    // 입력 오류처럼 400 「입력값이 올바르지 않습니다」+ 내부 이름(arg-1)으로 답하면 결함이 묻히므로 일반 서버 오류 길(ERROR 로그·스택,
    // 「예기치 않은 오류」, errors 없음)로 넘긴다.
    if (!ex.getStatusCode().is4xxClientError()) {
      return handleException(ex, request);
    }
    ValidationErrorCollector collector = new ValidationErrorCollector();
    for (ParameterValidationResult r : ex.getParameterValidationResults()) {
      String position = containerSuffix(r);
      if (r instanceof ParameterErrors errors) {
        addBeanErrors(collector, errors, position);
        continue;
      }
      MethodParameter p = r.getMethodParameter();
      ParamKind kind = paramKind(p, r.getArgument(), javaParameterName(p));
      for (MessageSourceResolvable error : r.getResolvableErrors()) {
        addKeyed(
            collector,
            composeKey(kind, position, ""),
            constraintCode(error),
            error.getDefaultMessage());
      }
    }
    // 교차 파라미터 제약(메서드 단위)은 특정 파라미터에 딸리지 않으므로 필드 없는 오류로 싣는다(파라미터 결과 목록에 없다).
    ex.getCrossParameterValidationResults()
        .forEach(e -> collector.addGlobal(e.getDefaultMessage()));
    return validationFailed(collector, request);
  }

  /** 클라이언트 이름을 줄 수 있는 바인딩 애너테이션(@RequestParam·@PathVariable·@RequestHeader 등)이 사는 패키지. */
  private static final String BIND_ANNOTATION_PACKAGE = RequestBody.class.getPackageName();

  /**
   * 검증 파라미터 하나의 종류(WD-665).
   *
   * @param object 객체 파라미터인지 — 참이면 키는 객체 기준 상대 경로이고 파라미터 이름을 붙이지 않는다.
   * @param name 값 파라미터일 때 키로 쓸 클라이언트 이름.
   */
  record ParamKind(boolean object, String name) {

    /** 파라미터에 딸리지 않은 위반(validator.validate(bean) 직접 호출 등) — 객체 기준 경로만 쓴다. */
    static final ParamKind ROOT_OBJECT = new ParamKind(true, null);
  }

  /**
   * 파라미터 종류를 정한다(WD-665) — 두 검증 층(HandlerMethodValidation·ConstraintViolation)이 같이 쓴다.
   *
   * <p>애너테이션은 한 번만 훑는다: @RequestBody 면 객체(본문에는 클라이언트가 부를 이름이 없다), 바인딩 애너테이션(이 패키지의 애너테이션 중 name 속성이
   * 있는 것 — @RequestParam·@PathVariable·@RequestHeader·@CookieValue·@MatrixVariable·@RequestPart 등)에
   * 이름이 적혀 있으면 그 이름을 값 키로 쓴다. Spring 의 애너테이션 목록을 손으로 베끼지 않으므로 새 바인딩 애너테이션이 생겨도 빠지지 않는다. 객체·값 구분 자체는
   * 애너테이션이 아니라 검증된 값의 타입으로 한다({@link #isBeanValue}).
   *
   * @param value 검증된 값 — 인자, 목록·맵 원소, Optional 안의 값(없으면 null)
   * @param fallbackName 이름이 적힌 바인딩 애너테이션이 없을 때 쓸 이름(자바 파라미터 이름)
   */
  static ParamKind paramKind(MethodParameter p, Object value, String fallbackName) {
    boolean body = false;
    String clientName = null;
    for (Annotation a : p.getParameterAnnotations()) {
      if (a instanceof RequestBody) {
        body = true;
      } else if (clientName == null
          && a.annotationType().getPackageName().equals(BIND_ANNOTATION_PACKAGE)) {
        clientName =
            MergedAnnotation.from(a)
                .getValue("name", String.class)
                .filter(StringUtils::hasText)
                .orElse(null);
      }
    }
    return new ParamKind(
        body || isBeanValue(p, value), clientName != null ? clientName : fallbackName);
  }

  /**
   * 검증된 값이 빈(단순 타입이 아닌 객체)인지 — 참이면 객체 파라미터다(WD-665).
   *
   * <p>MultipartFile·Part(목록·배열·Optional 포함)는 값이다 — Spring MVC 는 애너테이션 없는 이런 파라미터를 요청 파라미터로 바인딩하는데
   * BeanUtils 로는 단순 타입이 아니라서 예전에는 폼 객체(_global)로 잘못 갈렸다(WD-665 1). 값이 없거나(null) 목록·맵·Optional 전체면
   * 선언 타입의 원소 타입으로 본다 — 예: 빈 본문 {@code @NotEmpty List<Row>} 는 Row 라 객체, {@code Optional<Integer>} 는
   * Integer 라 값. 원소 타입을 알 수 없으면 값으로 본다(파라미터 이름이 있는 키가 더 많은 것을 알려 준다).
   */
  static boolean isBeanValue(MethodParameter p, Object value) {
    if (MultipartResolutionDelegate.isMultipartArgument(p.nestedIfOptional())) {
      return false;
    }
    Class<?> type = value != null && !isContainer(value) ? value.getClass() : elementType(p);
    return type != null && !BeanUtils.isSimpleProperty(type);
  }

  /** 목록·맵·배열·Optional 처럼 원소를 담는 값인지. */
  private static boolean isContainer(Object value) {
    return value instanceof Iterable<?>
        || value instanceof Map<?, ?>
        || value instanceof Optional<?>
        || value.getClass().isArray();
  }

  /** 선언 타입의 원소 타입 — Optional·배열·컬렉션·맵(값)을 한 겹 벗긴다. 알 수 없으면 null. */
  private static Class<?> elementType(MethodParameter p) {
    ResolvableType type = ResolvableType.forMethodParameter(p);
    if (Optional.class == type.toClass()) {
      type = type.getGeneric(0);
    }
    if (type.isArray()) {
      type = type.getComponentType();
    } else if (Collection.class.isAssignableFrom(type.toClass())) {
      type = type.asCollection().getGeneric(0);
    } else if (Map.class.isAssignableFrom(type.toClass())) {
      type = type.asMap().getGeneric(1);
    }
    return type.resolve();
  }

  /** 자바 파라미터 이름 — 컴파일 정보가 없으면 「arg0」 꼴. */
  private static String javaParameterName(MethodParameter p) {
    String name = p.getParameterName();
    return name != null ? name : "arg" + p.getParameterIndex();
  }

  /**
   * 경로 종류 규칙으로 응답 키를 만든다(WD-665) — 두 검증 층이 이 한 곳을 쓰므로 같은 위반은 같은 키다.
   *
   * <ul>
   *   <li>객체: 원소 위치 + 객체 안 상대 경로 — 「name」·「a.b」·「[3].name」·「[3]」. 둘 다 비면(객체 자체의 클래스 단위·값 위반) null →
   *       {@link ValidationErrorCollector#GLOBAL_KEY}.
   *   <li>값: 클라이언트 이름 + 원소 위치 — 「size」·「ids[1]」·「labels[x.y]」.
   * </ul>
   *
   * @param position 파라미터가 목록·맵일 때 그 원소 위치(「[3]」), 아니면 빈 문자열
   * @param relative 원소(또는 객체) 안의 속성 경로, 없으면 빈 문자열
   */
  static String composeKey(ParamKind kind, String position, String relative) {
    if (!kind.object()) {
      return kind.name() + position + (relative.isEmpty() ? "" : "." + relative);
    }
    if (relative.isEmpty()) {
      return position.isEmpty() ? null : position;
    }
    return position.isEmpty() ? relative : position + "." + relative;
  }

  /** 키가 있으면 필드 오류로, 없으면(null) 필드 없는 오류로 싣는다. */
  private static void addKeyed(
      ValidationErrorCollector collector, String key, String code, String message) {
    if (key == null) {
      collector.addGlobal(message);
    } else {
      collector.addField(key, code, message);
    }
  }

  /**
   * @Validated 메서드 검증(쿼리 파라미터·서비스 메서드 등) 제약 위반 → 400 (#139, WD-640, WD-665).
   *
   * <p>필드 키는 {@link #handleHandlerMethodValidation} 과 같은 규칙({@link #composeKey})이다 — 컨트롤러에서
   * 막히든 @Validated 서비스에서 막히든 같은 위반은 같은 키다. 예전에는 같은 목록 원소의 클래스 단위 위반이 컨트롤러에선 「[0]」, 서비스에선
   * 「rows[0]」이었다.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    ValidationErrorCollector collector = new ValidationErrorCollector();
    ex.getConstraintViolations()
        .forEach(
            v ->
                addKeyed(
                    collector,
                    violationField(v),
                    v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                    v.getMessage()));
    return validationFailed(collector, request);
  }

  /**
   * 위반 하나를 응답 키로 바꾼다(WD-640, WD-654, WD-665). 문자열을 다시 들여다보지 않고 경로 마디(Path.Node)의 종류로 나눈다.
   *
   * <ul>
   *   <li>메서드·생성자 마디는 뺀다. 교차 파라미터·반환값 위반은 null(→ 필드 없는 오류).
   *   <li>파라미터 바로 다음 마디가 목록·맵 원소 안이면 그 위치가 원소 위치(「[3]」)다. 그 뒤 속성 마디들이 상대
   *       경로(「name」·「items[1].name」)다.
   *   <li>파라미터 종류: 경로에 속성·BEAN 마디가 있으면 객체(Spring 이 ParameterErrors·빈 값으로 넘기는 경우와 같다), 아니면 {@link
   *       #paramKind}로 컨트롤러 경로와 똑같이 가른다 — 그래서 MultipartFile·Optional·바인딩 애너테이션 이름도 두 층이 같게 나온다.
   *   <li>파라미터가 없는 경로(validator.validate(bean) 직접 호출)는 객체 기준 경로다.
   * </ul>
   */
  static String violationField(ConstraintViolation<?> v) {
    Path.Node executable = null;
    Path.Node parameter = null;
    boolean firstAfterParameter = false;
    boolean objectPath = false;
    String position = "";
    StringBuilder relative = new StringBuilder();
    for (Path.Node node : v.getPropertyPath()) {
      ElementKind kind = node.getKind();
      if (kind == ElementKind.CROSS_PARAMETER || kind == ElementKind.RETURN_VALUE) {
        return null;
      }
      if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
        executable = node;
        continue;
      }
      if (kind == ElementKind.PARAMETER) {
        parameter = node;
        firstAfterParameter = true;
        continue;
      }
      if (kind == ElementKind.PROPERTY || kind == ElementKind.BEAN) {
        objectPath = true;
      }
      // 이 마디가 목록·맵 원소 안이면 그 위치 — HV 는 첨자를 원소 다음 마디에 싣는다. 파라미터 바로 다음이면 파라미터 원소 위치다.
      // Set 처럼 첨자·키가 없는 원소(둘 다 null)는 위치를 붙이지 않는다 — 컨트롤러 층(containerSuffix "")과 같은 키,
      // 그리고 API 키에 "[null]" 글자가 새지 않게 한다.
      Object at0 = node.getIndex() != null ? node.getIndex() : node.getKey();
      if (node.isInIterable() && at0 != null) {
        String at = "[" + at0 + "]";
        if (firstAfterParameter) {
          position = at;
        } else {
          relative.append(at);
        }
      }
      firstAfterParameter = false;
      if (kind == ElementKind.PROPERTY && node.getName() != null) {
        if (relative.length() > 0) {
          relative.append('.');
        }
        relative.append(node.getName());
      }
    }
    ParamKind kind;
    if (parameter == null || objectPath) {
      kind = ParamKind.ROOT_OBJECT;
    } else {
      kind = valueViolationKind(v, executable, parameter);
    }
    return composeKey(kind, position, relative.toString());
  }

  /**
   * 경로가 파라미터(또는 그 원소)에서 끝나는 위반의 파라미터 종류 — 메서드 파라미터와 검증된 값을 찾아 {@link #paramKind}에 넘긴다. 메서드를 찾지 못하면
   * 값 파라미터(경로의 파라미터 이름)로 본다.
   */
  private static ParamKind valueViolationKind(
      ConstraintViolation<?> v, Path.Node executable, Path.Node parameter) {
    int index = parameter.as(Path.ParameterNode.class).getParameterIndex();
    MethodParameter p = methodParameter(v.getRootBeanClass(), executable, index);
    if (p == null) {
      return new ParamKind(false, parameter.getName());
    }
    Object[] args = v.getExecutableParameters();
    Object arg = args != null && index < args.length ? args[index] : null;
    return paramKind(p, validatedValue(arg, v.getPropertyPath()), parameter.getName());
  }

  /** 위반 경로의 메서드·생성자 마디로 실제 메서드 파라미터를 찾는다. 못 찾으면 null. */
  private static MethodParameter methodParameter(
      Class<?> rootClass, Path.Node executable, int index) {
    if (rootClass == null || executable == null) {
      return null;
    }
    Executable found;
    if (executable.getKind() == ElementKind.METHOD) {
      List<Class<?>> types = executable.as(Path.MethodNode.class).getParameterTypes();
      found =
          ReflectionUtils.findMethod(
              rootClass, executable.getName(), types.toArray(Class<?>[]::new));
    } else {
      List<Class<?>> types = executable.as(Path.ConstructorNode.class).getParameterTypes();
      found = ClassUtils.getConstructorIfAvailable(rootClass, types.toArray(Class<?>[]::new));
    }
    if (found == null || index >= found.getParameterCount()) {
      return null;
    }
    return MethodParameter.forExecutable(found, index);
  }

  /**
   * 위반이 가리키는 값 — 인자가 목록·배열·맵이고 위반이 그 원소면 원소, Optional 이면 그 내용, 아니면 인자. Spring 의
   * MethodValidationAdapter 가 ParameterValidationResult 의 argument 로 넣는 값과 같게 맞춰 두 층이 같은 판단을 하게 한다.
   */
  private static Object validatedValue(Object arg, Path path) {
    Path.Node element = null;
    boolean afterParameter = false;
    for (Path.Node node : path) {
      if (afterParameter) {
        element = node;
        break;
      }
      afterParameter = node.getKind() == ElementKind.PARAMETER;
    }
    if (element != null && element.getIndex() != null) {
      if (arg instanceof List<?> list && element.getIndex() < list.size()) {
        return list.get(element.getIndex());
      }
      if (arg instanceof Object[] array && element.getIndex() < array.length) {
        return array[element.getIndex()];
      }
    }
    if (element != null && element.getKey() != null && arg instanceof Map<?, ?> map) {
      return map.get(element.getKey());
    }
    if (arg instanceof Optional<?> optional) {
      return optional.orElse(null);
    }
    return arg;
  }

  /** 바인딩 결과의 필드 오류·필드 없는 오류를 수집기에 옮긴다. prefix 는 목록 원소 위치(예: 「[0].」). */
  private static void collectErrors(
      ValidationErrorCollector collector, Errors errors, String prefix) {
    // FieldError 의 code 는 제약 애너테이션의 단순 이름(예: "NotBlank") 또는 변환 실패 "typeMismatch" 다.
    for (FieldError error : errors.getFieldErrors()) {
      collector.addField(prefix + error.getField(), error.getCode(), error.getDefaultMessage());
    }
    // 클래스 단위 합성 제약(예: 두 칸이 같아야 함)은 특정 필드가 없는 global error 로 온다.
    for (ObjectError error : errors.getGlobalErrors()) {
      collector.addGlobal(error.getDefaultMessage());
    }
  }

  /**
   * 객체 파라미터(본문·폼·파트)의 오류(WD-665) — 키는 {@link #composeKey} 의 객체 규칙. 필드 오류는 「name」·목록 원소면 「[3].name」,
   * 클래스 단위(필드 없는) 오류는 원소면 「[3]」, 객체 자체면 _global. 예전에는 원소의 클래스 단위 오류도 _global 로 보내 어느 행이 틀렸는지 잃었다.
   */
  private static void addBeanErrors(
      ValidationErrorCollector collector, ParameterErrors errors, String position) {
    // FieldError 의 code 는 제약 애너테이션의 단순 이름(예: "NotBlank") 또는 변환 실패 "typeMismatch" 다.
    for (FieldError error : errors.getFieldErrors()) {
      addKeyed(
          collector,
          composeKey(ParamKind.ROOT_OBJECT, position, error.getField()),
          error.getCode(),
          error.getDefaultMessage());
    }
    for (ObjectError error : errors.getGlobalErrors()) {
      addKeyed(
          collector,
          composeKey(ParamKind.ROOT_OBJECT, position, ""),
          error.getCode(),
          error.getDefaultMessage());
    }
  }

  /** 목록·맵 원소 검증이면 「[0]」·「[key]」. */
  private static String containerSuffix(ParameterValidationResult result) {
    if (result.getContainerIndex() != null) {
      return "[" + result.getContainerIndex() + "]";
    }
    if (result.getContainerKey() != null) {
      return "[" + result.getContainerKey() + "]";
    }
    return "";
  }

  /**
   * 검증 실패 공통 응답 — 400, 한국어 최상위 문구, 필드별 문구(필수값 우선 순), 기계용 코드 {@link #VALIDATION_FAILED_CODE}.
   *
   * <p>코드를 싣는 이유(WD-640): ai-agent 가 「입력 검증 실패」를 응답 모양으로 추측하면 임포트 행 오류(error_N)·필수 파라미터 누락처럼 모양이 같은
   * 다른 400 도 섞였다. 코드는 Bean Validation 경로에만 붙인다.
   */
  private ResponseEntity<ErrorResponse> validationFailed(
      ValidationErrorCollector collector, HttpServletRequest request) {
    ErrorResponse response =
        buildError(
            HttpStatus.BAD_REQUEST,
            VALIDATION_FAILED_MESSAGE,
            collector.toErrors(),
            request,
            VALIDATION_FAILED_CODE);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /**
   * MessageSourceResolvable 의 제약 이름. 메서드 검증 오류의 codes 는 구체적인 것부터 「Min.controller.size」…「Min」 순이라
   * 마지막이 단순 이름이다.
   */
  private static String constraintCode(MessageSourceResolvable error) {
    String[] codes = error.getCodes();
    return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
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
    // 행 순서를 지킨다(WD-640) — 웹 토스트는 첫 값에 「(외 N건)」을 붙여 보이므로 HashMap 순서면 1행이 아닌 임의의 행이 보였다.
    Map<String, String> errorMap = new LinkedHashMap<>();
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
    if (isCommitted(response)) {
      log.debug("IOException on committed response (client disconnect): {}", ex.getMessage());
      return null;
    }
    if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
      log.debug("IOException from disconnected client: {}", ex.getMessage());
      return null;
    }
    log.error("Unhandled IOException", ex);
    ErrorResponse body =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR_MESSAGE, null, request);
    // SSE 요청(Accept: text/event-stream)에서도 협상 실패 없이 JSON 이 나가도록 json() 이 형식을 명시한다.
    return json(HttpStatus.INTERNAL_SERVER_ERROR, body);
  }

  @ExceptionHandler(AsyncRequestTimeoutException.class)
  public void handleAsyncTimeout(
      AsyncRequestTimeoutException ex, HttpServletRequest request, HttpServletResponse response) {
    // SSE/streaming endpoints: response is already committed with text/event-stream,
    // so we cannot write a JSON ErrorResponse. Just complete silently.
    if (isCommitted(response)) {
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
   * (application/*+json 등)을 그대로 보이면 사용자에게 혼란스러워 대표 형식 하나만 고른다({@link #concreteTypes}).
   */
  private static String expectedRequestType(List<MediaType> supported) {
    return preferredType(
        concreteTypes(supported),
        MediaType.APPLICATION_JSON_VALUE,
        MediaType.MULTIPART_FORM_DATA_VALUE);
  }

  /**
   * 415·406 안내가 함께 쓰는 형식 정리(WD-626). 지원 목록을 사용자에게 보일 구체 형식("type/subtype")으로 줄인다 — {@code +json}
   * 계열(application/*+json, application/problem+json)은 application/json 으로 보고, 와일드카드({@code
   * *}/{@code *}, application/*)는 안내할 수 없으므로 건너뛴다. 순서는 원래 목록 순서를 따르고 중복은 없앤다.
   */
  private static Set<String> concreteTypes(List<MediaType> supported) {
    Set<String> types = new LinkedHashSet<>();
    for (MediaType type : supported) {
      if (type.isWildcardType()) {
        continue;
      }
      String subtype = type.getSubtype();
      if ("json".equals(subtype) || subtype.endsWith("+json")) {
        types.add(MediaType.APPLICATION_JSON_VALUE);
      } else if (!type.isWildcardSubtype()) {
        types.add(type.getType() + "/" + subtype);
      }
    }
    return types;
  }

  /** 정리된 형식 가운데 안내할 하나를 고른다 — 선호 순서대로 있는 것을, 없으면 첫 형식을, 비었으면 이 API 의 기본인 JSON 을 고른다(WD-626). */
  private static String preferredType(Set<String> types, String... preferences) {
    for (String preference : preferences) {
      if (types.contains(preference)) {
        return preference;
      }
    }
    return types.isEmpty() ? MediaType.APPLICATION_JSON_VALUE : types.iterator().next();
  }

  /**
   * 클라이언트 Accept 가 요구하는 응답 형식을 만들 수 없으면 406 한국어 JSON 으로 답한다(WD-548).
   *
   * <p>예전에는 Accept: text/html 이면 영어 Whitelabel HTML, application/xml·image/png 면 빈 본문 406 이 나갔다. 오류
   * 본문은 항상 JSON 이어야 화면·클라이언트가 같은 형식으로 읽을 수 있으므로 {@link #json} 으로 협상을 건너뛴다(협상을 다시 하면 같은 Accept 로 또
   * 실패한다). 클라이언트 잘못이고 요청 헤더 원문이 실리므로 DEBUG 로만 남긴다.
   *
   * <p>안내 문구는 엔드포인트가 실제로 만드는 형식에서 고른다({@link #notAcceptableMessage}, WD-626). 예전에는 SSE 전용
   * 엔드포인트(produces = text/event-stream)에도 「application/json 으로만 제공」이라 안내해 클라이언트가 틀린 Accept 로 고쳤다.
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(
      HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
    log.debug("Not acceptable media type on {}: {}", request.getRequestURI(), ex.getMessage());
    ErrorResponse response =
        buildError(
            HttpStatus.NOT_ACCEPTABLE,
            notAcceptableMessage(ex.getSupportedMediaTypes()),
            null,
            request);
    return json(HttpStatus.NOT_ACCEPTABLE, response);
  }

  /**
   * 406 안내 문구를 응답 가능 형식 목록에서 만든다(WD-626, WD-649). 형식 정리는 415 와 같은 {@link #concreteTypes} 를 쓴다.
   *
   * <ul>
   *   <li>형식이 여럿이면(produces 가 여러 개) 하나만 고르지 않고 전부 원래 순서대로 나열한다 — 예전에는 대표 하나만 「으로만 제공」이라 해 다른 지원 형식을
   *       빠뜨렸다(WD-649). 예: 「application/json, text/csv 또는 text/event-stream 으로 제공합니다」.
   *   <li>text/event-stream 하나뿐이면 SSE 로 받으라고 Accept 까지 안내한다.
   *   <li>하나뿐이면 그 형식, 목록이 비면(협상 이전 단계 실패 등) 이 API 의 기본인 JSON 으로 「으로만 제공」이라 안내한다.
   * </ul>
   *
   * 오류 본문 자체는 어느 경우든 JSON 이다.
   */
  private static String notAcceptableMessage(List<MediaType> supported) {
    // 모든 갈래가 같은 머리말로 시작한다.
    String prefix = "요청한 응답 형식을 지원하지 않습니다 — 응답은 ";
    List<String> types = List.copyOf(concreteTypes(supported));
    if (types.size() > 1) {
      String head = String.join(", ", types.subList(0, types.size() - 1));
      return prefix + head + " 또는 " + types.get(types.size() - 1) + " 으로 제공합니다.";
    }
    if (types.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
      return prefix + "text/event-stream(SSE) 으로 제공합니다. Accept 에 text/event-stream 을 넣으세요.";
    }
    String only = types.isEmpty() ? MediaType.APPLICATION_JSON_VALUE : types.get(0);
    return prefix + only + " 으로만 제공합니다.";
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
    return singleFieldBadRequest(
        ex.getName(), String.format("'%s'는 %s 타입이어야 합니다.", ex.getValue(), typeName), request);
  }

  /**
   * 필수 쿼리 파라미터가 빠진 요청은 클라이언트 입력 오류라 500 대신 400 으로 답한다(WD-405). 예전에는 전용 처리가 없어 일반 예외로 떨어져 500 「예기치
   * 않은 오류」가 났다(예: GET /api/v1/settings 에서 prefix 를 빼면 500).
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingRequestParameter(
      MissingServletRequestParameterException ex, HttpServletRequest request) {
    return singleFieldBadRequest(
        ex.getParameterName(), String.format("'%s' 값이 필요합니다.", ex.getParameterName()), request);
  }

  /**
   * 경로 변수에 공백만 온 요청(예: GET /api/v1/datasets/%20, /pipelines/1/executions/%20)은 클라이언트 입력 오류라 500 대신
   * 400 으로 답한다(WD-567). Long·UUID 변환 결과가 null 이 되어 Spring 이 이 예외를 던지는데, 전용 처리가 없어 일반 예외로 떨어져 500
   * 「예기치 않은 오류」가 났다 — 쿼리 파라미터 누락(WD-405)과 같은 모양이고, 같은 자리의 'abc' 가 400(#219)인 것과도 맞춘다.
   *
   * <p>단, 변환 전부터 변수가 없는 경우({@code missingAfterConversion=false})는 컨트롤러 매핑 템플릿과
   * {@code @PathVariable} 이 어긋난 코드 결함이라 400 으로 숨기지 않고 기존 일반 500 처리(오류 로그 포함)에 그대로 넘긴다. Spring 기본
   * 처리도 이 경우 500 이다.
   */
  @ExceptionHandler(MissingPathVariableException.class)
  public ResponseEntity<ErrorResponse> handleMissingPathVariable(
      MissingPathVariableException ex, HttpServletRequest request) {
    if (!ex.isMissingAfterConversion()) {
      return handleException(ex, request);
    }
    return singleFieldBadRequest(
        ex.getVariableName(), String.format("'%s' 값이 비어 있습니다.", ex.getVariableName()), request);
  }

  /**
   * 필수 요청 헤더({@code @RequestHeader} required)가 빠진 요청은 클라이언트 입력 오류라 500 대신 400 으로 답한다(WD-626). 예전에는
   * 전용 처리가 없어 일반 예외로 떨어져 500 「예기치 않은 오류」+ ERROR 스택이 남았다. 쿼리 파라미터 누락(WD-405)과 같은 모양으로 errors 에 헤더
   * 이름을 싣는다.
   *
   * <p>헤더는 왔지만 값이 비어 변환 결과가 없는 경우({@link MissingRequestHeaderException#isMissingAfterConversion()},
   * 예: 숫자 헤더에 빈 값)는 「필요합니다」가 틀린 안내라 「값이 올바르지 않습니다」로 답한다.
   */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ErrorResponse> handleMissingRequestHeader(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    String name = ex.getHeaderName();
    log.debug("Missing request header on {}: {}", request.getRequestURI(), name);
    String message =
        ex.isMissingAfterConversion()
            ? String.format("'%s' 헤더 값이 올바르지 않습니다.", name)
            : String.format("'%s' 헤더가 필요합니다.", name);
    return singleFieldBadRequest(name, message, request);
  }

  /**
   * 필수 multipart part(예: POST /api/v1/files 의 {@code files})가 빠진 요청은 클라이언트 입력 오류라 400 으로
   * 답한다(WD-521). 예전에는 전용 처리가 없어 일반 예외로 떨어져 500 「예기치 않은 오류」가 났다 — 쿼리 파라미터 누락(WD-405)과 같은 모양으로 맞춘다.
   */
  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ErrorResponse> handleMissingRequestPart(
      MissingServletRequestPartException ex, HttpServletRequest request) {
    return singleFieldBadRequest(
        ex.getRequestPartName(),
        String.format("'%s' 파일이 필요합니다.", ex.getRequestPartName()),
        request);
  }

  /**
   * 요청 값 하나가 잘못된 400 을 공통 모양(Validation failed + errors {이름: 사유})으로 만든다(WD-666). 쿼리 파라미터·경로
   * 변수·헤더·파트 누락과 타입 불일치 처리가 같은 세 줄을 각자 되풀이하던 것을 한 곳으로 모았다 — 모양이 한쪽만 바뀌지 않게 하려는 것이다.
   *
   * <p>키는 검증 오류와 같은 {@link #composeKey} 값 파라미터 규칙(클라이언트 이름)으로 만든다(WD-665) — 같은 파라미터가 누락·타입 불일치로 막히든
   * 제약 위반으로 막히든 같은 키가 되도록 키 규칙을 한 곳에 둔다.
   *
   * @param name 클라이언트가 보낸 이름(바인딩 애너테이션의 name, 없으면 자바 파라미터 이름)
   */
  private ResponseEntity<ErrorResponse> singleFieldBadRequest(
      String name, String message, HttpServletRequest request) {
    String key = composeKey(new ParamKind(false, name), "", "");
    ErrorResponse response =
        buildError(
            HttpStatus.BAD_REQUEST, VALIDATION_FAILED_MESSAGE, Map.of(key, message), request);
    return json(HttpStatus.BAD_REQUEST, response);
  }

  /**
   * multipart 본문을 해석하지 못한 요청(본문 없음·경계 없음 등)도 클라이언트 입력 오류라 400 으로 답한다(WD-521). 크기 초과 ({@link
   * MaxUploadSizeExceededException})는 더 구체적인 전용 처리가 먼저 잡는다.
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
    ErrorResponse response = buildError(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, null, request);
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
    ErrorResponse body =
        buildError(HttpStatus.SERVICE_UNAVAILABLE, POOL_EXHAUSTED_MESSAGE, null, request);
    return json(HttpStatus.SERVICE_UNAVAILABLE, body);
  }

  /**
   * 전용 처리가 없는 모든 예외의 마지막 처리.
   *
   * <p>두 갈래로 나뉜다(WD-626, WD-649).
   *
   * <ol>
   *   <li>Spring 표준 예외({@link org.springframework.web.ErrorResponse} — Spring MVC 표준 예외·{@link
   *       ResponseStatusException}·ErrorResponseException 이 구현)는 4xx 든 5xx 든 그 상태를 그대로 쓰는 한국어 JSON
   *       으로 답한다. 예외가 실은 응답 헤더(Allow·Retry-After·WWW-Authenticate 등, {@code getHeaders()})도 그대로
   *       싣는다. 예전에는 필수 쿠키 누락·ResponseStatusException(409) 등 클라이언트 잘못이 500 이 됐고(WD-626), 그 뒤에도
   *       503·501 같은 5xx 표준 예외는 500 으로 뭉개지고 Retry-After 가 사라졌다(WD-649). 로그 수준만 나눈다 — 4xx 는 클라이언트
   *       잘못이라 DEBUG, 5xx 는 서버 쪽 문제라 ERROR + 스택. 5xx 본문 문구는 예외 상세(내부 정보)를 싣지 않는 상태별 기본 한국어 문구다. 더
   *       구체적인 전용 처리(405·415·406 등)는 Spring 이 먼저 고르므로 가로채지 않는다.
   *   <li>그 밖의 예외는 지금처럼 500 「예기치 않은 오류」+ ERROR 로그다.
   * </ol>
   *
   * <p>응답이 이미 커밋됐으면(SSE·스트리밍이 흐르는 중) 본문은 {@link #json} 이 생략한다 — 로그는 위 규칙 그대로 남겨, 스트림 도중 난 서버 결함이
   * DEBUG 에 묻히지 않게 한다.
   *
   * <p>프로젝트에도 같은 이름의 {@link ErrorResponse} DTO 가 있어 Spring 쪽 타입은 완전한 이름으로 쓴다.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
    if (ex instanceof org.springframework.web.ErrorResponse standard) {
      HttpStatus status = toHttpStatus(standard.getStatusCode());
      if (status.is4xxClientError()) {
        // 클라이언트 잘못이라 ERROR 스택 없이 DEBUG 로만 남긴다(공개 엔드포인트로 오류 로그·알림을 채우지 못하게).
        log.debug(
            "Client error {} on {}: {}", status.value(), request.getRequestURI(), ex.getMessage());
      } else {
        log.error("Server error {} on {}", status.value(), request.getRequestURI(), ex);
      }
      ErrorResponse body = buildError(status, standardErrorMessage(status), null, request);
      return json(status, standard.getHeaders(), body);
    }
    log.error("Unhandled exception", ex);
    // 화면이 서버 message 를 그대로 토스트로 보여 주므로(handleApiError) 사용자 안내 문장으로 쓴다(WD-110).
    // 예전 영어 문구("An unexpected error occurred")가 실행 실패 등에서 그대로 노출됐다.
    ErrorResponse body =
        buildError(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR_MESSAGE, null, request);
    return json(HttpStatus.INTERNAL_SERVER_ERROR, body);
  }

  /**
   * 표준 예외의 상태 코드를 HttpStatus 로 바꾼다 — 표준에 없는 값은 계열로 본다: 4xx(예: 499)는 400, 그 밖(예: 599)은 500(WD-626,
   * WD-649). 4xx·5xx 가 아닌 값(1xx~3xx — 오류 처리로 올 일이 없다)도 오류 응답이 성공처럼 보이지 않게 500 으로 본다.
   */
  private static HttpStatus toHttpStatus(HttpStatusCode code) {
    HttpStatus status = HttpStatus.resolve(code.value());
    if (status != null && (status.is4xxClientError() || status.is5xxServerError())) {
      return status;
    }
    return code.is4xxClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
  }

  /** 503 안내 — 일시적으로 처리할 수 없는 상태라 재시도를 권한다(WD-649). */
  static final String SERVICE_UNAVAILABLE_MESSAGE = "서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도하세요.";

  /** 501 안내 — 서버가 아직 지원하지 않는 기능이다(WD-649). */
  static final String NOT_IMPLEMENTED_MESSAGE = "지원하지 않는 기능입니다.";

  /**
   * 표준 예외의 사용자 안내 문구 — 상태별 한국어 기본 문구다(WD-626, WD-649).
   *
   * <p>{@link ResponseStatusException} 의 reason 은 사용자에게 보이지 않는다(WD-649). 라이브러리·프레임워크가 던진 reason 은
   * 영어·내부 문구라 그대로 토스트에 나갔다. 우리 코드가 사용자 문구를 실어야 하면 ResponseStatusException 이 아니라 그 문구를 그대로 답하는 도메인
   * 예외(예: 상태 충돌은 IllegalStateException → 409)를 쓴다 — SavedQueryService 의 공유 쿼리 409 가 그렇게 바뀌었다. 5xx 도
   * 서버 쪽 상세(내부 정보)를 보이지 않고 상태별 기본 문구로만 답한다.
   */
  private static String standardErrorMessage(HttpStatus status) {
    return switch (status) {
      case BAD_REQUEST -> "요청이 올바르지 않습니다.";
      case UNAUTHORIZED -> "인증이 필요합니다.";
      case FORBIDDEN -> "이 작업을 할 권한이 없습니다.";
      case NOT_FOUND -> NOT_FOUND_MESSAGE;
      case CONFLICT -> "요청이 현재 상태와 충돌합니다.";
      case TOO_MANY_REQUESTS -> "요청이 너무 많습니다. 잠시 후 다시 시도하세요.";
      case SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE_MESSAGE;
      case NOT_IMPLEMENTED -> NOT_IMPLEMENTED_MESSAGE;
      default -> status.is5xxServerError() ? UNEXPECTED_ERROR_MESSAGE : "요청을 처리할 수 없습니다.";
    };
  }
}
