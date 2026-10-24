package com.smartfirehub.dataset.storage.executor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import java.util.List;

/**
 * executor 가 테넌트의 데이터셋 저장소에 접속할 때 쓰는 기술자(WD-75).
 *
 * <p>PostgreSQL 테넌트는 {@code tenantId}·{@code backend} 만 채운다(접속 정보는 executor 가 기존 경로로 해결). null 필드는
 * JSON 에서 생략한다. 소유 계정 비밀번호는 평문이므로 {@link Account#toString()} 과 이 레코드의 {@code toString} 은 항상 마스킹한다 —
 * 로그·예외 메시지로 새지 않게 하기 위해서다.
 *
 * <p>{@code ro}(읽기 전용 계정, WD-76)는 호출자가 {@code ?include=ro} 로 요청했고 RO 행이 있을 때만 채운다 — null 이라 JSON 에서
 * 키 자체가 빠져, 요청하지 않는 Python 실행기가 받는 응답은 이전과 같다.
 *
 * <p>{@code slots}(WD-78)는 Python 스크립트 읽기 슬롯 계정 S1..S10 이다. db-executor 토큰으로 {@code ?include=slots}
 * 를 요청했을 때만 복호화해 슬롯 번호 순으로 싣는다 — db-executor 의 슬롯 스트리밍 조회가 이 계정으로 접속한다. Python 실행기 토큰 경로(종류만)에는 절대
 * 싣지 않는다. 요청하지 않으면 null 이라 JSON 키가 빠진다.
 *
 * <p>{@code scriptTokenEpoch}(WD-78)는 전체 기술자(db-executor 경로)에만 싣는 테넌트별 스크립트 토큰 세대 번호다. 스크립트 토큰(Task
 * 13)이 발급 시점의 번호를 싣고 db-executor 가 이 값보다 낮은 토큰을 거부한다 — 여기서는 값만 전달하며 토큰은 만들지 않는다. 백엔드만 담는 기술자·PG
 * 기술자는 null 이라 JSON 에서 빠진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutorDataStoreDescriptor(
    long tenantId,
    DataStoreBackend backend,
    String host,
    Integer port,
    String database,
    String schema,
    Account owner,
    Account ro,
    List<SlotAccount> slots,
    Long scriptTokenEpoch) {

  /** 접속 계정. {@code password} 는 복호화된 평문이다. */
  public record Account(String username, String password) {

    /** 평문 비밀번호를 가린다. */
    @Override
    public String toString() {
      return "Account[username=" + username + ", password=****]";
    }
  }

  /** 읽기 슬롯 계정 하나(WD-78). {@code password} 는 복호화된 평문이다. */
  public record SlotAccount(int slot, String username, String password) {

    /** 평문 비밀번호를 가린다. */
    @Override
    public String toString() {
      return "SlotAccount[slot=" + slot + ", username=" + username + ", password=****]";
    }
  }

  /** PostgreSQL(기본) 테넌트용 기술자 — 접속 정보 없이 백엔드만 알린다. */
  public static ExecutorDataStoreDescriptor postgres(long tenantId) {
    return new ExecutorDataStoreDescriptor(
        tenantId, DataStoreBackend.POSTGRESQL, null, null, null, null, null, null, null, null);
  }

  /** owner·ro·slots 의 마스킹된 표현만 쓴다(비밀번호 비노출). */
  @Override
  public String toString() {
    return "ExecutorDataStoreDescriptor[tenantId="
        + tenantId
        + ", backend="
        + backend
        + ", host="
        + host
        + ", port="
        + port
        + ", database="
        + database
        + ", schema="
        + schema
        + ", owner="
        + owner
        + ", ro="
        + ro
        + ", slots="
        + slots
        + ", scriptTokenEpoch="
        + scriptTokenEpoch
        + "]";
  }
}
