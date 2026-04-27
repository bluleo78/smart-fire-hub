package com.smartfirehub.apiconnection.dto;

import java.util.Map;

/**
 * 연결 테스트(헬스체크) 결과 DTO.
 *
 * <p>운영자가 외부 API 디버깅에 필요한 정보를 함께 노출하기 위해 응답 본문/헤더/요청 URL 등 디버깅 메타데이터를 포함한다.
 *
 * <ul>
 *   <li>{@code ok} — HTTP 2xx 응답 수신 여부.
 *   <li>{@code status} — HTTP 응답 코드 (연결 실패 시 null).
 *   <li>{@code latencyMs} — 요청 시작~응답 완료까지 소요 시간(ms).
 *   <li>{@code errorMessage} — 오류 메시지 (정상 시 null).
 *   <li>{@code requestUrl} — 실제 호출된 URL (쿼리 파라미터 합성 결과).
 *   <li>{@code responseBodyPreview} — 응답 본문 앞부분(최대 4KB). null/실패 시 null.
 *   <li>{@code responseHeaders} — 응답 헤더 맵. 민감 헤더(Set-Cookie, Authorization 등)는 마스킹.
 *   <li>{@code responseContentType} — 응답 Content-Type (JSON pretty 처리에 활용).
 * </ul>
 */
public record TestConnectionResponse(
    boolean ok,
    Integer status,
    Long latencyMs,
    String errorMessage,
    String requestUrl,
    String responseBodyPreview,
    Map<String, String> responseHeaders,
    String responseContentType) {}
