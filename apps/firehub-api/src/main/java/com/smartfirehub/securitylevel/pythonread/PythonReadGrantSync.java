package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PYTHON 읽기 슬롯 롤의 테이블 SELECT GRANT 를 계산값에 맞추는 <b>단일 진입점</b>(스펙 §4.3).
 *
 * <p><b>왜 런타임 롤(app_tenant)인가.</b> 데이터셋 테이블 소유자가 app_tenant 라(DataTableService 생성, V86 이관)
 * GRANT/REVOKE 를 할 수 있다. 스키마 USAGE 등 소유자 전용 권한은 프로비저너 몫이다(Task 2).
 *
 * <p><b>왜 차이만 적용하는가.</b> PYTHON 실행 직전마다 부르므로(JIT — 이벤트 유실·미연결에도 과권한 창이 없게) 변경 없는 경우의 비용이 카탈로그 조회 몇
 * 번이어야 한다. 실제 ACL 은 {@code aclexplode(pg_class.relacl)} 로 읽는다. 또 REVOKE 는 <b>남는 권한이 있을 때만</b> 한다 —
 * 부족한 GRANT 만 있는 테이블에 REVOKE 를 던지면, 런타임 롤이 소유하지 않은(권한도 없는) 옛 테이블에서 오류가 나 "회수 실패"로 오판되고 그 테넌트의
 * PYTHON 전체가 거부된다.
 *
 * <p><b>왜 새 트랜잭션인가.</b> security_level·dataset 은 RLS 테이블이라 트랜잭션(=GUC) 없이 읽으면 0행이고, 0행이면 "전부 회수"가
 * 된다. 호출부(비트랜잭션 러너·afterCommit 콜백)와 무관하게 항상 자기 트랜잭션을 연다. 같은 테넌트의 동기화는 advisory lock 으로 직렬화한다(동시
 * GRANT 의 XX000 "tuple concurrently updated" 방지 — TenantPipelineRoleProvisioner 주석의 같은 함정).
 */
@Slf4j
@Service
public class PythonReadGrantSync {

  /**
   * 동기화 결과.
   *
   * @param levelsAsc 동기화에 쓴 등급 스냅샷(rank 오름차순) — prepareForRun 이 같은 스냅샷으로 슬롯을 계산해 계산 불일치 창을 없앤다
   * @param availableSlots 실제로 존재하는 슬롯 롤 번호(실행 준비 동기화면 실행 슬롯만 본다)
   * @param changedTables 권한을 바꾼 테이블 수
   * @param revokeFailed 남는 권한을 회수하지 못한 테이블 → 과권한이 남았을 수 있는 슬롯 롤 이름들. prepareForRun 은 <b>실행 슬롯
   *     롤</b>이 여기 들어 있을 때만 실행을 거부한다(다른 슬롯 롤의 과권한은 이 실행이 쓰지 않는 롤이라 무관). GRANT 실패는 과소권한(안전)이라 로그만 남긴다
   * @param runSlot 실행 준비 동기화(prepareForRun)에서 같은 등급 스냅샷으로 계산한 실행 슬롯(범위 밖이면 그 값 그대로), 그 밖의 동기화는 0
   */
  public record SyncResult(
      List<LevelPolicy> levelsAsc,
      Set<Integer> availableSlots,
      int changedTables,
      Map<String, Set<String>> revokeFailed,
      int runSlot) {

    /** 회수 실패 테이블 이름들(롤 구분 없이). */
    public Set<String> revokeFailedTables() {
      return revokeFailed.keySet();
    }
  }

  private final DSLContext dsl;
  private final SecurityLevelRepository levelRepository;
  private final TransactionTemplate requiresNew;

  /**
   * txManager 는 @Primary TenantAwareTransactionManager — 트랜잭션 시작 때 TenantContext 를 RLS GUC 로 주입한다.
   */
  public PythonReadGrantSync(
      DSLContext dsl,
      SecurityLevelRepository levelRepository,
      PlatformTransactionManager txManager) {
    this.dsl = dsl;
    this.levelRepository = levelRepository;
    this.requiresNew = new TransactionTemplate(txManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 현재 테넌트 전체 재동기화. 실패는 예외(호출부가 로그/실패를 정한다). */
  public SyncResult syncTenant() {
    return requiresNew.execute(status -> syncInTx(null, null));
  }

  /** 테이블 하나만 재동기화(생성·클론·맞바꿈 직후 — RENAME 맞바꿈은 ACL 을 잃는다). */
  public void syncTable(String tableName) {
    requiresNew.execute(status -> syncInTx(tableName, null));
  }

  /** 데이터셋 하나 — 테이블 이름을 찾아 syncTable. 물리 테이블이 없는 데이터셋(문서·파일형)은 할 일이 없다. */
  public void syncDataset(long datasetId) {
    String table =
        requiresNew.execute(
            status ->
                dsl.select(DATASET.TABLE_NAME)
                    .from(DATASET)
                    .where(DATASET.ID.eq(datasetId))
                    .fetchOptional(DATASET.TABLE_NAME)
                    .orElse(null));
    if (table != null) {
      syncTable(table);
    }
  }

  /**
   * 쓰기 경로 훅 — Spring 트랜잭션 동기화가 활성이면 커밋 후, 아니면 즉시 syncTable. 실패는 로그만 남긴다(판정이 아니라 이중 방어선이라 커밋을 막지
   * 않는다, 스펙 §4.3). 다음 JIT·일 1회 동기화가 회복한다.
   */
  public void syncTableAfterCommit(String tableName) {
    // 예약 단계 전체를 try 안에 둔다 — 테넌트 컨텍스트 누락(require 실패)·동기화 등록 실패도 "로그만, 커밋을 막지 않는다" 계약에 든다.
    // require 가 밖에 있으면 컨텍스트 없는 쓰기 경로에서 예외가 호출자 트랜잭션을 롤백시킨다(이중 방어선이 본 작업을 깨뜨림).
    try {
      long tenantId = TenantContext.require("PYTHON 읽기 권한 동기화 예약");
      Runnable work =
          () -> {
            try {
              // afterCommit 콜백은 다른 컨텍스트에서 돌 수 있어 테넌트를 명시로 다시 세운다.
              TenantContext.runScoped(tenantId, () -> syncTable(tableName));
            } catch (RuntimeException e) {
              log.warn(
                  "PYTHON 읽기 권한 동기화 실패(다음 동기화에서 회복): tenant={} table={}", tenantId, tableName, e);
            }
          };
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                work.run();
              }
            });
      } else {
        work.run();
      }
    } catch (RuntimeException e) {
      log.warn("PYTHON 읽기 권한 동기화 예약 실패(다음 동기화에서 회복): table={}", tableName, e);
    }
  }

  /**
   * PYTHON 실행 직전 준비(JIT) — 잠금 안에서 읽은 등급 스냅샷으로 실행 주체의 슬롯 k 를 계산하고, <b>그 슬롯 롤 하나의</b> ACL 만 계산값에 맞춘다
   * (CR3 — 매 스텝 10개 롤 전부를 맞추지 않는다. 다른 슬롯은 이 스크립트가 접속하지 않는 롤이라 이벤트·일 1회·그 슬롯의 실행이 맞춘다). 실행 슬롯의 과권한은
   * 매번 실제 ACL 로 확인하므로 손으로 건 GRANT 도 회수하거나(실패하면) 거부한다 — "마지막 동기화 이후 변경 없음" 단락은 이 확인을 건너뛰게 되므로 쓰지
   * 않는다. 어떤 실패든 {@link PythonReadAccessException}(fail-closed). 테넌트 실행 롤(pipeline_executor_t*)로
   * 대체하는 경로는 없다 — 그 롤은 모든 등급을 읽으므로 대체하는 순간 등급 경계가 사라진다.
   *
   * @return 실행에 쓸 슬롯 번호(1~10)
   */
  public int prepareForRun(Clearance runAs) {
    // 테넌트 일치 확인을 맨 먼저 — 동기화(syncTenant)는 TenantContext 테넌트를, 슬롯 계산은 runAs 의 rank 를 쓴다. 둘이 다르면
    // 남의 테넌트 등급 스냅샷으로 슬롯을 고르게 되므로(과권한 가능) 계산 전에 거부한다. 컨텍스트가 비어도 같은 예외(fail-closed).
    Long contextTenant = TenantContext.get();
    if (contextTenant == null || contextTenant != runAs.tenantId()) {
      log.error(
          "PYTHON 실행 주체 테넌트 불일치: runAs.tenant={} context.tenant={}",
          runAs.tenantId(),
          contextTenant);
      throw new PythonReadAccessException("실행 주체의 테넌트가 현재 실행 테넌트와 달라 Python 스크립트를 실행할 수 없습니다.");
    }
    if (runAs.rank() == Clearance.NO_RANK) {
      throw new PythonReadAccessException("실행 주체에게 열람 등급이 없어 Python 스크립트를 실행할 수 없습니다.");
    }
    SyncResult result;
    try {
      result = requiresNew.execute(status -> syncInTx(null, runAs.rank()));
    } catch (RuntimeException e) {
      throw new PythonReadAccessException("Python 읽기 권한을 준비하지 못해 실행을 중단했습니다.", e);
    }
    int slot = result.runSlot();
    if (slot < 1 || slot > TenantPipelineRole.PYTHON_READ_SLOTS) {
      throw new PythonReadAccessException("실행 주체의 열람 등급이 Python 읽기 슬롯 범위(1~10)를 벗어났습니다.");
    }
    if (!result.availableSlots().contains(slot)) {
      throw new PythonReadAccessException("Python 읽기 롤이 준비되지 않았습니다. 관리자에게 문의하세요.");
    }
    // 회수 실패 = 그 롤에 과권한이 남았을 수 있다. 거부는 이 실행이 접속할 슬롯 롤에 남은 경우로만 좁힌다 — 다른 슬롯 롤의 과권한은 이 스크립트가
    // 쓸 수 없다(슬롯 롤끼리 멤버십이 없고 직접 로그인한다). 넓게 거부하면 테이블 하나의 회수 실패가 테넌트 PYTHON 전체를 멈춘다.
    String slotRole = TenantPipelineRole.pythonReadRoleName(runAs.tenantId(), slot);
    Set<String> overGranted = new TreeSet<>();
    result
        .revokeFailed()
        .forEach(
            (table, rolesLeft) -> {
              if (rolesLeft.contains(slotRole)) {
                overGranted.add(table);
              }
            });
    if (!result.revokeFailed().isEmpty()) {
      // 테이블 이름은 사용자 메시지에 싣지 않는다(로그에만).
      log.error(
          "PYTHON 읽기 권한 회수 실패(tenant={}, 실행 슬롯 {}): {}",
          runAs.tenantId(),
          slot,
          result.revokeFailed());
    }
    if (!overGranted.isEmpty()) {
      throw new PythonReadAccessException("Python 읽기 권한을 회수하지 못한 데이터가 있어 실행을 중단했습니다. 관리자에게 문의하세요.");
    }
    return slot;
  }

  /**
   * 트랜잭션(=테넌트 GUC) 안에서 호출된다.
   *
   * @param onlyTable null 이면 스키마 전체, 아니면 그 테이블만
   * @param runRank null 이면 슬롯 롤 전부, 아니면 그 자격 rank 의 실행 슬롯 롤 하나만(실행 준비) — 슬롯은 잠금 뒤 읽은 등급 스냅샷으로 계산한다
   */
  private SyncResult syncInTx(String onlyTable, Integer runRank) {
    long tenantId = TenantContext.require("PYTHON 읽기 권한 동기화");
    // 같은 테넌트 동기화 직렬화(트랜잭션 종료 시 자동 해제).
    dsl.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended({0}, 0))",
        DSL.val("python_read_sync_t" + tenantId));
    String schema = DataSchema.current();

    List<String> slotRoleNames = new ArrayList<>();
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      slotRoleNames.add(TenantPipelineRole.pythonReadRoleName(tenantId, k));
    }
    List<LevelPolicy> levelsAsc = levelRepository.findAll(); // rank asc, 이 트랜잭션에 합류(RLS GUC)
    // 다룰 슬롯: 실행 준비면 실행 슬롯 하나(범위 밖이면 없음 — prepareForRun 이 거부), 아니면 전부.
    int runSlot = 0;
    List<String> targetRoles = new ArrayList<>();
    if (runRank == null) {
      targetRoles.addAll(slotRoleNames);
    } else {
      runSlot = PythonReadSlots.slotFor(levelsAsc, runRank);
      if (runSlot >= 1 && runSlot <= TenantPipelineRole.PYTHON_READ_SLOTS) {
        targetRoles.add(slotRoleNames.get(runSlot - 1));
      }
    }
    // 존재하는 슬롯 롤만 다룬다 — 없는 롤에 GRANT 하면 오류다. 없는 슬롯은 prepareForRun 이 거부한다.
    Set<String> existingRoles =
        targetRoles.isEmpty()
            ? Set.of()
            : new HashSet<>(
                dsl.fetch(
                        "select rolname::text from pg_roles where rolname::text = any({0}::text[])",
                        DSL.val(targetRoles.toArray(new String[0])))
                    .getValues(0, String.class));
    Set<Integer> availableSlots = new TreeSet<>();
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      if (existingRoles.contains(slotRoleNames.get(k - 1))) {
        availableSlots.add(k);
      }
    }

    if (existingRoles.isEmpty()) {
      if (runRank == null) {
        log.warn("PYTHON 읽기 슬롯 롤이 없다 — 동기화 생략(tenant={})", tenantId);
      }
      return new SyncResult(levelsAsc, availableSlots, 0, Map.of(), runSlot);
    }
    String[] roleArray = existingRoles.toArray(new String[0]);

    // 계산값: 데이터셋 테이블 → SELECT 를 받아야 할 슬롯 롤 집합. 테이블 하나만 맞출 때(생성·맞바꿈 훅)는 그 테이블의 데이터셋 행만 읽는다 —
    // 쓰기 경로마다 테넌트 데이터셋 전체를 읽지 않게(CR4).
    Map<String, Set<String>> desired = new HashMap<>();
    for (var r :
        dsl.select(DATASET.TABLE_NAME, DATASET.SECURITY_LEVEL_ID)
            .from(DATASET)
            .where(
                onlyTable == null
                    ? DATASET.TABLE_NAME.isNotNull()
                    : DATASET.TABLE_NAME.eq(onlyTable))
            .fetch()) {
      Set<String> roles = new TreeSet<>();
      for (int k : PythonReadSlots.grantSlotsFor(levelsAsc, r.value2())) {
        String role = slotRoleNames.get(k - 1);
        if (existingRoles.contains(role)) {
          roles.add(role);
        }
      }
      desired.put(r.value1(), roles);
    }

    // 실제: 스키마의 관계(테이블·뷰·구체화 뷰·외부 테이블)와 슬롯 롤에 걸린 SELECT.
    // 왜 뷰까지 보는가(CR5): 뷰는 소유자 권한으로 바닥 테이블을 읽는다 — 손으로(또는 SQL 스텝이) 슬롯 롤에 뷰 SELECT 를 걸면 등급 밖
    // 테이블을 그 뷰로 우회해 읽는다. 테이블만 보면 이 GRANT 를 탐지·회수하지 못한다. 시퀀스('S')는 행 데이터가 없어 제외한다.
    Map<String, Character> relkinds = new HashMap<>();
    for (var r :
        dsl.fetch(
            "select c.relname::text, c.relkind::text from pg_class c"
                + " join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = {0} and c.relkind in ('r','p','v','m','f')"
                + (onlyTable == null ? "" : " and c.relname = {1}"),
            onlyTable == null
                ? new Object[] {DSL.val(schema)}
                : new Object[] {DSL.val(schema), DSL.val(onlyTable)})) {
      relkinds.put(r.get(0, String.class), r.get(1, String.class).charAt(0));
    }
    List<String> physical = new ArrayList<>(relkinds.keySet());
    // 관계별 계산값 — 일반·분할 테이블이 아닌 관계(뷰 등)는 데이터셋 행이 같은 이름을 가리켜도 "없음"이다. 뷰 GRANT 는 소유자 권한 읽기라
    // 등급 경계를 지키지 못한다(fail-safe). 적용 루프와 재확인이 같은 규칙을 쓴다.
    java.util.function.Function<String, Set<String>> wantOf =
        t -> {
          char kind = relkinds.get(t);
          return kind == 'r' || kind == 'p' ? desired.getOrDefault(t, Set.of()) : Set.of();
        };
    Map<String, Set<String>> actual = new HashMap<>();
    for (var r : fetchSlotSelectGrants(schema, physical, roleArray)) {
      actual
          .computeIfAbsent(r.get(0, String.class), x -> new TreeSet<>())
          .add(r.get(1, String.class));
    }

    int changed = 0;
    // 테이블 → 과권한이 남았을 수 있는 슬롯 롤
    Map<String, Set<String>> revokeFailed = new TreeMap<>();
    List<String> changedTables = new ArrayList<>();
    for (String table : physical) {
      // 데이터셋이 아닌 테이블(staging·_tmp 등)·뷰는 계산값이 "없음" — 남아 있는 슬롯 GRANT 는 회수한다.
      Set<String> want = wantOf.apply(table);
      Set<String> have = actual.getOrDefault(table, Set.of());
      if (want.equals(have)) {
        continue;
      }
      Set<String> excess = new TreeSet<>(have);
      excess.removeAll(want);
      Set<String> missing = new TreeSet<>(want);
      missing.removeAll(have);
      String qualified = dsl.render(DSL.name(schema, table));
      // 테이블마다 savepoint 로 격리한다 — 테이블 하나의 오류(소유자 아님·동시 DROP·tuple concurrently updated)가 테넌트 전체
      // 동기화를 중단시키면 JIT 경로에서 그 테넌트의 모든 PYTHON 이 실패한다. REVOKE 와 GRANT 를 따로 감싸는 이유: GRANT 실패로 REVOKE
      // 까지 되돌리면 과권한이 남는다.
      if (!excess.isEmpty()
          && !inSavepoint(
              () ->
                  dsl.execute("REVOKE ALL ON TABLE " + qualified + " FROM " + quoteAll(excess)))) {
        // REVOKE 문장 전체가 되돌려졌다 — excess 롤 전부에 과권한이 남았을 수 있다(그 슬롯으로 실행하면 prepareForRun 이 거부).
        revokeFailed.computeIfAbsent(table, x -> new TreeSet<>()).addAll(excess);
        continue;
      }
      if (!missing.isEmpty()
          && !inSavepoint(
              () ->
                  dsl.execute("GRANT SELECT ON TABLE " + qualified + " TO " + quoteAll(missing)))) {
        // 과소권한(안전) — 스크립트가 그 테이블을 읽으면 permission denied. 다음 동기화가 재시도한다.
        log.warn("PYTHON 읽기 GRANT 실패(과소권한, 다음 동기화에서 재시도): tenant={} table={}", tenantId, table);
      }
      changed++;
      changedTables.add(table);
    }
    // 재확인: 소유자가 아닌 롤의 GRANT/REVOKE 는 일부 권한을 가진 경우 오류가 아니라 WARNING 만 내고 아무것도 하지 않는다. 그래서
    // savepoint 성공만으로는 회수를 믿을 수 없다 — 바꾼 테이블의 실제 ACL 을 다시 읽어 "원하지 않는 슬롯에 SELECT 가 남은" 테이블을 회수
    // 실패로 센다(과권한 → prepareForRun 이 실행 거부).
    if (!changedTables.isEmpty()) {
      for (var r : fetchSlotSelectGrants(schema, changedTables, roleArray)) {
        String table = r.get(0, String.class);
        String role = r.get(1, String.class);
        if (!wantOf.apply(table).contains(role)) {
          revokeFailed.computeIfAbsent(table, x -> new TreeSet<>()).add(role);
        }
      }
    }
    if (changed > 0 || !revokeFailed.isEmpty()) {
      log.info(
          "PYTHON 읽기 권한 동기화: tenant={} 변경 테이블 {}개, 회수 실패 {}개",
          tenantId,
          changed,
          revokeFailed.size());
    }
    return new SyncResult(levelsAsc, availableSlots, changed, revokeFailed, runSlot);
  }

  /** 주어진 테이블들에 슬롯 롤이 가진 SELECT 권한(테이블명, 롤명) 쌍. 테이블 목록이 비면 빈 결과. */
  private org.jooq.Result<org.jooq.Record> fetchSlotSelectGrants(
      String schema, List<String> tables, String[] roles) {
    if (tables.isEmpty()) {
      return dsl.newResult();
    }
    return dsl.fetch(
        "select c.relname::text, r.rolname::text from pg_class c"
            + " join pg_namespace n on n.oid = c.relnamespace"
            + " cross join lateral aclexplode(c.relacl) a join pg_roles r on r.oid = a.grantee"
            + " where n.nspname = {0} and c.relname::text = any({1}::text[])"
            + " and a.privilege_type = 'SELECT' and r.rolname::text = any({2}::text[])",
        DSL.val(schema), DSL.val(tables.toArray(new String[0])), DSL.val(roles));
  }

  /** 롤 이름들을 인용 식별자 목록으로("a", "b"). 이름은 TenantPipelineRole 조립값이지만 그래도 인용한다. */
  private String quoteAll(Set<String> roles) {
    return roles.stream().map(x -> dsl.render(DSL.name(x))).collect(Collectors.joining(", "));
  }

  /** 한 문장을 savepoint 안에서 실행한다. 실패하면 savepoint 로 되돌리고 false(트랜잭션은 계속 쓸 수 있다). */
  private boolean inSavepoint(Runnable statement) {
    dsl.execute("SAVEPOINT python_read_sync");
    try {
      statement.run();
      dsl.execute("RELEASE SAVEPOINT python_read_sync");
      return true;
    } catch (DataAccessException e) {
      dsl.execute("ROLLBACK TO SAVEPOINT python_read_sync");
      log.warn("PYTHON 읽기 권한 문장 실패: {}", e.getMessage());
      return false;
    }
  }
}
