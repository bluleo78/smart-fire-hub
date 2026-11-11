package com.smartfirehub.audit.repository.mybatis;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * audit_log MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/audit/AuditLogMapper.xml}.
 *
 * <p>audit_log 는 NULL 테넌트를 허용하는 정책(PG V99 형태 (b), Tibero {@code FH_TENANT_POLICY_NULLABLE}) 아래 있다 —
 * 테넌트 컨텍스트가 있으면 그 테넌트 행만, 없으면 tenant_id 가 NULL 인 행만 보인다. 문장은 tenant_id 를 쓰지 않는다(플랫폼 조회의 {@code
 * tenant_id IS NULL} 은 인터페이스 의미라 예외).
 */
@PlatformMapper
public interface AuditLogMapper {

  /** 한 건을 넣는다 — 생성된 id 는 {@code row.id} 에 채워진다. */
  void insert(AuditLogInsert row);

  /** id 로 한 건. 없으면(또는 정책이 가리면) null. */
  AuditLogResponse selectById(@Param("id") Long id);

  /** 리소스 조건 건수. */
  long countByResource(AuditLogQuery q);

  /** 리소스 조건 한 쪽(action_time desc, id desc). */
  List<AuditLogResponse> selectByResource(AuditLogQuery q);

  /** 복합 필터 건수. */
  long countAll(AuditLogQuery q);

  /** 복합 필터 한 쪽(action_time desc, id desc). */
  List<AuditLogResponse> selectAll(AuditLogQuery q);

  /** 플랫폼(tenant_id IS NULL) 건수. */
  long countPlatform(AuditLogQuery q);

  /** 플랫폼(tenant_id IS NULL) 한 쪽(action_time desc, id desc). */
  List<AuditLogResponse> selectPlatform(AuditLogQuery q);
}
