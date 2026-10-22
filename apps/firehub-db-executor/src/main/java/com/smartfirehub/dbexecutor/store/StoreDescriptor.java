package com.smartfirehub.dbexecutor.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * api 가 알려주는 테넌트 저장소 기술자(JSON 모양은 api {@code ExecutorDataStoreDescriptor} 와 같다). 비밀번호가 로그로 새지 않도록
 * {@code toString} 은 항상 마스킹한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoreDescriptor(
    long tenantId,
    String backend,
    String host,
    Integer port,
    String database,
    String schema,
    Account owner) {

  /** 접속 계정. {@code password} 는 평문이다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Account(String username, String password) {

    /** 평문 비밀번호를 가린다. */
    @Override
    public String toString() {
      return "Account[username=" + username + ", password=****]";
    }
  }

  /** 접속·검증에 필요한 값(host·port·database·schema·계정)이 모두 있는지. 없으면 응답이 불완전한 것이다. */
  boolean isComplete() {
    return notBlank(host)
        && port != null
        && notBlank(database)
        && notBlank(schema)
        && owner != null
        && notBlank(owner.username())
        && notBlank(owner.password());
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }
}
