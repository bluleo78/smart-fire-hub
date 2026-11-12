package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.proactive.repository.mybatis.ReportTemplateRows.SummaryRow;
import com.smartfirehub.proactive.repository.mybatis.ReportTemplateRows.TemplateInsert;
import com.smartfirehub.proactive.repository.mybatis.ReportTemplateRows.TemplateRow;
import com.smartfirehub.proactive.repository.mybatis.ReportTemplateRows.VersionRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * report_template MyBatis 매퍼(WD-709). SQL 은 {@code mybatis/proactive/ReportTemplateMapper.xml}.
 * 테넌트는 정책(RLS)이 맡는다 — tenant_id 는 INSERT 값·WHERE 술어로 쓰지 않는다(열 DEFAULT 가 채운다).
 */
@PlatformMapper
public interface ReportTemplateMapper {

  /** 본인·시스템(user_id NULL) 양식 목록 — id 오름차순 페이지. */
  List<SummaryRow> findAllForUser(
      @Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

  TemplateRow findById(@Param("id") Long id);

  /** 시스템 양식이거나 본인 양식일 때만. */
  TemplateRow findVisibleTo(@Param("id") Long id, @Param("userId") Long userId);

  int insert(TemplateInsert row);

  /** 행을 잠그고 수정 시각을 읽는다. 행이 없으면 null. */
  VersionRow lockVersionById(@Param("id") Long id);

  /**
   * 동적 수정. name·sectionsJson 은 null 이면 유지, 설명·스타일은 set 플래그가 참일 때만 쓴다(값 null 은 NULL 로 비움).
   *
   * @return 고친 행 수
   */
  int update(
      @Param("id") Long id,
      @Param("userId") Long userId,
      @Param("updatedAt") LocalDateTime updatedAt,
      @Param("name") String name,
      @Param("setDescription") boolean setDescription,
      @Param("description") String description,
      @Param("sectionsJson") String sectionsJson,
      @Param("setStyle") boolean setStyle,
      @Param("style") String style);

  int delete(@Param("id") Long id, @Param("userId") Long userId);
}
