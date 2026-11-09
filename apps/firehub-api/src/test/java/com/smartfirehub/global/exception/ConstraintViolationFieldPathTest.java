package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.dto.ErrorResponse;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.MethodValidationAdapter;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartFile;
import teststub.validation.ValidationStubController.ShortCode;

/**
 * ConstraintViolationException(@Validated 메서드 검증)의 필드 키(WD-640) — 실제 Hibernate Validator 메서드 검증으로
 * 만든 위반 경로로 확인한다.
 *
 * <p>결함: 경로의 마지막 마디만 키로 써서 서로 다른 속성의 같은 이름(a.name·b.name)이 한 키로 합쳐져 문구 하나가 사라졌고, 목록 첨자도 잃었다.
 */
class ConstraintViolationFieldPathTest {

  private static ValidatorFactory factory;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  /** 이름 하나를 가진 하위 객체. */
  record Named(@NotBlank String name) {}

  /** 같은 이름(name)을 가진 하위 객체 둘과 목록. */
  record Request(@Valid Named a, @Valid Named b, List<@Valid Named> items) {}

  /** 검증 대상 메서드 — 값 파라미터(size)와 객체 파라미터(req). 파라미터 이름이 경로에 실리도록 -parameters 로 컴파일된다. */
  static class Target {
    public void call(@Min(1) int size, @Valid Request req) {}

    /** WD-654: 클래스 단위 제약이 붙은 객체 파라미터·중첩 객체, 값 맵 파라미터. */
    public void classLevel(@Valid Outer outer, Map<String, @NotBlank String> labels) {}

    /** WD-654 리뷰: 클래스 단위 제약이 붙은 원소의 목록 파라미터. */
    public void rows(@Valid List<Inner> rows) {}
  }

  /** 항상 실패하는 클래스 단위 제약(WD-654) — 위반 경로 끝이 BEAN 마디가 되는 경우를 만든다. */
  @java.lang.annotation.Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = AlwaysFailValidator.class)
  @interface AlwaysFail {
    String message() default "클래스 단위 제약 위반";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
  }

  /** {@link AlwaysFail} 검증기. */
  public static class AlwaysFailValidator implements ConstraintValidator<AlwaysFail, Object> {
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
      return value == null;
    }
  }

  /** 클래스 단위 제약이 붙은 하위 객체. */
  @AlwaysFail(message = "안쪽 객체 위반")
  record Inner(String v) {}

  /** 클래스 단위 제약 + 필드 제약 + 클래스 단위 제약을 가진 하위 객체. */
  @AlwaysFail(message = "바깥 객체 위반")
  record Outer(@NotBlank String title, @Valid Inner inner) {}

  private ErrorResponse handleViolations(Set<? extends ConstraintViolation<?>> violations) {
    return new GlobalExceptionHandler(null)
        .handleConstraintViolation(
            new ConstraintViolationException(violations), new MockHttpServletRequest("GET", "/x"))
        .getBody();
  }

  private ErrorResponse handleClassLevel(Outer outer, Map<String, String> labels) throws Exception {
    Method m = Target.class.getMethod("classLevel", Outer.class, Map.class);
    return handleViolations(
        factory
            .getValidator()
            .forExecutables()
            .validateParameters(new Target(), m, new Object[] {outer, labels}));
  }

  private ErrorResponse handle(Object... args) throws Exception {
    Method m = Target.class.getMethod("call", int.class, Request.class);
    Set<ConstraintViolation<Target>> violations =
        factory.getValidator().forExecutables().validateParameters(new Target(), m, args);
    return new GlobalExceptionHandler(null)
        .handleConstraintViolation(
            new ConstraintViolationException(violations), new MockHttpServletRequest("GET", "/x"))
        .getBody();
  }

  /** 같은 이름의 다른 속성이 따로 남고, 목록 첨자도 남는다. 값 파라미터는 파라미터 이름이 키다. 객체 파라미터 이름(req)은 붙이지 않는다. */
  @Test
  void keepsNestedPathAndIndices() throws Exception {
    Request req =
        new Request(new Named(""), new Named(""), List.of(new Named("ok"), new Named("")));
    ErrorResponse body = handle(0, req);
    assertThat(body.errors())
        .containsEntry("size", "1 이상이어야 합니다.")
        .containsEntry("a.name", "필수 입력값입니다.")
        .containsEntry("b.name", "필수 입력값입니다.")
        .containsEntry("items[1].name", "필수 입력값입니다.")
        .doesNotContainKey("name")
        .doesNotContainKey("req.a.name");
    assertThat(body.code()).isEqualTo(GlobalExceptionHandler.VALIDATION_FAILED_CODE);
  }

  /**
   * 객체 파라미터 자체의 클래스 단위 제약은 파라미터 이름(outer)이 아니라 _global, 중첩 객체의 클래스 단위 제약은 그 속성 경로(inner)다 —
   * BindException 경로(SpringValidatorAdapter)와 같은 규칙. 결함: 예전에는 「outer」 키로 나갔다.
   */
  @Test
  void classLevelOnParameterObject_goesToGlobal() throws Exception {
    ErrorResponse body = handleClassLevel(new Outer("t", new Inner("v")), Map.of());
    assertThat(body.errors())
        .containsEntry(ValidationErrorCollector.GLOBAL_KEY, "바깥 객체 위반")
        .containsEntry("inner", "안쪽 객체 위반")
        .doesNotContainKey("outer")
        .doesNotContainKey("");
  }

  /** 값 맵 파라미터의 키에 점이 있어도(「x.y」) 파라미터 이름이 붙는다 — 결함: 문자열에 「.」이 있으면 이름을 빼 「[x.y]」로 나갔다. */
  @Test
  void mapKeyWithDot_keepsParameterName() throws Exception {
    ErrorResponse body = handleClassLevel(null, Map.of("x.y", " "));
    assertThat(body.errors()).containsEntry("labels[x.y]", "필수 입력값입니다.").doesNotContainKey("[x.y]");
  }

  /** validator.validate(bean) 직접 호출의 클래스 단위 위반은 빈 문자열 키가 아니라 _global, 필드 위반은 필드 이름이다. */
  @Test
  void directBeanValidation_classLevelGoesToGlobal() {
    ErrorResponse body =
        handleViolations(factory.getValidator().validate(new Outer(" ", new Inner("v"))));
    assertThat(body.errors())
        .containsEntry("title", "필수 입력값입니다.")
        .containsEntry("inner", "안쪽 객체 위반")
        .containsEntry(ValidationErrorCollector.GLOBAL_KEY, "바깥 객체 위반")
        .doesNotContainKey("");
  }

  /**
   * 같은 객체의 위반이 ConstraintViolation 경로와 BindException 경로(SpringValidatorAdapter)에서 같은 errors 맵이 된다.
   */
  @Test
  void constraintViolationAndBindPaths_agree() throws Exception {
    Outer outer = new Outer(" ", new Inner("v"));
    ErrorResponse direct = handleViolations(factory.getValidator().validate(outer));

    BeanPropertyBindingResult binding = new BeanPropertyBindingResult(outer, "outer");
    new SpringValidatorAdapter(factory.getValidator()).validate(outer, binding);
    ErrorResponse bind =
        new GlobalExceptionHandler(null)
            .handleValidationExceptions(
                new BindException(binding), new MockHttpServletRequest("POST", "/x"))
            .getBody();

    assertThat(direct.errors()).isEqualTo(bind.errors());
    assertThat(bind.errors())
        .containsOnlyKeys("title", "inner", ValidationErrorCollector.GLOBAL_KEY);
  }

  /**
   * 목록 파라미터 원소의 클래스 단위 제약은 어느 원소인지 남는다(「[1]」) — 파라미터 객체 자체가 아니므로 _global 이 아니다(WD-654 리뷰). 결함: BEAN
   * 마디를 건너뛰며 첨자까지 버려 모든 원소의 위반이 _global 하나로 합쳐졌다.
   *
   * <p>WD-665: 키는 「rows[1]」이 아니라 「[1]」이다 — 객체 목록 원소는 필드 오류(「[1].name」)와 같은 접두 모양을 쓰고, 컨트롤러 경로(본문
   * 목록)와도 같다.
   */
  @Test
  void classLevelOnListParameterElement_keepsIndex() throws Exception {
    Method m = Target.class.getMethod("rows", List.class);
    ErrorResponse body =
        handleViolations(
            factory
                .getValidator()
                .forExecutables()
                .validateParameters(
                    new Target(), m, new Object[] {List.of(new Inner("a"), new Inner("b"))}));
    assertThat(body.errors())
        .containsEntry("[0]", "안쪽 객체 위반")
        .containsEntry("[1]", "안쪽 객체 위반")
        .doesNotContainKey("rows[0]")
        .doesNotContainKey(ValidationErrorCollector.GLOBAL_KEY);
  }

  // ---------------------------------------------------------------------------------------------
  // WD-665: 두 검증 층(컨트롤러 HandlerMethodValidation · @Validated 서비스 ConstraintViolation)의 키 일치
  // ---------------------------------------------------------------------------------------------

  /** 필드 제약과 클래스 단위 제약을 함께 가진 행 — 한 원소가 「[i].name」과 「[i]」를 함께 낸다. */
  @AlwaysFail(message = "행 위반")
  record Row(@NotBlank String name) {}

  /** 같은 위반을 두 층으로 돌릴 메서드들 — 파라미터 종류마다 하나. */
  static class Both {
    public void rows(@Valid List<Row> rows) {}

    public void object(@Valid Outer outer) {}

    public void value(@Min(1) int size) {}

    public void valueList(List<@Min(1) Integer> ids) {}

    public void valueSet(Set<@NotBlank String> tags) {}

    public void labels(Map<String, @NotBlank String> labels) {}

    public void file(@NotNull MultipartFile file) {}

    public void files(@NotEmpty List<MultipartFile> files) {}

    public void page(Optional<@Min(1) Integer> page) {}

    public void named(@RequestParam("page_size") @Min(1) int size) {}

    public void body(@RequestBody @NotEmpty List<Row> rows) {}

    public void composed(@ShortCode String code) {}
  }

  /** 메서드 이름 · 인자 · 기대 키 묶음. */
  static Stream<Arguments> bothLayersCases() {
    return Stream.of(
        // 객체 목록 원소: 필드 「[1].name」, 클래스 단위 「[0]」·「[1]」 — 파라미터 이름(rows) 접두 없음(WD-665 2·3·4).
        Arguments.of(
            "rows",
            new Object[] {List.of(new Row("ok"), new Row(""))},
            Set.of("[0]", "[1]", "[1].name")),
        // 객체: 필드·중첩 객체는 상대 경로, 객체 자체의 클래스 단위는 _global.
        Arguments.of(
            "object",
            new Object[] {new Outer(" ", new Inner("v"))},
            Set.of("title", "inner", ValidationErrorCollector.GLOBAL_KEY)),
        // 값 파라미터와 그 원소: 이름 + 위치.
        Arguments.of("value", new Object[] {0}, Set.of("size")),
        Arguments.of("valueList", new Object[] {List.of(3, 0)}, Set.of("ids[1]")),
        Arguments.of("labels", new Object[] {Map.of("x.y", " ")}, Set.of("labels[x.y]")),
        // Set 원소는 첨자·키가 없어 위치 없이 이름만 — 「tags[null]」이 새지 않는다(WD-665 리뷰).
        Arguments.of("valueSet", new Object[] {Set.of(" ")}, Set.of("tags")),
        // MultipartFile·그 목록·Optional<단순>은 값(WD-665 1) — _global 이 아니다.
        Arguments.of("file", new Object[] {null}, Set.of("file")),
        Arguments.of("files", new Object[] {null}, Set.of("files")),
        Arguments.of("page", new Object[] {Optional.of(0)}, Set.of("page")),
        // 바인딩 애너테이션에 적힌 이름이 키(두 층 모두).
        Arguments.of("named", new Object[] {0}, Set.of("page_size")),
        // 본문 자체의 값 위반은 _global.
        Arguments.of("body", new Object[] {List.of()}, Set.of(ValidationErrorCollector.GLOBAL_KEY)),
        // 합성(메타 애너테이션) 제약 위반도 값 파라미터 이름(WD-665 5).
        Arguments.of("composed", new Object[] {"abcdef"}, Set.of("code")));
  }

  /**
   * 같은 위반을 컨트롤러 경로(Spring MethodValidationAdapter → HandlerMethodValidationException)와 @Validated
   * 서비스 경로(ConstraintViolationException)로 돌리면 errors 맵이 똑같다(WD-665 3). 결함: 같은 목록 원소의 클래스 단위 위반이
   * 컨트롤러에선 「[0]」, 서비스에선 「rows[0]」이었고, 객체 파라미터 자체의 위반은 「_global」/「req」로 갈렸다.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("bothLayersCases")
  void sameViolation_sameKeyInBothLayers(String methodName, Object[] args, Set<String> keys) {
    Method m =
        Arrays.stream(Both.class.getMethods())
            .filter(x -> x.getName().equals(methodName))
            .findFirst()
            .orElseThrow();

    MethodValidationResult result =
        new MethodValidationAdapter(factory.getValidator())
            .validateArguments(new Both(), m, null, args, new Class<?>[0]);
    ErrorResponse controller =
        new GlobalExceptionHandler(null)
            .handleHandlerMethodValidation(
                new HandlerMethodValidationException(result),
                new MockHttpServletRequest("POST", "/x"))
            .getBody();

    ErrorResponse service =
        handleViolations(
            factory.getValidator().forExecutables().validateParameters(new Both(), m, args));

    assertThat(controller.errors()).as("컨트롤러 경로 키").containsOnlyKeys(keys);
    assertThat(service.errors()).as("서비스 경로 errors").isEqualTo(controller.errors());
  }

  /**
   * 목록 원소의 ParameterErrors 에 실린 클래스 단위(필드 없는) 오류는 원소 위치 「[4]」로 나간다 — _global 로 가서 어느 행인지 잃지
   * 않는다(WD-665 4). 필드 오류는 같은 접두 「[4].name」.
   */
  @Test
  void parameterErrorsGlobalErrorOnListElement_keepsIndex() throws Exception {
    Method m = Both.class.getMethod("rows", List.class);
    List<Row> rows = List.of(new Row("a"), new Row("b"), new Row("c"), new Row("d"), new Row(""));
    BeanPropertyBindingResult binding = new BeanPropertyBindingResult(rows.get(4), "row");
    binding.addError(new ObjectError("row", new String[] {"AlwaysFail"}, null, "행 위반"));
    binding.addError(
        new FieldError("row", "name", "", false, new String[] {"NotBlank"}, null, "필수 입력값입니다."));
    ParameterErrors errors =
        new ParameterErrors(new MethodParameter(m, 0), rows.get(4), binding, rows, 4, null);

    ErrorResponse body =
        new GlobalExceptionHandler(null)
            .handleHandlerMethodValidation(
                new HandlerMethodValidationException(
                    MethodValidationResult.create(new Both(), m, List.of(errors))),
                new MockHttpServletRequest("POST", "/x"))
            .getBody();

    assertThat(body.errors())
        .containsEntry("[4]", "행 위반")
        .containsEntry("[4].name", "필수 입력값입니다.")
        .doesNotContainKey(ValidationErrorCollector.GLOBAL_KEY);
  }
}
