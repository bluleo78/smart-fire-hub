package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;

/** DatasetAccessGrantMapper 의 평평한 행·INSERT 홀더 모음(WD-711). */
public final class DatasetAccessGrantRows {

  private DatasetAccessGrantRows() {}

  /** 한 데이터셋의 허용 대상 한 줄 — 사용자·역할 중 하나만 non-null. 리포지토리가 데이터셋별 대상 집합으로 묶는다. */
  public record SubjectRow(Long datasetId, Long userId, Long roleId) {}

  /**
   * 허용 항목 INSERT 파라미터 겸 생성 키 홀더. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성
   * 키가 {@code id} 에 써 넣는다.
   */
  public static class GrantInsert extends GeneratedId {
    private final long datasetId;
    private final Long userId;
    private final Long roleId;
    private final Long grantedBy;

    public GrantInsert(long datasetId, Long userId, Long roleId, Long grantedBy) {
      this.datasetId = datasetId;
      this.userId = userId;
      this.roleId = roleId;
      this.grantedBy = grantedBy;
    }

    public long getDatasetId() {
      return datasetId;
    }

    public Long getUserId() {
      return userId;
    }

    public Long getRoleId() {
      return roleId;
    }

    public Long getGrantedBy() {
      return grantedBy;
    }
  }
}
