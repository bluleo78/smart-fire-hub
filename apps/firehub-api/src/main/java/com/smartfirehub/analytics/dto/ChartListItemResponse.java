package com.smartfirehub.analytics.dto;

import java.time.LocalDateTime;

/**
 * 차트 목록 한 줄(WD-733). {@link ChartResponse} 에서 {@code config} 를 뺀 모양이다.
 *
 * <p>목록 화면(웹 ChartListPage·차트 추가 다이얼로그)은 config 를 쓰지 않는데 config 는 JSON 열이라 목록 한 쪽(최대 100행)마다 읽고
 * 직렬화하는 비용만 든다. 그래서 목록 전용 응답을 따로 두고 리포지토리 목록 문장도 config 를 읽지 않는다. config 는 단건 조회({@link
 * ChartResponse})에서만 내려간다.
 *
 * @param configWithheld 조회자가 저장 쿼리의 데이터를 볼 수 없어 단건 응답이라면 config 를 가렸을 차트인가(WD-31②). 목록에는 config 가
 *     없어 표시용 플래그로만 남는다.
 */
public record ChartListItemResponse(
    Long id,
    String name,
    String description,
    Long savedQueryId,
    String savedQueryName,
    String chartType,
    boolean isShared,
    String createdByName,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long dashboardCount) {}
