package com.smartfirehub.apiconnection.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** URL 결합 유틸 테스트. baseUrl 끝 슬래시 제거, path 앞 슬래시 정규화, null/blank path 처리를 검증한다. */
class UrlUtilsTest {

  @Test
  void joinUrl_noTrailingSlash_noLeadingSlash() {
    assertEquals("https://a.com/b", UrlUtils.joinUrl("https://a.com", "/b"));
    assertEquals("https://a.com/b", UrlUtils.joinUrl("https://a.com/", "/b"));
    assertEquals("https://a.com/b", UrlUtils.joinUrl("https://a.com", "b"));
    assertEquals("https://a.com/b", UrlUtils.joinUrl("https://a.com/", "b"));
  }

  @Test
  void joinUrl_nullOrBlankPath_returnsBaseUrl() {
    assertEquals("https://a.com", UrlUtils.joinUrl("https://a.com", null));
    assertEquals("https://a.com", UrlUtils.joinUrl("https://a.com", ""));
    assertEquals("https://a.com", UrlUtils.joinUrl("https://a.com/", null));
  }

  /** (#114) "/" 단독은 의미 없는 trailing slash 를 만들지 않도록 빈 path 와 동일하게 처리한다. */
  @Test
  void joinUrl_rootSlashPath_returnsBaseUrlWithoutTrailingSlash() {
    assertEquals(
        "https://apis.data.go.kr/1360000/AsosDalyInfoService/getWthrDataList",
        UrlUtils.joinUrl(
            "https://apis.data.go.kr/1360000/AsosDalyInfoService/getWthrDataList", "/"));
    assertEquals("https://a.com", UrlUtils.joinUrl("https://a.com", "/"));
    assertEquals("https://a.com", UrlUtils.joinUrl("https://a.com/", "/"));
  }

  @Test
  void normalizeBaseUrl_removesTrailingSlash() {
    assertEquals("https://a.com", UrlUtils.normalizeBaseUrl("https://a.com/"));
    assertEquals("https://a.com", UrlUtils.normalizeBaseUrl("https://a.com"));
    assertEquals("https://a.com/v1", UrlUtils.normalizeBaseUrl("https://a.com/v1/"));
  }
}
