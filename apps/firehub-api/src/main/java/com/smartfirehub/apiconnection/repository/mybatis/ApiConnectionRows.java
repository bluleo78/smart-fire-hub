package com.smartfirehub.apiconnection.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;

/** ApiConnectionMapper 의 평평한 행·INSERT 홀더 모음(WD-708). */
public final class ApiConnectionRows {

  private ApiConnectionRows() {}

  /** 헬스체크 갱신 직전 행 — 행이 있었는지(id)와 직전 상태(없을 수 있음)를 함께 돌려주려고 한 줄로 묶는다. */
  public record PreviousStatus(Long id, String lastStatus) {}

  /**
   * API 연결 INSERT 파라미터 겸 생성 키 홀더. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성
   * 키가 {@code id} 에 써 넣는다. 인증 설정은 암호문이라 자르지 않는다(사용자 입력 열).
   */
  public static class ApiConnectionInsert extends GeneratedId {
    private final String name;
    private final String description;
    private final String authType;
    private final String authConfig;
    private final Long createdBy;
    private final String baseUrl;
    private final String healthCheckPath;

    public ApiConnectionInsert(
        String name,
        String description,
        String authType,
        String authConfig,
        Long createdBy,
        String baseUrl,
        String healthCheckPath) {
      this.name = name;
      this.description = description;
      this.authType = authType;
      this.authConfig = authConfig;
      this.createdBy = createdBy;
      this.baseUrl = baseUrl;
      this.healthCheckPath = healthCheckPath;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public String getAuthType() {
      return authType;
    }

    public String getAuthConfig() {
      return authConfig;
    }

    public Long getCreatedBy() {
      return createdBy;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public String getHealthCheckPath() {
      return healthCheckPath;
    }
  }
}
