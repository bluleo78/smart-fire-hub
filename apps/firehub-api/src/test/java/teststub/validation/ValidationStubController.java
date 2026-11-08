package teststub.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 검증 실패 응답 모양(WD-640) 확인용 스텁 컨트롤러 — 운영 코드에는 아직 없는 검증 경로(메서드 파라미터 제약, @ModelAttribute, 클래스 단위 합성 제약,
 * 목록 본문)를 일부러 만들어, 나중에 누가 붙여도 400 + 한국어 문구 + 필드 오류로 나오는지 고정한다.
 *
 * <p>패키지를 com.smartfirehub 밖(teststub)에 두는 이유: @SpringBootApplication 의 컴포넌트 스캔 범위 밖이라 전체
 * 컨텍스트(@SpringBootTest)에는 등록되지 않고, 이 스텁을 쓰는 WebMvc 슬라이스 테스트(ValidationErrorShapeTest)만 @Import 로
 * 올린다.
 */
@RestController
@RequestMapping("/test/validation")
public class ValidationStubController {

  /** 클래스 단위 합성 제약 예 — 두 칸이 같아야 한다(global error 로 보고된다). */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = SameValuesValidator.class)
  public @interface SameValues {
    String message() default "비밀번호 확인이 일치하지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
  }

  /** {@link SameValues} 검증기. */
  public static class SameValuesValidator implements ConstraintValidator<SameValues, PasswordPair> {
    @Override
    public boolean isValid(PasswordPair value, ConstraintValidatorContext context) {
      return value == null || Objects.equals(value.password(), value.confirm());
    }
  }

  /** 합성 제약이 붙은 본문. */
  @SameValues
  public record PasswordPair(String password, String confirm) {}

  /** 필드 제약 본문. */
  public record NamedBody(@NotBlank String name) {}

  /**
   * @ModelAttribute 폼 — 필수 이름과 숫자 나이.
   */
  public static class SearchForm {
    @NotBlank private String name;
    private Integer age;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Integer getAge() {
      return age;
    }

    public void setAge(Integer age) {
      this.age = age;
    }
  }

  /** 쿼리 파라미터 제약 — 클래스에 @Validated 가 없어도 Spring 6.1+ 가 검증한다(HandlerMethodValidationException). */
  @GetMapping("/param")
  public void param(@RequestParam("size") @Min(1) int pageSize) {}

  /** 경로 변수 제약. */
  @GetMapping("/path/{code}")
  public void path(@PathVariable("code") @Size(max = 3) String code) {}

  /** 본문(@Valid) + 쿼리 파라미터 제약 — 본문 오류도 HandlerMethodValidationException 의 bean 결과로 온다. */
  @PostMapping("/body-and-param")
  public void bodyAndParam(
      @Valid @RequestBody NamedBody body, @RequestParam("size") @Min(1) int size) {}

  /**
   * @ModelAttribute 바인딩·검증.
   */
  @GetMapping("/model")
  public void model(@Valid @ModelAttribute SearchForm form) {}

  /** 클래스 단위 합성 제약. */
  @PostMapping("/class-level")
  public void classLevel(@Valid @RequestBody PasswordPair body) {}

  /** 목록 본문 — 원소 오류 키는 자바 파라미터 이름 없이 「[1].name」이어야 한다(클라이언트는 JSON 배열을 보냈다). */
  @PostMapping("/list-body")
  public void listBody(@Valid @RequestBody List<NamedBody> requests) {}

  /** 값 목록 파라미터 원소 제약 — 키는 「ids[1]」. */
  @GetMapping("/list-param")
  public void listParam(@RequestParam("ids") List<@Min(1) Integer> ids) {}

  /** 쿠키 값 제약 — 키는 쿠키 이름(sid). */
  @GetMapping("/cookie")
  public void cookie(@CookieValue("sid") @Size(max = 3) String session) {}
}
