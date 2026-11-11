package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetDetailResponse.LinkedPipelineInfo;
import com.smartfirehub.dataset.dto.DatasetReferencesResponse.ReferenceItem;
import com.smartfirehub.dataset.dto.DatasetReferencesResponse.TriggerReferenceItem;
import com.smartfirehub.dataset.repository.DatasetReferenceRepository.DashboardHit;
import com.smartfirehub.dataset.repository.DatasetReferenceRepository.DashboardSqlCandidate;
import com.smartfirehub.dataset.repository.DatasetReferenceRepository.PipelineSqlCandidate;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DatasetReferenceRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-707). 파이프라인·대시보드·트리거 테이블은 모두 테넌트 정책 아래
 * 있다.
 *
 * <p>고정하는 것: 대시보드 가시성(본인 소유·공유만 visible, 남의 비공개는 visible=false 로 함께 나옴 — {@code
 * JooqAnalyticsDashboardRepository.visibleTo} 와 MyBatis 조각 {@code
 * DashboardVisibility.dashboardVisibleTo} 가 같은 결과), 리뷰 포인트 5(스크립트·저장 쿼리 본문의 대소문자 무시 포함 검색 —
 * 한글·{@code %}·{@code _}·{@code \} 이스케이프, Tibero CLOB·4000 바이트 넘는 본문), 트리거 config 의 datasetIds 배열
 * 포함 판정(부분 문자열 함정·문자열 원소·4000 바이트 넘는 배열), 다른 테넌트 차단.
 */
public abstract class DatasetReferenceRepositoryContractTest extends TenantPairContractBase {

  protected abstract DatasetReferenceRepository repository();

  private long pipeline(String name) {
    return fx.createPipeline(tenantA, prefix + name, true);
  }

  private String p(String name) {
    return prefix + name;
  }

  /** 저장 쿼리 → 차트 → 위젯으로 대시보드에 데이터셋(또는 SQL 본문)을 잇는다. */
  private void widget(long dashboardId, Long datasetId, String sqlText) {
    long q = fx.createSavedQuery(tenantA, datasetId, sqlText);
    fx.addWidget(dashboardId, fx.createChart(q));
  }

  /** 4000 바이트를 넘는 긴 한글 본문(Tibero VARCHAR2 4000 한도·CLOB 경로를 지나게 한다). */
  private static String longKoreanFiller() {
    String filler = "가나다라마바사아자차카타파하 ".repeat(400);
    assertThat(filler.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(4000);
    return filler;
  }

  @Test
  @DisplayName("findLinkedPipelines — 출력으로 쓰는 파이프라인을 중복 없이 활성 여부와 함께, 다른 데이터셋 출력은 빠진다")
  void findLinkedPipelines() {
    long ds = fx.createDataset(tenantA, p(" ds"));
    long other = fx.createDataset(tenantA, p(" other"));
    long active = pipeline(" active");
    long inactive = fx.createPipeline(tenantA, p(" inactive"), false);
    long unrelated = pipeline(" unrelated");
    // 한 파이프라인의 두 스텝이 같은 데이터셋을 출력해도 한 번만 나온다(DISTINCT).
    fx.createStep(active, "s1", "SQL", "select 1", ds, "REPLACE");
    fx.createStep(active, "s2", "SQL", "select 2", ds, "APPEND");
    fx.createStep(inactive, "s1", "SQL", null, ds, "REPLACE");
    fx.createStep(unrelated, "s1", "SQL", null, other, "REPLACE");

    assertThat(inA(() -> repository().findLinkedPipelines(ds)))
        .containsExactlyInAnyOrder(
            new LinkedPipelineInfo(active, p(" active"), true),
            new LinkedPipelineInfo(inactive, p(" inactive"), false));
    assertThat(inA(() -> repository().findLinkedPipelines(-1L))).isEmpty();
  }

  @Test
  @DisplayName("countStepsByOutputDataset — 출력 스텝 수(같은 파이프라인의 스텝도 각각), 입력 연결·다른 데이터셋은 세지 않는다")
  void countStepsByOutputDataset() {
    long ds = fx.createDataset(tenantA, p(" ds"));
    long other = fx.createDataset(tenantA, p(" other"));
    long p1 = pipeline(" p1");
    long p2 = pipeline(" p2");
    fx.createStep(p1, "s1", "SQL", null, ds, "REPLACE");
    fx.createStep(p1, "s2", "SQL", null, ds, "REPLACE");
    fx.createStep(p2, "s1", "SQL", null, ds, "MERGE");
    long inputOnly = fx.createStep(p2, "s2", "SQL", null, other, "REPLACE");
    fx.addStepInput(inputOnly, ds);

    assertThat(inA(() -> repository().countStepsByOutputDataset(ds))).isEqualTo(3);
    assertThat(inA(() -> repository().countStepsByOutputDataset(other))).isEqualTo(1);
    assertThat(inA(() -> repository().countStepsByOutputDataset(-1L))).isZero();
  }

  @Test
  @DisplayName("findPipelinesByFk — 출력·입력 어느 쪽이든 한 번씩 id 순, 무관한 파이프라인은 빠진다")
  void findPipelinesByFk() {
    long ds = fx.createDataset(tenantA, p(" ds"));
    long other = fx.createDataset(tenantA, p(" other"));
    long byOutput = pipeline(" out");
    long byInput = pipeline(" in");
    long byBoth = pipeline(" both");
    long unrelated = pipeline(" none");
    fx.createStep(byOutput, "s1", "SQL", null, ds, "REPLACE");
    fx.addStepInput(fx.createStep(byInput, "s1", "SQL", null, other, "REPLACE"), ds);
    long bothStep = fx.createStep(byBoth, "s1", "SQL", null, ds, "REPLACE");
    fx.addStepInput(bothStep, ds);
    fx.addStepInput(fx.createStep(byBoth, "s2", "SQL", null, null, "REPLACE"), ds);
    fx.addStepInput(fx.createStep(unrelated, "s1", "SQL", null, other, "REPLACE"), other);

    assertThat(inA(() -> repository().findPipelinesByFk(ds)))
        .containsExactly(
            new ReferenceItem(byOutput, p(" out")),
            new ReferenceItem(byInput, p(" in")),
            new ReferenceItem(byBoth, p(" both")));
  }

  @Test
  @DisplayName("findMergeOutputPipelines — MERGE 적재로 출력하는 파이프라인만 id 순, REPLACE·다른 데이터셋 MERGE 는 빠진다")
  void findMergeOutputPipelines() {
    long ds = fx.createDataset(tenantA, p(" ds"));
    long other = fx.createDataset(tenantA, p(" other"));
    long merge1 = pipeline(" m1");
    long replace = pipeline(" r");
    long merge2 = pipeline(" m2");
    long mergeOther = pipeline(" mo");
    fx.createStep(merge1, "s1", "SQL", null, ds, "MERGE");
    fx.createStep(merge1, "s2", "SQL", null, ds, "MERGE");
    fx.createStep(replace, "s1", "SQL", null, ds, "REPLACE");
    fx.createStep(merge2, "s1", "SQL", null, ds, "MERGE");
    fx.createStep(mergeOther, "s1", "SQL", null, other, "MERGE");

    assertThat(inA(() -> repository().findMergeOutputPipelines(ds)))
        .containsExactly(new ReferenceItem(merge1, p(" m1")), new ReferenceItem(merge2, p(" m2")));
  }

  @Test
  @DisplayName("findPipelineSqlCandidates — SQL 스텝 본문 대소문자 무시 포함, _ 는 와일드카드가 아니고 PYTHON 스텝은 빠진다")
  void findPipelineSqlCandidates_caseAndUnderscore() {
    long p1 = pipeline(" p1");
    long p2 = pipeline(" p2");
    String upper = "SELECT * FROM SALES_2024 s";
    String mixed = "select count(*) from Sales_2024";
    fx.createStep(p1, "s1", "SQL", upper, null, "REPLACE");
    // 같은 파이프라인의 두 스텝이 걸리면 스텝마다 한 행(DISTINCT 없음).
    fx.createStep(p1, "s2", "SQL", mixed, null, "REPLACE");
    // _ 가 와일드카드로 풀리면 salesX2024 도 걸린다 — 이스케이프돼야 빠진다.
    fx.createStep(p2, "s1", "SQL", "select * from salesX2024", null, "REPLACE");
    fx.createStep(p2, "s2", "PYTHON", "df = read('sales_2024')", null, "REPLACE");
    fx.createStep(p2, "s3", "SQL", null, null, "REPLACE");

    assertThat(inA(() -> repository().findPipelineSqlCandidates("sales_2024")))
        .containsExactlyInAnyOrder(
            new PipelineSqlCandidate(p1, p(" p1"), upper),
            new PipelineSqlCandidate(p1, p(" p1"), mixed));
  }

  @Test
  @DisplayName(
      "findPipelineSqlCandidates — 한글·%·\\ 는 글자 그대로, 4000 바이트 넘는 본문(Tibero CLOB) 끝의 이름도 찾는다")
  void findPipelineSqlCandidates_koreanPercentBackslashAndLongScript() {
    long p1 = pipeline(" p1");
    String korean = "select * from 매출_테이블 where 1=1";
    String longScript = longKoreanFiller() + "\nselect * from Tail_Marker_Tbl";
    String percent = "select '50%off' from t";
    String backslash = "select 'a\\b' from t";
    fx.createStep(p1, "s1", "SQL", korean, null, "REPLACE");
    fx.createStep(p1, "s2", "SQL", longScript, null, "REPLACE");
    fx.createStep(p1, "s3", "SQL", percent, null, "REPLACE");
    fx.createStep(p1, "s4", "SQL", "select '50xoff' from t", null, "REPLACE");
    fx.createStep(p1, "s5", "SQL", backslash, null, "REPLACE");
    fx.createStep(p1, "s6", "SQL", "select 'ab' from t", null, "REPLACE");

    assertThat(inA(() -> repository().findPipelineSqlCandidates("매출_테이블")))
        .containsExactly(new PipelineSqlCandidate(p1, p(" p1"), korean));
    // 긴 본문은 그대로(잘리지 않고) 돌아온다.
    assertThat(inA(() -> repository().findPipelineSqlCandidates("TAIL_MARKER_TBL")))
        .containsExactly(new PipelineSqlCandidate(p1, p(" p1"), longScript));
    assertThat(inA(() -> repository().findPipelineSqlCandidates("50%off")))
        .containsExactly(new PipelineSqlCandidate(p1, p(" p1"), percent));
    assertThat(inA(() -> repository().findPipelineSqlCandidates("a\\b")))
        .containsExactly(new PipelineSqlCandidate(p1, p(" p1"), backslash));
  }

  @Test
  @DisplayName(
      "findDashboardsByFk — 본인 소유·공유 대시보드는 visible, 남의 비공개는 visible=false 로 함께, 위젯이 여럿이어도 한 번")
  void findDashboardsByFk_visibility() {
    long viewer = fx.createUser(p("viewer"), null);
    long owner = fx.createUser(p("owner"), null);
    long ds = fx.createDataset(tenantA, p(" ds"));
    long other = fx.createDataset(tenantA, p(" other"));
    long mine = fx.createDashboard(tenantA, p(" mine"), viewer, false);
    long shared = fx.createDashboard(tenantA, p(" shared"), owner, true);
    long hidden = fx.createDashboard(tenantA, p(" hidden"), owner, false);
    long unrelated = fx.createDashboard(tenantA, p(" unrelated"), viewer, true);
    widget(mine, ds, "select 1");
    widget(mine, ds, "select 2");
    widget(shared, ds, "select 3");
    widget(hidden, ds, "select 4");
    widget(unrelated, other, "select 5");

    assertThat(inA(() -> repository().findDashboardsByFk(ds, viewer)))
        .containsExactlyInAnyOrder(
            new DashboardHit(mine, p(" mine"), true),
            new DashboardHit(shared, p(" shared"), true),
            new DashboardHit(hidden, p(" hidden"), false));
    // 소유자 시점에서는 둘 다 본인 것이라 보이고, viewer 의 비공개 대시보드는 가려진다.
    assertThat(inA(() -> repository().findDashboardsByFk(ds, owner)))
        .containsExactlyInAnyOrder(
            new DashboardHit(mine, p(" mine"), false),
            new DashboardHit(shared, p(" shared"), true),
            new DashboardHit(hidden, p(" hidden"), true));
  }

  @Test
  @DisplayName(
      "findDashboardSqlCandidates — 저장 쿼리 본문 대소문자 무시·이스케이프, 가시성 함께, 같은 본문 위젯은 한 번(긴 본문 DISTINCT 포함)")
  void findDashboardSqlCandidates() {
    long viewer = fx.createUser(p("viewer"), null);
    long owner = fx.createUser(p("owner"), null);
    long mine = fx.createDashboard(tenantA, p(" mine"), viewer, false);
    long hidden = fx.createDashboard(tenantA, p(" hidden"), owner, false);
    long shared = fx.createDashboard(tenantA, p(" shared"), owner, true);
    String mineSql = "SELECT * FROM Sales_2024";
    String longSql = longKoreanFiller() + " select * from sales_2024";
    String hiddenSql = "select * from 매출 join SALES_2024 using (id)";
    // 같은 대시보드에 같은 본문의 위젯이 둘 → DISTINCT 로 한 행(4000 바이트 넘는 본문도).
    widget(mine, null, mineSql);
    widget(mine, null, mineSql);
    widget(shared, null, longSql);
    widget(shared, null, longSql);
    widget(hidden, null, hiddenSql);
    // _ 가 와일드카드로 풀리면 걸린다 — 빠져야 한다.
    widget(shared, null, "select * from salesX2024");

    assertThat(inA(() -> repository().findDashboardSqlCandidates("sales_2024", viewer)))
        .containsExactlyInAnyOrder(
            new DashboardSqlCandidate(new DashboardHit(mine, p(" mine"), true), mineSql),
            new DashboardSqlCandidate(new DashboardHit(shared, p(" shared"), true), longSql),
            new DashboardSqlCandidate(new DashboardHit(hidden, p(" hidden"), false), hiddenSql));
    assertThat(inA(() -> repository().findDashboardSqlCandidates("매출", viewer)))
        .containsExactly(
            new DashboardSqlCandidate(new DashboardHit(hidden, p(" hidden"), false), hiddenSql));
    assertThat(inA(() -> repository().findDashboardSqlCandidates("50%", viewer))).isEmpty();
  }

  @Test
  @DisplayName(
      "SQL 후보 검색 — 테이블 이름 앞뒤 공백은 지우고 찾는다(LikePatternUtils.containsPattern, WD-124) — 두 구현이 같다")
  void sqlCandidates_trimPaddedTableName() {
    long viewer = fx.createUser(p("viewer"), null);
    long p1 = pipeline(" p1");
    String script = "select * from SALES where 1=1";
    fx.createStep(p1, "s1", "SQL", script, null, "REPLACE");
    long mine = fx.createDashboard(tenantA, p(" mine"), viewer, false);
    String sql = "select * from Sales";
    widget(mine, null, sql);

    // 공백을 남기면 「 sales 」가 「from SALES where」에만 맞고 줄 끝의 「Sales」는 놓친다 — 지워야 둘 다 맞는다.
    assertThat(inA(() -> repository().findPipelineSqlCandidates(" sales ")))
        .containsExactly(new PipelineSqlCandidate(p1, p(" p1"), script));
    assertThat(inA(() -> repository().findDashboardSqlCandidates(" sales ", viewer)))
        .containsExactly(new DashboardSqlCandidate(new DashboardHit(mine, p(" mine"), true), sql));
  }

  @Test
  @DisplayName(
      "findDatasetChangeTriggers — datasetIds 배열에 id 가 정확히 든 DATASET_CHANGE 트리거만 id 순(부분 문자열·문자열 원소·다른 유형 제외)")
  void findDatasetChangeTriggers_arrayContains() {
    long ds = fx.createDataset(tenantA, p(" ds"));
    long pl = pipeline(" pl");
    long id = ds;
    long t1 = fx.createTrigger(pl, "t1", "DATASET_CHANGE", "{\"datasetIds\":[" + id + ",7]}");
    // 공백이 낀 저장형도 맞는다.
    long t2 = fx.createTrigger(pl, "t2", "DATASET_CHANGE", "{\"datasetIds\": [ 8 , " + id + " ]}");
    // id 가 다른 id 의 부분 문자열일 뿐인 경우(앞·뒤에 숫자가 붙음)는 안 맞는다.
    fx.createTrigger(
        pl,
        "sub",
        "DATASET_CHANGE",
        "{\"datasetIds\":[1" + id + "," + id + "0," + id + "5" + id + "]}");
    // 문자열 원소 "id" 는 숫자 id 와 다르다(PG jsonb @> 와 같게).
    fx.createTrigger(pl, "str", "DATASET_CHANGE", "{\"datasetIds\":[\"" + id + "\"]}");
    // 쉼표가 낀 문자열 원소 "x,id,y" 도 안 맞는다(텍스트 쉼표 비교 C6 의 거짓 양성 함정).
    fx.createTrigger(pl, "comma", "DATASET_CHANGE", "{\"datasetIds\":[\"x," + id + ",y\"]}");
    // 다른 키·빈 배열·다른 유형은 빠진다.
    fx.createTrigger(pl, "otherKey", "DATASET_CHANGE", "{\"other\":[" + id + "]}");
    fx.createTrigger(pl, "empty", "DATASET_CHANGE", "{\"datasetIds\":[]}");
    fx.createTrigger(pl, "chain", "PIPELINE_CHAIN", "{\"datasetIds\":[" + id + "]}");

    assertThat(inA(() -> repository().findDatasetChangeTriggers(id)))
        .containsExactly(
            new TriggerReferenceItem(t1, "t1", pl, p(" pl")),
            new TriggerReferenceItem(t2, "t2", pl, p(" pl")));
    assertThat(inA(() -> repository().findDatasetChangeTriggers(7L)))
        .containsExactly(new TriggerReferenceItem(t1, "t1", pl, p(" pl")));
  }

  @Test
  @DisplayName(
      "findDatasetChangeTriggers — 4000 바이트 넘는 datasetIds 배열(끝에 id)도 맞고, 그 안의 부분 문자열 id 는 안 맞는다")
  void findDatasetChangeTriggers_longArray() {
    long ds = fx.createDataset(tenantA, p(" ds"));
    long pl = pipeline(" pl");
    StringJoiner ids = new StringJoiner(",", "{\"datasetIds\":[", "]}");
    for (int i = 0; i < 500; i++) {
      ids.add(String.valueOf(9_000_000_000L + i));
    }
    ids.add(String.valueOf(ds));
    String config = ids.toString();
    assertThat(config.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(4000);
    long longTrigger = fx.createTrigger(pl, "long", "DATASET_CHANGE", config);

    assertThat(inA(() -> repository().findDatasetChangeTriggers(ds)))
        .containsExactly(new TriggerReferenceItem(longTrigger, "long", pl, p(" pl")));
    // 마지막 원소 9000000499 의 부분 문자열(앞 9 를 뗀 값)은 원소가 아니다.
    assertThat(inA(() -> repository().findDatasetChangeTriggers(499L))).isEmpty();
    assertThat(inA(() -> repository().findDatasetChangeTriggers(9_000_000_250L)))
        .containsExactly(new TriggerReferenceItem(longTrigger, "long", pl, p(" pl")));
  }

  @Test
  @DisplayName(
      "findDatasetChangeTriggers — VARCHAR2 최대(65532 바이트)도 넘는 datasetIds 배열(끝에 id)도 맞는다 — 텍스트 길이 한도 없음")
  void findDatasetChangeTriggers_hugeArray() {
    long ds = fx.createDataset(tenantA, p(" ds"));
    long pl = pipeline(" pl");
    StringJoiner ids = new StringJoiner(",", "{\"datasetIds\":[", "]}");
    for (int i = 0; i < 7000; i++) {
      ids.add(String.valueOf(8_000_000_000L + i));
    }
    ids.add(String.valueOf(ds));
    String config = ids.toString();
    assertThat(config.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(65_532);
    long hugeTrigger = fx.createTrigger(pl, "huge", "DATASET_CHANGE", config);

    assertThat(inA(() -> repository().findDatasetChangeTriggers(ds)))
        .containsExactly(new TriggerReferenceItem(hugeTrigger, "huge", pl, p(" pl")));
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트에서는 A 의 파이프라인·대시보드·트리거 참조가 하나도 안 보인다")
  void otherTenantIsBlocked() {
    long viewer = fx.createUser(p("viewer"), null);
    long ds = fx.createDataset(tenantA, p(" ds"));
    long pl = pipeline(" pl");
    long step = fx.createStep(pl, "s1", "SQL", "select * from blocked_tbl", ds, "MERGE");
    fx.addStepInput(step, ds);
    fx.createTrigger(pl, "t", "DATASET_CHANGE", "{\"datasetIds\":[" + ds + "]}");
    long dash = fx.createDashboard(tenantA, p(" d"), viewer, true);
    widget(dash, ds, "select * from blocked_tbl");
    // A 에서는 다 보인다(시나리오가 실제로 행을 만든 것을 확인).
    assertThat(inA(() -> repository().countStepsByOutputDataset(ds))).isEqualTo(1);
    assertThat(inA(() -> repository().findDashboardsByFk(ds, viewer))).hasSize(1);

    Supplier<List<?>> all =
        () ->
            List.of(
                repository().findLinkedPipelines(ds),
                repository().findPipelinesByFk(ds),
                repository().findMergeOutputPipelines(ds),
                repository().findPipelineSqlCandidates("blocked_tbl"),
                repository().findDashboardsByFk(ds, viewer),
                repository().findDashboardSqlCandidates("blocked_tbl", viewer),
                repository().findDatasetChangeTriggers(ds));
    List<?> results = fx.inTenant(tenantB, all);
    for (Object r : results) {
      assertThat((List<?>) r).isEmpty();
    }
    assertThat(fx.inTenant(tenantB, () -> repository().countStepsByOutputDataset(ds))).isZero();
  }
}
