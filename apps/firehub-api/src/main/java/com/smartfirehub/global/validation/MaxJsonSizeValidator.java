package com.smartfirehub.global.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link MaxJsonSize} 검증기(WD-425) — 값을 JSON 으로 직렬화한 UTF-8 바이트 수를 상한과 비교한다.
 *
 * <p>저장 경로가 같은 {@code ObjectMapper} 로 직렬화하므로, 앱의 {@code ObjectMapper} 를 주입받아 "실제로 저장될 크기"를 잰다.
 * Spring 이 검증기를 만들면({@code SpringConstraintValidatorFactory}) {@code @Autowired} 생성자로 주입되고, Spring
 * 밖의 기본 팩토리(단위 테스트 등)는 인자 없는 생성자로 만들어 기본 {@code ObjectMapper} 를 쓴다.
 */
public class MaxJsonSizeValidator implements ConstraintValidator<MaxJsonSize, Object> {

  /** Spring 밖에서 생성됐을 때 쓰는 기본 직렬화기. */
  private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

  private final ObjectMapper objectMapper;
  private long maxBytes;

  /** Spring 밖(기본 검증 팩토리)에서 생성될 때 쓰는 생성자. */
  public MaxJsonSizeValidator() {
    this(FALLBACK_MAPPER);
  }

  /** Spring 이 앱의 {@code ObjectMapper} 를 주입하는 생성자. */
  @Autowired
  public MaxJsonSizeValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void initialize(MaxJsonSize annotation) {
    this.maxBytes = annotation.maxBytes();
  }

  @Override
  public boolean isValid(Object value, ConstraintValidatorContext context) {
    // 필수 여부는 @NotNull 이 맡는다 — 생략(null)은 "변경 없음"인 수정 요청도 있어 통과시킨다.
    if (value == null) {
      return true;
    }
    try {
      return objectMapper.writeValueAsBytes(value).length <= maxBytes;
    } catch (JsonProcessingException e) {
      // 직렬화조차 안 되는 값은 어차피 저장할 수 없으므로 거부한다.
      return false;
    }
  }
}
