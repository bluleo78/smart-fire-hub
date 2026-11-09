package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * baseUrl 사용자 정보(아이디:비밀번호@) 읽기·떼기·가리기 규칙(WD-681) 단위 테스트.
 *
 * <p>가림은 비밀번호만 {@code ****} 로 바꾸고, 가린 값 되돌리기는 스킴·아이디·호스트·포트·경로가 모두 같을 때만 참이어야 한다 — 다른 호스트로 바꾼 URL 에
 * 저장된 비밀번호가 붙으면 비밀이 다른 서버로 간다.
 */
class BaseUrlUserInfoTest {

  /** 비밀번호만 가린다. 비밀번호가 없거나 사용자 정보가 없으면 그대로. */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "https://alice:s3cret@api.example.com/v1|https://alice:****@api.example.com/v1",
        "http://u:p%40ss@my_api.internal:8080|http://u:****@my_api.internal:8080",
        "https://alice:s3cret@api.example.com?x=1#f|https://alice:****@api.example.com?x=1#f",
        "https://alice@api.example.com/v1|https://alice@api.example.com/v1",
        "https://alice:@api.example.com/v1|https://alice:@api.example.com/v1",
        "https://api.example.com/v1|https://api.example.com/v1",
        "https://api.example.com/a@b|https://api.example.com/a@b",
        "not a url|not a url"
      })
  void mask_hidesOnlyPassword(String url, String expected) {
    assertThat(BaseUrlUserInfo.mask(url)).isEqualTo(expected);
  }

  @Test
  void mask_null_isNull() {
    assertThat(BaseUrlUserInfo.mask(null)).isNull();
  }

  /** 떼면 사용자 정보 없는 URL, 읽으면 퍼센트 디코딩한 값 — 「+」 는 글자 그대로다. */
  @Test
  void stripAndExtract_decodePercentButKeepPlus() {
    String url = "https://al%20ice:p%40ss+w0rd%EB%B9%84@api.example.com/v1";
    assertThat(BaseUrlUserInfo.hasUserInfo(url)).isTrue();
    assertThat(BaseUrlUserInfo.strip(url)).isEqualTo("https://api.example.com/v1");
    BaseUrlUserInfo.UserInfo info = BaseUrlUserInfo.extract(url).orElseThrow();
    assertThat(info.username()).isEqualTo("al ice");
    assertThat(info.password()).isEqualTo("p@ss+w0rd비");
  }

  @Test
  void extract_usernameOnly_hasNullPassword() {
    BaseUrlUserInfo.UserInfo info =
        BaseUrlUserInfo.extract("https://alice@api.example.com").orElseThrow();
    assertThat(info.username()).isEqualTo("alice");
    assertThat(info.password()).isNull();
    assertThat(BaseUrlUserInfo.extract("https://api.example.com/x@y")).isEmpty();
  }

  @Test
  void hasMaskedPassword_onlyForExactMask() {
    assertThat(BaseUrlUserInfo.hasMaskedPassword("https://a:****@h.example")).isTrue();
    assertThat(BaseUrlUserInfo.hasMaskedPassword("https://a:real@h.example")).isFalse();
    assertThat(BaseUrlUserInfo.hasMaskedPassword("https://h.example")).isFalse();
  }

  /**
   * 가린 사용자 정보를 저장값으로 되살리는 것은 스킴·호스트·포트·아이디가 같을 때뿐이다(WD-681 리뷰) — 경로·쿼리는 바뀌어도 되고, 호스트는 대소문자를 무시한다.
   * 저장값과 사용자 정보가 글자 그대로 같으면(아이디만 있는 옛 값 등) 그대로 되살린다.
   */
  @Test
  void restoreStoredUserInfo_onlyForSameOriginAndUser() {
    String stored = "https://alice:s%40cret@api.example.com/v1";
    assertThat(
            BaseUrlUserInfo.restoreStoredUserInfo("https://alice:****@api.example.com/v1", stored))
        .contains(stored);
    assertThat(
            BaseUrlUserInfo.restoreStoredUserInfo(
                "https://alice:****@API.example.com/v2?x=1#f", stored))
        .contains("https://alice:s%40cret@API.example.com/v2?x=1#f");
    for (String other :
        java.util.List.of(
            "https://alice:****@evil.example.com/v1",
            "https://bob:****@api.example.com/v1",
            "https://alice:****@api.example.com:8443/v1",
            "http://alice:****@api.example.com/v1",
            "https://alice:typed@api.example.com/v1",
            "https://api.example.com/v1")) {
      assertThat(BaseUrlUserInfo.restoreStoredUserInfo(other, stored)).as(other).isEmpty();
    }
    // 아이디만 있는 옛 값은 글자 그대로 같으면 되살린다(가릴 비밀번호가 없다)
    assertThat(
            BaseUrlUserInfo.restoreStoredUserInfo(
                "https://alice@api.example.com/x", "https://alice@api.example.com"))
        .contains("https://alice@api.example.com/x");
    // 저장값에 비밀번호가 없으면 가린 값을 되살릴 수 없다
    assertThat(
            BaseUrlUserInfo.restoreStoredUserInfo(
                "https://alice:****@api.example.com", "https://alice@api.example.com"))
        .isEmpty();
    assertThat(BaseUrlUserInfo.restoreStoredUserInfo(null, stored)).isEmpty();
    assertThat(BaseUrlUserInfo.restoreStoredUserInfo("https://alice:****@api.example.com", null))
        .isEmpty();
    assertThat(BaseUrlUserInfo.hasPassword(stored)).isTrue();
    assertThat(BaseUrlUserInfo.hasPassword("https://alice@api.example.com")).isFalse();
    assertThat(BaseUrlUserInfo.hasPassword("https://alice:@api.example.com")).isFalse();
  }
}
