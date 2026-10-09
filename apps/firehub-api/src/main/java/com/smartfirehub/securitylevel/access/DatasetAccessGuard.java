package com.smartfirehub.securitylevel.access;

import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.util.SqlValidationUtils;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.validator.PgLexicalAmbiguityCheck;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.ai.AiCall;
import com.smartfirehub.securitylevel.ai.AiCallContext;
import com.smartfirehub.securitylevel.ai.PolicyBlockedException;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository.AccessFacts;
import com.smartfirehub.securitylevel.service.SecurityAuditRecorder;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

  /** 거부 감사 기록기(보충 스펙 §3). 사용자 요청의 거부로 드러나는 require* 지점만 부른다 — 값 판정(check·checkSql)은 부르지 않는다. */
  private final SecurityAuditRecorder auditRecorder;

  /**
   * 현재 AI 문맥(S3 §4.3). 가시성 3-인자 조건·{@link #requireView(Clearance, long)}·대화형 {@link #checkSql} 이 이
   * 값을 읽어 AI(+SHARE) 정책을 함께 건다 — 호출처마다 AI 변형을 고르게 하면 하나만 빠져도 이름·행이 LLM 으로 샌다. 비AI 문맥(웹 JWT·배경
   * 작업)에서는 empty 라 기존 동작과 같다.
   */
  private final AiCallContext aiCallContext;

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

  /** 거부 상세 — 감사에만 쓴다. 응답·실행 이력에는 절대 싣지 않는다(존재 은닉, 스펙 §2.5). */
  public record DenialDetail(String reasonCode, Long datasetId, String tableName) {}

  /**
   * SQL 판정 결과 + 거부 상세 + (AI 차단이면) 상세 예외. {@link SqlAccessResult} 는 응답·실행 이력으로 나가므로 상세를 거기 싣지 않고 여기
   * 따로 둔다. 거부를 값으로 받았다가 나중에 던지는 호출부(애널리틱스 판정 토큰)와 {@link #requireSql} 이 차단 응답의 errors
   * 맵(action·levelName· policyKey)을 잃지 않게 blocked 를 함께 싣는다(흐름 A·B 통합 — 판정 결과 레코드는 이것 하나다).
   *
   * @param denial 거부 상세(감사 전용), 허용이면 null
   * @param blocked AI·공유 정책 차단이면 그 예외(result 는 같은 코드의 denied), 아니면 null
   */
  public record SqlVerdict(
      SqlAccessResult result, DenialDetail denial, PolicyBlockedException blocked) {

    /** AI 차단이 아닌 판정(허용·VIEW 계열·쓰기 하향 거부). */
    public SqlVerdict(SqlAccessResult result, DenialDetail denial) {
      this(result, denial, null);
    }
  }

  /**
   * 거부를 감사한다(보충 스펙 §3). 없는 데이터셋(LEVEL_UNKNOWN)은 거부가 아니라 "없음"이므로 남기지 않는다. 판정만 하는 값
   * 경로(check·checkSql) 는 이 메서드를 부르지 않는다 — 사용자 요청의 거부로 드러나는 지점(require*·게이트·실행기)만 부른다.
   */
  public void auditDenial(Clearance c, AccessDenialAction action, DenialDetail d) {
    if (d == null || "LEVEL_UNKNOWN".equals(d.reasonCode())) {
      return;
    }
    auditRecorder.recordDenial(c.userId(), action, d.reasonCode(), d.datasetId(), d.tableName());
  }

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
    AccessFacts f = accessRepository.findFactsByDatasetIds(List.of(datasetId), c).get(datasetId);
    // 없는 데이터셋은 LEVEL_UNKNOWN — auditDenial 이 "없음"으로 보고 남기지 않는다.
    Decision d =
        f == null
            ? Decision.deny("LEVEL_UNKNOWN", null, null)
            : decide(c, f, DatasetAction.VIEW, null);
    if (!d.allowed()) {
      // 실제 사유는 감사에만 남긴다 — 응답은 "없음"과 같은 404 그대로.
      auditDenial(c, AccessDenialAction.VIEW, new DenialDetail(d.reasonCode(), datasetId, null));
      throw new DatasetNotFoundException("Dataset not found: " + datasetId);
    }
    // AI 대행 요청·AI 범위면 VIEW 통과 후에만 AI(+SHARE) 판정(스펙 §4.3). 볼 수 없는 데이터셋은 위에서 404 로 존재를 숨기고(스펙
    // §2.5), 볼 수 있는 것만 등급 이름이 실린 403 POLICY_BLOCKED 가 된다.
    aiCallContext.current().ifPresent(ai -> requireAiFacts(c, f, ai));
  }

  /** 정책 차단 응답 코드(스펙 §4.3 계약). 흐름 A 의 AI 차단과 같은 코드라 병합 시 A 의 상수와 하나로 합친다(공통 결정 R6). */
  public static final String POLICY_BLOCKED_CODE = "POLICY_BLOCKED";

  /** 내보내기 엔드포인트 공통 권한 — exportAllowed 플래그는 이 권한까지 본다(UI 가 실제로 내려받을 수 있는가). */
  public static final String EXPORT_PERMISSION = "data:export";

  /** 쿼리 결과(여러 데이터셋) 내보내기 거부 문구 — 어느 데이터셋이 막혔는지 드러내지 않는다. */
  static final String EXPORT_MULTI_MESSAGE = "쿼리가 참조하는 데이터 중 보안 등급 정책상 내보낼 수 없는 데이터가 있습니다.";

  /** 현재 요청 사용자 기준 내보내기 강제. */
  public void requireExport(long datasetId) {
    requireExport(clearanceResolver.current(), datasetId);
  }

  /**
   * 내보내기 강제(스펙 §4.4). VIEW 거부는 존재 은닉 404(VIEW 로 감사), 정책 거부는 403 POLICY_BLOCKED(EXPORT 로 감사). 등급 이름은
   * VIEW 를 통과한 뒤에만 싣는다 — 사용자가 이미 볼 수 있는 정보다.
   *
   * <p>감사는 {@link SecurityAuditRecorder} 의 REQUIRES_NEW 로 남으므로 호출자 트랜잭션이 이 예외로 롤백돼도 거부 행은
   * 남는다(Review Focus 1).
   */
  public void requireExport(Clearance c, long datasetId) {
    AccessFacts f = accessRepository.findFactsByDatasetIds(List.of(datasetId), c).get(datasetId);
    if (f == null) {
      throw new DatasetNotFoundException("Dataset not found: " + datasetId);
    }
    Decision view = decide(c, f, DatasetAction.VIEW, null);
    if (!view.allowed()) {
      auditDenial(c, AccessDenialAction.VIEW, new DenialDetail(view.reasonCode(), datasetId, null));
      throw new DatasetNotFoundException("Dataset not found: " + datasetId);
    }
    Decision d = decide(c, f, DatasetAction.EXPORT, null);
    if (!d.allowed()) {
      auditDenial(
          c, AccessDenialAction.EXPORT, new DenialDetail(d.reasonCode(), datasetId, f.tableName()));
      String levelName = f.level().name();
      // 문구는 Global Constraints 바이트 지정 — PERMISSION 정책은 권한 안내, DENY 는 불가.
      String message =
          "EXPORT_PERMISSION_REQUIRED".equals(d.reasonCode())
              ? "'" + levelName + "' 등급 데이터를 내보내려면 제한 데이터 내보내기 권한이 필요합니다."
              : "'" + levelName + "' 등급 데이터는 내보낼 수 없습니다.";
      throw new CodedApiException(
          HttpStatus.FORBIDDEN,
          POLICY_BLOCKED_CODE,
          message,
          Map.of("action", "EXPORT", "levelName", levelName, "policyKey", "export_policy"));
    }
  }

  /**
   * 파일 다운로드(presigned attachment) 강제 — 내보내기 엔드포인트와 달리 {@code @RequirePermission("data:export")} 가
   * 없는 경로(dataset:read)라 data:export 권한을 여기서 먼저 본다. 권한이 없으면 인터셉터와 같은 403, 있으면 {@link
   * #requireExport(Clearance, long)}.
   */
  public void requireExportDownload(long datasetId) {
    Clearance c = clearanceResolver.current();
    if (!c.permissions().contains(EXPORT_PERMISSION)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Missing required permission: " + EXPORT_PERMISSION);
    }
    requireExport(c, datasetId);
  }

  /** 여러 데이터셋(쿼리 결과) 내보내기 강제 — 하나라도 막히면 403(등급 이름 없음, 첫 차단 데이터셋을 감사). VIEW 는 SQL 판정이 이미 했다. */
  public void requireExportAll(Clearance c, Collection<Long> datasetIds) {
    Map<Long, AccessFacts> facts =
        datasetIds.isEmpty() ? Map.of() : accessRepository.findFactsByDatasetIds(datasetIds, c);
    for (Long id : datasetIds) {
      AccessFacts f = facts.get(id);
      Decision d =
          f == null
              ? Decision.deny("LEVEL_UNKNOWN", null, null)
              : decide(c, f, DatasetAction.EXPORT, null);
      if (!d.allowed()) {
        auditDenial(
            c,
            AccessDenialAction.EXPORT,
            new DenialDetail(d.reasonCode(), id, f == null ? null : f.tableName()));
        throw new CodedApiException(
            HttpStatus.FORBIDDEN,
            POLICY_BLOCKED_CODE,
            EXPORT_MULTI_MESSAGE,
            Map.of("action", "EXPORT", "policyKey", "export_policy"));
      }
    }
  }

  /** 이미 VIEW 를 통과한 데이터셋(목록 행·상세)의 내보내기 가능 여부 — data:export 권한 AND 등급 export_policy. */
  public boolean exportAllowed(Clearance c, LevelPolicy level) {
    return level != null && exportAllowed(c, level.exportPolicy());
  }

  /**
   * 현재 요청 사용자(조회자) 기준 내보내기 가능 여부 — 응답 등급 요약의 export_policy 문자열로 판정한다. 등급 요약이 없으면
   * false(fail-closed). 웹이 다운로드 UI 를 숨기는 데 쓴다(UI 수준 — 서버 강제는 require*).
   */
  public boolean exportAllowedForCurrent(
      com.smartfirehub.securitylevel.dto.SecurityLevelSummary s) {
    if (s == null || s.exportPolicy() == null) {
      return false;
    }
    return exportAllowed(
        clearanceResolver.current(), LevelPolicy.ExportPolicy.valueOf(s.exportPolicy()));
  }

  /** 권한 AND 정책 — 위 두 공개 메서드의 공통 본문. */
  private static boolean exportAllowed(Clearance c, LevelPolicy.ExportPolicy p) {
    return c.permissions().contains(EXPORT_PERMISSION)
        && DatasetAccessPolicy.exportPolicyAllows(p, c.permissions());
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
    return visibleCondition(clearanceResolver.current());
  }

  /**
   * 명시 자격 + DatasetRepository 관례 이름. 요청 사용자가 아니라 서비스 인자 userId 로 자격을 계산하는 호출부(저장 쿼리 등)가 쓴다 — 조회 대상
   * 테이블이 {@code "dataset"} 이름으로 조인돼 있어야 한다.
   */
  public Condition visibleCondition(Clearance c) {
    return visibleCondition(
        c,
        field(name("dataset", "id"), Long.class),
        field(name("dataset", "security_level_id"), Long.class));
  }

  /**
   * 목록 쿼리용 조건 — {@link DatasetAccessPolicy#canView} 와 같은 규칙을 SQL 로 옮긴 것. 둘의 일치는
   * DatasetAccessGuardTest#visibleCondition_agreesWithPolicy_acrossMatrix 가 고정한다.
   */
  public Condition visibleCondition(
      Clearance c, Field<Long> datasetIdField, Field<Long> levelIdField) {
    // AI 대행 요청·AI 범위(배경)이면 이름·설명도 LLM 으로 가므로 AI(+SHARE) 정책까지 건다(스펙 §4.3 2행). 모든 가시성 호출처(목록·검색·
    // 스키마·태그·대시보드·저장 쿼리·검수·그래프 게이트)가 이 코어를 거치므로 한 곳의 훅으로 전부 덮인다. 비AI 문맥에서는 null 이라 기존 VIEW
    // 규칙과 같은 SQL 이다.
    return visibleCondition(c, datasetIdField, levelIdField, aiCallContext.current().orElse(null));
  }

  /**
   * 가시성 조건 + (AI 문맥이면) 등급의 ai_policy·share_policy 술어(스펙 §4.3). ai 가 null 이면 기존 VIEW 규칙 그대로다. {@link
   * DatasetAccessPolicy#aiAllowedForLevel}·{@link DatasetAccessPolicy#shareAllowedForLevel} 와의 일치는
   * DatasetAccessGuardTest#aiVisibleCondition_agreesWithPolicy_acrossHostingAndShare 가 고정한다.
   */
  Condition visibleCondition(
      Clearance c, Field<Long> datasetIdField, Field<Long> levelIdField, AiCall ai) {
    if (c.rank() == Clearance.NO_RANK) {
      return falseCondition();
    }
    var sl = SECURITY_LEVEL.as("vis_sl");
    Condition allowlistOk =
        sl.ALLOWLIST_REQUIRED
            .isFalse()
            .or(c.tenantAdmin() ? sl.ADMIN_BYPASS.isTrue() : falseCondition())
            .or(DatasetAccessRepository.onAllowlistCondition(c, datasetIdField));
    // AI 문맥이면 이름·설명까지 LLM 으로 가므로 목록 단계에서 ai_policy(+공유 목적이면 share_policy)로 거른다.
    Condition aiOk = ai == null ? noCondition() : aiPolicyAllows(sl.AI_POLICY, ai.hosting());
    Condition shareOk =
        ai != null && ai.share()
            ? sl.SHARE_POLICY.eq(LevelPolicy.SharePolicy.ALLOW.name())
            : noCondition();
    return exists(
        selectOne()
            .from(sl)
            .where(sl.ID.eq(levelIdField))
            .and(sl.RANK.le(c.rank()))
            .and(allowlistOk)
            .and(aiOk)
            .and(shareOk));
  }

  /**
   * ai_policy 컬럼 술어 — 사용자 없는 경로(임베딩 SQL)도 쓴다. {@link DatasetAccessPolicy#aiAllowedForLevel} 과 같은
   * 규칙(hosting null = 외부).
   */
  public static Condition aiPolicyAllows(Field<String> aiPolicyField, ProviderHosting hosting) {
    Condition all = aiPolicyField.eq(LevelPolicy.AiPolicy.ALL.name());
    return hosting == ProviderHosting.SELF_HOSTED
        ? all.or(aiPolicyField.eq(LevelPolicy.AiPolicy.SELF_HOSTED_ONLY.name()))
        : all;
  }

  /**
   * id 목록(AI_CLASSIFY 입력·SQL 읽기/쓰기 집합·온톨로지 출처)의 AI(+SHARE) 강제. 볼 수 없는 id 는 {@link
   * #requireDatasetReads} 와 같은 구분 불가 403(DATASET_SQL_ACCESS_DENIED), 볼 수 있으나 정책 위반이면 첫 위반의
   * POLICY_BLOCKED.
   */
  public void requireAiForDatasets(Clearance c, Collection<Long> datasetIds, AiCall call) {
    if (datasetIds.isEmpty()) {
      return;
    }
    if (datasetIds.stream().anyMatch(Objects::isNull)) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN, SQL_ACCESS_DENIED_CODE, SQL_ACCESS_DENIED_MESSAGE);
    }
    Map<Long, AccessFacts> facts = accessRepository.findFactsByDatasetIds(datasetIds, c);
    // VIEW 를 전부 먼저 확인한다 — 앞쪽 id 의 POLICY_BLOCKED(등급 이름)가 뒤쪽 숨김 id 의 거부보다 먼저 나가도 숨김 존재는 드러나지 않지만,
    // 응답이 입력 순서에 따라 달라지지 않게 VIEW 계열 거부를 우선한다.
    for (Long id : datasetIds) {
      AccessFacts f = facts.get(id);
      if (f == null || !decide(c, f, DatasetAction.VIEW, null).allowed()) {
        throw new CodedApiException(
            HttpStatus.FORBIDDEN, SQL_ACCESS_DENIED_CODE, SQL_ACCESS_DENIED_MESSAGE);
      }
    }
    for (Long id : datasetIds) {
      requireAiFacts(c, facts.get(id), call);
    }
  }

  /**
   * id 목록의 VIEW → AI(+SHARE) 강제 — {@link #requireAiForDatasets} 와 같되 VIEW 거부가 <b>존재하지 않는 데이터셋과 같은
   * 404</b>다 (단건 {@link #requireView} 와 같은 존재 은닉 계약을 쓰는 다건 입력 — 온톨로지 출처 등). 사실은 한 번에 읽는다(N+1 없음).
   *
   * <p>VIEW 를 전부 먼저 판정한다 — [볼 수 있으나 AI 불허, 숨김] 순서에서도 404 가 나가 응답이 입력 순서에 달라지지 않는다. 전부 볼 수 있을 때만 정책
   * 판정으로 넘어가므로 403 POLICY_BLOCKED 의 등급 이름은 이미 볼 수 있는 정보다. null id 는 호출부가 먼저 거른다.
   */
  public void requireViewThenAiForDatasets(Clearance c, Collection<Long> datasetIds, AiCall call) {
    if (datasetIds.isEmpty()) {
      return;
    }
    Map<Long, AccessFacts> facts = accessRepository.findFactsByDatasetIds(datasetIds, c);
    for (Long id : datasetIds) {
      AccessFacts f = facts.get(id);
      if (f == null || !decide(c, f, DatasetAction.VIEW, null).allowed()) {
        // 메시지는 requireView 의 404 와 바이트 단위로 같아야 한다(존재 은닉).
        throw new DatasetNotFoundException("Dataset not found: " + id);
      }
    }
    for (Long id : datasetIds) {
      requireAiFacts(c, facts.get(id), call);
    }
  }

  /**
   * id 목록이 전부 VIEW + AI(+SHARE) 허용인가 — 예외 없이 값으로 답한다. 거부를 오류 응답이 아니라 원문 가림으로 바꾸는 호출부(파이프라인 실행 기록의
   * 원문 오류·로그 공개 판정)가 쓴다. 없는 id·null id·볼 수 없는 id 는 불허(fail-closed), 빈 목록은 허용(LLM 으로 갈 데이터셋 값이 없다).
   */
  public boolean checkAiForDatasets(Clearance c, Collection<Long> datasetIds, AiCall call) {
    if (datasetIds.isEmpty()) {
      return true;
    }
    if (datasetIds.stream().anyMatch(Objects::isNull)) {
      return false;
    }
    Map<Long, AccessFacts> facts = accessRepository.findFactsByDatasetIds(datasetIds, c);
    for (Long id : datasetIds) {
      AccessFacts f = facts.get(id);
      if (f == null || !decide(c, f, DatasetAction.VIEW, null).allowed()) {
        return false;
      }
      if (!decide(c, f, DatasetAction.AI, call.hosting()).allowed()) {
        return false;
      }
      if (call.share() && !decide(c, f, DatasetAction.SHARE, null).allowed()) {
        return false;
      }
    }
    return true;
  }

  /** 이미 VIEW 를 통과한 사실에 AI·SHARE 를 판정한다. Decision 의 policyKey 를 그대로 싣는다. */
  void requireAiFacts(Clearance c, AccessFacts f, AiCall call) {
    Decision ai = decide(c, f, DatasetAction.AI, call.hosting());
    if (!ai.allowed()) {
      throw new PolicyBlockedException(DatasetAction.AI, f.level().name(), ai.policyKey());
    }
    if (call.share()) {
      Decision share = decide(c, f, DatasetAction.SHARE, null);
      if (!share.allowed()) {
        throw new PolicyBlockedException(DatasetAction.SHARE, f.level().name(), share.policyKey());
      }
    }
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
    return judgeDatasetReads(c, datasetIds).result();
  }

  /**
   * {@link #checkDatasetReads} 와 같은 판정 + 감사용 거부 상세. null·없는 id 는 LEVEL_UNKNOWN 상세라 {@link
   * #auditDenial} 이 남기지 않는다("없음"은 거부가 아니다).
   */
  private SqlVerdict judgeDatasetReads(Clearance c, Collection<Long> datasetIds) {
    if (datasetIds.stream().anyMatch(Objects::isNull)) {
      return deniedVerdict("LEVEL_UNKNOWN", null, null);
    }
    Map<Long, AccessFacts> facts =
        datasetIds.isEmpty() ? Map.of() : accessRepository.findFactsByDatasetIds(datasetIds, c);
    LevelPolicy effective = null;
    boolean exportAllowed = true;
    Set<Long> ids = new LinkedHashSet<>();
    for (Long id : datasetIds) {
      AccessFacts f = facts.get(id);
      if (f == null) {
        return deniedVerdict("LEVEL_UNKNOWN", id, null);
      }
      Decision d = decide(c, f, DatasetAction.VIEW, null);
      if (!d.allowed()) {
        return deniedVerdict(d.reasonCode(), id, null);
      }
      ids.add(id);
      if (effective == null || f.level().rank() > effective.rank()) {
        effective = f.level();
      }
      exportAllowed &= decide(c, f, DatasetAction.EXPORT, null).allowed();
    }
    return new SqlVerdict(
        new SqlAccessResult(true, null, null, effective, ids, Set.of(), exportAllowed), null);
  }

  /** {@link #checkDatasetReads} 를 강제한다 — 거부 시 403 {@link CodedApiException}(SQL 경로와 같은 코드·메시지). */
  public SqlAccessResult requireDatasetReads(Clearance c, Collection<Long> datasetIds) {
    SqlVerdict v = judgeDatasetReads(c, datasetIds);
    if (!v.result().allowed()) {
      auditDenial(c, AccessDenialAction.DATASET_REFS, v.denial());
      throw new CodedApiException(HttpStatus.FORBIDDEN, v.result().code(), v.result().message());
    }
    return v.result();
  }

  /**
   * SQL 참조 테이블 판정을 강제한다. 거부 시 403 {@link CodedApiException}(코드는 {@link SqlAccessResult#code()}). 파싱
   * 실패·빈 SQL·SELECT/DML 외 문장은 UnsafeSqlException(400) 이 그대로 올라간다.
   */
  public SqlAccessResult requireSql(Clearance c, String sql, SqlAccessMode mode) {
    SqlVerdict v = judgeSql(c, sql, mode);
    // AI 차단은 상세(action·levelName·policyKey)가 실린 원래 예외로 던진다 — 값 결과만으로는 errors 맵을 잃는다.
    if (v.blocked() != null) {
      throw v.blocked();
    }
    if (!v.result().allowed()) {
      // 대화형(애드혹·데이터셋 /query·메트릭)은 SQL, 파이프라인 저장·실행은 PIPELINE 으로 구분해 남긴다.
      auditDenial(
          c,
          mode == SqlAccessMode.INTERACTIVE ? AccessDenialAction.SQL : AccessDenialAction.PIPELINE,
          v.denial());
      throw new CodedApiException(HttpStatus.FORBIDDEN, v.result().code(), v.result().message());
    }
    return v.result();
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
    return judgeSql(c, sql, mode).result();
  }

  /**
   * {@link #checkSql} 과 같은 판정 + 감사용 거부 상세 + (AI 차단이면) 상세 예외. 규칙·순서·메시지는 checkSql Javadoc 그대로다 —
   * 응답으로 나가는 {@link SqlAccessResult} 는 거부 사유를 구분하지 않고, 상세(실제 사유·테이블·데이터셋)는 {@link DenialDetail} 에만
   * 싣는다.
   */
  public SqlVerdict judgeSql(Clearance c, String sql, SqlAccessMode mode) {
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
        return deniedVerdict("SQL_OTHER_SCHEMA", null, t.schema() + "." + t.name());
      }
    }
    for (SqlValidator.TableName t : refs.writes()) {
      if (!collect(t, dataSchema, mode, writeNames)) {
        return deniedVerdict("SQL_OTHER_SCHEMA", null, t.schema() + "." + t.name());
      }
    }

    // 2) 읽기 ∪ 쓰기 전부 VIEW 판정. 데이터셋에 매핑되지 않는 이름(f == null)도 같은 거부 — fail-closed.
    Set<String> all = new HashSet<>(readNames);
    all.addAll(writeNames);
    Map<String, AccessFacts> facts = accessRepository.findFactsByTableNames(all, c);
    for (String name : all) {
      AccessFacts f = facts.get(name);
      if (f == null) {
        return deniedVerdict("SQL_UNMAPPED_TABLE", null, name);
      }
      Decision d = decide(c, f, DatasetAction.VIEW, null);
      if (!d.allowed()) {
        return deniedVerdict(d.reasonCode(), f.datasetId(), name);
      }
    }

    // 2-1) AI 대행 대화형 SQL(MCP 쿼리·차트·대시보드 위젯): 결과가 LLM 으로 가므로 읽기·쓰기 대상 전부 AI(+SHARE). VIEW 를 전부 통과한
    //      뒤에만 오므로 숨김 테이블은 위의 구분 불가 거부가 먼저다(존재 은닉). 파이프라인 모드는 제외 — 파이프라인 SQL 은 LLM 으로 가지 않고,
    //      AI_CLASSIFY 는 분류 공급자 호스팅으로 호출부가 따로 판정한다.
    if (mode == SqlAccessMode.INTERACTIVE) {
      Optional<AiCall> ai = aiCallContext.current();
      if (ai.isPresent()) {
        for (String name : all) {
          try {
            requireAiFacts(c, facts.get(name), ai.get());
          } catch (PolicyBlockedException e) {
            return new SqlVerdict(
                SqlAccessResult.denied(PolicyBlockedException.CODE, e.getMessage()), null, e);
          }
        }
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
      // 4) 쓰기 하향 금지(스펙 §4.1)는 대화형 SQL 만. 파이프라인 실행은 게이트가 쓰기 대상을 자동 상향한다(§4.5), 저장은 VIEW 만
      //    본다(판단 사항 4). VIEW 를 모두 통과한 뒤에만 오는 분기라 메시지의 등급 이름은 사용자가 이미 볼 수 있는 정보다.
      if (mode == SqlAccessMode.INTERACTIVE
          && effective != null
          && f.level().rank() < effective.rank()) {
        return new SqlVerdict(
            SqlAccessResult.denied(
                SQL_WRITE_DOWNGRADE_CODE, "'" + effective.name() + "' 데이터를 더 낮은 등급 데이터셋에 쓸 수 없습니다"),
            new DenialDetail(SQL_WRITE_DOWNGRADE_CODE, f.datasetId(), name));
      }
    }
    return new SqlVerdict(
        new SqlAccessResult(true, null, null, effective, readIds, writeIds, exportAllowed), null);
  }

  /** VIEW 계열 거부 — 사유(숨김·매핑 없음·없는 테이블·다른 스키마)를 구분하지 않는 단일 결과. */
  private static SqlAccessResult accessDenied() {
    return SqlAccessResult.denied(SQL_ACCESS_DENIED_CODE, SQL_ACCESS_DENIED_MESSAGE);
  }

  /** VIEW 계열 거부 판정 — 응답은 구분 불가 단일 결과, 실제 사유·대상은 감사용 상세에만. */
  private static SqlVerdict deniedVerdict(String reason, Long datasetId, String tableName) {
    return new SqlVerdict(accessDenied(), new DenialDetail(reason, datasetId, tableName));
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
