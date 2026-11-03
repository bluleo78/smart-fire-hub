package com.smartfirehub.global.platformdb.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.DbErrorDetail;
import com.smartfirehub.global.platformdb.PlatformDbErrors;
import com.smartfirehub.global.platformdb.PlatformDbErrors.IntegrityViolation;
import com.smartfirehub.support.IntegrationTestBase;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PostgresPlatformDbErrors 의 계약(WD-486) — 실제 PG 에서 제약 위반을 일으켜 얻은 예외로 판정한다. 임시 테이블은 커넥션 하나에만 보이고
 * 커넥션을 닫으면 사라지므로 다른 테스트에 영향이 없다.
 */
class PostgresPlatformDbErrorsTest extends IntegrationTestBase {

  @Autowired private PlatformDbErrors errors;
  @Autowired private DataSource dataSource;

  /** 임시 테이블 위에서 SQL 들을 차례로 실행하고, 마지막 문장이 던진 SQLException 을 돌려준다. */
  private SQLException violate(String... statements) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement st = connection.createStatement()) {
      for (int i = 0; i < statements.length - 1; i++) {
        st.execute(statements[i]);
      }
      try {
        st.execute(statements[statements.length - 1]);
      } catch (SQLException e) {
        return e;
      }
      throw new AssertionError("위반이 일어나지 않았다");
    }
  }

  @Test
  @DisplayName("유일 제약 위반은 제약 이름을 돌려준다")
  void uniqueViolationReportsConstraint() throws SQLException {
    SQLException e =
        violate(
            "create temp table t_err_u (c int constraint t_err_u_c_key unique)",
            "insert into t_err_u values (1)",
            "insert into t_err_u values (1)");
    Throwable wrapped = new RuntimeException("outer", e);

    assertThat(errors.violatedConstraint(wrapped)).contains("t_err_u_c_key");
    assertThat(errors.violatesConstraintOnCauseChain(wrapped, "t_err_u_c_key")).isTrue();
    assertThat(errors.violatesConstraintOnCauseChain(wrapped, "other")).isFalse();
    assertThat(errors.findError(wrapped))
        .get()
        .extracting(DbErrorDetail::sqlState)
        .isEqualTo("23505");
  }

  @Test
  @DisplayName("nextException 에만 있는 오류는 violatedConstraint 만 찾고 getCause 전용 탐색은 비어 있다")
  void nextExceptionOnlyIsFoundByFullWalkNotByCauseWalk() throws SQLException {
    SQLException psql =
        violate(
            "create temp table t_err_n (c int constraint t_err_n_c_key unique)",
            "insert into t_err_n values (1)",
            "insert into t_err_n values (1)");
    BatchUpdateException batch = new BatchUpdateException("batch", new int[0]);
    batch.setNextException(psql);

    assertThat(errors.violatedConstraint(batch)).contains("t_err_n_c_key");
    assertThat(errors.violatesConstraintOnCauseChain(batch, "t_err_n_c_key")).isFalse();
  }

  @Test
  @DisplayName("바깥 PSQLException 이 다른 제약이어도 안쪽 원인이 대상 제약이면 true 다")
  void outerOtherConstraintInnerTargetStillMatches() throws SQLException {
    SQLException inner =
        violate(
            "create temp table t_err_i (c int constraint t_err_i_c_key unique)",
            "insert into t_err_i values (1)",
            "insert into t_err_i values (1)");
    SQLException outer =
        violate(
            "create temp table t_err_o (c int constraint t_err_o_c_key unique)",
            "insert into t_err_o values (1)",
            "insert into t_err_o values (1)");
    outer.initCause(inner);

    assertThat(errors.violatesConstraintOnCauseChain(outer, "t_err_i_c_key")).isTrue();
    assertThat(errors.violatesConstraintOnCauseChain(outer, "t_err_o_c_key")).isTrue();
  }

  @Test
  @DisplayName("NOT NULL 위반은 SQLState 23502 와 열 이름을 돌려준다")
  void notNullViolationReportsColumn() throws SQLException {
    SQLException e =
        violate(
            "create temp table t_err_nn (c int not null)", "insert into t_err_nn values (null)");

    DbErrorDetail detail = errors.findError(e).orElseThrow();

    assertThat(detail.sqlState()).isEqualTo("23502");
    assertThat(detail.column()).isEqualTo("c");
    assertThat(detail.message()).isNotBlank();
  }

  @Test
  @DisplayName("PG 서버 오류가 없는 체인은 모두 비어 있다")
  void noServerErrorIsEmpty() {
    Throwable plain = new RuntimeException("x", new SQLException("no server error"));

    assertThat(errors.findError(plain)).isEmpty();
    assertThat(errors.violatedConstraint(plain)).isEmpty();
    assertThat(errors.violatesConstraintOnCauseChain(plain, "x")).isFalse();
  }

  @Test
  @DisplayName("SQLState 42501 인 SQLException 만 행 보안 거부다")
  void rowSecurityDenied() {
    assertThat(errors.isRowSecurityDenied(new SQLException("denied", "42501"))).isTrue();
    assertThat(errors.isRowSecurityDenied(new SQLException("other", "23505"))).isFalse();
    assertThat(errors.isRowSecurityDenied(new RuntimeException("not sql"))).isFalse();
  }

  @Test
  @DisplayName("무결성 위반 분류 — 중복·체크·외래 키 방향")
  void classifyIntegrityViolation() throws SQLException {
    Throwable dup =
        new RuntimeException(
            "outer",
            violate(
                "create temp table t_cls_u (c int unique)",
                "insert into t_cls_u values (1)",
                "insert into t_cls_u values (1)"));
    Throwable check =
        new RuntimeException(
            "outer",
            violate(
                "create temp table t_cls_c (c int check (c > 0))",
                "insert into t_cls_c values (0)"));
    Throwable fkMissing =
        new RuntimeException(
            "outer",
            violate(
                "create temp table t_cls_p (id int primary key)",
                "create temp table t_cls_f (p int references t_cls_p(id))",
                "insert into t_cls_f values (7)"));
    Throwable fkInUse =
        new RuntimeException(
            "outer",
            violate(
                "create temp table t_cls_p2 (id int primary key)",
                "create temp table t_cls_f2 (p int references t_cls_p2(id))",
                "insert into t_cls_p2 values (1)",
                "insert into t_cls_f2 values (1)",
                "delete from t_cls_p2"));
    Throwable notNull =
        new RuntimeException(
            "outer",
            violate(
                "create temp table t_cls_n (c int not null)", "insert into t_cls_n values (null)"));

    assertThat(errors.classifyIntegrityViolation(dup)).isEqualTo(IntegrityViolation.DUPLICATE_KEY);
    assertThat(errors.classifyIntegrityViolation(check))
        .isEqualTo(IntegrityViolation.CHECK_CONSTRAINT);
    assertThat(errors.classifyIntegrityViolation(fkMissing))
        .isEqualTo(IntegrityViolation.FOREIGN_KEY_MISSING_TARGET);
    assertThat(errors.classifyIntegrityViolation(fkInUse))
        .isEqualTo(IntegrityViolation.FOREIGN_KEY_IN_USE);
    assertThat(errors.classifyIntegrityViolation(notNull))
        .isEqualTo(IntegrityViolation.UNCLASSIFIED);
    assertThat(errors.classifyIntegrityViolation(new RuntimeException("no cause")))
        .isEqualTo(IntegrityViolation.UNCLASSIFIED);
  }
}
