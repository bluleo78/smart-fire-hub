package com.smartfirehub.securitylevel.repository;

import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;

import com.smartfirehub.jooq.tables.records.SecurityLevelRecord;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * security_level 접근. 테넌트 범위는 RLS(GUC)가 정한다 — GUC 는 트랜잭션 시작 때만 주입되므로 클래스 레벨 @Transactional 로 "트랜잭션
 * 없는 호출자"(비동기 러너·인터셉터)도 안전하게 한다(DatasetRepository 와 같은 관례). 호출자 트랜잭션이 있으면 합류한다.
 */
@Repository
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class SecurityLevelRepository {

  private final DSLContext dsl;

  /** 현재 테넌트의 등급 전부(rank 오름차순 — 화면의 "아래로 갈수록 높음" 순서). */
  public List<LevelPolicy> findAll() {
    return dsl.selectFrom(SECURITY_LEVEL)
        .orderBy(SECURITY_LEVEL.RANK.asc())
        .fetch(SecurityLevelRepository::toPolicy);
  }

  public Optional<LevelPolicy> findById(long id) {
    return dsl.selectFrom(SECURITY_LEVEL)
        .where(SECURITY_LEVEL.ID.eq(id))
        .fetchOptional(SecurityLevelRepository::toPolicy);
  }

  /** DB 행 → 판정용 값 객체. 문자열 정책은 enum 으로 엄격 변환(CHECK 제약과 같은 집합). */
  public static LevelPolicy toPolicy(SecurityLevelRecord r) {
    return new LevelPolicy(
        r.getId(),
        r.getName(),
        r.getRank(),
        Boolean.TRUE.equals(r.getIsDefault()),
        Boolean.TRUE.equals(r.getAllowlistRequired()),
        Boolean.TRUE.equals(r.getAdminBypass()),
        LevelPolicy.ExportPolicy.valueOf(r.getExportPolicy()),
        LevelPolicy.AiPolicy.valueOf(r.getAiPolicy()),
        LevelPolicy.SharePolicy.valueOf(r.getSharePolicy()),
        Boolean.TRUE.equals(r.getAuditAccess()));
  }
}
