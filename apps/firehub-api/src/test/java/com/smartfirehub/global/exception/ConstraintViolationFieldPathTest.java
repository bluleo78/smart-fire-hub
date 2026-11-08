package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.dto.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
}
