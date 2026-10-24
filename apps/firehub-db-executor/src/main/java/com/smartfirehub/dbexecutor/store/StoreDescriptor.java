package com.smartfirehub.dbexecutor.store;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * api 가 알려주는 테넌트 저장소 기술자(JSON 모양은 api {@code ExecutorDataStoreDescriptor} 와 같다). 비밀번호가 로그로 새지 않도록
 * {@code toString} 은 항상 마스킹한다.
 *
 * <p>{@code ro} 는 분석 SQL 용 읽기 전용 계정이다. 기술자를 {@code ?include=ro} 로 요청했을 때만 오고, 아직 만들어지지 않았으면 null 이라
 * {@link #isComplete()} 는 owner 만 요구한다(파이프라인 경로는 RO 없이도 동작해야 한다).
 *
 * <p>{@code slots}(WD-78)는 Python 스크립트 읽기 슬롯 계정 S1..S10 이다. {@code ?include=slots} 로 요청했을 때만 오고, 슬롯
 * 조회 경로({@code SlotQueryRunner})만 쓴다. {@code scriptTokenEpoch} 는 테넌트별 스크립트 토큰 세대 번호로, 이보다 낮은 세대의
 * 스크립트 토큰은 거부한다(Ruling 16 — 등급 변경 뒤 실행 중인 스크립트의 옛 토큰을 끊는다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoreDescriptor(
    long tenantId,
    String backend,
    String host,
    Integer port,
    String database,
    String schema,
    Account owner,
    Account ro,
    List<SlotAccount> slots,
    Long scriptTokenEpoch) {

  /** JSON 역직렬화는 이 정식 생성자를 쓴다(보조 생성자가 있어 명시한다). */
  @JsonCreator
  public StoreDescriptor {
    slots = slots == null ? null : List.copyOf(slots);
  }

  /** 슬롯·세대 없이 만드는 편의 생성자(OWNER·RO 만 쓰는 경로와 테스트용). */
  public StoreDescriptor(
      long tenantId,
      String backend,
      String host,
      Integer port,
      String database,
      String schema,
      Account owner,
      Account ro) {
    this(tenantId, backend, host, port, database, schema, owner, ro, null, null);
  }

  /** 접속 계정. {@code password} 는 평문이다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Account(String username, String password) {

    /** 평문 비밀번호를 가린다. */
    @Override
    public String toString() {
      return "Account[username=" + username + ", password=****]";
    }
  }

  /** 읽기 슬롯 계정 하나. {@code password} 는 평문이다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SlotAccount(int slot, String username, String password) {

    /** 평문 비밀번호를 가린다. */
    @Override
    public String toString() {
      return "SlotAccount[slot=" + slot + ", username=" + username + ", password=****]";
    }
  }

  /** 슬롯 k 의 접속 계정. 기술자에 없거나 이름·비밀번호가 비었으면 null(호출자가 「슬롯 계정이 준비되지 않았다」로 거부한다). */
  public Account slot(int k) {
    if (slots == null) {
      return null;
    }
    for (SlotAccount s : slots) {
      if (s != null && s.slot() == k && notBlank(s.username()) && notBlank(s.password())) {
        return new Account(s.username(), s.password());
      }
    }
    return null;
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
