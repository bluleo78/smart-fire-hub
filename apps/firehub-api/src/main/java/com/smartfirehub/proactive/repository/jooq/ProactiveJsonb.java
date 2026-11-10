package com.smartfirehub.proactive.repository.jooq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.JSONB;

/**
 * 스마트 작업 jOOQ 저장소들이 JSONB 열(작업 config·양식 sections·메시지 content·실행 result)에 쓸 값을 직렬화하는 도우미.
 *
 * <p><b>왜 따로 두는가(WD-609).</b> 예전에는 각 쓰기 메서드가 직렬화와 DB 문 실행을 한 {@code try { … } catch (Exception)} 으로
 * 감싸 "Failed to serialize …" RuntimeException 으로 바꿨다. 그 catch 가 DB 예외까지 삼켜, NUL(U+0000) 같은 데이터
 * 오류(SQLState 22xxx)로 난 {@code DataIntegrityViolationException} 이 전역 처리기의 400 변환({@code
 * DataExceptionTranslator})을 못 타고 500 이 됐다. 직렬화만 여기서 감싸고 DB 문은 try 밖에 두어, DB 예외는 다른 API 와 같은 공통 변환을
 * 그대로 타게 한다.
 */
final class ProactiveJsonb {

  private ProactiveJsonb() {}

  /**
   * 값을 JSON 으로 직렬화해 JSONB 로 돌려준다. 직렬화 실패({@link JsonProcessingException})만 "Failed to serialize
   * {what}" 비검사 예외로 바꾼다 — 호출부가 DB 문을 try 로 감쌀 필요가 없게 하려는 것이다.
   *
   * @param what 오류 메시지에 넣을 대상 이름(config·sections·content·result)
   */
  static JSONB toJsonb(ObjectMapper objectMapper, Object value, String what) {
    try {
      return JSONB.valueOf(objectMapper.writeValueAsString(value));
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize " + what, e);
    }
  }
}
