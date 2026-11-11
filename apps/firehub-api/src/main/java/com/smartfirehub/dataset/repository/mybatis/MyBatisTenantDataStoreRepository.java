package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * TenantDataStoreRepository 의 MyBatis 구현(WD-707) — {@code JooqTenantDataStoreRepository} 와 같은 의미.
 *
 * <p>두 테이블은 정책(RLS)이 없는 전역 테이블이라 DB 가 테넌트를 걸러 주지 않는다. 그래서 모든 문장이 tenant id 인자를 WHERE 술어로 직접 건다 — 한
 * 테넌트의 id 로 부르면 그 테넌트의 행만 읽고 바꾼다. jOOQ 구현처럼 {@code @Transactional} 을 달지 않는다(호출 서비스 트랜잭션에 참여하며, 세대
 * 번호 올리기는 UPDATE 뒤 같은 트랜잭션에서 읽으므로 호출자 트랜잭션 안에서 불러야 한다 — 호출자들은 모두 그렇다).
 */
@PlatformMyBatisRepository
public class MyBatisTenantDataStoreRepository implements TenantDataStoreRepository {

  private final TenantDataStoreMapper mapper;

  public MyBatisTenantDataStoreRepository(TenantDataStoreMapper mapper) {
    this.mapper = mapper;
  }

  /** 저장소 설정과 계정 목록을 함께 저장한다. 호출자 트랜잭션 안에서 원자적으로 실행돼야 한다. */
  @Override
  public void insert(TenantDataStoreRow row, List<StoredAccount> accounts) {
    mapper.insertStore(
        row.tenantId(),
        row.backend().name(),
        row.host(),
        row.port(),
        row.database(),
        row.schemaName(),
        row.tablespace(),
        row.mode().name(),
        row.status().name());
    for (StoredAccount account : accounts) {
      mapper.insertAccount(
          row.tenantId(), account.role().name(), account.username(), account.encryptedPassword());
    }
  }

  /** 테넌트의 저장소 백엔드. 행이 없으면 empty(= 기본 PostgreSQL). */
  @Override
  public Optional<DataStoreBackend> findBackend(long tenantId) {
    return Optional.ofNullable(mapper.selectBackend(tenantId)).map(DataStoreBackend::valueOf);
  }

  /** 테넌트의 저장소 설정 전체. 없으면 empty. */
  @Override
  public Optional<TenantDataStoreRow> find(long tenantId) {
    return Optional.ofNullable(mapper.selectStore(tenantId))
        .map(
            r ->
                new TenantDataStoreRow(
                    r.tenantId(),
                    DataStoreBackend.valueOf(r.backend()),
                    r.host(),
                    r.port(),
                    r.databaseName(),
                    r.schemaName(),
                    r.tablespace(),
                    DataStoreProvisionMode.valueOf(r.provisionMode()),
                    TenantDataStoreStatus.valueOf(r.status())));
  }

  /** 지정한 백엔드의 READY 저장소를 가진 ACTIVE 테넌트 id(오름차순). 정지된 테넌트는 뺀다(WD-449). */
  @Override
  public List<Long> findReadyTenantIds(DataStoreBackend backend) {
    return mapper.selectReadyTenantIds(backend.name(), TenantDataStoreStatus.READY.name());
  }

  /** 테넌트의 계정 목록을 역할 선언 순서(OWNER, RO, S1..S10)로 — 문자열 정렬이면 S10 이 S2 앞에 온다. */
  @Override
  public List<StoredAccount> findAccounts(long tenantId) {
    return mapper.selectAccounts(tenantId).stream()
        .map(
            r ->
                new StoredAccount(
                    DataStoreAccountRole.valueOf(r.accountRole()), r.username(), r.passwordEnc()))
        .sorted(Comparator.comparing(StoredAccount::role))
        .toList();
  }

  /** 상태를 바꾸고 updated_at 을 앱 시계로 갱신한다(jOOQ 판과 같음). */
  @Override
  public void updateStatus(long tenantId, TenantDataStoreStatus status) {
    mapper.updateStatus(tenantId, status.name(), LocalDateTime.now());
  }

  /** 저장소 행을 SELECT … FOR UPDATE 로 잠근다. 행이 없으면 아무것도 잠그지 않는다. */
  @Override
  public void lockForUpdate(long tenantId) {
    mapper.lockStore(tenantId);
  }

  /**
   * 계정의 암호화된 비밀번호만 교체한다.
   *
   * @throws IllegalStateException 어떤 역할의 계정 행이 없어 갱신되지 않음
   */
  @Override
  public void replacePasswords(long tenantId, List<StoredAccount> accounts) {
    for (StoredAccount account : accounts) {
      int updated =
          mapper.updatePassword(tenantId, account.role().name(), account.encryptedPassword());
      if (updated == 0) {
        throw new IllegalStateException(
            "저장소 계정 행이 없어 비밀번호를 교체하지 못했습니다: tenant=" + tenantId + ", role=" + account.role());
      }
    }
  }

  /** 테넌트의 스크립트 토큰 세대 번호(V147). 행이 없으면 empty. */
  @Override
  public Optional<Long> findScriptTokenEpoch(long tenantId) {
    return Optional.ofNullable(mapper.selectScriptTokenEpoch(tenantId));
  }

  /**
   * 세대 번호를 1 올리고 새 값을 돌려준다(행이 없으면 empty). Tibero 에는 UPDATE … RETURNING 이 없어 UPDATE 뒤 같은 트랜잭션에서 읽는다
   * — UPDATE 가 행 잠금을 쥐고 있어 읽은 값이 내가 올린 값이다.
   */
  @Override
  public Optional<Long> incrementScriptTokenEpoch(long tenantId) {
    int updated = mapper.incrementScriptTokenEpoch(tenantId, LocalDateTime.now(), null);
    return updated == 0 ? Optional.empty() : findScriptTokenEpoch(tenantId);
  }

  /**
   * 행 잠금 대기에 상한을 둔 세대 번호 올리기(WD-664 4b) — 커밋 직전 경로 전용. 문장 시간 제한(JDBC {@code setQueryTimeout}, 초)을 이
   * UPDATE 하나에만 건다. 초 단위로 올림하고 최소 1초다(0 은 「제한 없음」) — jOOQ 판과 같은 계산.
   */
  @Override
  public Optional<Long> incrementScriptTokenEpoch(long tenantId, Duration lockWait) {
    int seconds = (int) Math.max(1L, (lockWait.toMillis() + 999L) / 1000L);
    int updated = mapper.incrementScriptTokenEpoch(tenantId, LocalDateTime.now(), seconds);
    return updated == 0 ? Optional.empty() : findScriptTokenEpoch(tenantId);
  }
}
