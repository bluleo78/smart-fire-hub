package com.smartfirehub.pipeline.service;

import com.smartfirehub.securitylevel.access.SqlAccessResult;
import java.util.function.BooleanSupplier;

/**
 * REPLACE SQL 스텝이 실행 때 비워질 자기 출력 테이블을 읽거나 고칠 때 어떻게 실행할지 정한다(WD-426).
 *
 * <p>REPLACE 는 출력 비우기({@code DELETE FROM 출력})를 사용자 SQL 과 같은 트랜잭션의 <b>선행 문장</b>으로
 * 보낸다(PipelineAsyncRunner — 원자성·출력 잠금 계약). 그래서 사용자 SQL 이 같은 출력을 읽으면 방금 비운 테이블을 읽어 늘 0행이고, 그러고도 스텝은
 * 완료로 끝나 기존 데이터가 조용히 사라졌다. 이제 경우마다 다르게 한다:
 *
 * <ul>
 *   <li>PG SELECT 스텝 — 비우기를 본 문장 안으로 옮겨 비우기 <b>전</b> 출력을 읽는다({@link
 *       OutputClearStatement#reloadFromOwnOutput}). 선행 문장은 출력 잠금만 잡는다({@link
 *       OutputClearStatement#lockOnly}).
 *   <li>사용자 DML(INSERT·UPDATE·DELETE) — 사용자 문장을 고쳐 쓸 수 없으므로 거부한다. 출력을 INSERT 대상으로만 쓰는 것은 정상(비운 뒤
 *       채움)이고, 출력을 읽거나({@code INSERT INTO 출력 SELECT … FROM 출력} 포함) UPDATE/DELETE 로 고칠 때만 거부한다. CTE
 *       안에 숨긴 UPDATE/DELETE({@code WITH x AS (UPDATE 출력 …) …})는 이 판정까지 오지 못한다 — 검증기(JSqlParser
 *       5.0)가 데이터 변경 CTE 를 파싱하지 못해 저장·실행 검증에서 먼저 거부된다(SqlValidatorReferencedTablesTest 가 고정).
 *   <li>Tibero — db-executor 선행문 규칙이 잠금 전용 형태를 받지 않고 Tibero 판 한 문장 형태도 없어 SELECT·DML 모두 거부한다.
 * </ul>
 *
 * <p>증분 스텝의 전체 재생성(APPEND/MERGE 출력 비우기)은 이 판정 대상이 아니다 — 비운 뒤 읽는 지금 동작이 이미 있는 행을 거르는 NOT IN·NOT
 * EXISTS 중복 제거 패턴에 맞다(처음부터 다시 채운다). 화면의 예약 확인 창이 그 동작을 안내한다.
 *
 * <p>판정은 테이블 이름이 아니라 보안 가드가 SQL 참조를 데이터셋으로 풀어 낸 id 로 한다 — PG(data_t{id}, 소문자)·Tibero(GENIA_T{id},
 * 대문자)의 이름 규칙을 가드가 이미 맞춰 준다. 선언 입력(inputDatasetIds)은 보지 않는다 — SQL 이 실제로 읽지 않으면 비워도 잃는 것이 없다. 저장
 * 검증(PipelineService)과 실행 방어(PipelineAsyncRunner)가 이 판정 하나를 쓴다 — 두 곳이 다른 규칙을 쓰면 한 경로로 다시 샌다.
 */
final class OutputSelfReference {

  /** REPLACE 사용자 DML 이 출력 자신을 읽거나 고칠 때(PG)의 거부 문구. 저장은 끝에 스텝 이름을, 실행은 마침표를 붙인다. */
  static final String USER_DML_MESSAGE =
      "교체(REPLACE) 스텝의 INSERT·UPDATE·DELETE 문은 출력 데이터셋 자신을 읽거나 고칠 수 없습니다 — 실행할 때 출력을 먼저 비운 뒤"
          + " 실행해 기존 데이터가 사라집니다. 출력을 읽어 다시 채우려면 SELECT 문으로 바꾸세요(비우기 전 데이터를 읽습니다). 아니면 적재 방식을"
          + " APPEND 로 바꾸거나 다른 출력 데이터셋을 지정하세요";

  /** Tibero 테넌트의 REPLACE 스텝(SELECT·DML)이 출력 자신을 읽거나 고칠 때의 거부 문구. */
  static final String TIBERO_MESSAGE =
      "Tibero 저장소에서는 교체(REPLACE) 스텝의 SQL 이 출력 데이터셋 자신을 읽거나 고칠 수 없습니다 — 실행할 때 출력을 먼저 비운 뒤"
          + " 실행해 기존 데이터가 사라집니다. 적재 방식을 MERGE(SELECT 문·기본 키 필요)나 APPEND 로 바꾸거나 다른 출력 데이터셋을 지정하세요";

  /** 실행 방식. 거부면 {@link #message()} 가 문구다. */
  enum Plan {
    /** 자기 출력을 읽거나 고치지 않는다(또는 REPLACE 가 아니거나 출력이 없다) — 지금 경로 그대로. */
    AS_IS(null),
    /** PG SELECT + REPLACE 가 출력 자신을 읽는다 — 잠금 전용 선행 문장 + 본 문장 안 비우기로 비우기 전 출력을 읽는다. */
    RELOAD_FROM_OWN_OUTPUT(null),
    /** PG 사용자 DML 이 출력 자신을 읽거나 고친다 — 거부. */
    REJECT_USER_DML(USER_DML_MESSAGE),
    /** Tibero 에서 출력 자신을 읽거나 고친다 — 거부. */
    REJECT_TIBERO(TIBERO_MESSAGE);

    private final String message;

    Plan(String message) {
      this.message = message;
    }

    /** 거부 문구(끝 구두점 없음) — 거부가 아니면 null. */
    String message() {
      return message;
    }

    boolean rejected() {
      return message != null;
    }
  }

  private OutputSelfReference() {}

  /**
   * REPLACE 스텝의 실행 방식을 정한다 — 호출자가 적재 방식이 REPLACE 일 때만 부른다(다른 방식은 출력을 비우지 않아 늘 그대로다). 저장 검증과 실행 방어가
   * 같은 조건으로 부르게 REPLACE 판정을 호출자 쪽에 둔다.
   *
   * @param isSelect 검증기가 조회문(SELECT)으로 판정했는가 — 아니면 사용자 DML
   * @param tibero Tibero 테넌트인가
   * @param access 사용자 SQL 만의 판정 결과(선언 입력을 합치기 전)
   * @param updateOrDelete 사용자 SQL 이 UPDATE/DELETE 인가({@code SqlValidator#isUpdateOrDelete}) — 쓰기
   *     집합에는 INSERT 대상도 들어 있어 둘을 가른다. SQL 을 다시 파싱하는 판정이라 결과가 이것에 달린 경우(SELECT 가 아니고 출력을 읽지는 않지만 출력에
   *     쓸 때)에만 묻는다
   * @param outputDatasetId 비울 출력 데이터셋 id — null 이면 비울 출력이 없어 {@link Plan#AS_IS}
   */
  static Plan plan(
      boolean isSelect,
      boolean tibero,
      SqlAccessResult access,
      BooleanSupplier updateOrDelete,
      Long outputDatasetId) {
    if (outputDatasetId == null) {
      return Plan.AS_IS;
    }
    boolean reads = access.readDatasetIds().contains(outputDatasetId);
    boolean rewrites =
        !reads
            && !isSelect
            && access.writeDatasetIds().contains(outputDatasetId)
            && updateOrDelete.getAsBoolean();
    if (!reads && !rewrites) {
      return Plan.AS_IS;
    }
    if (tibero) {
      return Plan.REJECT_TIBERO;
    }
    return isSelect ? Plan.RELOAD_FROM_OWN_OUTPUT : Plan.REJECT_USER_DML;
  }
}
