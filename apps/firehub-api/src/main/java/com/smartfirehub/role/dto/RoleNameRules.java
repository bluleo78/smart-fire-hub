package com.smartfirehub.role.dto;

/**
 * 역할 이름 규칙 — 생성·수정 요청이 함께 쓴다.
 *
 * <p>WD-279: 수정 요청에만 규칙이 빠져 「ADMIN 」 같은 이름이 저장됐다. 두 DTO 가 다시 어긋나지 않도록 규칙을 한 곳에 둔다(애너테이션 값은 컴파일 시
 * 상수여야 해서 상수로 공유한다).
 */
public final class RoleNameRules {

  /** 대문자로 시작하고 대문자·숫자·밑줄만. 공백·소문자·한글을 막아 시스템 역할과 똑같아 보이는 이름을 만들 수 없게 한다. */
  public static final String PATTERN = "[A-Z][A-Z0-9_]*";

  public static final String PATTERN_MESSAGE = "역할 이름은 대문자, 숫자, 밑줄만 허용됩니다";

  public static final int MAX_LENGTH = 50;

  public static final String SIZE_MESSAGE = "역할 이름은 50자 이내여야 합니다";

  private RoleNameRules() {}
}
