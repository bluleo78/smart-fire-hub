package com.smartfirehub.settings.dto;

/**
 * {@code POST /settings/ai-credential/probe} 요청 바디.
 *
 * <p>필드명이 {@code baseURL}(대문자 URL)인 이유: 이 프로젝트는 Jackson 프로퍼티 네이밍 전략을
 * 커스터마이징하지 않으므로 필드명이 JSON 키와 <b>대소문자까지 그대로</b> 일치해야 한다 —
 * 설계서·화면(Task 9)이 쓰는 키가 {@code baseURL}이라 여기서 {@code baseUrl}로 적으면 바인딩되지
 * 않고 항상 {@code null}이 된다.
 *
 * <p>{@code apiKey} 는 의도적으로 nullable 이다 — 생략/공백은 "저장된 값 재사용"을 뜻하고, 그
 * 재사용 판정(현재 테넌트 행만, 유형·baseURL 일치 검사 포함)은 이 DTO 가 아니라 {@code OpencodeProbeService}/컨트롤러가
 * 한다.
 */
public record OpencodeProbeRequest(String baseURL, String apiKey) {}
