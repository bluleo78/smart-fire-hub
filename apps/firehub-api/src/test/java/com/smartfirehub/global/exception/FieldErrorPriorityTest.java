package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 한 필드에 여러 검증 위반이 겹칠 때 응답 문구 선택 규칙(WD-570) — 순서를 직접 정해 결정성을 확인한다.
 *
 * <p>HTTP 왕복 테스트로는 Hibernate Validator 가 위반을 어떤 순서로 내는지 고를 수 없어서, 바인딩 결과를 직접 만들어 「필수값이 아닌 위반이 먼저
 * 들어온」 최악의 순서를 재현한다. 처리기는 Spring 컨텍스트 없이 만든다(이 경로는 DB 판정기를 쓰지 않는다).
 */
class FieldErrorPriorityTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler(null);

  /** Spring 이 만드는 FieldError 처럼 codes 마지막에 제약 단순 이름을 둔다(getCode() 가 이를 돌려준다). */
  private static FieldError error(String field, String constraint, String message) {
    return new FieldError(
        "req",
        field,
        "",
        false,
        new String[] {constraint + ".req." + field, constraint},
        null,
        message);
  }

  private ErrorResponse handle(FieldError... errors) throws Exception {
    BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "req");
    for (FieldError e : errors) {
      br.addError(e);
    }
    MethodParameter param = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
    return handler
        .handleValidationExceptions(new MethodArgumentNotValidException(param, br), request)
        .getBody();
  }

  /** Pattern 이 먼저 와도 NotBlank 문구가 이긴다. */
  @Test
  void requiredViolationWins_evenWhenEncounteredLater() throws Exception {
    ErrorResponse body =
        handle(error("slug", "Pattern", "형식이 올바르지 않습니다."), error("slug", "NotBlank", "필수 입력값입니다."));
    assertThat(body.errors()).containsEntry("slug", "필수 입력값입니다.");
  }

  /** NotBlank 가 먼저면 뒤의 Pattern 이 덮지 않는다. */
  @Test
  void requiredViolationKept_whenEncounteredFirst() throws Exception {
    ErrorResponse body =
        handle(error("slug", "NotBlank", "필수 입력값입니다."), error("slug", "Pattern", "형식이 올바르지 않습니다."));
    assertThat(body.errors()).containsEntry("slug", "필수 입력값입니다.");
  }

  /** 필수값 위반이 없으면 처음 만난 문구를 유지한다(뒤의 것이 덮지 않는다). */
  @Test
  void nonRequiredViolations_firstOneKept() throws Exception {
    ErrorResponse body =
        handle(
            error("name", "Size", "100자 이하로 입력하세요."), error("name", "Pattern", "형식이 올바르지 않습니다."));
    assertThat(body.errors()).containsEntry("name", "100자 이하로 입력하세요.");
  }
}
