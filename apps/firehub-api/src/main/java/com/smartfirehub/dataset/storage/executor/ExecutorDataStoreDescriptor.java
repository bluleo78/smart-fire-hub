package com.smartfirehub.dataset.storage.executor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartfirehub.dataset.storage.DataStoreBackend;

/**
 * executor 가 테넌트의 데이터셋 저장소에 접속할 때 쓰는 기술자(WD-75).
 *
 * <p>PostgreSQL 테넌트는 {@code tenantId}·{@code backend} 만 채운다(접속 정보는 executor 가 기존 경로로 해결). null 필드는
 * JSON 에서 생략한다. 소유 계정 비밀번호는 평문이므로 {@link Account#toString()} 과 이 레코드의 {@code toString} 은 항상 마스킹한다 —
 * 로그·예외 메시지로 새지 않게 하기 위해서다. 슬롯 계정(S1..S10)은 WD-78 에서 필드를 덧붙인다.
 *
 * <p>{@code ro}(읽기 전용 계정, WD-76)는 호출자가 {@code ?include=ro} 로 요청했고 RO 행이 있을 때만 채운다 — null 이라 JSON 에서
 * 키 자체가 빠져, 요청하지 않는 Python 실행기가 받는 응답은 이전과 같다.
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
    Account ro) {

  /** 접속 계정. {@code password} 는 복호화된 평문이다. */
  public record Account(String username, String password) {

    /** 평문 비밀번호를 가린다. */
    @Override
    public String toString() {
      return "Account[username=" + username + ", password=****]";
    }
  }

  /** PostgreSQL(기본) 테넌트용 기술자 — 접속 정보 없이 백엔드만 알린다. */
  public static ExecutorDataStoreDescriptor postgres(long tenantId) {
    return new ExecutorDataStoreDescriptor(
        tenantId, DataStoreBackend.POSTGRESQL, null, null, null, null, null, null);
  }

  /** owner·ro 의 마스킹된 표현만 쓴다(비밀번호 비노출). */
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
        + "]";
  }
}
