package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.user.repository.AccessContractBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * PlatformSql 공용 조각(WD-707) — {@code dbNowPlusSeconds}·{@code likeIgnoreCase}·{@code
 * nullSafeEquals}·{@code nullSafeDistinct} 가 include·속성 치환·파라미터 이름까지 맞물려 PG 에서 jOOQ 판과 같은 의미를 내는지
 * 시험 전용 질의(PlatformSqlFragmentsTestMapper.xml)로 확인한다. Tibero 판은 {@code
 * TiberoBundle2SyntaxProbeLiveTest} 가 같은 질의로 확인한다.
 *
 * <p>왜 따로 시험하나: 조각을 쓰는 리포지토리는 이후 태스크에 생긴다 — 조각이 깨져도(속성 이름 오타, PG 형 해소 실패) 그때까지 아무 시험도 돌지 않는다.
 */
class PlatformSqlFragmentsTest extends AccessContractBase {

  @Autowired private DSLContext dsl;

  @Autowired
  @Qualifier("platformSqlSessionTemplate")
  private SqlSessionTemplate sql;

  /** 공용 조각 시험 질의 호출 도우미(Tibero 탐침과 공유). */
  private PlatformSqlFragmentQueries q() {
    return new PlatformSqlFragmentQueries(sql);
  }

  @Test
  @DisplayName("dbNowPlusSeconds 는 DB 현재 시각에 초를 더하고, 음수면 과거 시각이 된다")
  void dbNowPlusSeconds_addsSecondsIncludingNegative() {
    assertThat(q().laterMinusNowMillis(90)).isEqualTo(90_000L);
    assertThat(q().laterMinusNowMillis(0)).isZero();
    // 이미 만료된 임대 경계 재현용 — 음수 초.
    assertThat(q().laterMinusNowMillis(-30)).isEqualTo(-30_000L);
  }

  @Test
  @DisplayName("likeIgnoreCase 는 영문 대소문자를 무시하고 한글을 그대로 찾는다")
  void likeIgnoreCase_ignoresCase() {
    assertThat(q().like("Monthly SALES Report", LikePatternUtils.containsPattern("sales")))
        .isEqualTo(1);
    assertThat(q().like("monthly sales", LikePatternUtils.containsPattern("SALES"))).isEqualTo(1);
    assertThat(q().like("월간 매출 Report", LikePatternUtils.containsPattern("매출 rep"))).isEqualTo(1);
    assertThat(q().like("월간 매입", LikePatternUtils.containsPattern("매출"))).isZero();
  }

  @Test
  @DisplayName("likeIgnoreCase 는 이스케이프한 %·_·역슬래시를 글자 그대로 찾는다")
  void likeIgnoreCase_escapedWildcardsAreLiteral() {
    assertThat(q().like("growth 50% up", LikePatternUtils.containsPattern("50%"))).isEqualTo(1);
    // 이스케이프 없으면 % 가 와일드카드라 맞았을 값 — 이스케이프한 패턴은 맞지 않아야 한다.
    assertThat(q().like("growth 500 up", LikePatternUtils.containsPattern("50%"))).isZero();
    assertThat(q().like("a_b", LikePatternUtils.containsPattern("a_b"))).isEqualTo(1);
    assertThat(q().like("axb", LikePatternUtils.containsPattern("a_b"))).isZero();
    assertThat(q().like("dir\\file", LikePatternUtils.containsPattern("r\\f"))).isEqualTo(1);
    assertThat(q().like("dirfile", LikePatternUtils.containsPattern("r\\f"))).isZero();
  }

  @Test
  @DisplayName("nullSafeEquals 는 같은 값·둘 다 NULL 이면 참, 한쪽만 NULL 이거나 값이 다르면 거짓")
  void nullSafeEquals_treatsNullsAsEqual() {
    assertThat(q().nullSafe("m1", "m1")).isEqualTo(1);
    assertThat(q().nullSafe(null, null)).isEqualTo(1);
    assertThat(q().nullSafe("m1", null)).isZero();
    assertThat(q().nullSafe(null, "m1")).isZero();
    assertThat(q().nullSafe("m1", "m2")).isZero();
  }

  @Test
  @DisplayName("nullSafeEquals 의 빈 문자열은 DB 마다 다르다 — PG 는 NULL 과 다르고, Tibero 는 NULL 로 바뀌어 같다")
  void nullSafeEquals_emptyStringFollowsDbRule() {
    assertThat(q().nullSafe("", null)).isEqualTo(emptyStringIsNull() ? 1 : 0);
    assertThat(q().nullSafe("", "")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "nullSafeDistinct 는 nullSafeEquals 의 정확한 반대 — 한쪽만 NULL 이면 참, 둘 다 NULL·같은 값이면 거짓(숫자 열·null 파라미터 포함)")
  void nullSafeDistinct_isExactNegation() {
    assertThat(q().nullSafeDistinct("m1", "m1")).isZero();
    assertThat(q().nullSafeDistinct(null, null)).isZero();
    assertThat(q().nullSafeDistinct("m1", null)).isEqualTo(1);
    assertThat(q().nullSafeDistinct(null, "m1")).isEqualTo(1);
    assertThat(q().nullSafeDistinct("m1", "m2")).isEqualTo(1);
    assertThat(q().nullSafeDistinct("", null)).isEqualTo(emptyStringIsNull() ? 0 : 1);
    assertThat(q().nullSafeDistinctNumber(1024, 1024)).isZero();
    assertThat(q().nullSafeDistinctNumber(null, null)).isZero();
    assertThat(q().nullSafeDistinctNumber(1024, null)).isEqualTo(1);
    assertThat(q().nullSafeDistinctNumber(null, 1024)).isEqualTo(1);
    assertThat(q().nullSafeDistinctNumber(1024, 768)).isEqualTo(1);
  }

  @Test
  @DisplayName("likeIgnoreCase 는 정책 테이블의 긴 설명 열에서도 대소문자를 무시하고 찾는다")
  void likeIgnoreCase_onDatasetDescription() {
    long t = fx.createTenant();
    long ds = fx.createDataset(t, "조각 시험 " + prefix);
    String description = "가".repeat(3000) + " Tail MARKER 끝";
    fx.inTenant(
        t, () -> dsl.execute("update dataset set description = ? where id = ?", description, ds));
    assertThat(descriptionLike(t, ds, LikePatternUtils.containsPattern("tail marker")))
        .isEqualTo(1);
    assertThat(descriptionLike(t, ds, LikePatternUtils.containsPattern("tail_marker"))).isZero();
  }

  @Test
  @DisplayName("데이터셋·카테고리·컬럼 픽스처가 그 테넌트 행을 만들고, 같은 이름의 데이터셋 둘도 만들 수 있다")
  void datasetFixtures_createRowsInTenant() {
    long t = fx.createTenant();
    long cat = fx.createCategory(t, "카테고리 " + prefix);
    long ds1 = fx.createDataset(t, "Same 이름 50%_x");
    long ds2 = fx.createDataset(t, "Same 이름 50%_x");
    long col = fx.createColumn(ds1, "c_one", 1);
    fx.inTenant(
        t,
        () -> {
          assertThat(
                  dsl.fetchValue(
                      "select count(*) from dataset where id in (?, ?) and name = ?",
                      ds1,
                      ds2,
                      "Same 이름 50%_x"))
              .hasToString("2");
          assertThat(dsl.fetchValue("select tenant_id from dataset_category where id = ?", cat))
              .hasToString(String.valueOf(t));
          assertThat(
                  dsl.fetchValue(
                      "select dataset_id || ':' || column_order || ':' || tenant_id"
                          + " from dataset_column where id = ?",
                      col))
              .hasToString(ds1 + ":1:" + t);
          return null;
        });
  }

  private int descriptionLike(long tenantId, long datasetId, String pattern) {
    return fx.inTenant(tenantId, () -> q().datasetDescriptionLike(datasetId, pattern));
  }
}
