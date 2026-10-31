package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.util.AdhocResultValues;
import com.smartfirehub.global.util.AdhocSqlErrorMessages;
import com.smartfirehub.global.util.AdhocSqlStatements;
import com.smartfirehub.global.util.NormalizedSql;
import com.smartfirehub.global.util.ResultColumnNames;
import com.smartfirehub.global.util.SqlLexicalMask;
import com.smartfirehub.global.util.SqlValidationUtils;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.sql.TiberoQueryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DataTableQueryService {

  /** 데이터셋 화면에서 Tibero 테넌트의 데이터 변경 SQL 을 거부하는 문구 — 변경은 파이프라인(OWNER 계정)으로만 한다. */
  static final String TIBERO_DML_MESSAGE =
      "Tibero 저장소에서는 이 화면에서 데이터를 바꾸는 SQL 을 실행할 수 없습니다. 파이프라인을 이용해 주세요.";

  private final DSLContext dsl;

  /** Tibero 테넌트의 데이터셋 화면 SQL 실행 창구(WD-76) — Tibero 면 PG 세션 문장 없이 이것으로만 조회한다. */
  private final TiberoQueryService tiberoQueryService;

  /**
   * 데이터셋 애드혹 쿼리 전용 검증기 인스턴스 — {@link SqlValidator#forAdhocDataSchemaQueries()}로 직접 생성한다(팩터리 도입 근거는
   * R1, #385). 아래 {@code SET LOCAL search_path} 가 현재 테넌트의 데이터 스키마 <b>하나만</b> 세우는 것이 미한정(스키마 없는) 테이블
   * 참조 허용의 안전 전제다 — 두 스키마를 세우면 안 된다.
   */
  private final SqlValidator sqlValidator = SqlValidator.forAdhocDataSchemaQueries();

  /**
   * Execute user-supplied SQL against the data schema. Security: rejects DDL, multi-statement
   * input, and restricts search_path to data schema. Must be called within a @Transactional context
   * for SET LOCAL to be effective.
   */
  public SqlQueryResponse executeQuery(String sql, int maxRows) {
    // 테스트 전용 진입점 — 프로덕션 호출은 SqlGateArchitectureTest 가 거부한다(판정 없이 실행되므로).
    return executeQuery(NormalizedSql.of(sql), maxRows);
  }

  /**
   * 관문(GuardedSqlExecutor)이 정규화·판정을 마친 문자열을 그대로 실행한다. 여기서 다시 정규화하지 않는다 — 판정한 String 과 실행하는 String 이
   * 같은 인스턴스여야 한다(스펙 §4.1, {@link NormalizedSql} 참고). 실행 문자열 = 판정 문자열 + 최상위 행 제한(SELECT 만)이다.
   */
  public SqlQueryResponse executeQuery(NormalizedSql normalized, int maxRows) {
    String cleanSql = normalized.text();
    String queryType = SqlValidationUtils.detectQueryType(cleanSql);

    // Tibero 테넌트(WD-76): 데이터가 Tibero 에 있으므로 db-executor 읽기 전용 조회로만 실행한다. 아래 PG 검증기(data_t{id}
    // 허용목록)·search_path·SAVEPOINT 는 PG 세션 전용이라 그 앞에서 갈라 하나도 실행하지 않는다. PG 폴백은 없다.
    if (tiberoQueryService.isTibero()) {
      return executeOnTibero(cleanSql, queryType, maxRows);
    }

    // 부분문자열 대조(예: upperSql.contains("PUBLIC.")) 는 폐기했다 — PostgreSQL 이 허용하는 동등 표기
    // 변형(따옴표로 감싼 "public", 점 주변 공백 등)에 뚫린다(#385 R3 실측). AST 기반 스키마 화이트리스트가
    // 정본이며, 아래 SqlValidator 가 스키마 허용목록·차단 함수(set_config 등)를 함께 검사한다.
    //
    // 파싱 자체가 안 되는 문자열도 여기서 거부한다(폴백 없음) — "파싱 실패 시 DB 실행으로 넘긴다"는 대안은
    // 검토했으나 채택하지 않았다(#385 Task 2 판정과 동일한 이유). 그 폴백은 검증기 전체(스키마·차단 함수
    // 검사 포함)를 건너뛰므로, 공격자가 스키마 위반 쿼리에 JSqlParser 가 못 읽는(그러나 Postgres 는 실행하는)
    // 구문을 일부러 섞어 검증 자체를 우회할 새 경로가 된다 — "이 R3 변형들은 전부 파싱된다"는 관찰은 이
    // 순간의 테스트 케이스에만 성립하고 일반적으로 참임을 보장하지 못한다. 대가: 순수 오타(예: "FORM" 대신
    // "FROM")도 이제 error 필드가 아니라 예외로 거부되고, query_history 에 실패 이력이 남지 않는다
    // (DatasetDataService#executeQuery 가 이 예외를 잡지 않기 때문) — 이는 보안과 무관한 UX 회귀이지만
    // 검증 우회 가능성보다 낫다고 판단했다.
    sqlValidator.validate(cleanSql);

    // 위치 파라미터($1 …)는 실행 전에 원인 메시지로 거부한다(#753). 바인드 값이 없으니 서버가 Bind 단계에서
    // 08P01(프로토콜 위반)로 거부하고, Hikari 가 SQLSTATE 08 계열을 깨진 커넥션으로 보아 폐기해 이 요청의
    // 트랜잭션(query_history 저장 포함)까지 잃는다. savepoint 로는 막을 수 없으므로 SAVEPOINT 전에 거른다.
    String paramMessage = AdhocSqlStatements.positionalParameterMessage(cleanSql);
    if (paramMessage != null) {
      throw new SqlQueryException(paramMessage);
    }

    long startTime = System.currentTimeMillis();

    // 스키마명은 한 번만 해석해 설정과 복원(finally)이 반드시 같은 값을 쓰게 한다. 여기서 미리
    // 해석해 두면 테넌트 컨텍스트가 없을 때 finally 의 catch(ignored) 에 삼켜지지 않고 즉시 터진다.
    String schema = DataSchema.current();

    // Restrict search_path to data schema only — prevents access to public schema tables
    //
    // P3-b2 T4 — 무변경 판정(실측 근거). 인용된 단일 스키마 조립은 숫자 접미사가 붙어도
    // (data_t{id}) 그대로 해석된다 — data_t900000123 라는 한 이름으로 PostgreSQL 로 직접
    // 확인했다(psql SHOW search_path 프로브, 자릿수 자체는 해석에 영향 없음).
    // DataTableQueryServiceTenantSchemaTest 가 접미사 붙은 테넌트로 실제 프로덕션 경로를 재확인
    // 한다. 고치지 않는다.
    dsl.execute("SET LOCAL search_path = '" + schema + "'");
    dsl.execute("SET LOCAL statement_timeout = '30s'");

    // Use SAVEPOINT so that SQL errors don't abort the outer transaction.
    // PostgreSQL marks the entire transaction as aborted on any error,
    // preventing subsequent commands (like saving query history).
    // Rolling back to a savepoint clears the error state.
    dsl.execute("SAVEPOINT user_query");

    // 실제로 DB 에 보낸 SQL — 오류 문구에서 jOOQ 의 "SQL [실행 SQL]; " 메아리를 정확히 떼는 데 쓴다(WD-421).
    String executedSql = cleanSql;
    try {
      SqlQueryResponse response;
      if ("SELECT".equals(queryType)) {
        // 최상위 행 제한이 없거나 LIMIT ALL 이면 maxRows 를 적용한다(#749 — FETCH FIRST 도 사용자 제한으로
        // 존중하고, 서브쿼리·CTE·문자열 속 LIMIT 에는 속지 않는다). 애드혹 분석 경로와 같은 공용 판정.
        String limitedSql = SqlLexicalMask.applyRowLimit(cleanSql, maxRows);
        executedSql = limitedSql;

        // 정적 Statement 로 원문 그대로 실행한다(#753) — PreparedStatement 는 jsonb ? 연산자를 바인드
        // 자리로 오해한다. 실행 문자열 = 위에서 검증한 문자열 + 행 제한이다(AdhocSqlStatements 참고).
        var result = AdhocSqlStatements.fetch(dsl, limitedSql);
        long executionTimeMs = System.currentTimeMillis() - startTime;

        // Filter out system columns (id, import_id, created_at, _updated_at)
        List<String> allNames = new ArrayList<>(result.fields().length);
        for (var field : result.fields()) {
          allNames.add(field.getName());
        }
        List<Integer> visibleIndices = visibleColumnIndices(allNames, false);
        List<String> visibleNames = new ArrayList<>(visibleIndices.size());
        for (int i : visibleIndices) {
          visibleNames.add(allNames.get(i));
        }
        // 행을 이름 키 Map 으로 담으므로 JOIN 의 같은 이름 컬럼은 고유하게 바꾼다 — 안 그러면 앞 값이 덮인다(WD-85)
        List<String> columns = ResultColumnNames.unique(visibleNames);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var record : result) {
          Map<String, Object> row = new HashMap<>();
          for (int i = 0; i < visibleIndices.size(); i++) {
            // jsonb/json/xml/interval 등 Jackson 이 못 쓰는 값은 PG 텍스트로 바꾼다(#756)
            row.put(
                columns.get(i),
                AdhocResultValues.toResponseValue(record.get(visibleIndices.get(i))));
          }
          rows.add(row);
        }

        response =
            new SqlQueryResponse(queryType, columns, rows, rows.size(), executionTimeMs, null);
      } else {
        // DML: INSERT, UPDATE, DELETE
        int affectedRows = AdhocSqlStatements.execute(dsl, cleanSql);
        long executionTimeMs = System.currentTimeMillis() - startTime;
        response =
            new SqlQueryResponse(
                queryType, List.of(), List.of(), affectedRows, executionTimeMs, null);
      }
      dsl.execute("RELEASE SAVEPOINT user_query");
      return response;
    } catch (Exception e) {
      long executionTimeMs = System.currentTimeMillis() - startTime;
      // Rollback to savepoint to clear the aborted transaction state
      // 되돌리기마저 실패하면 원래 SQL 오류를 덮지 않도록 suppressed 로 붙여 원래 예외를 던진다(#753).
      AdhocSqlStatements.rollbackToSavepointOrRethrow(dsl, "user_query", e);
      // jOOQ 예외 메시지를 그대로 실으면 "SQL [<내부 행 제한까지 붙은 SQL>]; ERROR: …" 가 화면과 쿼리 이력에 드러난다(WD-421).
      // 분석 편집기와 같은 ERROR/SQLState/Position 포맷에 흔한 오류는 한국어 안내 한 줄을 앞에 붙인다. 이력은 이 문구를 그대로 저장한다.
      String errorMessage = AdhocSqlErrorMessages.formatForUser(e, executedSql);
      return new SqlQueryResponse(
          queryType, List.of(), List.of(), 0, executionTimeMs, errorMessage);
    } finally {
      // Restore search_path so subsequent operations in the same transaction
      // (e.g. QueryHistoryRepository.save) can access public schema tables
      // 복원을 빠뜨리거나 낡은 스키마명을 남기면, 같은 트랜잭션의 뒤 연산이 조용히 다른 스키마를
      // 본다. public 이 앞에 오는 기존 우선순위를 그대로 유지한다.
      //
      // P3-b2 T4 — 무변경 판정(실측 근거). 이 문장은 네 지점 중 유일하게 <b>인용이 없다</b>
      // (무인용 식별자 목록). data_t900000123 라는 한 이름으로 PostgreSQL 로 직접 확인한 결과
      // 무인용 식별자도 숫자로 시작하지 않는 한(스키마명은 항상 "data" 또는 "data_t"+숫자라
      // 첫 글자가 항상 문자다) 인용된 형태와 동일하게 해석된다(자릿수 자체는 해석에 영향 없음)
      // — 무인용 식별자는 소문자로 폴딩될 뿐인데 이 스키마명은 이미 전부 소문자다
      // (DataSchemaTenantResolutionTest.derivedNamesAreSafeUnquotedIdentifiers 가 그 전제를
      // 고정). DataTableQueryServiceTenantSchemaTest 가 이 복원 문장 <b>직후, 같은 트랜잭션
      // 안에서</b> public 스키마 테이블(query_history)에 저장이 성공하는지로 접미사 붙은
      // 테넌트에서도 이 복원이 실제로 실행됨을 확인한다(복원이 실패했다면 그 저장 자체가
      // 스키마를 못 찾아 실패했을 것이다). 고치지 않는다.
      try {
        dsl.execute("SET LOCAL search_path TO public, " + schema);
      } catch (Exception ignored) {
        // May fail if connection is broken; non-critical since transaction will end
      }
    }
  }

  /**
   * Tibero 테넌트의 데이터셋 화면 SQL 을 db-executor 읽기 전용 조회로 실행한다(WD-76).
   *
   * <p>데이터 변경 문장(INSERT·UPDATE·DELETE 등 SELECT 가 아닌 명시적 동사)은 실행기를 부르지 않고 이 화면 전용 문구로 거부한다 — PG 화면은
   * DML 을 실행하지만 Tibero 분석 경로는 읽기 전용 계정이라 변경이 불가능하고, 사용자에게는 "파이프라인을 쓰라"가 맞는 안내다. 첫 키워드로 종류를 가릴 수 없는
   * 문장(UNKNOWN — 괄호로 감싼 WITH 본문 등)은 여기서 단정하지 않고 TiberoQueryService 의 조회 전용 판정에 맡긴다(데이터를 바꾼다고 잘못
   * 안내하지 않게). 어느 쪽이든 조회가 아니면 실행되지 않는다.
   *
   * <p>결과에서는 PG 경로와 같이 시스템 컬럼(id·import_id·created_at·_updated_at)을 뺀다. db-executor 는 컬럼 이름을 소문자로
   * 돌려주지만, 대소문자에 기대지 않게 소문자로 접어 대조한다. 실패는 PG 경로와 같이 200 + error 다.
   */
  private SqlQueryResponse executeOnTibero(String sql, String queryType, int maxRows) {
    if (!"SELECT".equals(queryType) && !"UNKNOWN".equals(queryType)) {
      throw new SqlQueryException(TIBERO_DML_MESSAGE);
    }
    TiberoQueryService.Outcome out = tiberoQueryService.run(sql, maxRows);
    if (out.error() != null) {
      return new SqlQueryResponse(
          queryType, List.of(), List.of(), 0, out.executionTimeMs(), out.error());
    }
    List<Integer> visibleIndices = visibleColumnIndices(out.columns(), true);
    List<String> columns = new ArrayList<>(visibleIndices.size());
    for (int i : visibleIndices) {
      columns.add(out.columns().get(i));
    }
    // 시스템 컬럼이 없으면 행을 그대로 쓴다. 있으면 보이는 컬럼만 옮겨 담는다(시스템 컬럼 값이 응답 JSON 에 남지 않게).
    List<Map<String, Object>> rows;
    if (columns.size() == out.columns().size()) {
      rows = out.rows();
    } else {
      rows = new ArrayList<>(out.rows().size());
      for (Map<String, Object> source : out.rows()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String name : columns) {
          row.put(name, source.get(name));
        }
        rows.add(row);
      }
    }
    // affectedRows 는 PG SELECT 응답과 같이 행 수다(조회 이력의 "N행" 표시가 PG 와 같게).
    return new SqlQueryResponse("SELECT", columns, rows, rows.size(), out.executionTimeMs(), null);
  }

  /**
   * 시스템 컬럼(id·import_id·created_at·_updated_at)을 뺀 보이는 컬럼의 위치 목록(PG·Tibero 공용).
   *
   * @param caseFold true 면 소문자로 접어 대조한다(Tibero — 실행기가 이름을 소문자로 주지만 대소문자에 기대지 않는다). false 면 있는 그대로
   *     대조한다(PG 기존 동작).
   */
  private static List<Integer> visibleColumnIndices(List<String> names, boolean caseFold) {
    Set<String> systemColumns = DataTableService.SYSTEM_COLUMNS;
    List<Integer> visible = new ArrayList<>(names.size());
    for (int i = 0; i < names.size(); i++) {
      String name = caseFold ? names.get(i).toLowerCase(Locale.ROOT) : names.get(i);
      if (!systemColumns.contains(name)) {
        visible.add(i);
      }
    }
    return visible;
  }
}
