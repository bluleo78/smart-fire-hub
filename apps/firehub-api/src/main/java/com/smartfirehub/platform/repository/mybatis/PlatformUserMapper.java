package com.smartfirehub.platform.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.dto.PlatformUserResponse;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 운영자 평면 사용자 검색·계정 목록 MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/platform/PlatformUserMapper.xml}.
 *
 * <p>user·membership·platform_user_role 은 정책 없는 전역 테이블이라 테넌트 컨텍스트 없이 전 테넌트 사용자를 읽는다. 정책 테이블(role·
 * user_role)은 조인하지 않는다 — 컨텍스트 없는 운영자 토큰에서 조용히 0행이 된다.
 */
@PlatformMapper
public interface PlatformUserMapper {

  /**
   * 활성 사용자 이메일·이름 부분일치 검색(이메일 정확일치 → 이메일 → 이름 → id).
   *
   * @param q 원 검색어(정확일치 순위용)
   * @param pattern 이스케이프된 LIKE 패턴
   * @param limit 상한
   * @param offset 늘 0 — 공용 페이지 조각(pageClause)을 쓰려고 넘긴다
   */
  List<PlatformUserResponse> search(
      @Param("q") String q,
      @Param("pattern") String pattern,
      @Param("limit") int limit,
      @Param("offset") long offset);

  /** 계정 목록 총 건수(검색어 없으면 전체). */
  long countAccounts(@Param("pattern") String pattern);

  /** 계정 목록 한 쪽. pattern 이 null 이면 생성 최신순, 아니면 검색 순. */
  List<PlatformAccountResponse> selectAccounts(
      @Param("q") String q,
      @Param("pattern") String pattern,
      @Param("limit") int limit,
      @Param("offset") long offset);
}
