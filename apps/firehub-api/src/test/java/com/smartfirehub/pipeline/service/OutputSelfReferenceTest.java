package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.service.OutputSelfReference.Plan;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * REPLACE 자기 출력 판정 표(WD-426) — 저장 검증(PipelineService)과 실행 방어(PipelineAsyncRunner)가 이 판정 하나를 쓰므로 경우
 * 전체를 여기서 고정한다.
 */
class OutputSelfReferenceTest {

  private static final Long OUT = 7L;

  private static SqlAccessResult access(Set<Long> reads, Set<Long> writes) {
    return new SqlAccessResult(true, null, null, null, reads, writes, true);
  }

  /** UPDATE/DELETE 판정 결과(파싱을 대신한다). */
  private static BooleanSupplier updateOrDelete(boolean value) {
    return () -> value;
  }

  /** 결과가 이 판정에 달리지 않는 경우 — 불리면(다시 파싱하면) 실패한다. */
  private static final BooleanSupplier MUST_NOT_PARSE =
      () -> {
        throw new AssertionError("UPDATE/DELETE 판정(파싱)이 필요 없는 경우다");
      };

  /** PG SELECT + REPLACE 가 출력을 읽으면 비우기 전 출력을 읽는 한 문장으로 바꿔 실행한다(거부하지 않는다). */
  @Test
  void pgSelectReplaceReadingOutput_reloadsFromOwnOutput() {
    Plan plan =
        OutputSelfReference.plan(true, false, access(Set.of(OUT), Set.of()), MUST_NOT_PARSE, OUT);
    assertThat(plan).isEqualTo(Plan.RELOAD_FROM_OWN_OUTPUT);
    assertThat(plan.rejected()).isFalse();
    assertThat(plan.message()).isNull();
  }

  /** Tibero SELECT + REPLACE 가 출력을 읽으면 그 형태가 없어 거부한다. */
  @Test
  void tiberoSelectReplaceReadingOutput_isRejected() {
    Plan plan =
        OutputSelfReference.plan(true, true, access(Set.of(OUT), Set.of()), MUST_NOT_PARSE, OUT);
    assertThat(plan).isEqualTo(Plan.REJECT_TIBERO);
    assertThat(plan.message()).startsWith("Tibero 저장소에서는 교체(REPLACE)");
  }

  /** 사용자 DML 이 출력을 읽으면(INSERT … SELECT FROM 출력) PG 는 DML 문구, Tibero 는 Tibero 문구로 거부한다. */
  @Test
  void userDmlReadingOutput_isRejected() {
    SqlAccessResult insertSelf = access(Set.of(OUT), Set.of(OUT));
    assertThat(OutputSelfReference.plan(false, false, insertSelf, MUST_NOT_PARSE, OUT))
        .isEqualTo(Plan.REJECT_USER_DML);
    assertThat(OutputSelfReference.plan(false, true, insertSelf, MUST_NOT_PARSE, OUT))
        .isEqualTo(Plan.REJECT_TIBERO);
    assertThat(Plan.REJECT_USER_DML.message()).contains("SELECT 문으로 바꾸세요");
  }

  /** UPDATE/DELETE 로 출력을 고치면 거부하고, 출력을 INSERT 대상으로만 쓰면(비운 뒤 채움 — 정상 사용법) 통과한다. */
  @Test
  void userDmlWritingOutput_rejectedOnlyForUpdateOrDelete() {
    SqlAccessResult writesOutput = access(Set.of(99L), Set.of(OUT));
    assertThat(OutputSelfReference.plan(false, false, writesOutput, updateOrDelete(true), OUT))
        .isEqualTo(Plan.REJECT_USER_DML);
    assertThat(OutputSelfReference.plan(false, false, writesOutput, updateOrDelete(false), OUT))
        .isEqualTo(Plan.AS_IS);
  }

  /**
   * 출력이 없거나 출력을 참조하지 않으면 지금 경로 그대로다(REPLACE 가 아니면 호출자가 부르지 않는다). 출력에 쓰지 않는 사용자 DML 은 UPDATE/DELETE
   * 판정(파싱)을 하지 않는다.
   */
  @Test
  void noOutputOrNoReference_isAsIs() {
    SqlAccessResult readsOutput = access(Set.of(OUT), Set.of(OUT));
    assertThat(OutputSelfReference.plan(true, false, readsOutput, MUST_NOT_PARSE, null))
        .isEqualTo(Plan.AS_IS);
    assertThat(
            OutputSelfReference.plan(true, true, access(Set.of(1L), Set.of()), MUST_NOT_PARSE, OUT))
        .isEqualTo(Plan.AS_IS);
    assertThat(
            OutputSelfReference.plan(
                false, false, access(Set.of(1L), Set.of(2L)), MUST_NOT_PARSE, OUT))
        .isEqualTo(Plan.AS_IS);
  }
}
