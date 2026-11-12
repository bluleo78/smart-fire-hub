package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.proactive.repository.ProactiveJobRepository.WorkspaceJobRow;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobRows.JobInsert;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobRows.JobRow;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobRows.VersionRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * proactive_job MyBatis 매퍼(WD-709). SQL 은 {@code mybatis/proactive/ProactiveJobMapper.xml}. 테넌트는
 * 정책(RLS)이 맡아 tenant_id 를 술어·INSERT 값으로 쓰지 않는다 — 단 인터페이스가 테넌트를 인자로 받는 다섯 문장(findAllEnabled·
 * findWorkspaceRows·findWorkspaceRow·disableInTenant·deleteInTenant)은 명시 술어를 그대로 둔다(스펙 D1).
 */
@PlatformMapper
public interface ProactiveJobMapper {

  List<JobRow> findByUserId(@Param("userId") Long userId);

  JobRow findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

  JobRow findById(@Param("id") Long id);

  /** 지정 테넌트의 활성 작업(명시 tenant_id 술어). */
  List<JobRow> findAllEnabled(@Param("tenantId") long tenantId);

  int insert(JobInsert row);

  VersionRow lockVersionById(@Param("id") Long id);

  /**
   * 동적 수정 — null 인 인자는 유지한다. templateId 가 null 이고 clearTemplate 이면 NULL 로 되돌린다.
   *
   * @return 고친 행 수
   */
  int update(
      @Param("id") Long id,
      @Param("userId") Long userId,
      @Param("updatedAt") LocalDateTime updatedAt,
      @Param("name") String name,
      @Param("prompt") String prompt,
      @Param("templateId") Long templateId,
      @Param("clearTemplate") boolean clearTemplate,
      @Param("cronExpression") String cronExpression,
      @Param("timezone") String timezone,
      @Param("enabled") Boolean enabled,
      @Param("triggerType") String triggerType,
      @Param("configJson") String configJson);

  int updateLastExecuted(
      @Param("id") Long id,
      @Param("lastExecutedAt") LocalDateTime lastExecutedAt,
      @Param("nextExecuteAt") LocalDateTime nextExecuteAt);

  int updateNextExecuteAt(
      @Param("id") Long id, @Param("nextExecuteAt") LocalDateTime nextExecuteAt);

  int delete(@Param("id") Long id, @Param("userId") Long userId);

  /** 이 테넌트의 작업 전부(최소 열 + 작성자 이름, 명시 tenant_id 술어). */
  List<WorkspaceJobRow> findWorkspaceRows(@Param("tenantId") long tenantId);

  WorkspaceJobRow findWorkspaceRow(@Param("id") Long id, @Param("tenantId") long tenantId);

  int disableInTenant(
      @Param("id") Long id,
      @Param("tenantId") long tenantId,
      @Param("updatedAt") LocalDateTime updatedAt);

  int deleteInTenant(@Param("id") Long id, @Param("tenantId") long tenantId);
}
