package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;

/** SavedQueryMapper 의 INSERT 홀더(WD-710). 공용 행 모양(id·이름, 수정 시각, 공유 수)은 {@link AnalyticsRows}. */
public final class SavedQueryRows {

  private SavedQueryRows() {}

  /**
   * 저장 쿼리 INSERT 파라미터 겸 생성 키 홀더. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성
   * 키가 {@code id} 에 써 넣는다.
   */
  public static class SavedQueryInsert extends GeneratedId {
    private final String name;
    private final String description;
    private final String sqlText;
    private final Long datasetId;
    private final String folder;
    private final boolean shared;
    private final Long createdBy;

    public SavedQueryInsert(
        String name,
        String description,
        String sqlText,
        Long datasetId,
        String folder,
        boolean shared,
        Long createdBy) {
      this.name = name;
      this.description = description;
      this.sqlText = sqlText;
      this.datasetId = datasetId;
      this.folder = folder;
      this.shared = shared;
      this.createdBy = createdBy;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public String getSqlText() {
      return sqlText;
    }

    public Long getDatasetId() {
      return datasetId;
    }

    public String getFolder() {
      return folder;
    }

    /** 공유 여부 — XML 의 boolLiteral 조각이 {@code shared} 로 읽는다. */
    public boolean isShared() {
      return shared;
    }

    public Long getCreatedBy() {
      return createdBy;
    }
  }
}
