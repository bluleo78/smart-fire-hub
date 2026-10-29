package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 출력 비우기 문장 형태(WD-426 의 잠금 전용·자기 출력 다시 채우기 포함)와 잠금 키. 실행은 SqlScriptExecutorSandboxTest·
 * SqlScriptExecutorOutputLockTest 가 실제 DB 로 확인한다.
 */
class OutputClearStatementTest {

  @BeforeEach
  void setTenant() {
    // 기본 테넌트(1) — 스키마는 data 다.
    TenantContext.set(1L);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  /** 잠금 전용 문장은 전체 비우기 뒤에 정확히 " WHERE false" 만 붙인 형태이고, 잠금 키가 전체 비우기와 같다(두 실행이 서로 배타). */
  @Test
  void lockOnly_hasExactSuffixAndSameLockKeyAsDeleteAll() {
    assertThat(OutputClearStatement.lockOnly("out_1"))
        .isEqualTo("DELETE FROM \"data\".\"out_1\" WHERE false");
    assertThat(SqlScriptExecutor.outputLockKey(OutputClearStatement.lockOnly("out_1")))
        .isEqualTo("\"data\".\"out_1\"")
        .isEqualTo(SqlScriptExecutor.outputLockKey(OutputClearStatement.deleteAll("out_1")));
    // 실행기가 쓰는 판별·접미사 떼기 — 접미사는 끝에 정확히 붙었을 때 한 번만 뗀다.
    assertThat(OutputClearStatement.isLockOnly(OutputClearStatement.lockOnly("out_1"))).isTrue();
    assertThat(OutputClearStatement.isLockOnly(OutputClearStatement.deleteAll("out_1"))).isFalse();
    assertThat(OutputClearStatement.isLockOnly(null)).isFalse();
    assertThat(OutputClearStatement.stripLockOnly(OutputClearStatement.lockOnly("out_1")))
        .isEqualTo(OutputClearStatement.deleteAll("out_1"));
    assertThat(OutputClearStatement.stripLockOnly("x WHERE false WHERE false"))
        .isEqualTo("x WHERE false");
  }

  /**
   * 다시 채우기 문장 — 사용자 SELECT 를 자기 줄에 얹고(끝 주석·세미콜론은 걷는다) 비우기를 InitPlan 으로 참조해 INSERT 보다 먼저 끝나게 한다. 만든
   * 문장을 알아보면 출력 테이블과 걷어 낸 안쪽 SELECT 를 돌려준다.
   */
  @Test
  void reloadFromOwnOutput_shapeAndRoundTrip() {
    String sql =
        OutputClearStatement.reloadFromOwnOutput(
            "out_1", List.of("code", "name"), "SELECT code, name FROM data.\"out_1\" -- note\n;");

    assertThat(sql)
        .isEqualTo(
            "WITH \"_fh_self_src\" AS (\nSELECT code, name FROM data.\"out_1\"\n),"
                + " \"_fh_self_clear\" AS (DELETE FROM data.\"out_1\" RETURNING 1)"
                + " INSERT INTO data.\"out_1\" (\"code\", \"name\") SELECT * FROM \"_fh_self_src\""
                + " WHERE (SELECT count(*) FROM \"_fh_self_clear\") >= 0");
    assertThat(OutputClearStatement.reloadSourceOf(sql))
        .contains(
            new OutputClearStatement.ReloadParts("out_1", "SELECT code, name FROM data.\"out_1\""));
  }

  /** 정확한 형태가 아니면 알아보지 않는다 — 비우기·적재 대상이 다르거나, 다른 스키마거나, 꼬리에 무엇이 덧붙거나, 머리가 다르면 빈 값이다. */
  @Test
  void reloadSourceOf_rejectsAnythingButTheExactShape() {
    String ok = OutputClearStatement.reloadFromOwnOutput("out_1", List.of("a"), "SELECT 1 AS a");

    assertThat(OutputClearStatement.reloadSourceOf(ok)).isPresent();
    assertThat(OutputClearStatement.reloadSourceOf("INSERT INTO data.\"out_1\" (\"a\") SELECT 1"))
        .isEmpty();
    assertThat(
            OutputClearStatement.reloadSourceOf(
                ok.replace("INSERT INTO data.\"out_1\"", "INSERT INTO data.\"other\"")))
        .as("비우기 대상과 적재 대상이 다르다")
        .isEmpty();
    assertThat(OutputClearStatement.reloadSourceOf(ok.replace("data.", "public.")))
        .as("다른 스키마")
        .isEmpty();
    assertThat(OutputClearStatement.reloadSourceOf(ok.replace("data.", "data_t2.")))
        .as("다른 테넌트 스키마 — 꼬리 형태는 맞아도 현재 테넌트 스키마가 아니다")
        .isEmpty();
    assertThat(OutputClearStatement.reloadSourceOf(ok + "; DROP TABLE x")).isEmpty();
    assertThat(OutputClearStatement.reloadSourceOf(ok + "\n")).isEmpty();
    assertThat(OutputClearStatement.reloadSourceOf(ok.replace(" >= 0", " >= 0 OR true"))).isEmpty();
    assertThat(OutputClearStatement.reloadSourceOf(" " + ok)).isEmpty();
  }
}
