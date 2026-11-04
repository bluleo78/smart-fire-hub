package com.smartfirehub.settings.repository;

import com.smartfirehub.settings.dto.SettingResponse;
import java.util.List;
import java.util.Optional;

/**
 * 전역 시스템 설정(system_settings 테이블) 조회 리포지토리.
 *
 * <p>구현은 {@code JooqSettingsRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface SettingsRepository {

  /**
   * 프리픽스에 속한 설정 행. <b>프리픽스에 마침표를 붙이지 않는다</b> — 예: {@code "ai"}(O), {@code "ai."}(X). 이 메서드가 스스로
   * {@code prefix + ".%"} 를 만들기 때문에 마침표를 붙이면 {@code "ai..%"} 가 되어 <b>아무 행도 매칭하지 않고, 예외도 없이 빈
   * 목록</b>을 돌려준다. 실제로 {@code ProactiveJobAsyncRunner} 가 이 함정에 빠져 저장된 {@code ai.agent_type} 을 한 번도
   * 읽지 못했다(P7-b Task 8 에서 수정).
   */
  List<SettingResponse> findByPrefix(String prefix);

  Optional<String> getValue(String key);
}
