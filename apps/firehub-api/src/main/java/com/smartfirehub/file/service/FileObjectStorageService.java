package com.smartfirehub.file.service;

import com.smartfirehub.file.config.MinioProperties;
import com.smartfirehub.file.dto.ObjectItemResponse;
import com.smartfirehub.file.dto.ObjectListResponse;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Service;

/** MinIO(S3 호환) 오브젝트 스토리지 접근 서비스. 대량 파일 바이트는 앱을 통과하지 않으며, 목록 조회와 presigned GET URL 발급만 담당한다. */
@Service
public class FileObjectStorageService {

  private final MinioClient minioClient;
  private final MinioProperties props;

  public FileObjectStorageService(MinioClient minioClient, MinioProperties props) {
    this.minioClient = minioClient;
    this.props = props;
  }

  /** 설정된 기본 버킷명. FILE 데이터셋 생성 시 버킷 미지정이면 이 값을 사용한다. */
  public String defaultBucket() {
    return props.bucket();
  }

  /** 설정된 기본 presigned URL 만료 시간(초). 컨트롤러가 하드코딩값 대신 이 값을 사용해야 한다. */
  public int defaultPresignExpiry() {
    return props.presignExpirySeconds();
  }

  /** 프리픽스 하위 오브젝트를 페이지 단위로 조회한다. DB에 파일을 저장하지 않으므로 목록은 항상 S3 list로 실시간 계산한다. */
  public ObjectListResponse listObjects(
      String bucket, String prefix, String continuationToken, int maxKeys) {
    ListObjectsArgs.Builder builder =
        ListObjectsArgs.builder().bucket(bucket).prefix(prefix).maxKeys(maxKeys).recursive(true);
    if (continuationToken != null && !continuationToken.isBlank()) {
      builder.startAfter(continuationToken);
    }

    // MinIO SDK의 listObjects는 continuation token을 자동으로 따라가는 지연(lazy) Iterable을 반환한다.
    // 끝까지 순회하면 prefix 하위 전체 오브젝트를 읽어버리므로, maxKeys를 실제 페이지 크기 상한으로 쓰려면
    // 명시적으로 이터레이터를 제어하며 maxKeys개를 채운 뒤 멈추고, "그 다음 원소가 더 있는지"로 hasMore를 판단해야 한다.
    List<ObjectItemResponse> items = new ArrayList<>();
    String lastKey = null;
    boolean hasMore;
    try {
      Iterator<Result<Item>> it = minioClient.listObjects(builder.build()).iterator();
      while (it.hasNext() && items.size() < maxKeys) {
        Item item = it.next().get();
        if (item.isDir()) continue;
        lastKey = item.objectName();
        String modified = item.lastModified() != null ? item.lastModified().toString() : null;
        items.add(new ObjectItemResponse(item.objectName(), item.size(), modified));
      }
      // 현재 페이지를 채운 뒤에도 이터레이터에 원소가 남아 있으면 다음 페이지가 존재하는 것이다.
      hasMore = it.hasNext();
    } catch (Exception e) {
      throw new RuntimeException("오브젝트 목록 조회 실패: " + e.getMessage(), e);
    }

    String nextToken = hasMore ? lastKey : null;
    return new ObjectListResponse(items, nextToken, hasMore);
  }

  /** 오브젝트 단건에 대한 단기 presigned GET URL을 발급한다(브라우저가 MinIO에서 직접 GET). */
  public PresignedUrlResponse presignedGetUrl(String bucket, String objectKey, int expirySeconds) {
    try {
      String url =
          minioClient.getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(Method.GET)
                  .bucket(bucket)
                  .object(objectKey)
                  .expiry(expirySeconds)
                  .build());
      return new PresignedUrlResponse(url, expirySeconds);
    } catch (Exception e) {
      throw new RuntimeException("presigned URL 발급 실패: " + e.getMessage(), e);
    }
  }
}
