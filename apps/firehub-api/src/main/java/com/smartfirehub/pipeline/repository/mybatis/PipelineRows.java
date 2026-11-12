package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import java.time.LocalDateTime;

/** PipelineMapper 의 평평한 행·INSERT 홀더 모음(WD-708). */
public final class PipelineRows {

  private PipelineRows() {}

  /**
   * 화면에 보일 사용자 이름 — 사용자 행이 없어 이름이 null 이면 사용자 id 문자열로 대신한다(jOOQ 판과 같다). 파이프라인 만든이·실행자 표시가 함께 쓴다.
   */
  static String displayName(String userName, Long userId) {
    return userName != null ? userName : String.valueOf(userId);
  }

  /** 목록·상세 한 줄 — 사용자 이름은 사용자가 없으면 null(구현이 만든이 id 문자열로 대신한다). 개수는 상관 서브쿼리 결과. */
  public record PipelineRow(
      Long id,
      String name,
      String description,
      boolean isActive,
      Long createdBy,
      LocalDateTime createdAt,
      int stepCount,
      int triggerCount,
      String userName) {}

  /**
   * 파이프라인 INSERT 파라미터 겸 생성 키 홀더. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(...)} 대신 JDBC 생성
   * 키가 {@code id} 에 써 넣고, 나머지 값은 구현이 다시 읽는다.
   */
  public static class PipelineInsert extends GeneratedId {
    private final String name;
    private final String description;
    private final Long createdBy;

    public PipelineInsert(String name, String description, Long createdBy) {
      this.name = name;
      this.description = description;
      this.createdBy = createdBy;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public Long getCreatedBy() {
      return createdBy;
    }
  }
}
