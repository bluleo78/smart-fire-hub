package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;

/**
 * 파이프라인 수정 응답 — 새로 저장된 수정 시각(WD-353).
 *
 * <p>웹 편집기가 다음 저장의 기준 버전({@code expectedUpdatedAt})으로 쓴다. 상세 재조회를 기다리면 그 사이 다시 고쳐 저장할 때 자기 저장과
 * 충돌한다.
 */
public record UpdatePipelineResponse(LocalDateTime updatedAt) {}
