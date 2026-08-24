package com.smartfirehub.platform.service;

import com.smartfirehub.platform.dto.PlatformUserResponse;
import com.smartfirehub.platform.repository.PlatformUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영자 평면 사용자 검색. 하한·상한 정책을 여기 한곳에 둔다. */
@Service
@RequiredArgsConstructor
public class PlatformUserService {

  /**
   * 검색어 최소 길이.
   *
   * <p>하한이 없으면 빈 문자열이 전 사용자 덤프가 된다 — 이 엔드포인트는 Owner 를 고르기 위한
   * 좁은 도구이지 사용자 목록 API 가 아니다.
   */
  static final int MIN_QUERY_LENGTH = 2;

  /**
   * 결과 상한.
   *
   * <p>페이징을 주지 않는 대신 상한을 둔다. 20건 안에서 못 찾으면 운영자가 검색어를 좁히는 편이
   * 낫고, 페이징을 주면 결국 목록 API 가 되어 하한을 둔 의미가 사라진다.
   */
  static final int MAX_RESULTS = 20;

  private final PlatformUserRepository userRepository;

  /**
   * @throws IllegalArgumentException 검색어가 없거나 {@value #MIN_QUERY_LENGTH} 자 미만일 때 —
   *     전역 예외 처리기가 400 으로 바꾼다.
   */
  @Transactional(readOnly = true)
  public List<PlatformUserResponse> search(String q) {
    String trimmed = q == null ? "" : q.trim();
    if (trimmed.length() < MIN_QUERY_LENGTH) {
      throw new IllegalArgumentException("검색어는 " + MIN_QUERY_LENGTH + "자 이상이어야 합니다");
    }
    return userRepository.search(trimmed, MAX_RESULTS);
  }
}
