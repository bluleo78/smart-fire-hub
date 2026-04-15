package com.smartfirehub.apiconnection.dto;

/**
 * 연결 테스트(헬스체크) 결과 DTO.
 * ok — HTTP 2xx 응답 수신 여부.
 * status — HTTP 응답 코드 (연결 실패 시 null).
 * latencyMs — 요청 시작~응답 완료까지 소요 시간(ms).
 * errorMessage — 오류 메시지 (정상 시 null).
 */
public record TestConnectionResponse(
    boolean ok,
    Integer status,
    Long latencyMs,
    String errorMessage) {}
