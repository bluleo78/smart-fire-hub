package com.smartfirehub.securitylevel.access;

import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.selectOne;

import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.util.SqlValidationUtils;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.validator.PgLexicalAmbiguityCheck;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository.AccessFacts;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 데이터셋 열람 통제의 단일 진입점(스펙 §4.1). 판정은 {@link DatasetAccessPolicy} 에 위임하고, 이 클래스는 사실 조회와 결과 표현(404·SQL
 * 조각)만 맡는다.
 */
@Service
@RequiredArgsConstructor
public class DatasetAccessGuard {

  private final DatasetAccessRepository accessRepository;
  private final ClearanceResolver clearanceResolver;
  private final DSLContext dsl;

  /**
   * 참조 테이블 추출 전용 인스턴스 — 스프링 빈이 아니다. 스키마 허용 여부는 이 인스턴스가 아니라 {@link #checkSql} 이 테넌트 data 스키마와 대조해
   * 판정한다(추출은 스키마를 거부하지 않는다).
   */
  private final SqlValidator sqlParser = SqlValidator.forAdhocDataSchemaQueries();

  /** "볼 수 없음·데이터셋 아님·없음" 을 구분하지 않는 단일 메시지(존재 은닉, 스펙 §2.5). */
  public static final String SQL_ACCESS_DENIED_MESSAGE =
      "쿼리가 참조하는 테이블 중 열람할 수 없거나 확인할 수 없는 테이블이 있습니다.";

  /** VIEW 거부 코드 — 숨김·매핑 없음·없는 테이블·다른 스키마 모두 이 코드 하나다. 차트·대시보드가 거부 예외를 denied 로 바꿀 때도 쓴다. */
  public static final String SQL_ACCESS_DENIED_CODE = "DATASET_SQL_ACCESS_DENIED";

  /** 쓰기 하향 거부 코드(스펙 §4.1) — 읽기 집합보다 낮은 등급 데이터셋에 쓰는 SQL. */
  public static final String SQL_WRITE_DOWNGRADE_CODE = "SQL_WRITE_DOWNGRADE";

  /** 파이프라인 저장 검증이 {@code {{#N}}} 을 치환한 더미 테이블 이름(판단 사항 4). */
  private static final Pattern STEP_REF_PLACEHOLDER = Pattern.compile("step_ref_\\d+");

  /** 현재 요청 사용자 기준 VIEW 강제. */
  public void requireView(long datasetId) {
    requireView(clearanceResolver.current(), datasetId);
  }

  /**
   * VIEW 강제. 볼 수 없으면 <b>존재하지 않는 데이터셋과 같은</b> 404 를 던진다(스펙 §2.5 존재 은닉) — 메시지 형식은 기존
   * DatasetNotFoundException 사용처와 바이트 단위로 같아야 한다.
   *
   * <p>가드 메서드에는 @Transactional 을 두지 않는다(판단 사항 19): 트랜잭션은 리포지토리가 보장하고, 가드가 던지는 예외가 호출자의 바깥 트랜잭션을
   * rollback-only 로 만들지 않게 한다.
   */
  public void requireView(Clearance c, long datasetId) {
    if (!check(c, datasetId, DatasetAction.VIEW, null).allowed()) {
      throw new DatasetNotFoundException("Dataset not found: " + datasetId);
    }
  }

  /** 행위 판정. 데이터셋이 없으면 LEVEL_UNKNOWN 거부(존재 여부를 따로 드러내지 않는다). */
  public Decision check(
      Clearance c, long datasetId, DatasetAction action, ProviderHosting hosting) {
    AccessFacts f = accessRepository.findFactsByDatasetIds(List.of(datasetId), c).get(datasetId);
    if (f == null) {
      return Decision.deny("LEVEL_UNKNOWN", null, null);
    }
    return decide(c, f, action, hosting);
  }

  /** 이미 읽은 사실로 판정(SQL 경로가 여러 데이터셋을 한 번에 읽은 뒤 쓴다). */
  Decision decide(Clearance c, AccessFacts f, DatasetAction action, ProviderHosting hosting) {
    return DatasetAccessPolicy.decide(
        new AccessInput(
            c.rank(),
            f.onAllowlist(),
            c.tenantAdmin(),
            f.level(),
            action,
            c.permissions(),
            hosting));
  }

  /**
   * 현재 사용자 + DatasetRepository 관례({@code "dataset"."id"}, {@code "dataset"."security_level_id"}).
   */
  public Condition visibleCondition() {
    return visibleCondition(
        clearanceResolver.current(),
        field(name("dataset", "id"), Long.class),
        field(name("dataset", "security_level_id"), Long.class));
  }

  /**
   * 목록 쿼리용 조건 — {@link DatasetAccessPolicy#canView} 와 같은 규칙을 SQL 로 옮긴 것. 둘의 일치는
   * DatasetAccessGuardTest#visibleCondition_agreesWithPolicy_acrossMatrix 가 고정한다.
   */
  public Condition visibleCondition(
      Clearance c, Field<Long> datasetIdField, Field<Long> levelIdField) {
    if (c.rank() == Clearance.NO_RANK) {
      return falseCondition();
    }
    var sl = SECURITY_LEVEL.as("vis_sl");
    Condition allowlistOk =
        sl.ALLOWLIST_REQUIRED
            .isFalse()
            .or(c.tenantAdmin() ? sl.ADMIN_BYPASS.isTrue() : falseCondition())
            .or(DatasetAccessRepository.onAllowlistCondition(c, datasetIdField));
    return exists(
        selectOne()
            .from(sl)
            .where(sl.ID.eq(levelIdField))
            .and(sl.RANK.le(c.rank()))
            .and(allowlistOk));
  }

  /**
   * 문자열 SQL 을 조립하는 호출부(시맨틱 검색·스키마 조회)용. 값이 모두 long/boolean 이라 인라인 렌더링이 안전하다.
   *
   * @param datasetAlias 호출부 SQL 에서 dataset 테이블의 별칭(예: "d")
   */
  public String visibleSql(Clearance c, String datasetAlias) {
    return dsl.renderInlined(
        visibleCondition(
            c,
            field(name(datasetAlias, "id"), Long.class),
            field(name(datasetAlias, "security_level_id"), Long.class)));
  }

  /**
   * SQL 이 아니라 <b>데이터셋 id 목록</b>을 직접 읽는 실행 지점(AI_CLASSIFY 스텝 입력)의 판정 — {@link #checkSql} 의 읽기 집합 규칙과
   * 같다: 전부 VIEW 여야 하고, 실효 등급은 최대 rank, 내보내기 허용은 전부의 EXPORT. 없는 id·null id·볼 수 없는 id 는 같은 거부(SQL 경로와
   * 같은 코드·메시지 — 실행 이력에 남는 문구로 숨김 데이터셋 존재를 구분할 수 없게).
   *
   * @return 빈 목록이면 허용 + 실효 등급 null(전파할 등급 없음)
   */
  public SqlAccessResult checkDatasetReads(Clearance c, Collection<Long> datasetIds) {
    if (datasetIds.stream().anyMatch(Objects::isNull)) {
      return accessDenied();
    }
    Map<Long, AccessFacts> facts =
        datasetIds.isEmpty() ? Map.of() : accessRepository.findFactsByDatasetIds(datasetIds, c);
    LevelPolicy effective = null;
    boolean exportAllowed = true;
    Set<Long> ids = new LinkedHashSet<>();
    for (Long id : datasetIds) {
      AccessFacts f = facts.get(id);
      if (f == null || !decide(c, f, DatasetAction.VIEW, null).allowed()) {
        return accessDenied();
      }
      ids.add(id);
      if (effective == null || f.level().rank() > effective.rank()) {
        effective = f.level();
      }
      exportAllowed &= decide(c, f, DatasetAction.EXPORT, null).allowed();
    }
    return new SqlAccessResult(true, null, null, effective, ids, Set.of(), exportAllowed);
  }

  /** {@link #checkDatasetReads} 를 강제한다 — 거부 시 403 {@link CodedApiException}(SQL 경로와 같은 코드·메시지). */
  public SqlAccessResult requireDatasetReads(Clearance c, Collection<Long> datasetIds) {
    SqlAccessResult r = checkDatasetReads(c, datasetIds);
    if (!r.allowed()) {
      throw new CodedApiException(HttpStatus.FORBIDDEN, r.code(), r.message());
    }
    return r;
  }

  /**
   * SQL 참조 테이블 판정을 강제한다. 거부 시 403 {@link CodedApiException}(코드는 {@link SqlAccessResult#code()}). 파싱
   * 실패·빈 SQL·SELECT/DML 외 문장은 UnsafeSqlException(400) 이 그대로 올라간다.
   */
  public SqlAccessResult requireSql(Clearance c, String sql, SqlAccessMode mode) {
    SqlAccessResult r = checkSql(c, sql, mode);
    if (!r.allowed()) {
      throw new CodedApiException(HttpStatus.FORBIDDEN, r.code(), r.message());
    }
    return r;
  }

  /**
   * 참조 테이블 → 데이터셋 매핑 → 판정(스펙 §4.1). 거부를 값으로 돌려준다(차트가 {@code denied} 로 쓴다).
   *
   * <p>fail-closed: 다른 스키마, 데이터셋에 매핑되지 않는 data 스키마 테이블(stg_import_* 등), 없는 테이블은 전부 "볼 수 없음"과 같은
   * 코드·메시지다 — 응답으로 숨김 데이터셋의 존재를 추측할 수 없게(존재 은닉). 쓰기 대상(INSERT/UPDATE/DELETE)도 VIEW 를 요구한다 —
   * UPDATE/DELETE 대상은 WHERE·RETURNING 으로 읽히고, 쓰기만 허용하면 존재를 탐지하는 경로가 된다.
   *
   * <p>파싱(referencedTables)을 DB 접근보다 먼저 한다 — 파싱 예외를 잡아 계속 진행하는 호출자(차트·메트릭)의 트랜잭션이 오염되지 않게.
   *
   * <p><b>받은 문자열을 그대로 판정한다(주석 정규화 없음).</b> 정규식 주석 제거({@code SqlValidationUtils.stripAndValidate})는
   * 리터럴 안의 {@code /*}·{@code --} 를 주석으로 오인해 그 사이의 테이블 참조를 지웠다(실측 우회: {@code ... WHERE b = '/*' OR
   * EXISTS (SELECT 1 FROM hidden) OR b = '*}{@code /'}). 끝 세미콜론만 뗀다. 단 JSqlParser 의 어휘 규칙도 PG 와 완전히
   * 같지 않다 — 중첩 블록 주석, 백슬래시가 든 E 문자열, 태그 달러 인용 등에서 주석·문자열 경계를 다르게 자른다. 그래서 파싱 전에 받은 문자열 그대로 {@link
   * PgLexicalAmbiguityCheck#requireUnambiguous} 로 그런 표기를 거부한다 (fail-closed, 400). 멀티 스테이트먼트는 파서가
   * 거부한다.
   *
   * <p><b>전제(호출자 계약):</b> 호출자는 <b>실행할 바로 그 문자열</b>을 넘겨야 하고, 같은 문자열에 {@code SqlValidator.validate} 를
   * 실행해야 한다 — 다른 문자열을 실행하면 판정이 본 테이블과 실행되는 테이블이 달라질 수 있고, validate 없이 쓰면 함수·타입 경유 참조(query_to_xml
   * 등)를 못 본다.
   */
  public SqlAccessResult checkSql(Clearance c, String sql, SqlAccessMode mode) {
    if (sql == null || sql.isBlank()) {
      throw new UnsafeSqlException("SQL 이 비어 있습니다.");
    }
    // PG·JSqlParser 어휘가 갈리는 표기를 받은 문자열 그대로 먼저 거부한다(위 Javadoc).
    PgLexicalAmbiguityCheck.requireUnambiguous(sql);
    // 끝 세미콜론(뒤 공백 포함)만 뗀다 — 그 외 정규화는 판정 문자열과 실행 문자열을 어긋나게 한다(위 Javadoc).
    String exact = SqlValidationUtils.removeTrailingSemicolon(sql.strip());
    SqlValidator.ReferencedTables refs = sqlParser.referencedTables(exact);
    String dataSchema = DataSchema.current();

    // 1) 스키마 검사 + 이름 수집. 다른 스키마 참조는 매핑을 볼 것도 없이 거부.
    Set<String> readNames = new LinkedHashSet<>();
    Set<String> writeNames = new LinkedHashSet<>();
    for (SqlValidator.TableName t : refs.reads()) {
      if (!collect(t, dataSchema, mode, readNames)) {
        return accessDenied();
      }
    }
    for (SqlValidator.TableName t : refs.writes()) {
      if (!collect(t, dataSchema, mode, writeNames)) {
        return accessDenied();
      }
    }

    // 2) 읽기 ∪ 쓰기 전부 VIEW 판정. 데이터셋에 매핑되지 않는 이름(f == null)도 같은 거부 — fail-closed.
    Set<String> all = new HashSet<>(readNames);
    all.addAll(writeNames);
    Map<String, AccessFacts> facts = accessRepository.findFactsByTableNames(all, c);
    for (String name : all) {
      AccessFacts f = facts.get(name);
      if (f == null || !decide(c, f, DatasetAction.VIEW, null).allowed()) {
        return accessDenied();
      }
    }

    // 3) 실효 등급 = 읽기 집합의 최대 rank. 내보내기 허용은 읽기·쓰기 대상 전부의 EXPORT 판정 —
    //    UPDATE/DELETE ... RETURNING 은 쓰기 대상 행을 그대로 돌려주므로 쓰기 대상도 내보내기 판정에 넣는다.
    LevelPolicy effective = null;
    boolean exportAllowed = true;
    Set<Long> readIds = new LinkedHashSet<>();
    for (String name : readNames) {
      AccessFacts f = facts.get(name);
      readIds.add(f.datasetId());
      if (effective == null || f.level().rank() > effective.rank()) {
        effective = f.level();
      }
      exportAllowed &= decide(c, f, DatasetAction.EXPORT, null).allowed();
    }
    Set<Long> writeIds = new LinkedHashSet<>();
    for (String name : writeNames) {
      AccessFacts f = facts.get(name);
      writeIds.add(f.datasetId());
      exportAllowed &= decide(c, f, DatasetAction.EXPORT, null).allowed();
      // 4) 쓰기 하향 금지(스펙 §4.1): 쓰기 대상 rank ≥ 읽기 최대 rank. VIEW 를 모두 통과한 뒤에만 오는 분기라
      //    메시지의 등급 이름은 사용자가 이미 볼 수 있는 정보다. PIPELINE_SAVE 는 VIEW 만 본다(판단 사항 4).
      if (mode != SqlAccessMode.PIPELINE_SAVE
          && effective != null
          && f.level().rank() < effective.rank()) {
        return SqlAccessResult.denied(
            SQL_WRITE_DOWNGRADE_CODE, "'" + effective.name() + "' 데이터를 더 낮은 등급 데이터셋에 쓸 수 없습니다");
      }
    }
    return new SqlAccessResult(true, null, null, effective, readIds, writeIds, exportAllowed);
  }

  /** VIEW 계열 거부 — 사유(숨김·매핑 없음·없는 테이블·다른 스키마)를 구분하지 않는 단일 결과. */
  private static SqlAccessResult accessDenied() {
    return SqlAccessResult.denied(SQL_ACCESS_DENIED_CODE, SQL_ACCESS_DENIED_MESSAGE);
  }

  /**
   * 스키마 검사 + 이름 수집. 테넌트 data 스키마가 아닌 한정 이름이면 false(거부). 미한정 이름은 data 스키마 이름으로 보고 매핑 단계에 맡긴다 — 다른
   * 스키마로 해석될 미한정 이름(public 테이블 등)은 데이터셋 매핑이 없어 어차피 거부된다. PIPELINE_SAVE 의 step_ref 더미는 건너뛴다.
   */
  private static boolean collect(
      SqlValidator.TableName t, String dataSchema, SqlAccessMode mode, Set<String> out) {
    // 이름은 PG 폴딩이 끝난 값이라 바이트 비교한다 — "DATA_T1" 처럼 인용된 대문자 스키마는 PG 에서 다른 스키마다.
    if (t.schema() != null && !t.schema().equals(dataSchema)) {
      return false;
    }
    if (mode == SqlAccessMode.PIPELINE_SAVE && STEP_REF_PLACEHOLDER.matcher(t.name()).matches()) {
      return true;
    }
    out.add(t.name());
    return true;
  }
}
