package com.smartfirehub.pipeline.dto;

/**
 * 트리거 활성화·비활성화 요청 본문(WD-435).
 *
 * <p>{@code enabled} 는 사용자가 원하는 상태다. 서버가 읽은 현재 값을 뒤집으면 새로고침하지 않은 화면에서 누른 요청이 누른 것과 반대로 적용되므로, 화면이
 * 원하는 상태를 실어 보내고 서버는 그 값으로 설정한다(이미 그 상태면 무동작).
 */
public record ToggleTriggerRequest(Boolean enabled) {}
