package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 테넌트별 외부 저장소 설정({@code tenant_data_store}, {@code tenant_data_store_account}) 접근(WD-73).
 *
 * <p>두 테이블은 테넌트 경계 위의 전역 테이블(V138, RLS 없음)이라 테넌트 컨텍스트 없이도 읽고 쓸 수 있다. 코드젠 대상이 아니므로 {@code
 * PlatformTenantRepository} 처럼 이름 기반 jOOQ 를 쓴다. enum 은 {@code name()} 문자열로 저장한다.
 *
 * <p>트랜잭션 경계는 호출 서비스가 잡는다(테넌트 생성과 같은 트랜잭션에서 insert 해야 한다).
 *
 * <p>구현은 {@code JooqTenantDataStoreRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface TenantDataStoreRepository {

  /** 저장소 설정과 계정 목록을 함께 저장한다. 호출자 트랜잭션 안에서 원자적으로 실행돼야 한다. */
  void insert(TenantDataStoreRow row, List<StoredAccount> accounts);

  /** 테넌트의 저장소 백엔드. 행이 없으면 empty(= 기본 PostgreSQL) — 리졸버가 캐시해서 쓴다. */
  Optional<DataStoreBackend> findBackend(long tenantId);

  /** 테넌트의 저장소 설정 전체. 없으면 empty. */
  Optional<TenantDataStoreRow> find(long tenantId);

  /**
   * 지정한 백엔드의 READY 저장소를 가진 <b>ACTIVE</b> 테넌트 id(오름차순). 배경 작업(고아 스테이징 정리 등)이 외부 저장소 테넌트만 순회할 때 쓴다 —
   * PENDING 저장소는 풀을 만들 수 없으므로 제외한다.
   *
   * <p>정지된 테넌트도 뺀다(WD-449). 플랫폼에는 테넌트 삭제가 없어 정지가 끝 상태이고, 정지 뒤 외부 DB 계정을 지우는 일이 흔하다 — 저장소 행은 READY 로
   * 남으므로 이 조건이 없으면 배경 작업이 30분마다 지워진 계정으로 접속하다 실패하고 외부 DB 에 로그인 실패를 쌓는다. PG 스윕({@code
   * forEachActiveTenant})도 ACTIVE 만 돈다.
   */
  List<Long> findReadyTenantIds(DataStoreBackend backend);

  /** 테넌트의 계정 목록. 역할 선언 순서(OWNER, RO, S1..S10)로 돌려준다 — 문자열 정렬이면 S10 이 S2 앞에 온다. */
  List<StoredAccount> findAccounts(long tenantId);

  /** 상태를 바꾸고 {@code updated_at} 을 갱신한다. */
  void updateStatus(long tenantId, TenantDataStoreStatus status);

  /**
   * 저장소 행을 {@code SELECT … FOR UPDATE} 로 잠근다. 호출자 트랜잭션이 끝날 때까지 같은 테넌트의 검증 전환과 비밀번호 재발급이 서로를 기다리게 해,
   * 재발급 직전 비밀번호로 확인한 결과가 READY 로 확정되는 경합을 막는다. 행이 없으면 아무것도 잠그지 않는다.
   */
  void lockForUpdate(long tenantId);

  /**
   * 계정의 암호화된 비밀번호만 교체한다(username 은 유지). 비밀번호 재발급용.
   *
   * @throws IllegalStateException 어떤 역할의 계정 행이 없어 갱신되지 않음 — 일부만 바뀐 채 새 스크립트를 내주면 안 되므로 트랜잭션을 롤백시킨다
   */
  void replacePasswords(long tenantId, List<StoredAccount> accounts);

  /**
   * 테넌트의 스크립트 토큰 세대 번호(V147). 행이 없으면 empty.
   *
   * <p>스크립트 토큰(Task 13)이 발급 시점의 이 값을 싣고, db-executor 는 기술자의 값보다 낮은 토큰을 거부한다. 이 저장소는 값을 읽고 올리기만 한다.
   */
  Optional<Long> findScriptTokenEpoch(long tenantId);

  /**
   * 스크립트 토큰 세대 번호를 1 올리고 새 값을 돌려준다(행이 없으면 empty). 호출자는 이 변경이 <b>커밋된 뒤에</b> 넓히는 GRANT 를 걸어야 한다 — 그래야
   * 다른 읽는 쪽(기술자 조회)이 새 번호를 먼저 본다.
   */
  Optional<Long> incrementScriptTokenEpoch(long tenantId);

  /**
   * {@link #incrementScriptTokenEpoch(long)} 와 같되, 그 행의 잠금을 최대 {@code lockWait}(초 단위로 올림, 최소 1초)만
   * 기다린다(WD-664 4b). 넘기면 예외 — 문장만 취소되고(PG 는 57014, 트랜잭션은 aborted) 부르는 쪽이 savepoint 로 되돌린다.
   *
   * <p>커밋 직전 경로(사용자 트랜잭션의 커넥션을 쥔 채 부른다) 전용이다. 다른 트랜잭션이 같은 행을 잡고 있으면 상한 없이 기다려 등급 변경 커밋과 요청 스레드가 함께
   * 멈추기 때문이다.
   */
  Optional<Long> incrementScriptTokenEpoch(long tenantId, Duration lockWait);
}
