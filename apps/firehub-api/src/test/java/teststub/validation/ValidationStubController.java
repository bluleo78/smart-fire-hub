package teststub.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
  public static class SameValuesValidator implements ConstraintValidator<SameValues, PairLike> {
    @Override
    public boolean isValid(PairLike value, ConstraintValidatorContext context) {
      return value == null || Objects.equals(value.password(), value.confirm());
    }
  }

  /** 비밀번호·확인 두 칸을 가진 값 — {@link SameValues} 가 검사한다(WD-665 에서 여러 레코드가 함께 쓰도록 인터페이스로 뺐다). */
  public interface PairLike {
    String password();

    String confirm();
  }

  /** 합성 제약이 붙은 본문. */
  @SameValues
  public record PasswordPair(String password, String confirm) implements PairLike {}

  /** 필드 제약(name)과 클래스 단위 제약(두 칸 일치)을 함께 가진 원소(WD-665) — 같은 원소의 두 오류가 같은 접두 모양인지 본다. */
  @SameValues
  public record NamedPair(@NotBlank String name, String password, String confirm)
      implements PairLike {}

  /**
   * 합성 제약(WD-665) — 제약 애너테이션 위에 @NotBlank·@Size 를 메타 애너테이션으로 얹었다. 합성 제약의 위반도 값 파라미터 키(클라이언트 이름)로
   * 나와야 한다.
   */
  @Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = {})
  @NotBlank
  @Size(max = 3)
  public @interface ShortCode {
    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
  }

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

  /**
   * 본문 자체에 값 제약(@NotEmpty 목록)이 붙은 경우(WD-654) — Spring 6.2.1 의 visitResults 는 본문 결과를 ParameterErrors
   * 로 바꾸다 IllegalStateException(「Expected ParameterErrors」)을 던져 처리기가 깨졌다. 쿼리 파라미터 제약도 함께 두어 같은 응답에서
   * 파라미터 키(size)가 지켜지는지 본다.
   */
  @PostMapping("/not-empty-body")
  public void notEmptyBody(
      @Valid @RequestBody @NotEmpty List<NamedBody> requests,
      @RequestParam("size") @Min(1) int size) {}

  /** 본문 값 제약(@Size 목록)과 원소 오류가 한 요청에 함께 나는 경우(WD-654) — 원소 오류(ParameterErrors)도 함께 남는지 본다. */
  @PostMapping("/sized-body")
  public void sizedBody(@Valid @RequestBody @Size(min = 2) List<NamedBody> requests) {}

  /**
   * 폼 객체 자체의 값 위반(WD-654 리뷰) — Spring 이 항상 객체를 만들어 넣으므로 @Null 은 늘 어긴다. @ModelAttribute 를 적은 것과 안 적은
   * 것(Spring 이 단순 타입이 아니면 폼 객체로 본다)이 같은 키 모양(_global)이어야 한다.
   */
  @GetMapping("/model-value-annotated")
  public void modelValueAnnotated(@ModelAttribute @Null SearchForm form) {}

  /** 위와 같되 애너테이션 없는 폼 객체. */
  @GetMapping("/model-value-unannotated")
  public void modelValueUnannotated(@Null SearchForm form) {}

  /**
   * 애너테이션 없는 MultipartFile(WD-665 1) — Spring 은 요청 파라미터로 바인딩한다(없으면 null). 키는 파라미터 이름(file)이어야 하고 폼
   * 객체처럼 _global 로 가면 안 된다.
   */
  @PostMapping("/multipart-unannotated")
  public void multipartUnannotated(@NotNull MultipartFile file) {}

  /** 애너테이션 없는 MultipartFile 목록(WD-665 1) — 키는 files. */
  @PostMapping("/multipart-list-unannotated")
  public void multipartListUnannotated(@NotEmpty List<MultipartFile> files) {}

  /** 애너테이션 없는 Optional 단순 타입(WD-665 1) — Spring 은 요청 파라미터로 바인딩한다. 키는 page. */
  @GetMapping("/optional-param")
  public void optionalParam(Optional<@Min(1) Integer> page) {}

  /**
   * 목록 본문 원소의 필드 오류와 클래스 단위 오류(WD-665 2·4) — 한 원소의 두 오류가 「[1].name」·「[1]」로, 같은 접두 모양이어야
   * 한다(「pairs[1]」· _global 이 아니다).
   */
  @PostMapping("/pair-list")
  public void pairList(@Valid @RequestBody List<NamedPair> pairs) {}

  /** 합성(메타 애너테이션) 제약이 붙은 쿼리 파라미터(WD-665 5) — 키는 @RequestParam 이름(code). */
  @GetMapping("/composed")
  public void composed(@RequestParam("code") @ShortCode String value) {}
}
