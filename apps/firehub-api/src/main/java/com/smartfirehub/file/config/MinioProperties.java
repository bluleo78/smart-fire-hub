package com.smartfirehub.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MinIO(S3 호환) 접속·기본 버킷 설정을 담는 바인딩 객체. */
@ConfigurationProperties(prefix = "firehub.minio")
public record MinioProperties(
    String endpoint, String accessKey, String secretKey, String bucket, int presignExpirySeconds) {}
