package com.smartfirehub.dataset.repository.mybatis;

import java.time.LocalDateTime;

/**
 * dataset 매퍼(WD-707)가 읽는 행 모양들. 응답 DTO 로 바로 옮길 수 없는 행(조인 열을 펼친 행, 「행 없음」과 「값 NULL」을 가려야 하는 행)만 둔다 —
 * 생성자 매핑이라 MyBatis 가 열을 생성자 인자로 넣는다.
 */
public final class DatasetRows {

  private DatasetRows() {}

  /** 데이터셋 한 행 + 카테고리·상태 변경자 이름(LEFT JOIN). 태그·즐겨찾기·등급 요약은 리포지토리가 따로 읽어 응답에 붙인다(jOOQ 판과 같은 조립). */
  public record DatasetRow(
      Long id,
      String name,
      String tableName,
      String description,
      String storageType,
      String originType,
      LocalDateTime createdAt,
      String status,
      String statusNote,
      LocalDateTime statusUpdatedAt,
      Long sourcePipelineStepId,
      Long securityLevelId,
      LocalDateTime securityLevelAutoRaisedAt,
      Long categoryId,
      String categoryName,
      String categoryDescription,
      String statusUpdatedByName) {}

  /** 데이터셋 id 와 태그 이름 한 쌍. */
  public record TagRow(Long datasetId, String tagName) {}

  /**
   * 잠근 행의 id 와 수정 시각. id 를 함께 읽는 이유: 수정 시각 하나만 읽으면 「행 없음」과 「행은 있는데 수정 시각 NULL」이 둘 다 null 로 와서 가릴 수
   * 없다(jOOQ 판은 후자를 updatedAt=null 인 값으로 돌려준다).
   */
  public record LockedRow(Long id, LocalDateTime updatedAt) {}

  /** 스키마 메타 한 행 — 데이터셋 열 + LEFT JOIN 한 컬럼 열(컬럼 없는 데이터셋은 컬럼 열이 NULL). */
  public record SchemaRow(
      Long id,
      String name,
      String tableName,
      String storageType,
      String columnName,
      String dataType,
      String displayName) {}
}
