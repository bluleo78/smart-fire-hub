package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tibero 서버 오류(스펙 §4.2·§4.3-5). api 는 {@code code} 로 흔한 오류를 분류해 한국어 안내로 번역한다(§5.3).
 *
 * @param code {@code SQLException.getErrorCode()} — Tibero 의 부호 있는 자체 코드(예: -10007)
 * @param sqlState {@code SQLException.getSQLState()} — 없으면 null(Python 실행기 db_error 와 같은 이름)
 * @param message 드라이버 메시지(접속 정보·비밀번호는 담지 않는다)
 */
public record DbError(int code, @JsonProperty("sql_state") String sqlState, String message) {}
