package com.smartfirehub.settings.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.settings.dto.SettingResponse;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 전역 system_settings MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/settings/SettingsMapper.xml}. 테넌트 개념이
 * 없는 전역 테이블이다.
 */
@PlatformMapper
public interface SettingsMapper {

  /** LIKE 패턴(이스케이프 문자 {@code \})에 맞는 행, 키 오름차순. */
  List<SettingResponse> selectByPattern(@Param("pattern") String pattern);

  /** 키의 값(없으면 null). */
  String selectValue(@Param("key") String key);
}
