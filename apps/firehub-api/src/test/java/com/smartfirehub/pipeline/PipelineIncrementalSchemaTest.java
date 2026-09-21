package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.config.TenantPipelineDataSourceRegistry;
import com.smartfirehub.support.IntegrationTestBase;
import db.migration.V125__pipeline_incremental_updated_at_indexes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * V123 + V124 + V125 가 만든 증분 처리 스키마(함수·컬럼·트리거·인덱스 백필)를 카탈로그로 고정한다.
 *
 * <p>인덱스는 V124 백필이 아니라 V125 가 CREATE INDEX CONCURRENTLY 로 만든다(운영 배포 중 쓰기 차단을
 * 피하려는 의도적 분리 — V125 클래스 주석 참고). 그래서 아래 테스트들은 "V123 하나의 내부"가 아니라
 * <b>모든 마이그레이션이 끝난 뒤의 최종 상태</b>를 단언한다.
 */
class PipelineIncrementalSchemaTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  // 다른 롤(테넌트 파이프라인 실행 롤) 연결을 얻기 위해 SqlScriptExecutorSandboxTest 와 같은 방식을 쓴다.
  @Autowired private TenantPipelineDataSourceRegistry tenantPipelineDataSources;

  // 아래 두 신규 테스트(백필 비공허성·트리거 실동작)의 픽스처(진짜 TABLE 데이터셋)를 만들기 위해
  // 프로덕션 서비스를 그대로 쓴다 — 손으로 INSERT 문을 흉내 내면 dataset/dataset_column 의
  // NOT NULL·FK 제약을 다 맞춰야 하고, 실제 생성 경로(컬럼 영속화+물리 테이블 생성)와도 어긋날 위험이 있다.
  @Autowired private DatasetService datasetService;

  /**
   * V124 의 백필 DO 블록은 <b>실제 운영에서 Flyway 소유자 롤({@code app}) 로 실행되고, 소유자는 RLS 를
   * 우회한다.</b> app_tenant({@code dsl})로 그대로 재실행하면 {@code dataset} 테이블의 RLS 정책이
   * {@code app.tenant_id} GUC 미설정(트랜잭션 밖) 상태에서 모든 행을 가려 버려, 방금 만든 픽스처조차
   * "보이지 않는" 것으로 취급돼 루프가 아무 것도 처리하지 않는다(실측 — dsl 로 먼저 시도했다가
   * 재현했다). 그래서 {@code SqlScriptExecutorSandboxTest} 와 같은 소유자 커넥션으로 재실행한다.
   */
  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  @Test
  void 스텝과_실행기록에_책갈피_컬럼이_있다() {
    List<String> stepCols =
        dsl.fetch(
                "select column_name from information_schema.columns "
                    + "where table_schema='public' and table_name='pipeline_step'")
            .getValues(0, String.class);
    assertThat(stepCols).contains("last_run_at", "full_rebuild_pending");
    List<String> execCols =
        dsl.fetch(
                "select column_name from information_schema.columns "
                    + "where table_schema='public' and table_name='pipeline_step_execution'")
            .getValues(0, String.class);
    assertThat(execCols).contains("injected_last_run_at");
  }

  @Test
  void 후보값_함수는_SECURITY_DEFINER이고_app_tenant가_실행할_수_있다() {
    Boolean secdef =
        (Boolean)
            dsl.fetchValue(
                "select prosecdef from pg_proc where proname='fh_incremental_cursor_candidate'");
    assertThat(secdef).isTrue();
    // 런타임 롤(app_tenant)로 호출 가능해야 한다 — 권한 누락이면 여기서 예외.
    assertThat(dsl.fetchValue("select public.fh_incremental_cursor_candidate()")).isNotNull();
    // PUBLIC EXECUTE 는 회수돼 있어야 한다(다른 임의 롤은 기본적으로 거부돼야 함) — 전수 검사는
    // TenantSchemaConformanceTest.securityDefinerFunctionsAreKnownAndLeastPrivileged 가
    // KNOWN_SECURITY_DEFINER_FUNCTIONS 에 이 함수를 등재해 이미 수행한다(acl 이 비어있지 않고
    // PUBLIC 항목이 없는지). 여기서는 그 위임 사실만 남겨 둔다 — 별도 스크래치 롤을 만들어
    // "거부됨"을 재확인하는 것은 이미 있는 커버리지에 비해 비용 대비 가치가 낮다고 판단했다.
  }

  /**
   * 함수가 존재하는 이유를 검증한다: 다른 롤(실행기 테넌트 롤) 세션의 열린 트랜잭션 시작 시각이 후보값에 반영돼야 한다.
   * 같은 롤 연결로 검증하면 SECURITY DEFINER 없이도 보이므로 공허한 테스트가 된다 — 반드시 테넌트 파이프라인 롤로 연다.
   *
   * <p>SqlScriptExecutorSandboxTest 의 {@code tenantPipelineDataSources.withTenantDsl(...)} 로 테넌트 1
   * 파이프라인 실행 롤({@code pipeline_executor_t1}) 커넥션을 얻는다. 그 커넥션 위에서 트랜잭션을 연 채로
   * (람다를 리턴할 때까지 커밋되지 않는다) app_tenant 커넥션({@code dsl})으로 후보값 함수를 호출해,
   * 아직 커밋되지 않은 다른 롤 트랜잭션의 시작 시각이 후보값을 끌어내리는지 확인한다.
   */
  /**
   * 코드리뷰 MEDIUM — 함수 소유자(= Flyway 롤)가 다른 롤 세션의 {@code xact_start} 를 볼 권한이 없으면
   * 그 컬럼이 전부 NULL 로 읽히고, {@code xact_start IS NOT NULL} 필터가 모든 행을 떨어뜨려 후보값이
   * <b>조용히</b> {@code clock_timestamp()} 로 내려앉는다 — 오류도 로그도 없이, 늦게 커밋한 행을 영원히
   * 놓치는 사고(이 함수가 존재하는 이유)가 그대로 되살아난다. 그래서 그 전제를 카탈로그로 못 박는다.
   *
   * <p>실측(2026-09-21 로컬 test DB): 소유자 = {@code app}(superuser). 권한을 낮추려면 반드시
   * {@code pg_read_all_stats} 를 함께 부여해야 한다(V123 상단 주석 참고).
   */
  @Test
  void 후보값_함수_소유자는_다른_백엔드의_xact_start를_볼_수_있어야_한다() {
    var row =
        dsl.fetchOne(
            "select pg_get_userbyid(p.proowner) as owner, r.rolsuper as is_super,"
                + " pg_has_role(p.proowner, 'pg_read_all_stats', 'member') as reads_stats"
                + " from pg_proc p join pg_roles r on r.oid = p.proowner"
                + " where p.proname = 'fh_incremental_cursor_candidate'");
    assertThat(row).as("fh_incremental_cursor_candidate 함수를 찾지 못했다").isNotNull();
    boolean isSuper = Boolean.TRUE.equals(row.get("is_super", Boolean.class));
    boolean readsStats = Boolean.TRUE.equals(row.get("reads_stats", Boolean.class));
    assertThat(isSuper || readsStats)
        .as(
            "SECURITY DEFINER 소유자 '%s' 가 superuser 도 pg_read_all_stats 멤버도 아니다 —"
                + " pg_stat_activity.xact_start 가 NULL 로 보여 후보값이 clock_timestamp() 로"
                + " 조용히 내려앉고, 늦게 커밋한 행을 영원히 놓친다(V123 상단 주석 참고).",
            row.get("owner"))
        .isTrue();
  }

  @Test
  void 다른_롤의_열린_트랜잭션이_후보값을_끌어내린다() throws Exception {
    tenantPipelineDataSources.withTenantDsl(
        DEFAULT_TEST_TENANT_ID,
        leasedDsl -> {
          leasedDsl.transaction(
              cfg -> {
                // 1) 테넌트 롤 연결에서 트랜잭션을 시작하고 시작 시각(openedAt)과 pid 를 얻는다.
                cfg.dsl().execute("select 1");
                OffsetDateTime openedAt = (OffsetDateTime) cfg.dsl().fetchValue("select now()");
                Integer tenantPid = (Integer) cfg.dsl().fetchValue("select pg_backend_pid()");

                // 2) 가시성 탐침(코드리뷰 MEDIUM) — 아래 3)의 "후보값이 끌려 내려왔다" 단언만으로는
                //    공허해질 수 있다. 공유 test DB 에 무관한 idle-in-transaction 백엔드가 하나라도
                //    있으면 이 트랜잭션이 전혀 안 보여도 후보값이 그 백엔드 때문에 내려가 초록이 된다.
                //    그래서 "함수 소유자 롤이 이 pid 의 xact_start 를 실제로 보는가"를 직접 확인한다.
                assertThat(ownerDsl().fetchValue("select current_user"))
                    .as("탐침이 진짜 함수 소유자 권한인지 — 아니면 이 검사는 무의미하다")
                    .isEqualTo(
                        dsl.fetchValue(
                            "select pg_get_userbyid(proowner) from pg_proc"
                                + " where proname='fh_incremental_cursor_candidate'"));
                OffsetDateTime seenByOwner =
                    (OffsetDateTime)
                        ownerDsl()
                            .fetchValue(
                                "select xact_start from pg_stat_activity where pid = ?", tenantPid);
                assertThat(seenByOwner)
                    .as("소유자 롤이 다른 롤(%d)의 열린 트랜잭션 xact_start 를 볼 수 있어야 한다", tenantPid)
                    .isNotNull();
                // 참고 — 런타임 롤(app_tenant)은 같은 값을 보지 못한다. 그래서 이 함수가 SECURITY
                // DEFINER 여야 한다. (보이면 SECURITY DEFINER 가 불필요하다는 뜻이므로 함께 못 박는다.)
                assertThat(
                        dsl.fetchValue(
                            "select xact_start from pg_stat_activity where pid = ?", tenantPid))
                    .as("app_tenant 가 직접 볼 수 있다면 SECURITY DEFINER 가 필요 없다는 뜻 — 전제가 바뀐 것")
                    .isNull();

                // 3) 1초 대기 후 app_tenant(dsl) 로 후보값을 계산한다 — 위 트랜잭션은 아직 커밋 전이다.
                Thread.sleep(1000);
                OffsetDateTime candidate =
                    (OffsetDateTime)
                        dsl.fetchValue("select public.fh_incremental_cursor_candidate()");

                // 4) 열려 있는 다른 롤 트랜잭션의 시작 시각이 후보값을 끌어내렸어야 한다.
                assertThat(candidate).isBeforeOrEqualTo(openedAt);
                assertThat(candidate).isBeforeOrEqualTo(seenByOwner);
              });
          // leasedDsl.transaction 람다가 정상 종료(예외 없음)하면 커밋되지만, 테스트 목적상
          // 이 트랜잭션이 실제로 데이터를 바꾸지 않으므로 커밋되어도 무해하다(select 문뿐).
          return null;
        });
  }

  @Test
  void 모든_TABLE_데이터셋의_물리테이블에_updated_at_트리거와_인덱스가_있다() {
    // 백필 누락 테이블 = _updated_at 컬럼·트리거·인덱스 중 하나라도 없는 데이터셋 물리 테이블.
    // 인덱스까지 확인한다(코드리뷰 지적 4) — 컬럼·트리거만 보면 CREATE INDEX 줄이 통째로
    // 빠져도 이 테스트가 못 잡는다.
    List<String> missing =
        dsl.fetch(
                """
                select d.table_name
                from dataset d
                cross join lateral (select case when d.tenant_id = 1 then 'data'
                                     else 'data_t' || d.tenant_id end as sch) s
                where d.storage_type = 'TABLE'
                  and to_regclass(format('%I.%I', s.sch, d.table_name)) is not null
                  and (not exists (select 1 from information_schema.columns c
                                   where c.table_schema = s.sch and c.table_name = d.table_name
                                     and c.column_name = '_updated_at')
                    or not exists (select 1 from pg_trigger t
                                   where t.tgrelid = to_regclass(format('%I.%I', s.sch, d.table_name))
                                     and t.tgname = 'fh_touch_updated_at')
                    or not exists (select 1 from pg_indexes i
                                   where i.schemaname = s.sch and i.tablename = d.table_name
                                     and i.indexname = 'ix_' || d.table_name || '_upd'))
                """)
            .getValues(0, String.class);
    assertThat(missing).isEmpty();
  }

  /**
   * 코드리뷰 지적 1 — 위 전수검사 테스트는 <b>이미 백필된(공유 test DB에 누적된) TABLE 데이터셋이
   * 하나라도 있어야만</b> 의미가 있다. 깨끗한 DB(V124 적용 시점에 TABLE 데이터셋이 0건)에서는
   * 백필 DO 블록을 통째로 지워도 그 테스트가 초록으로 남는다 — 공허한 테스트다.
   *
   * <p>이 테스트는 스스로 픽스처(진짜 TABLE 데이터셋 + 물리 테이블, {@code _updated_at} 없음)를
   * 만들어 사전 상태(컬럼·트리거·인덱스 없음)를 먼저 확인한 뒤, <b>V124 마이그레이션 파일 자체에서</b>
   * 백필 DO 블록 텍스트를 읽어 그대로 재실행한다({@link #loadBackfillDoBlock()}). 마이그레이션 파일이
   * 아니라 이 테스트 안에 SQL을 손으로 복사해 두면 파일이 바뀌어도 테스트가 따라가지 못하므로,
   * 반드시 파일에서 읽는다 — 그래야 파일의 DO 블록을 지우는 변이가 이 테스트를 실제로 빨갛게 만든다.
   *
   * <p>변이 확인(수동, 커밋하지 않음): V124 파일의 {@code CREATE TRIGGER fh_touch_updated_at ...} 줄을
   * 임시로 지우고 이 테스트를 단독 실행 → FAIL 확인. 원복 후 재실행 → PASS 확인. (보고서에 기록)
   */
  @Test
  void 백필_DO_블록을_재실행하면_새_TABLE_데이터셋의_물리테이블에도_적용된다() throws Exception {
    Fixture fx = createTableDatasetFixture("bf");
    try {
      // Task 2(f08222be)부터는 DataTableService.createTable 자체가 _updated_at 컬럼·트리거·
      // 인덱스를 만든다 — 그래서 위 createTableDatasetFixture(생성 경로 그대로 사용)로 만든
      // 물리 테이블은 이 시점에 이미 "백필 후" 상태다. 이 테스트의 목적은 "V124 백필 DO 블록이
      // 그 셋을 실제로 만든다"는 것이므로, 아래 사전 상태 단언이 의미를 가지려면 먼저 생성 시
      // 붙은 트리거·인덱스·컬럼을 걷어내 V124 적용 이전(구 스키마) 상태로 되돌려야 한다.
      // 그렇지 않으면 사전 상태 단언이 항상 실패(또는 아무것도 증명하지 못하는 통과)하게 된다 —
      // 다음에 이 코드를 보는 사람이 "이미 있는데 왜 지우지" 하며 걷어내면 이 테스트가 다시
      // 공허해지므로 지우지 말 것.
      ownerDsl()
          .execute(
              "DROP TRIGGER IF EXISTS fh_touch_updated_at ON data." + fx.tableName());
      ownerDsl().execute("DROP INDEX IF EXISTS data.ix_" + fx.tableName() + "_upd");
      ownerDsl()
          .execute("ALTER TABLE data." + fx.tableName() + " DROP COLUMN IF EXISTS _updated_at");

      // 사전 상태 — 아직 백필 전이라 컬럼·트리거·인덱스가 전혀 없어야 한다. 이게 거짓이면 아래
      // "적용됐다" 단언이 애초에 공허해진다(이미 있던 걸 다시 확인하는 꼴).
      assertThat(hasUpdatedAtColumn(fx.tableName())).as("사전 상태: 컬럼 없음").isFalse();
      assertThat(hasUpdatedAtTrigger(fx.tableName())).as("사전 상태: 트리거 없음").isFalse();
      assertThat(hasUpdatedAtIndex(fx.tableName())).as("사전 상태: 인덱스 없음").isFalse();

      ownerDsl().execute(loadBackfillDoBlock());

      assertThat(hasUpdatedAtColumn(fx.tableName())).as("V124 백필 후: 컬럼 생김").isTrue();
      assertThat(hasUpdatedAtTrigger(fx.tableName())).as("V124 백필 후: 트리거 생김").isTrue();
      // 인덱스는 V124 의 책임이 아니다 — 여기서 이미 생겼다면 누군가 인덱스 생성을 백필로 되접은 것이다
      // (그 순간 운영 배포 중 쓰기 차단이 돌아온다 — V125 클래스 주석 참고).
      assertThat(hasUpdatedAtIndex(fx.tableName())).as("V124 백필은 인덱스를 만들지 않는다(V125 의 책임)").isFalse();

      // V125 를 마이그레이션 클래스 그대로 호출한다 — 테스트 안에 SQL 을 복사해 두면 파일이 바뀌어도
      // 테스트가 따라가지 못한다. CREATE INDEX CONCURRENTLY 는 autocommit 연결이어야 한다.
      try (Connection conn = schemaOwnerDataSource.getConnection()) {
        conn.setAutoCommit(true);
        V125__pipeline_incremental_updated_at_indexes.ensureUpdatedAtIndexes(conn);
      }
      assertThat(hasUpdatedAtIndex(fx.tableName())).as("V125 후: 인덱스 생김").isTrue();
      assertThat(isUpdatedAtIndexValid(fx.tableName())).as("V125 후: 인덱스가 VALID").isTrue();

      // 재실행 안전성 — 이미 유효한 인덱스가 있는 DB(운영/dev 의 실제 상황)에서 다시 돌려도
      // 예외 없이 통과하고 인덱스가 그대로 유효해야 한다.
      try (Connection conn = schemaOwnerDataSource.getConnection()) {
        conn.setAutoCommit(true);
        V125__pipeline_incremental_updated_at_indexes.ensureUpdatedAtIndexes(conn);
      }
      assertThat(isUpdatedAtIndexValid(fx.tableName())).as("V125 재실행 후에도 인덱스가 VALID").isTrue();
    } finally {
      dropFixture(fx);
    }
  }

  /**
   * 코드리뷰 지적 2 — 지금까지 모든 테스트는 카탈로그(정보 스키마·pg_trigger)만 봤다. {@code
   * fh_touch_updated_at()} 트리거 함수 본문이 엉뚱한 필드를 대입해도(예: 오타로 다른 컬럼에 대입,
   * 또는 대입 자체를 빠뜨려도) 카탈로그 검사는 여전히 초록이다. 이 테스트는 실제 INSERT·UPDATE를
   * 실행해 트리거가 진짜로 값을 채우고 갱신하는지 행위로 증명한다.
   *
   * <p>INSERT 와 UPDATE 를 서로 다른(암묵적 autocommit) 트랜잭션으로 실행해야 한다 — {@code now()}
   * 는 트랜잭션 시작 시각이라 같은 트랜잭션 안에서 두 문장을 실행하면 항상 같은 값이 나와
   * "갱신됐다"를 증명할 수 없다(V124 마이그레이션 파일 상단 주석 참고). 이 테스트 클래스는
   * 클래스 레벨 {@code @Transactional} 을 쓰지 않으므로(IntegrationTestBase 의 "클래스 레벨
   * @Transactional 금지" 규칙) {@code dsl.execute} 각 호출이 별도 트랜잭션으로 커밋된다.
   */
  @Test
  void INSERT와_UPDATE에서_updated_at이_실제로_채워지고_갱신된다() throws Exception {
    Fixture fx = createTableDatasetFixture("trig");
    try {
      ownerDsl().execute(loadBackfillDoBlock());
      assertThat(hasUpdatedAtTrigger(fx.tableName())).as("백필 선행 조건").isTrue();

      dsl.execute("INSERT INTO data." + fx.tableName() + " (val) VALUES ('a')");
      OffsetDateTime insertedAt = fetchUpdatedAt(fx.tableName());
      assertThat(insertedAt).as("INSERT 시점에 _updated_at 이 채워져야 한다").isNotNull();

      // 서로 다른 트랜잭션이 되도록 약간 대기(타임스탬프 해상도 여유) 후 UPDATE.
      Thread.sleep(10);
      dsl.execute("UPDATE data." + fx.tableName() + " SET val = 'b'");
      OffsetDateTime updatedAt = fetchUpdatedAt(fx.tableName());

      assertThat(updatedAt)
          .as("UPDATE 후 _updated_at 이 INSERT 시점보다 엄격히 커야 한다")
          .isAfter(insertedAt);
    } finally {
      dropFixture(fx);
    }
  }

  // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────

  /** 테스트가 스스로 만들고 지우는 TABLE 데이터셋(메타 + 물리 테이블) 식별자. */
  private record Fixture(Long userId, Long datasetId, String tableName) {}

  /**
   * 진짜 TABLE 데이터셋을 하나 만든다 — {@code DatasetService.createDataset} 을 그대로 써서
   * dataset/dataset_column NOT NULL·FK 제약과 실제 물리 테이블 생성 경로를 손으로 흉내 내지 않는다.
   * 컬럼은 평범한 TEXT 컬럼 하나뿐이다(_updated_at 이 아님 — 백필 전 상태를 재현해야 하므로).
   *
   * <p>이 클래스는 클래스 레벨 {@code @Transactional} 을 쓰지 않으므로 여기서 만든 행·테이블은
   * 그대로 커밋된다({@code inTenantFixture} 는 픽스처 트랜잭션을 열고 정상 종료 시 커밋한다) —
   * {@link #dropFixture(Fixture)} 가 반드시 정리해야 한다.
   */
  private Fixture createTableDatasetFixture(String suffix) {
    String tableName = "v123_fx_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8);
    return inTenantFixture(
        () -> {
          Long userId =
              (Long)
                  dsl.fetchValue(
                      "insert into \"user\" (username, password, name, email) values (?, ?, ?, ?)"
                          + " returning id",
                      tableName,
                      "unused",
                      "V124 Fixture",
                      tableName + "@example.com");

          DatasetDetailResponse created =
              datasetService.createDataset(
                  new CreateDatasetRequest(
                      tableName,
                      tableName,
                      "V124 백필 테스트 픽스처",
                      null,
                      "TABLE",
                      "SOURCE",
                      List.of(new DatasetColumnRequest("val", "Val", "TEXT", null, true, false, null)),
                      null),
                  userId);
          return new Fixture(userId, created.id(), tableName);
        });
  }

  /** {@link #createTableDatasetFixture(String)} 이 만든 것을 전부 지운다(물리 테이블 → dataset → user 순). */
  private void dropFixture(Fixture fx) {
    if (fx == null) {
      return;
    }
    inTenantFixture(
        () -> {
          dsl.execute("DROP TABLE IF EXISTS data." + fx.tableName());
          // dataset_column 등은 dataset FK 의 ON DELETE CASCADE 로 함께 지워진다(V2).
          dsl.execute("DELETE FROM dataset WHERE id = ?", fx.datasetId());
          // createDataset 이 남긴 감사 로그(audit_log.user_id FK, CASCADE 아님)를 먼저 지워야
          // user 삭제가 FK 위반 없이 통과한다.
          dsl.execute("DELETE FROM audit_log WHERE user_id = ?", fx.userId());
          dsl.execute("DELETE FROM \"user\" WHERE id = ?", fx.userId());
        });
  }

  private boolean hasUpdatedAtColumn(String tableName) {
    return dsl.fetchExists(
        DSL.select()
            .from(DSL.table("information_schema.columns"))
            .where(
                "table_schema = 'data' and table_name = ? and column_name = '_updated_at'",
                tableName));
  }

  private boolean hasUpdatedAtTrigger(String tableName) {
    Integer count =
        (Integer)
            dsl.fetchValue(
                "select count(*)::int from pg_trigger"
                    + " where tgrelid = to_regclass(format('%I.%I', 'data', ?))"
                    + " and tgname = 'fh_touch_updated_at'",
                tableName);
    return count != null && count > 0;
  }

  private boolean hasUpdatedAtIndex(String tableName) {
    return dsl.fetchExists(
        DSL.select()
            .from(DSL.table("pg_indexes"))
            .where(
                "schemaname = 'data' and tablename = ? and indexname = ?",
                tableName,
                "ix_" + tableName + "_upd"));
  }

  /** CREATE INDEX CONCURRENTLY 가 실패하면 INVALID 인덱스가 남는다 — 존재만으로는 충분하지 않다. */
  private boolean isUpdatedAtIndexValid(String tableName) {
    Boolean valid =
        (Boolean)
            dsl.fetchValue(
                "select i.indisvalid from pg_class c"
                    + " join pg_namespace n on n.oid = c.relnamespace"
                    + " join pg_index i on i.indexrelid = c.oid"
                    + " where n.nspname = 'data' and c.relname = ?",
                "ix_" + tableName + "_upd");
    return Boolean.TRUE.equals(valid);
  }

  private OffsetDateTime fetchUpdatedAt(String tableName) {
    return (OffsetDateTime) dsl.fetchValue("SELECT _updated_at FROM data." + tableName);
  }

  /**
   * V124 마이그레이션 파일에서 백필 {@code DO $$ ... $$;} 블록 <b>하나만</b> 잘라 읽는다.
   *
   * <p>끝을 {@code "$$;"} 로 끊는 것이 핵심이다 — 파일 끝까지 가져오면 뒤따르는
   * {@code RESET lock_timeout;} 까지 붙어 <b>여러 문장</b>이 되고, JDBC 단순 질의는 그 묶음을 암묵
   * 트랜잭션으로 감싸므로 블록 안의 {@code COMMIT} 이 {@code invalid transaction termination} 으로
   * 실패한다. 그러면 이 테스트가 제품 결함이 아닌 이유로 빨개진다.
   */
  private String loadBackfillDoBlock() throws IOException {
    try (InputStream in =
        getClass().getResourceAsStream("/db/migration/V124__pipeline_incremental_backfill.sql")) {
      assertThat(in).as("V124 마이그레이션 파일을 클래스패스에서 찾지 못했다").isNotNull();
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      int idx = sql.indexOf("DO $$");
      assertThat(idx).as("V124 마이그레이션에서 백필 DO 블록을 찾지 못했다").isGreaterThanOrEqualTo(0);
      int end = sql.indexOf("$$;", idx + "DO $$".length());
      assertThat(end).as("백필 DO 블록의 끝($$;)을 찾지 못했다").isGreaterThan(idx);
      return sql.substring(idx, end + 2);
    }
  }
}
