package com.smartfirehub.user.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link PasswordMaxBytes} 검증기(WD-605) — 판정은 {@link PasswordRules#withinMaxBytes} 한 곳에 둔다. */
public class PasswordMaxBytesValidator implements ConstraintValidator<PasswordMaxBytes, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return PasswordRules.withinMaxBytes(value);
  }
}
