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

  /**
   * 역할 설명 최대 길이(자). DB 의 {@code role.description VARCHAR(255)} 와 맞춘다. WD-865: 이 검사가 없으면 256자 이상 설명이
   * DB 제약까지 가서 실패했고, 그 오류 문구가 화면의 이름 칸에 붙어 보였다. DTO 에서 먼저 막아 설명 필드 오류로 답하려고 둔다.
   */
  public static final int DESCRIPTION_MAX_LENGTH = 255;

  /** 설명이 {@link #DESCRIPTION_MAX_LENGTH} 를 넘을 때 설명 필드에 내려가는 한국어 문구(웹 zod 문구와 같은 문장). */
  public static final String DESCRIPTION_SIZE_MESSAGE =
      "설명은 " + DESCRIPTION_MAX_LENGTH + "자 이내여야 합니다";

  private RoleNameRules() {}
}
