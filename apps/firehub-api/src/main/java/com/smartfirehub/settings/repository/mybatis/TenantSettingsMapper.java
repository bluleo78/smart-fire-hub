package com.smartfirehub.settings.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * tenant_settings MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/settings/TenantSettingsMapper.xml}. 테넌트
 * 정책 대상이라 tenant_id 는 문장에 쓰지 않는다(DEFAULT + RLS 가 채우고 거른다).
 */
@PlatformMapper
public interface TenantSettingsMapper {

  /** 키·값 한 쌍(프리픽스 조회의 한 줄). */
  record Entry(String key, String value) {}

  String selectValue(@Param("key") String key);

  /** LIKE 패턴(이스케이프 문자 {@code \})에 맞는 행, 키 오름차순. */
  List<Entry> selectByPattern(@Param("pattern") String pattern);

  /** 현재 테넌트 행을 넣거나(신규) 값·수정자·수정 시각을 바꾼다(기존). */
  int upsert(@Param("key") String key, @Param("value") String value, @Param("userId") Long userId);

  int deleteByKey(@Param("key") String key);

  int deleteByKeys(@Param("keys") List<String> keys);
}
