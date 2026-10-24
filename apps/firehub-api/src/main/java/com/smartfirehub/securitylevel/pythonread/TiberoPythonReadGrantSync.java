package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.dataset.storage.tibero.TiberoCatalog;
import com.smartfirehub.dataset.storage.tibero.TiberoDdlSql;
import com.smartfirehub.dataset.storage.tibero.TiberoNames;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tibero 테넌트의 PYTHON 읽기 슬롯 계정({@code GENIA_T{id}_S1..S10})에 <b>테이블별 SELECT 권한</b>을 계산값에 맞춰
 * 동기화한다(WD-78, 스펙 §5.1). PG 판({@link PythonReadGrantSync})과 같은 구조 — 보안 등급 경계를 스크립트 코드가 아니라 DB 권한이
 * 지킨다.
 *
 * <p><b>계산값은 PG 와 같다.</b> 어느 슬롯이 어느 테이블을 읽는지는 {@link PythonReadSlots#grantSlotsFor}(위치 p 면 p..10,
 * 허용 목록 등급·10 초과 위치는 무권한)를 그대로 쓴다. 등급·데이터셋 메타는 PG 메타 DB 에 있으므로(RLS) 이 동기화도 PG 의 새 트랜잭션 안에서 돈다.
 *
 * <p><b>현재 권한.</b> OWNER 세션({@link ExternalStoreConnections#withDdl})에서 {@code USER_TAB_PRIVS_MADE}
 * 로 읽고 차이만 GRANT/REVOKE 한다. 데이터셋 행이 없는 테이블(스테이징 {@code STG_*}·뷰 등)의 계산값은 "없음"이라, 슬롯에 걸려 있으면 회수한다.
 * 슬롯 계정에는 처음부터 CREATE SESSION 만 있다({@code TiberoAccountPlan}).
 *
 * <p><b>직렬화.</b> 같은 테넌트의 동기화는 PG 메타의 트랜잭션 advisory 잠금({@link PythonReadGrantSync#lockTenantSync})으로
 * 직렬화한다 — PG 판과 같은 잠금이라 한 테넌트의 두 백엔드 동기화가 겹치지 않는다. Tibero 문장은 그 PG 트랜잭션이 끝날 때까지 잠금을 쥔 채 실행한다.
 *
 * <p><b>세션 종료는 하지 않는다(스펙 §5.1).</b> 스크립트는 슬롯 연결을 쥐지 않고 읽을 때마다 db-executor 가 새 문장을 실행하므로 REVOKE 가 다음
 * 조회부터 반영된다. OWNER 에게는 세션 종료 권한도 없다. 그래서 등급 정의 변경 동기화도 일반 전체 동기화와 같다.
 */
@Slf4j
@Service
public class TiberoPythonReadGrantSync implements PythonReadGrantBackend {

  private final DSLContext dsl;
  private final ExternalStoreConnections connections;
  private final TenantDataStoreRepository storeRepository;
  private final SecurityLevelRepository levelRepository;
  private final TransactionTemplate requiresNew;

  /**
   * txManager 는 @Primary TenantAwareTransactionManager — 트랜잭션 시작 때 TenantContext 를 RLS GUC 로 주입한다.
   */
  public TiberoPythonReadGrantSync(
      DSLContext dsl,
      ExternalStoreConnections connections,
      TenantDataStoreRepository storeRepository,
      SecurityLevelRepository levelRepository,
      PlatformTransactionManager txManager) {
    this.dsl = dsl;
    this.connections = connections;
    this.storeRepository = storeRepository;
    this.levelRepository = levelRepository;
    this.requiresNew = new TransactionTemplate(txManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 현재 테넌트 전체 재동기화. 실패는 예외(호출부가 로그/실패를 정한다). */
  @Override
  public PythonReadGrantSync.SyncResult syncTenant() {
    return requiresNew.execute(status -> syncInTx(null, null));
  }

  /**
   * 등급 정의 변경(순서 변경·삭제·허용 목록 해제) 뒤 전체 재동기화.
   *
   * <p>PG 는 새 GRANT 를 커밋하기 전에 슬롯 롤 세션을 끊어 실행 중인 스크립트가 넓어진 슬롯으로 자격 밖 등급을 읽지 못하게 한다. Tibero 는 세션을 끊을
   * 수 없으므로 (OWNER 에 권한이 없다, 스펙 §5.1) 대신 <b>스크립트 토큰 세대 번호</b>(Ruling 15)를 올린다: GRANT 를 하나라도 걸기
   * <b>전에</b> 번호를 올리고 그 변경을 별도 트랜잭션으로 <b>먼저 커밋</b>해 기술자를 읽는 쪽이 새 번호를 보게 한다. 스크립트 토큰(Task 13)이 옛 번호를
   * 싣고 있으면 db-executor 가 거부하므로 이미 실행 중이던 스크립트의 다음 읽기가 막힌다. 번호를 올리지 못하면 예외로 끝낸다 — 넓히는 GRANT 는 걸지
   * 않는다.
   */
  @Override
  public PythonReadGrantSync.SyncResult syncTenantForLevelsChange() {
    long tenantId = TenantContext.require("Tibero 등급 정의 변경 동기화");
    // 별도 트랜잭션: 반환 시점에 이미 커밋돼 있어야 한다(아래 GRANT 동기화와 같은 트랜잭션이면 커밋 순서가 보장되지 않는다).
    Optional<Long> epoch =
        requiresNew.execute(status -> storeRepository.incrementScriptTokenEpoch(tenantId));
    if (epoch == null || epoch.isEmpty()) {
      log.warn("Tibero 저장소 행이 없어 스크립트 토큰 세대 번호를 올리지 못했다(tenant={})", tenantId);
    } else {
      log.info("등급 정의 변경으로 스크립트 토큰 세대 번호를 올렸다: tenant={} epoch={}", tenantId, epoch.get());
    }
    return syncTenant();
  }

  /** 테이블 하나만 재동기화(생성·클론 직후, 데이터셋 등급 변경). 이름은 소문자 메타데이터 이름이다. */
  public void syncTable(String tableName) {
    requiresNew.execute(status -> syncInTx(tableName, null));
  }

  /** 데이터셋 하나 — 테이블 이름을 찾아 syncTable. 물리 테이블이 없는 데이터셋은 할 일이 없다. */
  @Override
  public void syncDataset(long datasetId) {
    PythonReadGrantBackend.syncDatasetTable(requiresNew, dsl, datasetId, this::syncTable);
  }

  /**
   * 쓰기 경로 훅 — 트랜잭션 동기화가 활성이면 커밋 후, 아니면 즉시 syncTable. PG 판과 같이 실패는 로그만 남긴다(이중 방어선이라 커밋을 막지 않는다 — 다음
   * 실행 직전 동기화·야간 동기화가 회복한다). 미커밋 PG 트랜잭션 안에서 바로 동기화하면 새 트랜잭션이 아직 보이지 않는 데이터셋 행을 못 읽어 "없음"으로 회수하므로
   * 반드시 커밋 뒤에 돈다. 공통부는 {@link PythonReadGrantBackend#scheduleTableSync}.
   */
  @Override
  public void syncTableAfterCommit(String tableName) {
    PythonReadGrantBackend.scheduleTableSync(tableName, log, "Tibero ", this::syncTable);
  }

  /**
   * PYTHON 실행 직전 준비(JIT) — PG 판과 같은 규칙({@link PythonReadRunGate})으로, 실행 슬롯 계정 하나의 SELECT 권한만 계산값에
   * 맞추고 슬롯 번호를 돌려준다. 어떤 실패든 {@link PythonReadAccessException}(fail-closed).
   *
   * @return 실행에 쓸 슬롯 번호(1~10)
   */
  @Override
  public int prepareForRun(Clearance runAs, int readRank) {
    return PythonReadRunGate.prepare(
        runAs,
        readRank,
        () -> requiresNew.execute(status -> syncInTx(null, readRank)),
        slot -> DataStoreAccountRole.forSlot(slot).usernameFor(runAs.tenantId()));
  }

  /** Tibero 쪽에서 한 번에 읽고 적용한 결과. */
  private record Applied(int changedTables, Map<String, Set<String>> revokeFailed) {}

  /**
   * 트랜잭션(=테넌트 GUC) 안에서 호출된다.
   *
   * @param onlyTable null 이면 테넌트 전체, 아니면 그 테이블만(소문자 메타데이터 이름)
   * @param runRank null 이면 슬롯 계정 전부, 아니면 그 자격 rank 의 실행 슬롯 계정 하나만(실행 준비)
   */
  private PythonReadGrantSync.SyncResult syncInTx(String onlyTable, Integer runRank) {
    long tenantId = TenantContext.require("Tibero PYTHON 읽기 권한 동기화");
    // 같은 테넌트 동기화 직렬화(PG 트랜잭션 종료 시 자동 해제) — PG 판과 같은 잠금.
    PythonReadGrantSync.lockTenantSync(dsl, tenantId);

    List<LevelPolicy> levelsAsc = levelRepository.findAll();
    // 슬롯 k(1~10)의 계정 이름과, 저장소 계정 행이 실제로 있는 슬롯(없으면 PG 의 "없는 슬롯 롤" 과 같이 취급).
    List<String> slotAccounts = new ArrayList<>();
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      slotAccounts.add(DataStoreAccountRole.forSlot(k).usernameFor(tenantId));
    }
    Set<Integer> availableSlots = new TreeSet<>();
    // 저장소가 READY 일 때만 계정 행을 본다 — PENDING 은 접속 자체가 불가하고 슬롯이 준비되지 않은 것과 같다.
    Optional<TenantDataStoreRow> row = storeRepository.find(tenantId);
    if (row.isPresent() && row.get().status() == TenantDataStoreStatus.READY) {
      Set<String> stored = new TreeSet<>();
      for (StoredAccount a : storeRepository.findAccounts(tenantId)) {
        stored.add(a.username());
      }
      for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
        if (stored.contains(slotAccounts.get(k - 1))) {
          availableSlots.add(k);
        }
      }
    }

    // 다룰 슬롯: 실행 준비면 실행 슬롯 하나(범위 밖이면 없음 — prepareForRun 이 거부), 아니면 전부.
    int runSlot = 0;
    Set<Integer> targetSlots = new TreeSet<>();
    if (runRank == null) {
      targetSlots.addAll(availableSlots);
    } else {
      runSlot = PythonReadSlots.slotFor(levelsAsc, runRank);
      if (availableSlots.contains(runSlot)) {
        targetSlots.add(runSlot);
      }
    }
    if (targetSlots.isEmpty()) {
      if (runRank == null) {
        log.warn("Tibero PYTHON 읽기 슬롯 계정이 없다 — 동기화 생략(tenant={})", tenantId);
      }
      return new PythonReadGrantSync.SyncResult(levelsAsc, availableSlots, 0, Map.of(), runSlot);
    }
    Set<String> targetAccounts = new TreeSet<>();
    for (int k : targetSlots) {
      targetAccounts.add(slotAccounts.get(k - 1));
    }

    // 계산값: 데이터셋 테이블(물리 이름) → SELECT 를 받아야 할 슬롯 계정 집합. 테이블 하나만 맞출 때는 그 데이터셋 행만 읽는다.
    // 임시 데이터셋(ptmp_*)도 PG 와 마찬가지로 데이터셋 행이 있으므로 자기 등급대로 계산된다. 스테이징(STG_*)은 데이터셋이 아니라 행이 없다 → "없음".
    Map<String, Set<String>> desired = new HashMap<>();
    for (var r :
        dsl.select(DATASET.TABLE_NAME, DATASET.SECURITY_LEVEL_ID)
            .from(DATASET)
            .where(
                onlyTable == null
                    ? DATASET.TABLE_NAME.isNotNull()
                    : DATASET.TABLE_NAME.eq(onlyTable))
            .fetch()) {
      Set<String> accounts = new TreeSet<>();
      for (int k : PythonReadSlots.grantSlotsFor(levelsAsc, r.value2())) {
        String account = slotAccounts.get(k - 1);
        if (targetAccounts.contains(account)) {
          accounts.add(account);
        }
      }
      desired.put(TiberoNames.physical(r.value1()), accounts);
    }

    String onlyPhysical = onlyTable == null ? null : TiberoNames.physical(onlyTable);
    Applied applied =
        connections.withDdl(
            tenantId,
            conn -> {
              String schema = connections.schemaOf(tenantId);
              Set<String> tables = TiberoCatalog.ownTables(conn, onlyPhysical);
              Map<String, Set<String>> actual =
                  slotGrants(conn, tenantId, onlyPhysical, targetAccounts, runRank != null);
              // 테이블이 아닌 객체(뷰 등)의 GRANT 도 훑는다 — 뷰는 소유자 권한으로 바닥 테이블을 읽어 등급 경계를 우회하므로 "없음" 으로 회수한다(PG
              // CR5).
              Set<String> names = new TreeSet<>(tables);
              names.addAll(actual.keySet());
              int changed = 0;
              Map<String, Set<String>> failed = new TreeMap<>();
              List<String> changedTables = new ArrayList<>();
              for (String table : names) {
                Set<String> want =
                    tables.contains(table) ? desired.getOrDefault(table, Set.of()) : Set.of();
                Set<String> have = actual.getOrDefault(table, Set.of());
                if (want.equals(have)) {
                  continue;
                }
                Set<String> excess = new TreeSet<>(have);
                excess.removeAll(want);
                Set<String> missing = new TreeSet<>(want);
                missing.removeAll(have);
                // REVOKE 는 남는 권한이 있을 때만 한다(없는 권한을 회수하면 오류). 실패하면 그 계정들에 과권한이 남았을 수 있다.
                boolean revokeOk = true;
                for (String account : excess) {
                  if (!execute(conn, TiberoDdlSql.revokeSelectFromSlot(schema, table, account))) {
                    revokeOk = false;
                    failed.computeIfAbsent(table, x -> new TreeSet<>()).add(account);
                  }
                }
                if (!revokeOk) {
                  continue;
                }
                for (String account : missing) {
                  if (!execute(conn, TiberoDdlSql.grantSelectToSlot(schema, table, account))) {
                    // 과소권한(안전) — 읽으면 거부된다. 다음 동기화가 재시도한다.
                    log.warn(
                        "Tibero PYTHON 읽기 GRANT 실패(과소권한, 다음 동기화에서 재시도): tenant={} table={}",
                        tenantId,
                        table);
                  }
                }
                changed++;
                changedTables.add(table);
              }
              // 재확인: 문장이 성공해도 실제 권한을 다시 읽어 "원하지 않는 슬롯에 SELECT 가 남은" 테이블을 회수 실패로 센다.
              if (!changedTables.isEmpty()) {
                Map<String, Set<String>> after =
                    slotGrants(conn, tenantId, onlyPhysical, targetAccounts, runRank != null);
                for (String table : changedTables) {
                  Set<String> want =
                      tables.contains(table) ? desired.getOrDefault(table, Set.of()) : Set.of();
                  for (String account : after.getOrDefault(table, Set.of())) {
                    if (!want.contains(account)) {
                      failed.computeIfAbsent(table, x -> new TreeSet<>()).add(account);
                    }
                  }
                }
              }
              return new Applied(changed, failed);
            });
    if (!applied.revokeFailed().isEmpty()) {
      // 회수 실패는 과권한이 남았을 수 있다는 뜻 — 운영자가 봐야 하므로 warn.
      log.warn(
          "Tibero PYTHON 읽기 권한 회수 실패: tenant={} 변경 테이블 {}개, 회수 실패 {}개 {}",
          tenantId,
          applied.changedTables(),
          applied.revokeFailed().size(),
          applied.revokeFailed());
    } else if (applied.changedTables() > 0) {
      log.info("Tibero PYTHON 읽기 권한 동기화: tenant={} 변경 테이블 {}개", tenantId, applied.changedTables());
    }
    return new PythonReadGrantSync.SyncResult(
        levelsAsc, availableSlots, applied.changedTables(), applied.revokeFailed(), runSlot);
  }

  /**
   * 대상 슬롯 계정에 걸린 SELECT 권한만 남긴 {테이블 → 계정 집합}.
   *
   * <p>실행 준비({@code exact}, 실행 슬롯 계정 하나)는 그 계정을 정확히 바인드해 읽는다 — PYTHON 실행마다 도는 경로라 테넌트 슬롯 전부를 LIKE 로
   * 읽고 버리지 않는다. 전체·테이블 동기화는 LIKE 로 테넌트 슬롯 계정 전부를 읽고, LIKE 가 넓게 잡은 이름({@code GENIA_T1_SXYZ} 등)과 대상이
   * 아닌 슬롯은 여기서 거른다. 두 방식의 결과는 같다.
   */
  private static Map<String, Set<String>> slotGrants(
      java.sql.Connection conn,
      long tenantId,
      String onlyPhysical,
      Set<String> targetAccounts,
      boolean exact)
      throws SQLException {
    if (exact) {
      return TiberoCatalog.slotSelectGrants(conn, targetAccounts, onlyPhysical);
    }
    Map<String, Set<String>> all = TiberoCatalog.slotSelectGrants(conn, tenantId, onlyPhysical);
    Map<String, Set<String>> filtered = new HashMap<>();
    all.forEach(
        (table, accounts) -> {
          Set<String> kept = new TreeSet<>(accounts);
          kept.retainAll(targetAccounts);
          if (!kept.isEmpty()) {
            filtered.put(table, kept);
          }
        });
    return filtered;
  }

  /** 한 문장을 실행한다. 실패는 false(다른 테이블 처리를 막지 않는다 — 한 테이블의 오류가 테넌트 전체 동기화를 중단시키면 안 된다). */
  private static boolean execute(java.sql.Connection conn, String sql) {
    try (Statement st = conn.createStatement()) {
      st.execute(sql);
      return true;
    } catch (SQLException e) {
      log.warn("Tibero PYTHON 읽기 권한 문장 실패: code={} {}", e.getErrorCode(), e.getMessage());
      return false;
    }
  }
}
