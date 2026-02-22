package com.smartfirehub.ai.exception;

public class AiSessionNotFoundException extends RuntimeException {
  public AiSessionNotFoundException(Long id) {
    super("AI 세션을 찾을 수 없습니다: " + id);
  }
}
