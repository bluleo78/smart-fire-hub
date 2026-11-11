package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import com.smartfirehub.global.platformdb.mybatis.PlatformSqlFragmentQueries;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.support.tibero.TiberoTestEvidence;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 묶음 ②(데이터셋 리포지토리, WD-707) MyBatis Tibero 판이 기댈 문법을 공유 개발 Tibero 에서 먼저 확인하는 스파이크.
 *
 * <ul>
 *   <li>(a) JSON 열 숫자 배열 포함 술어 — PG {@code (config -> 'datasetIds') @> to_jsonb(?::bigint)} 대체 후보를
 *       차례로 돌려 어느 것이 PG 와 같은 행을 고르는지 기록하고(후보 비교), 채택한 술어를 따로 고정한다. 스칼라 추출({@code JSON_VALUE})과 배열
 *       원소 제거({@code JSON_TRANSFORM})도 되는지 기록만 한다(Task 5 판단 근거).
 *   <li>(b) {@code NUMTODSINTERVAL} 덧셈 — 공용 조각 {@code dbNowPlusSeconds} 와 임대 UPDATE 모양.
 *   <li>(c) {@code LOWER(col) LIKE … ESCAPE '\'} — VARCHAR2(65532) 긴 값·한글·이스케이프. 공용 조각 {@code
 *       likeIgnoreCase} 를 런타임 계정 + RLS 아래 실제 dataset.description 에서도 돌린다.
 *   <li>(d) {@code SELECT … FOR UPDATE} — 런타임 계정 + DBMS_RLS 아래 자기 테넌트 행은 잠그고, 다른 테넌트는 행이 안 보여 기다리지
 *       않는다.
 * </ul>
 *
 * <p>탐침 테이블은 정책이 없는 방언 확인용이라 소유자 계정으로 만들고 끝에서 지운다. 정책 확인((c) 뒤쪽·(d))은 실제 정책 테이블(dataset)과 픽스처
 * {@link TiberoAccessFixtures} 를 쓰고 끝에서 픽스처가 지운다. 실행기는 UTC JVM 이라 KST JVM 어긋남은 이 스파이크가 보지 않는다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TiberoBundle2SyntaxProbeLiveTest {

  /** 이번 실행의 탐침 테이블 이름(접미사로 다른 실행과 겹치지 않게). */
  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

  private final String jsonTable = "fhp_b2_json_" + suffix;
  private final String leaseTable = "fhp_b2_lease_" + suffix;
  private final String longTable = "fhp_b2_long_" + suffix;

  private final List<String> createdTables = new ArrayList<>();

  private TiberoPlatformSchema poc;
  private TiberoAccessFixtures fx;
  private SqlSessionTemplate sql;

  /** 공용 조각 시험 질의 호출 도우미(PG PlatformSqlFragmentsTest 와 공유). */
  private PlatformSqlFragmentQueries q;

  private JdbcTemplate runtimeJdbc;

  @BeforeAll
  void setUp() {
    poc = TiberoPlatformSchema.shared();
    fx = new TiberoAccessFixtures(poc);
    // 하네스 런타임 풀 위 팩토리 — 시험 전용 질의 XML 도 classpath*:mybatis/** 로 함께 읽힌다. 같은 DataSource 라 poc.tx() 에
    // 참여한다.
    sql =
        MyBatisPlatformConfig.sqlSessionTemplate(
            MyBatisPlatformConfig.sqlSessionFactory(poc.dataSource()));
    q = new PlatformSqlFragmentQueries(sql);
    runtimeJdbc = new JdbcTemplate(poc.dataSource());
  }

  /** 픽스처 행과 이번 실행이 만든 탐침 테이블만 지운다. */
  @AfterAll
  void tearDown() {
    if (fx != null) fx.cleanup();
    for (String t : createdTables) {
      try {
        poc.jdbc().execute("DROP TABLE " + t);
      } catch (RuntimeException e) {
        ev("drop " + t + " 실패 " + rootMessage(e));
      }
    }
  }

  private static void ev(String line) {
    TiberoTestEvidence.write("B2PROBE " + line);
  }

  private static String rootMessage(Throwable e) {
    Throwable c = e;
    while (c.getCause() != null) c = c.getCause();
    return c.getClass().getSimpleName() + ": " + c.getMessage();
  }

  private void createTable(String name, String ddl) {
    poc.jdbc().execute(ddl);
    createdTables.add(name);
  }

  // ---------------------------------------------------------------- (a) JSON

  /**
   * (a) 픽스처 — PG 기준 기대값: id 5 → {1, 8}, id 7 → {1}, id 50 → {2}, id 8 → {8}. 나머지 행은 어느 id 에도 맞지 않아야
   * 한다.
   */
  private void ensureJsonTable() {
    if (createdTables.contains(jsonTable)) return;
    createTable(jsonTable, "CREATE TABLE " + jsonTable + " (id NUMBER(10), config JSON)");
    String[] rows = {
      "{\"datasetIds\":[5,7]}", // 1 — 5·7 포함
      "{\"datasetIds\":[15,50,55]}", // 2 — 부분 문자열 함정(5 는 아님), 50 포함
      "{\"datasetIds\":[\"5\"]}", // 3 — 문자열 "5" 는 PG @> to_jsonb(5::bigint) 와 다르다
      "{\"datasetIds\":5}", // 4 — 배열 아님(PG 는 jsonb_typeof = 'array' 로 거른다)
      "{\"other\":[5]}", // 5 — 다른 키
      "{\"datasetIds\":[]}", // 6 — 빈 배열
      "{\"upstreamPipelineId\":42}", // 7 — 스칼라 추출 탐침용
      "{\"datasetIds\": [ 8 , 5 ]}" // 8 — 원소 사이 공백(저장 형태가 정규화되지 않아도 맞아야 한다)
    };
    for (int i = 0; i < rows.length; i++) {
      poc.jdbc().update("INSERT INTO " + jsonTable + " (id, config) VALUES (?, ?)", i + 1, rows[i]);
    }
  }

  /** PG 기준 기대 행(id → 맞는 행 id). */
  private static final Map<Long, List<Long>> JSON_EXPECTED =
      Map.of(5L, List.of(1L, 8L), 7L, List.of(1L), 50L, List.of(2L), 8L, List.of(8L));

  /**
   * 술어 하나를 세 id 로 돌려 PG 기대와 비교한 결과 문자열(OK / WRONG / ERROR). bind 가 false 면 {ID} 자리에 id 를 리터럴로 넣는다.
   */
  private String runJsonCandidate(String predicate, boolean bind) {
    StringBuilder wrong = new StringBuilder();
    for (Map.Entry<Long, List<Long>> e : JSON_EXPECTED.entrySet()) {
      long id = e.getKey();
      List<Long> got;
      try {
        String where = bind ? predicate : predicate.replace("{ID}", Long.toString(id));
        String q = "SELECT id FROM " + jsonTable + " WHERE " + where + " ORDER BY id";
        got =
            bind
                ? poc.jdbc().queryForList(q, Long.class, id)
                : poc.jdbc().queryForList(q, Long.class);
      } catch (RuntimeException ex) {
        return "ERROR " + rootMessage(ex);
      }
      if (!got.equals(e.getValue())) {
        wrong.append(" id=").append(id).append(" got=").append(got);
      }
    }
    return wrong.isEmpty() ? "OK" : "WRONG" + wrong;
  }

  /**
   * 채택한 JSON 포함 술어(바인드 1개 = 데이터셋 id) — 1차 실측에서 lax JSON_EXISTS 는 문자열 "5"·스칼라 5 까지, JSON_TABLE
   * NUMBER 는 문자열 "5" 까지, JSON_QUERY 텍스트 LIKE 는 스칼라 5 까지 맞혀 PG 와 달랐다(strict 모드·JSON_TRANSFORM 은 문법
   * 오류). 그래서 {@code JSON_QUERY} 결과가 배열({@code [} 로 시작)인지 먼저 보고(PG {@code jsonb_typeof = 'array'}),
   * 공백·괄호를 걷은 원소 목록을 쉼표로 감싸 {@code ,id,} 로 찾는다 — 따옴표가 남는 문자열 원소는 맞지 않고, {@code 15}·{@code 50} 같은 부분
   * 문자열도 맞지 않는다.
   */
  static final String ADOPTED_JSON_CONTAINS =
      "(JSON_QUERY(config, '$.datasetIds') LIKE '[%' AND (',' || REPLACE(REPLACE(REPLACE("
          + "JSON_QUERY(config, '$.datasetIds'), ' ', ''), '[', ''), ']', '') || ',')"
          + " LIKE ('%,' || ? || ',%'))";

  /** (a) 후보 술어. 앞이 바람직한 순서(바인드 가능·짧음). */
  private static final Map<String, String> JSON_CANDIDATES = new LinkedHashMap<>();

  static {
    JSON_CANDIDATES.put(
        "C1 JSON_EXISTS 리터럴 lax", "JSON_EXISTS(config, '$.datasetIds[*]?(@ == {ID})')");
    JSON_CANDIDATES.put(
        "C2 JSON_EXISTS PASSING lax",
        "JSON_EXISTS(config, '$.datasetIds[*]?(@ == $id)' PASSING ? AS \"id\")");
    JSON_CANDIDATES.put(
        "C3 JSON_EXISTS PASSING strict",
        "JSON_EXISTS(config, 'strict $.datasetIds[*]?(@ == $id)' PASSING ? AS \"id\")");
    JSON_CANDIDATES.put(
        "C4 JSON_TABLE NUMBER",
        "EXISTS (SELECT 1 FROM JSON_TABLE(config, '$.datasetIds[*]'"
            + " COLUMNS (v NUMBER PATH '$')) j WHERE j.v = ?)");
    JSON_CANDIDATES.put(
        "C5 JSON_QUERY 텍스트 LIKE",
        "(',' || REPLACE(REPLACE(REPLACE(JSON_QUERY(config, '$.datasetIds'), ' ', ''), '[', ''),"
            + " ']', '') || ',') LIKE ('%,' || ? || ',%')");
    JSON_CANDIDATES.put("C6 배열 확인 + JSON_QUERY 텍스트 LIKE", ADOPTED_JSON_CONTAINS);
    JSON_CANDIDATES.put(
        "C7 배열 확인 + JSON_EXISTS type()",
        "(JSON_QUERY(config, '$.datasetIds') LIKE '[%' AND JSON_EXISTS(config,"
            + " '$.datasetIds[*]?(@ == $id && @.type() == \"number\")' PASSING ? AS \"id\"))");
    JSON_CANDIDATES.put(
        "C8 배열 확인 + JSON_TABLE FORMAT JSON",
        "(JSON_QUERY(config, '$.datasetIds') LIKE '[%' AND EXISTS (SELECT 1 FROM"
            + " JSON_TABLE(config, '$.datasetIds[*]' COLUMNS (t VARCHAR2(100) FORMAT JSON PATH '$')) j"
            + " WHERE j.t = TO_CHAR(?)))");
  }

  /** 후보마다 리터럴 판(C1)은 바인드 없이, 나머지는 바인드로 돈다. */
  private Map<String, String> runAllJsonCandidates() {
    ensureJsonTable();
    Map<String, String> results = new LinkedHashMap<>();
    JSON_CANDIDATES.forEach(
        (name, pred) -> results.put(name, runJsonCandidate(pred, !name.startsWith("C1"))));
    return results;
  }

  @Test
  @DisplayName("(a) JSON 숫자 배열 포함 후보 술어를 PG 기대와 비교해 기록하고, 채택 술어는 PG 와 같은 행을 고른다")
  void jsonContains_candidates() {
    Map<String, String> results = runAllJsonCandidates();
    results.forEach((name, r) -> ev("json " + name + ": " + r));
    // 다른 후보는 기록만 한다(음성 결과도 Task 5 판단 근거). 채택 술어만 PG 와 같은 행을 골라야 한다.
    assertThat(results.get("C6 배열 확인 + JSON_QUERY 텍스트 LIKE"))
        .as("채택 JSON 포함 술어 — 후보 전체 결과 %s", results)
        .isEqualTo("OK");
  }

  @Test
  @DisplayName("(a) JSON_VALUE 스칼라 추출과 JSON_TRANSFORM 원소 제거가 되는지 기록한다(Task 5 판단 근거)")
  void jsonScalarAndMutation_recorded() {
    ensureJsonTable();
    try {
      List<Long> got =
          poc.jdbc()
              .queryForList(
                  "SELECT id FROM "
                      + jsonTable
                      + " WHERE JSON_VALUE(config, '$.upstreamPipelineId' RETURNING NUMBER) = ?",
                  Long.class,
                  42L);
      ev("json_value RETURNING NUMBER = ?: " + (got.equals(List.of(7L)) ? "OK" : "WRONG " + got));
    } catch (RuntimeException e) {
      ev("json_value RETURNING NUMBER = ?: ERROR " + rootMessage(e));
    }
    try {
      String out =
          poc.jdbc()
              .queryForObject(
                  "SELECT JSON_TRANSFORM(config, REMOVE '$.datasetIds[*]?(@ == 5)') FROM "
                      + jsonTable
                      + " WHERE id = 1",
                  String.class);
      ev("json_transform REMOVE: OK " + out);
    } catch (RuntimeException e) {
      ev("json_transform REMOVE: ERROR " + rootMessage(e));
    }
    try {
      String out =
          poc.jdbc()
              .queryForObject(
                  "SELECT JSON_MERGEPATCH(config, '{\"datasetIds\":[7]}') FROM "
                      + jsonTable
                      + " WHERE id = 1",
                  String.class);
      ev("json_mergepatch: OK " + out);
    } catch (RuntimeException e) {
      ev("json_mergepatch: ERROR " + rootMessage(e));
    }
  }

  // ---------------------------------------------------------------- (b) NUMTODSINTERVAL

  @Test
  @DisplayName("(b) dbNowPlusSeconds 조각(NUMTODSINTERVAL)은 바인드한 초를 더하고 음수면 과거가 된다")
  void dbNowPlusSeconds_fragment() {
    assertThat(q.laterMinusNowMillis(90)).isEqualTo(90_000L);
    assertThat(q.laterMinusNowMillis(0)).isZero();
    assertThat(q.laterMinusNowMillis(-30)).isEqualTo(-30_000L);
  }

  @Test
  @DisplayName("(b) 임대 UPDATE 모양 — 만료된 임대는 잡히고 아직 유효한 임대는 다시 잡히지 않는다")
  void leaseUpdate_numtodsinterval() {
    createTable(leaseTable, "CREATE TABLE " + leaseTable + " (id NUMBER(10), until_at TIMESTAMP)");
    poc.jdbc()
        .update(
            "INSERT INTO "
                + leaseTable
                + " (id, until_at) VALUES (1, SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'SECOND'))",
            -1L);
    String acquire =
        "UPDATE "
            + leaseTable
            + " SET until_at = SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'SECOND')"
            + " WHERE id = ? AND (until_at IS NULL OR until_at < SYS_EXTRACT_UTC(SYSTIMESTAMP))";
    assertThat(poc.jdbc().update(acquire, 60L, 1L)).as("막 만료된 임대").isEqualTo(1);
    assertThat(poc.jdbc().update(acquire, 60L, 1L)).as("아직 유효한 임대").isZero();
    ev("numtodsinterval lease: OK");
  }

  // ---------------------------------------------------------------- (c) LIKE

  @Test
  @DisplayName("(c) LOWER LIKE ESCAPE 가 VARCHAR2(65532) 긴 한글 값 끝의 대소문자 다른 검색어를 찾고 이스케이프를 지킨다")
  void likeIgnoreCase_longVarchar() {
    createTable(longTable, "CREATE TABLE " + longTable + " (id NUMBER(10), txt VARCHAR2(65532))");
    // 15,000 바이트 넘는 한글(3바이트 × 5000) 뒤에 표식 — 4000 바이트 한도를 넘는 곳에서 찾아야 한다.
    String value = "가".repeat(5000) + " Tail MARKER 50% a_b 끝";
    poc.jdbc().update("INSERT INTO " + longTable + " (id, txt) VALUES (1, ?)", value);
    String q =
        "SELECT COUNT(*) FROM "
            + longTable
            + " WHERE id = 1 AND LOWER(txt) LIKE LOWER(?) ESCAPE '\\'";
    assertThat(
            poc.jdbc()
                .queryForObject(q, Integer.class, LikePatternUtils.containsPattern("tail marker")))
        .isEqualTo(1);
    assertThat(
            poc.jdbc()
                .queryForObject(q, Integer.class, LikePatternUtils.containsPattern("50% A_B 끝")))
        .isEqualTo(1);
    assertThat(
            poc.jdbc()
                .queryForObject(q, Integer.class, LikePatternUtils.containsPattern("tail_marker")))
        .isZero();
    assertThat(
            poc.jdbc().queryForObject(q, Integer.class, LikePatternUtils.containsPattern("50%x")))
        .isZero();
    ev("like long varchar2(65532): OK");
  }

  @Test
  @DisplayName("(c) likeIgnoreCase 조각이 PG 시험과 같은 결과를 낸다(대소문자·한글·이스케이프)")
  void likeIgnoreCase_fragment() {
    assertThat(q.like("Monthly SALES Report", LikePatternUtils.containsPattern("sales")))
        .isEqualTo(1);
    assertThat(q.like("월간 매출 Report", LikePatternUtils.containsPattern("매출 rep"))).isEqualTo(1);
    assertThat(q.like("월간 매입", LikePatternUtils.containsPattern("매출"))).isZero();
    assertThat(q.like("growth 50% up", LikePatternUtils.containsPattern("50%"))).isEqualTo(1);
    assertThat(q.like("growth 500 up", LikePatternUtils.containsPattern("50%"))).isZero();
    assertThat(q.like("a_b", LikePatternUtils.containsPattern("a_b"))).isEqualTo(1);
    assertThat(q.like("axb", LikePatternUtils.containsPattern("a_b"))).isZero();
    assertThat(q.like("dir\\file", LikePatternUtils.containsPattern("r\\f"))).isEqualTo(1);
    assertThat(q.like("dirfile", LikePatternUtils.containsPattern("r\\f"))).isZero();
  }

  @Test
  @DisplayName("nullSafeEquals 조각 — 같은 값·둘 다 NULL 참, 한쪽 NULL·다른 값 거짓, '' 는 NULL 과 같다(Tibero)")
  void nullSafeEquals_fragment() {
    assertThat(q.nullSafe("m1", "m1")).isEqualTo(1);
    assertThat(q.nullSafe(null, null)).isEqualTo(1);
    assertThat(q.nullSafe("m1", null)).isZero();
    assertThat(q.nullSafe(null, "m1")).isZero();
    assertThat(q.nullSafe("m1", "m2")).isZero();
    assertThat(q.nullSafe("", null)).isEqualTo(1);
    assertThat(q.nullSafe("", "")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "nullSafeDistinct 조각(DECODE) — 한쪽만 NULL 참, 둘 다 NULL·같은 값 거짓, 숫자 열과 jdbcType 없는 null 바인드도 같다")
  void nullSafeDistinct_fragment() {
    assertThat(q.nullSafeDistinct("m1", "m1")).isZero();
    assertThat(q.nullSafeDistinct(null, null)).isZero();
    assertThat(q.nullSafeDistinct("m1", null)).isEqualTo(1);
    assertThat(q.nullSafeDistinct(null, "m1")).isEqualTo(1);
    assertThat(q.nullSafeDistinct("m1", "m2")).isEqualTo(1);
    assertThat(q.nullSafeDistinct("", null)).isZero();
    assertThat(q.nullSafeDistinctNumber(1024, 1024)).isZero();
    assertThat(q.nullSafeDistinctNumber(null, null)).isZero();
    assertThat(q.nullSafeDistinctNumber(1024, null)).isEqualTo(1);
    assertThat(q.nullSafeDistinctNumber(null, 1024)).isEqualTo(1);
    assertThat(q.nullSafeDistinctNumber(1024, 768)).isEqualTo(1);
    ev("nullSafeDistinct DECODE (varchar·number·null bind): OK");
  }

  @Test
  @DisplayName(
      "(c) likeIgnoreCase 조각이 런타임 계정 + RLS 아래 긴 dataset.description 에서 동작하고 다른 테넌트에는 행이 없다")
  void likeIgnoreCase_onDatasetDescriptionUnderRls() {
    long a = fx.createTenant();
    long b = fx.createTenant();
    long ds = fx.createDataset(a, "조각 시험");
    poc.jdbc()
        .update(
            "UPDATE dataset SET description = ? WHERE id = ?",
            "가".repeat(5000) + " Tail MARKER 끝",
            ds);
    assertThat(descriptionLike(a, ds, LikePatternUtils.containsPattern("tail marker")))
        .isEqualTo(1);
    assertThat(descriptionLike(a, ds, LikePatternUtils.containsPattern("tail_marker"))).isZero();
    assertThat(descriptionLike(b, ds, LikePatternUtils.containsPattern("tail marker")))
        .as("다른 테넌트는 정책이 행을 거른다")
        .isZero();
  }

  private int descriptionLike(long tenantId, long datasetId, String pattern) {
    return TenantContext.runScopedGet(
        tenantId, () -> poc.tx().execute(st -> q.datasetDescriptionLike(datasetId, pattern)));
  }

  // ---------------------------------------------------------------- (d) FOR UPDATE

  private List<Long> selectForUpdate(long tenantId, long datasetId, String lockClause) {
    return TenantContext.runScopedGet(
        tenantId,
        () ->
            poc.tx()
                .execute(
                    s ->
                        runtimeJdbc.queryForList(
                            "SELECT id FROM dataset WHERE id = ? " + lockClause,
                            Long.class,
                            datasetId)));
  }

  @Test
  @DisplayName("(d) FOR UPDATE 는 런타임 계정 + RLS 아래 자기 테넌트 행을 잠그고, 다른 테넌트는 행이 안 보여 기다리지 않는다")
  void selectForUpdate_underRls() throws Exception {
    long a = fx.createTenant();
    long b = fx.createTenant();
    long ds = fx.createDataset(a, "잠금 시험");

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // 보유자: 테넌트 A 트랜잭션에서 FOR UPDATE 로 잡고, 확인이 끝날 때까지 트랜잭션을 연 채 둔다(풀 커넥션 1개 점유).
      Future<List<Long>> holder =
          pool.submit(
              () ->
                  TenantContext.runScopedGet(
                      a,
                      () ->
                          poc.tx()
                              .execute(
                                  s -> {
                                    List<Long> got =
                                        runtimeJdbc.queryForList(
                                            "SELECT id FROM dataset WHERE id = ? FOR UPDATE",
                                            Long.class,
                                            ds);
                                    locked.countDown();
                                    try {
                                      release.await(30, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                      Thread.currentThread().interrupt();
                                    }
                                    return got;
                                  })));
      assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

      // 다른 테넌트: 정책이 행을 걸러 0행 — NOWAIT 인데도 잠금 오류가 나지 않는다(기다릴 행이 없다).
      List<Long> other =
          pool.submit(() -> selectForUpdate(b, ds, "FOR UPDATE NOWAIT")).get(30, TimeUnit.SECONDS);
      assertThat(other).isEmpty();

      // 같은 테넌트: 잠긴 행이라 NOWAIT 가 곧바로 실패한다 — 보유자가 실제로 행 잠금을 잡았다는 증거.
      Throwable same =
          catchThrowable(
              () ->
                  pool.submit(() -> selectForUpdate(a, ds, "FOR UPDATE NOWAIT"))
                      .get(30, TimeUnit.SECONDS));
      assertThat(same).as("같은 테넌트 NOWAIT 는 잠금 때문에 실패").isNotNull();
      ev("for update same-tenant nowait: " + rootMessage(same));

      release.countDown();
      assertThat(holder.get(30, TimeUnit.SECONDS)).containsExactly(ds);
      ev("for update under rls: OK");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  // ---------------------------------------------------------------- 픽스처

  @Test
  @DisplayName("데이터셋·카테고리·컬럼 픽스처 행이 런타임 계정에서 그 테넌트에만 보인다")
  void datasetFixtures_visibleOnlyInTenant() {
    long a = fx.createTenant();
    long b = fx.createTenant();
    long cat = fx.createCategory(a, "카테고리");
    long ds1 = fx.createDataset(a, "Same 이름 50%_x");
    long ds2 = fx.createDataset(a, "Same 이름 50%_x");
    long col = fx.createColumn(ds1, "c_one", 1);
    String q =
        "SELECT (SELECT COUNT(*) FROM dataset WHERE id IN (?, ?))"
            + " + (SELECT COUNT(*) FROM dataset_category WHERE id = ?)"
            + " + (SELECT COUNT(*) FROM dataset_column WHERE id = ? AND dataset_id = ? AND column_order = 1)"
            + " FROM DUAL";
    assertThat(countAs(a, q, ds1, ds2, cat, col, ds1)).isEqualTo(4);
    assertThat(countAs(b, q, ds1, ds2, cat, col, ds1)).isZero();
  }

  private int countAs(long tenantId, String q, Object... args) {
    return TenantContext.runScopedGet(
        tenantId, () -> poc.tx().execute(s -> runtimeJdbc.queryForObject(q, Integer.class, args)));
  }
}
