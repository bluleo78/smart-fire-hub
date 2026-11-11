package com.smartfirehub.audit.repository.mybatis;

import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.time.LocalDateTime;

/**
 * 감사 로그 목록 조회 파라미터(WD-706) — 매퍼 문장 하나가 건수·목록 두 번에 같은 값을 쓰도록 필터·가시성·페이지를 한 객체로 묶는다.
 *
 * <p>무엇을: 세 조회(findByResource·findAll·findPlatform)가 쓰는 필드를 모두 갖고, 쓰지 않는 필드는 null 로 둔다. 공백 정리·LIKE
 * 패턴 이스케이프는 구현이 미리 끝내 넣는다 — XML 은 null 여부만 본다.
 *
 * <p>왜 한 객체인가: 가시성 조각({@code DatasetVisibility.visible})은 {@code vis.*}, 공용 페이지 조각은 {@code
 * limit}·{@code offset} 이름을 읽는다. 매퍼 인자를 @Param 으로 늘어놓으면 문장마다 이름을 맞춰야 해서, 이름이 고정된 한 객체로 넘긴다.
 */
public class AuditLogQuery {

  /** 리소스 종류 정확 일치(findByResource 는 필수, findAll 은 선택). */
  private String resource;

  /** 액션 유형 정확 일치. */
  private String actionType;

  /** 리소스 id 정확 일치(findByResource). */
  private String resourceId;

  /** 사용자명·설명 부분일치 LIKE 패턴(이스케이프 끝난 값, findAll). */
  private String searchPattern;

  /** 사용자 id 정확 일치(findAll). */
  private Long userId;

  /** 결과(SUCCESS/FAILURE) 정확 일치(findAll). */
  private String result;

  /** 행위자 username 부분일치 LIKE 패턴(findPlatform). */
  private String actorPattern;

  /** 대상 — resource_id 정확 일치용 원값(findPlatform). */
  private String target;

  /** 대상 — metadata 부분일치 LIKE 패턴(findPlatform). */
  private String targetPattern;

  /** 시각 하한(포함). */
  private LocalDateTime fromInclusive;

  /** 시각 상한(포함, findAll). */
  private LocalDateTime toInclusive;

  /** 시각 상한(미포함, findPlatform). */
  private LocalDateTime toExclusive;

  /** 데이터셋 가시성 조각 파라미터. null 이면 데이터셋 필터를 붙이지 않는다(ADMIN 경로). */
  private VisibilityParams vis;

  /** 페이지 크기. */
  private int limit;

  /** 건너뛸 행 수. */
  private long offset;

  public String getResource() {
    return resource;
  }

  public AuditLogQuery resource(String v) {
    this.resource = v;
    return this;
  }

  public String getActionType() {
    return actionType;
  }

  public AuditLogQuery actionType(String v) {
    this.actionType = v;
    return this;
  }

  public String getResourceId() {
    return resourceId;
  }

  public AuditLogQuery resourceId(String v) {
    this.resourceId = v;
    return this;
  }

  public String getSearchPattern() {
    return searchPattern;
  }

  public AuditLogQuery searchPattern(String v) {
    this.searchPattern = v;
    return this;
  }

  public Long getUserId() {
    return userId;
  }

  public AuditLogQuery userId(Long v) {
    this.userId = v;
    return this;
  }

  public String getResult() {
    return result;
  }

  public AuditLogQuery result(String v) {
    this.result = v;
    return this;
  }

  public String getActorPattern() {
    return actorPattern;
  }

  public AuditLogQuery actorPattern(String v) {
    this.actorPattern = v;
    return this;
  }

  public String getTarget() {
    return target;
  }

  public String getTargetPattern() {
    return targetPattern;
  }

  /** 대상 원값과 그 부분일치 패턴을 함께 넣는다(둘은 늘 짝이다). */
  public AuditLogQuery target(String raw, String pattern) {
    this.target = raw;
    this.targetPattern = pattern;
    return this;
  }

  public LocalDateTime getFromInclusive() {
    return fromInclusive;
  }

  public AuditLogQuery fromInclusive(LocalDateTime v) {
    this.fromInclusive = v;
    return this;
  }

  public LocalDateTime getToInclusive() {
    return toInclusive;
  }

  public AuditLogQuery toInclusive(LocalDateTime v) {
    this.toInclusive = v;
    return this;
  }

  public LocalDateTime getToExclusive() {
    return toExclusive;
  }

  public AuditLogQuery toExclusive(LocalDateTime v) {
    this.toExclusive = v;
    return this;
  }

  public VisibilityParams getVis() {
    return vis;
  }

  public AuditLogQuery vis(VisibilityParams v) {
    this.vis = v;
    return this;
  }

  public int getLimit() {
    return limit;
  }

  public long getOffset() {
    return offset;
  }

  /** 페이지 번호·크기를 limit·offset 으로 바꿔 넣는다. */
  public AuditLogQuery page(int page, int size) {
    this.limit = size;
    this.offset = (long) page * size;
    return this;
  }
}
