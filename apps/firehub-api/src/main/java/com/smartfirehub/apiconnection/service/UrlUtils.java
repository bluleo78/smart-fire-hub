package com.smartfirehub.apiconnection.service;

/**
 * API 연결 Base URL과 Path 결합을 담당하는 유틸.
 * baseUrl의 끝 슬래시 / path의 앞 슬래시를 정규화하여 중복 슬래시를 방지한다.
 */
public final class UrlUtils {

    private UrlUtils() {}

    /**
     * baseUrl과 path를 결합한다.
     * - baseUrl 끝 슬래시 제거, path 앞 슬래시 보장
     * - path가 null 또는 blank이면 baseUrl만 반환
     */
    public static String joinUrl(String baseUrl, String path) {
        String base = normalizeBaseUrl(baseUrl);
        if (path == null || path.isBlank()) return base;
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }

    /**
     * baseUrl 끝 슬래시를 제거하여 정규화한다.
     * null 입력은 null 반환.
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) return null;
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }
}
