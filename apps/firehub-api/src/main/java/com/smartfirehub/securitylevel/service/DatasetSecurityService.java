package com.smartfirehub.securitylevel.service;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.USER;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAction;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.AccessGrantResponse;
import com.smartfirehub.securitylevel.dto.AddAccessGrantRequest;
import com.smartfirehub.securitylevel.dto.ChangeDatasetLevelRequest;
import com.smartfirehub.securitylevel.dto.GrantCandidatesResponse;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 데이터셋 단위 보안 작업 — 등급 변경·허용 목록·상속·(S2) 파이프라인 출력 상향. 판정(볼 수 있는가)은 하지 않는다 — 호출 경로의 인터셉터·가드가 이미 했다. */
@Service
@RequiredArgsConstructor
public class DatasetSecurityService {

  private final DSLContext dsl;
  private final DatasetAccessGrantRepository grantRepository;
  private final SecurityAuditRecorder audit;
  private final SecurityLevelRepository levelRepository;
  private final DatasetAccessGuard guard;

  /**
   * clone 은 원본 등급을 상속하고 허용 목록을 복사한다(스펙 §4.2 2행, §4.5). 복사하지 않으면 허용 목록 필요 등급의 사본은 아무도 못 보는 고아가 된다.
   * 복제자는 원본을 볼 수 있었으므로 이미 목록에 있거나 역할로 충족한다.
   */
  @Transactional
  public void inheritFromSource(long sourceDatasetId, long newDatasetId, long actorUserId) {
    Long sourceLevel =
        dsl.select(DATASET.SECURITY_LEVEL_ID)
            .from(DATASET)
            .where(DATASET.ID.eq(sourceDatasetId))
            .fetchSingle(DATASET.SECURITY_LEVEL_ID);
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, sourceLevel)
        .where(DATASET.ID.eq(newDatasetId))
        .execute();
    int copied = grantRepository.copy(sourceDatasetId, newDatasetId, actorUserId);
    audit.record(
        actorUserId,
        "DATASET_SECURITY_LEVEL_CHANGE",
        "dataset",
        String.valueOf(newDatasetId),
        "복제 원본 등급 상속",
        Map.of(
            "sourceDatasetId", sourceDatasetId, "toLevelId", sourceLevel, "copiedGrants", copied));
  }

  /**
   * 러너 소유 TEMP 출력의 최소 전파(판단 사항 5): 입력 최대 등급으로 올리고 자동 상향 시각을 남긴다. 결과 등급이 허용 목록 필요면 {실행 주체}를 넣는다 —
   * 비우면 다음 스텝(같은 실행 주체가 {@code {{#N}}} 로 읽는다)부터 못 보고, 아무도 못 보는 고아가 된다(스펙 §4.5). 일반 출력의 교집합 시드·자동
   * 상향은 S4.
   *
   * <p>상향 여부(입력보다 낮은가)는 호출자(PipelineSecurityGate)가 판단한다 — 여기서는 지정 등급으로 옮기기만 한다.
   */
  @Transactional
  public void raiseForPipelineOutput(long datasetId, LevelPolicy toLevel, long runAsUserId) {
    Long fromId = currentLevelId(datasetId);
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, toLevel.id())
        .set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, LocalDateTime.now())
        .where(DATASET.ID.eq(datasetId))
        .execute();
    if (toLevel.allowlistRequired() && !grantRepository.existsUser(datasetId, runAsUserId)) {
      grantRepository.insertUser(datasetId, runAsUserId, runAsUserId);
    }
    audit.record(
        runAsUserId,
        "DATASET_SECURITY_LEVEL_AUTO_RAISE",
        "dataset",
        String.valueOf(datasetId),
        "파이프라인 입력 등급에 따른 자동 상향",
        Map.of("fromLevelId", fromId, "toLevelId", toLevel.id()));
  }

  /**
   * 러너가 이번 실행에서 새로 만든(빈) TEMP 출력의 등급을 입력 최대 등급으로 정확히 맞춘다(스펙 §4.5 — 기본 등급보다 낮아도). 상향이 아니므로 자동 상향 시각은
   * 남기지 않고 등급 변경 감사만 남긴다. 빈 테이블이라 낮춰도 노출되는 데이터가 없다 — 재사용 TEMP 에는 호출하지 않는다(호출자 책임).
   */
  @Transactional
  public void assignNewPipelineTempLevel(long datasetId, LevelPolicy level, long runAsUserId) {
    Long fromId = currentLevelId(datasetId);
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, level.id())
        .where(DATASET.ID.eq(datasetId))
        .execute();
    audit.record(
        runAsUserId,
        "DATASET_SECURITY_LEVEL_CHANGE",
        "dataset",
        String.valueOf(datasetId),
        "파이프라인 신규 임시 출력 등급 = 입력 최대 등급",
        Map.of("fromLevelId", fromId, "toLevelId", level.id()));
  }

  /**
   * 파이프라인 출력(러너 소유 TEMP)의 허용 목록에 실행 주체를 넣는다(멱등). 이미 있으면 아무것도 하지 않는다. 새로 넣을 때만 감사 {@code
   * DATASET_ACCESS_GRANT_ADD} 를 남긴다 — 실행마다 감사가 쌓이지 않게.
   */
  @Transactional
  public void seedPipelineOutputRunAs(long datasetId, long runAsUserId) {
    if (grantRepository.existsUser(datasetId, runAsUserId)) {
      return;
    }
    long id = grantRepository.insertUser(datasetId, runAsUserId, runAsUserId);
    audit.record(
        runAsUserId,
        "DATASET_ACCESS_GRANT_ADD",
        "dataset",
        String.valueOf(datasetId),
        "파이프라인 실행 주체 허용 목록 시드",
        Map.of("grantId", id, "type", "USER", "subjectId", runAsUserId));
  }

  /**
   * 등급 변경(스펙 §4.7): 본인 자격 초과 금지, 하향은 사유 필수, 허용 목록 필요 등급으로 갈 때 변경자가 볼 수 없게 되면 본인을 목록에 넣는다(판단 사항 11 —
   * 변경 직후 본인도 못 보는 잠김 방지).
   */
  @Transactional
  public void changeLevel(long datasetId, ChangeDatasetLevelRequest req, Clearance caller) {
    LevelPolicy to =
        levelRepository
            .findById(req.securityLevelId())
            .orElseThrow(
                () ->
                    new CodedApiException(
                        HttpStatus.BAD_REQUEST, "SECURITY_LEVEL_NOT_FOUND", "보안 등급을 찾을 수 없습니다."));
    if (to.rank() > caller.rank()) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN, "CLASSIFY_ABOVE_CLEARANCE", "본인 열람 등급보다 높은 등급으로 지정할 수 없습니다.");
    }
    LevelPolicy from = levelRepository.findById(currentLevelId(datasetId)).orElseThrow();
    String reason = req.reason() == null ? "" : req.reason().trim();
    if (to.rank() < from.rank() && reason.length() < SecurityLevelService.MIN_DOWNGRADE_REASON) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST, "DOWNGRADE_REASON_REQUIRED", "등급을 낮추려면 사유(10자 이상)가 필요합니다.");
    }
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, to.id())
        .where(DATASET.ID.eq(datasetId))
        .execute();
    // 허용 목록 필요 등급이면 (목록이 비었거나 본인이 못 보게 되는 경우) 본인을 시드한다(스펙 §2.4).
    // 관리자 우회(admin_bypass)로 보인다는 이유로 건너뛰면 목록이 빈 채로 남고, 우회를 끄는 순간 아무도 못 보는 고아가 된다.
    boolean seededSelf = false;
    if (to.allowlistRequired()
        && !grantRepository.existsUser(datasetId, caller.userId())
        && (grantRepository.countByDataset(datasetId) == 0
            || !guard.check(caller, datasetId, DatasetAction.VIEW, null).allowed())) {
      grantRepository.insertUser(datasetId, caller.userId(), caller.userId());
      seededSelf = true;
    }
    Map<String, Object> meta = new HashMap<>();
    meta.put("fromLevelId", from.id());
    meta.put("fromLevelName", from.name());
    meta.put("toLevelId", to.id());
    meta.put("toLevelName", to.name());
    meta.put("reason", reason.isEmpty() ? null : reason);
    meta.put("seededSelf", seededSelf);
    audit.record(
        caller.userId(),
        "DATASET_SECURITY_LEVEL_CHANGE",
        "dataset",
        String.valueOf(datasetId),
        from.name() + " → " + to.name(),
        meta);
  }

  /** 허용 목록 조회(카드 표시용). */
  @Transactional(readOnly = true)
  public List<AccessGrantResponse> listGrants(long datasetId) {
    return grantRepository.findByDataset(datasetId).stream()
        .map(
            g ->
                new AccessGrantResponse(
                    g.id(),
                    g.userId() != null ? "USER" : "ROLE",
                    g.userId() != null ? g.userId() : g.roleId(),
                    g.subjectName(),
                    g.grantedByName(),
                    g.grantedAt()))
        .toList();
  }

  /**
   * 허용 항목 추가. dataset_access_grant 의 user/role FK 는 테넌트 복합 FK 가 아니라서 DB 가 타 테넌트 대상을 막지 못한다 — 그래서
   * 사용자는 이 테넌트 ACTIVE 멤버, 역할은 이 테넌트(RLS) 역할인지 여기서 검증한다. 이미 있는 항목이면 새로 만들지 않고 기존 항목을 돌려준다(멱등, 감사
   * 없음).
   */
  @Transactional
  public AccessGrantResponse addGrant(long datasetId, AddAccessGrantRequest req, long actor) {
    boolean user = req.userId() != null;
    boolean role = req.roleId() != null;
    if (user == role) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST, "GRANT_SUBJECT_INVALID", "사용자 또는 역할 중 하나를 지정하세요.");
    }
    long tenantId = TenantContext.require("허용 목록 추가");
    if (user && !isActiveMember(req.userId(), tenantId)) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST, "GRANT_SUBJECT_INVALID", "이 워크스페이스의 활성 멤버가 아닙니다.");
    }
    if (role && !dsl.fetchExists(ROLE, ROLE.ID.eq(req.roleId()))) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST, "GRANT_SUBJECT_INVALID", "역할을 찾을 수 없습니다.");
    }
    long subjectId = user ? req.userId() : req.roleId();
    String type = user ? "USER" : "ROLE";
    var existing =
        listGrants(datasetId).stream()
            .filter(g -> g.type().equals(type) && g.subjectId() == subjectId)
            .findFirst();
    if (existing.isPresent()) {
      return existing.get();
    }
    long id =
        user
            ? grantRepository.insertUser(datasetId, subjectId, actor)
            : grantRepository.insertRole(datasetId, subjectId, actor);
    audit.record(
        actor,
        "DATASET_ACCESS_GRANT_ADD",
        "dataset",
        String.valueOf(datasetId),
        null,
        Map.of("grantId", id, "type", type, "subjectId", subjectId));
    return listGrants(datasetId).stream().filter(g -> g.id() == id).findFirst().orElseThrow();
  }

  /** 마지막 항목은 허용 목록 필요 등급에서 제거 불가(스펙 §2.4) — 아무도 못 보는 고아 방지. */
  @Transactional
  public void removeGrant(long datasetId, long grantId, long actor) {
    var grant =
        grantRepository
            .findById(grantId)
            .filter(g -> g.datasetId() == datasetId)
            .orElseThrow(
                () ->
                    new CodedApiException(
                        HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "허용 항목을 찾을 수 없습니다."));
    boolean allowlistLevel =
        levelRepository
            .findById(currentLevelId(datasetId))
            .map(LevelPolicy::allowlistRequired)
            .orElse(false);
    if (allowlistLevel && grantRepository.countByDataset(datasetId) <= 1) {
      throw new CodedApiException(
          HttpStatus.CONFLICT, "ALLOWLIST_LAST_ENTRY", "마지막 허용 목록 항목은 제거할 수 없습니다.");
    }
    grantRepository.delete(grantId);
    audit.record(
        actor,
        "DATASET_ACCESS_GRANT_REMOVE",
        "dataset",
        String.valueOf(datasetId),
        null,
        Map.of(
            "grantId",
            grantId,
            "type",
            grant.userId() != null ? "USER" : "ROLE",
            "subjectId",
            grant.userId() != null ? grant.userId() : grant.roleId()));
  }

  /** 추가 후보 — 이 테넌트 ACTIVE 멤버와 역할(이름만). membership 은 RLS 없는 전역 테이블이라 tenant_id 를 명시한다. */
  @Transactional(readOnly = true)
  public GrantCandidatesResponse candidates() {
    long tenantId = TenantContext.require("허용 목록 후보");
    var users =
        dsl.select(USER.ID, USER.NAME, USER.EMAIL)
            .from(USER)
            .join(table(name("membership")))
            .on(field(name("membership", "user_id"), Long.class).eq(USER.ID))
            .where(field(name("membership", "tenant_id"), Long.class).eq(tenantId))
            .and(field(name("membership", "status"), String.class).eq("ACTIVE"))
            .and(USER.IS_ACTIVE.isTrue())
            .orderBy(USER.NAME.asc())
            .fetch(
                r ->
                    new GrantCandidatesResponse.UserCandidate(
                        r.get(USER.ID), r.get(USER.NAME), r.get(USER.EMAIL)));
    var roles =
        dsl.select(ROLE.ID, ROLE.NAME)
            .from(ROLE)
            .orderBy(ROLE.NAME.asc())
            .fetch(
                r -> new GrantCandidatesResponse.RoleCandidate(r.get(ROLE.ID), r.get(ROLE.NAME)));
    return new GrantCandidatesResponse(users, roles);
  }

  private Long currentLevelId(long datasetId) {
    return dsl.select(DATASET.SECURITY_LEVEL_ID)
        .from(DATASET)
        .where(DATASET.ID.eq(datasetId))
        .fetchSingle(DATASET.SECURITY_LEVEL_ID);
  }

  private boolean isActiveMember(long userId, long tenantId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("membership")))
            .where(field(name("membership", "user_id"), Long.class).eq(userId))
            .and(field(name("membership", "tenant_id"), Long.class).eq(tenantId))
            .and(field(name("membership", "status"), String.class).eq("ACTIVE"))
            // candidates() 와 같은 기준: 비활성화된 계정은 멤버십이 ACTIVE 여도 대상이 아니다.
            .and(
                org.jooq.impl.DSL.exists(
                    dsl.selectOne()
                        .from(USER)
                        .where(USER.ID.eq(userId))
                        .and(USER.IS_ACTIVE.isTrue()))));
  }
}
