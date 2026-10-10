package com.smartfirehub.analytics.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 차트 응답.
 *
 * @param configWithheld 조회자가 저장 쿼리의 데이터를 볼 수 없어 config(원본 컬럼명)를 뺐다(WD-31②). true 면 config 는 null —
 *     저장 시 config 를 보내지 않으면 기존 값이 유지된다.
 */
public record ChartResponse(
    Long id,
    String name,
    String description,
    Long savedQueryId,
    String savedQueryName,
    String chartType,
    Map<String, Object> config,
    boolean isShared,
    String createdByName,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long dashboardCount,
    boolean configWithheld) {

  /** config 를 가린 복사본. null 인 이유: 클라이언트가 GET 결과를 그대로 PUT 해도 리포지토리 계약상 "유지"가 되게(빈 맵이면 덮어써 지워진다). */
  public ChartResponse withConfigWithheld() {
    return new ChartResponse(
        id,
        name,
        description,
        savedQueryId,
        savedQueryName,
        chartType,
        null,
        isShared,
        createdByName,
        createdBy,
        createdAt,
        updatedAt,
        dashboardCount,
        true);
  }
}
