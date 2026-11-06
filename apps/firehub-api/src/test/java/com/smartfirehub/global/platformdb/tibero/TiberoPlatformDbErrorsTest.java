package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.global.platformdb.DbErrorDetail;
import com.smartfirehub.global.platformdb.PlatformDbErrors.IntegrityViolation;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link TiberoPlatformDbErrors} 판정 단위 테스트(WD-496) — 메시지·코드는 실서버 측정값을 그대로 옮겼다(2026-11-06, {@code
 * TiberoPlatformDbErrorsLiveTest} 가 실서버에서 같은 판정을 다시 확인한다).
 */
class TiberoPlatformDbErrorsTest {

  private final TiberoPlatformDbErrors errors = new TiberoPlatformDbErrors();

  private static SQLException tibero(int code, String body) {
    return new SQLIntegrityConstraintViolationException("JDBC" + code + ":" + body, "23000", code);
  }

  /** 스프링 번역기처럼 무결성 위반 예외로 감싼다. */
  private static DataIntegrityViolationException wrapped(SQLException e) {
    return new DataIntegrityViolationException("insert ...", e);
  }

  @Test
  void 유일_위반은_중복_키이고_제약_이름을_소문자로_꺼낸다() {
    var ex = wrapped(tibero(-10007, "UNIQUE constraint violation ('FHT_X'.'UQ_PARENT_NAME')."));

    assertThat(errors.classifyIntegrityViolation(ex)).isEqualTo(IntegrityViolation.DUPLICATE_KEY);
    assertThat(errors.violatedConstraint(ex)).contains("uq_parent_name");
    assertThat(errors.findError(ex)).map(DbErrorDetail::sqlState).contains("23505");
  }

  @Test
  void 다중_행_삽입_중복도_중복_키다() {
    var ex = wrapped(tibero(-4008, "duplicate in multi-row insert."));

    assertThat(errors.classifyIntegrityViolation(ex)).isEqualTo(IntegrityViolation.DUPLICATE_KEY);
    assertThat(errors.findError(ex)).map(DbErrorDetail::sqlState).contains("23505");
  }

  @Test
  void 외래_키는_코드로_방향을_가른다() {
    var missing =
        wrapped(
            tibero(
                -10008,
                "INTEGRITY constraint violation ('FHT_X'.'FK_CHILD_PARENT'): primary key not"
                    + " found."));
    var inUse =
        wrapped(
            tibero(
                -10009,
                "INTEGRITY constraint violation ('FHT_X'.'FK_CHILD_PARENT'): foreign key exists."));

    assertThat(errors.classifyIntegrityViolation(missing))
        .isEqualTo(IntegrityViolation.FOREIGN_KEY_MISSING_TARGET);
    assertThat(errors.classifyIntegrityViolation(inUse))
        .isEqualTo(IntegrityViolation.FOREIGN_KEY_IN_USE);
    assertThat(errors.findError(inUse)).map(DbErrorDetail::sqlState).contains("23503");
    assertThat(errors.violatedConstraint(inUse)).contains("fk_child_parent");
  }

  @Test
  void 체크_위반은_체크_제약이다() {
    var ex = wrapped(tibero(-10006, "CHECK constraint violation ('FHT_X'.'CK_PARENT_QTY')."));

    assertThat(errors.classifyIntegrityViolation(ex))
        .isEqualTo(IntegrityViolation.CHECK_CONSTRAINT);
    assertThat(errors.findError(ex)).map(DbErrorDetail::sqlState).contains("23514");
  }

  /** 전역 예외 처리기는 SQLState 23502 + 열 이름으로 "기본 보안 등급 없음" 을 가린다 — Tibero 에서도 같은 값이 나와야 한다. */
  @Test
  void NOT_NULL_위반은_23502_와_열_이름을_준다() {
    var ex =
        wrapped(
            tibero(
                -10005, "NOT NULL constraint violation ('FHT_X'.'DATASET'.'SECURITY_LEVEL_ID')."));

    DbErrorDetail detail = errors.findError(ex).orElseThrow();
    assertThat(detail.sqlState()).isEqualTo("23502");
    assertThat(detail.column()).isEqualTo("security_level_id");
    assertThat(detail.constraint()).isNull();
    assertThat(errors.classifyIntegrityViolation(ex)).isEqualTo(IntegrityViolation.UNCLASSIFIED);
  }

  @Test
  void 길이_초과는_22001_과_열_이름을_준다() {
    SQLException e =
        new SQLException(
            "JDBC-11048:\"FHT_X\".\"PARENT\".\"NAME\" The value is too large for the column."
                + " (Actual value: 16, Maximum value: 10)",
            "22001",
            -11048);

    DbErrorDetail detail = errors.findError(wrapped(e)).orElseThrow();
    assertThat(detail.sqlState()).isEqualTo("22001");
    assertThat(detail.column()).isEqualTo("name");
  }

  @Test
  void 잠금_실패는_55P03_이다() {
    SQLException nowait =
        new SQLException("JDBC-12033:Lock acquisition failed in NOWAIT mode.", "ERRFR", -12033);

    assertThat(errors.findError(nowait)).map(DbErrorDetail::sqlState).contains("55P03");
  }

  @Test
  void 원인_체인_어딘가의_제약_이름을_대소문자_무시로_비교한다() {
    var ex =
        new IllegalStateException(
            "outer",
            wrapped(
                tibero(
                    -10007,
                    "UNIQUE constraint violation ('FHT_X'.'IDX_ASYNC_JOB_ACTIVE_UNIQUE').")));

    assertThat(errors.violatesConstraintOnCauseChain(ex, "idx_async_job_active_unique")).isTrue();
    assertThat(errors.violatesConstraintOnCauseChain(ex, "other_constraint")).isFalse();
  }

  @Test
  void 연쇄_예외_getNextException_도_찾는다() {
    SQLException batch = new SQLException("batch failed", "23000", 0);
    batch.setNextException(
        tibero(-10007, "UNIQUE constraint violation ('FHT_X'.'UQ_PARENT_NAME')."));

    assertThat(errors.findError(new DataIntegrityViolationException("batch", batch)))
        .map(DbErrorDetail::constraint)
        .contains("uq_parent_name");
  }

  @Test
  void 순환_원인_체인에서도_끝난다() {
    RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);

    assertThat(errors.findError(a)).isEmpty();
    assertThat(errors.violatesConstraintOnCauseChain(a, "x")).isFalse();
    assertThat(errors.classifyIntegrityViolation(a)).isEqualTo(IntegrityViolation.UNCLASSIFIED);
  }

  @Test
  void Tibero_오류가_아니면_판정하지_않는다() {
    SQLException pgLike = new SQLException("ERROR: duplicate key value", "23505", 0);

    assertThat(errors.findError(wrapped(pgLike))).isEmpty();
    assertThat(errors.classifyIntegrityViolation(wrapped(pgLike)))
        .isEqualTo(IntegrityViolation.UNCLASSIFIED);
  }

  /**
   * 데이터 저장소 대응표({@link TiberoDialect})와 같은 측정값을 두 곳에 둔다 — 한쪽만 고쳐 어긋나지 않게 겹치는 코드는 여기서 묶어 확인한다(플랫폼
   * 전용인 외래 키·체크·잠금 코드는 저장소 표에 없다).
   */
  @Test
  void 겹치는_코드는_데이터_저장소_대응표와_같다() {
    assertThat(TiberoDialect.UNIQUE_VIOLATION).contains(TiberoPlatformDbErrors.UNIQUE_VIOLATION);
    assertThat(TiberoDialect.MULTI_INSERT_DUPLICATE)
        .contains(TiberoPlatformDbErrors.MULTI_INSERT_DUPLICATE);
    assertThat(TiberoDialect.NOT_NULL_INSERT).contains(TiberoPlatformDbErrors.NOT_NULL_VIOLATION);
    assertThat(TiberoDialect.VALUE_TOO_LARGE).contains(TiberoPlatformDbErrors.VALUE_TOO_LARGE);
  }

  /** Tibero 에는 PG RLS 가 없다 — 테넌트 격리는 WD-492 가 다른 방식으로 만든다. */
  @Test
  void 행_보안_거부는_언제나_아니다() {
    assertThat(errors.isRowSecurityDenied(new SQLException("x", "42501", 0))).isFalse();
  }
}
