package com.smartfirehub.audit.repository.mybatis;

/**
 * 감사 로그 INSERT 파라미터 겸 생성 키 홀더(WD-706) — {@code useGeneratedKeys} 가 id 를 채운다.
 *
 * <p>tenant_id 는 담지 않는다 — 두 DB 모두 열 DEFAULT(PG GUC, Tibero 컨텍스트)가 채우고, 테넌트가 없으면 NULL(플랫폼 감사)이 된다.
 */
public class AuditLogInsert {

  private final Long userId;
  private final String username;
  private final String actionType;
  private final String resource;
  private final String resourceId;
  private final String description;
  private final String ipAddress;
  private final String userAgent;
  private final String result;
  private final String errorMessage;
  private final String metadataJson;

  private Long id;

  public AuditLogInsert(
      Long userId,
      String username,
      String actionType,
      String resource,
      String resourceId,
      String description,
      String ipAddress,
      String userAgent,
      String result,
      String errorMessage,
      String metadataJson) {
    this.userId = userId;
    this.username = username;
    this.actionType = actionType;
    this.resource = resource;
    this.resourceId = resourceId;
    this.description = description;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.result = result;
    this.errorMessage = errorMessage;
    this.metadataJson = metadataJson;
  }

  public Long getUserId() {
    return userId;
  }

  public String getUsername() {
    return username;
  }

  public String getActionType() {
    return actionType;
  }

  public String getResource() {
    return resource;
  }

  public String getResourceId() {
    return resourceId;
  }

  public String getDescription() {
    return description;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public String getResult() {
    return result;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getMetadataJson() {
    return metadataJson;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }
}
