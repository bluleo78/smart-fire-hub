package com.smartfirehub.settings.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.repository.SettingsRepository;
import java.util.List;
import java.util.Optional;

/**
 * SettingsRepository 의 MyBatis 구현(WD-706) — {@code JooqSettingsRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현이 {@code @Transactional} 을 달지 않으므로 여기서도 달지 않는다 — system_settings 는 테넌트 개념이 없는 전역 테이블이라
 * 테넌트 컨텍스트 없이 읽어도 결과가 같다. 빈 문자열은 변환하지 않는다(controller Ruling).
 */
@PlatformMyBatisRepository
public class MyBatisSettingsRepository implements SettingsRepository {

  private final SettingsMapper mapper;

  public MyBatisSettingsRepository(SettingsMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<SettingResponse> findByPrefix(String prefix) {
    // 프리픽스에 든 LIKE 특수문자(%, _, \)를 이스케이프하고 ".%" 를 붙인다 — jOOQ 구현과 같은 패턴.
    return mapper.selectByPattern(LikePatternUtils.settingKeyPrefixPattern(prefix));
  }

  @Override
  public Optional<String> getValue(String key) {
    return Optional.ofNullable(mapper.selectValue(key));
  }
}
