package com.smartfirehub.file.dto;

/** 오브젝트 단건에 대한 presigned GET URL 응답. */
public record PresignedUrlResponse(String url, int expiresInSeconds) {}
