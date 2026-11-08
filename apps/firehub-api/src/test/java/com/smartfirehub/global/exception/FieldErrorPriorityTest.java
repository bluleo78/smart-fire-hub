package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 한 필드에 여러 검증 위반이 겹칠 때 응답 문구 선택 규칙(WD-570, WD-640) — 순서를 직접 정해 결정성을 확인한다.
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

  /**
   * 필수값 위반이 없을 때도 들어온 순서와 상관없이 같은 문구를 고른다(WD-640) — 예전에는 「처음 만난 것」이라 위반 순서에 따라 응답이 바뀔 수 있었다. 규칙은 제약
   * 종류의 고정 순위(길이 Size 가 형식 Pattern 보다 앞)다.
   */
  @Test
  void nonRequiredViolations_sameResultRegardlessOfOrder() throws Exception {
    ErrorResponse sizeFirst =
        handle(
            error("name", "Size", "100자 이하로 입력하세요."), error("name", "Pattern", "형식이 올바르지 않습니다."));
    ErrorResponse patternFirst =
        handle(
            error("name", "Pattern", "형식이 올바르지 않습니다."), error("name", "Size", "100자 이하로 입력하세요."));
    assertThat(sizeFirst.errors()).containsEntry("name", "100자 이하로 입력하세요.");
    assertThat(patternFirst.errors()).containsEntry("name", "100자 이하로 입력하세요.");
  }

  /** 순위표에 없는 제약끼리는 제약 이름순으로 정한다 — 사용자 정의 제약이 겹쳐도 결정적이다. */
  @Test
  void unknownConstraints_tiebreakByCodeThenMessage() throws Exception {
    ErrorResponse a =
        handle(
            error("sections", "ReportSectionLimits", "섹션 초과"),
            error("sections", "MaxJsonSize", "JSON 큼"));
    ErrorResponse b =
        handle(
            error("sections", "MaxJsonSize", "JSON 큼"),
            error("sections", "ReportSectionLimits", "섹션 초과"));
    assertThat(a.errors()).containsEntry("sections", "JSON 큼");
    assertThat(b.errors()).containsEntry("sections", "JSON 큼");
  }

  /**
   * 필드 사이 순서는 고른 문구의 제약 순위(필수값 먼저) → 필드 이름 — 웹 토스트가 첫 값을 쓰므로, 데이터셋 생성에서 description 길이 오류가 name 필수값
   * 오류보다 먼저 보이면 안 된다.
   */
  @Test
  void errorsOrderedByRankThenFieldName() throws Exception {
    ErrorResponse body =
        handle(
            error("tableName", "NotBlank", "필수 입력값입니다."),
            error("description", "Size", "500자 이하로 입력하세요."),
            error("name", "NotBlank", "필수 입력값입니다."));
    assertThat(body.errors().keySet()).containsExactly("name", "tableName", "description");
  }

  /** 목록 첨자는 수 크기로 정렬한다 — rows[2] 가 rows[10] 앞(사전순이면 뒤집힌다). */
  @Test
  void containerIndicesSortedNumerically() throws Exception {
    ErrorResponse body =
        handle(
            error("rows[10].name", "NotBlank", "필수 입력값입니다."),
            error("rows[2].name", "NotBlank", "필수 입력값입니다."),
            error("rows[1].name", "NotBlank", "필수 입력값입니다."));
    assertThat(body.errors().keySet())
        .containsExactly("rows[1].name", "rows[2].name", "rows[10].name");
  }

  /** 필드 없는 오류(_global)는 필드 오류 뒤 맨 끝이다 — 「_」가 영문자보다 앞이라도 토스트 첫 값을 빼앗지 않는다. */
  @Test
  void globalErrorsComeLast() throws Exception {
    BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "req");
    br.addError(new ObjectError("req", new String[] {"SameValues"}, null, "두 값이 같아야 합니다."));
    br.addError(error("name", "Size", "100자 이하로 입력하세요."));
    MethodParameter param = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
    ErrorResponse body =
        handler
            .handleValidationExceptions(
                new MethodArgumentNotValidException(param, br),
                new MockHttpServletRequest("POST", "/api/v1/test"))
            .getBody();
    assertThat(body.errors().keySet()).containsExactly("name", ValidationErrorCollector.GLOBAL_KEY);
    assertThat(body.code()).isEqualTo(GlobalExceptionHandler.VALIDATION_FAILED_CODE);
  }

  /** 바인딩 타입 변환 실패(typeMismatch)는 Spring 영어 기본 문구 대신 한국어로 바꾼다. */
  @Test
  void typeMismatch_translatedToKorean() throws Exception {
    ErrorResponse body =
        handle(
            error(
                "age",
                "typeMismatch",
                "Failed to convert property value of type 'java.lang.String'"));
    assertThat(body.errors()).containsEntry("age", ValidationErrorCollector.TYPE_MISMATCH_MESSAGE);
  }
}
