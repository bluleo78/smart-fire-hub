package com.smartfirehub.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.file.config.MinioProperties;
import com.smartfirehub.file.dto.ObjectListResponse;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** presigned URL 발급이 MinioClient에 올바르게 위임되는지 검증(빠른 단위 테스트). */
@ExtendWith(MockitoExtension.class)
class FileObjectStorageServiceTest {

  @Mock MinioClient minioClient;
  @Mock MinioClient presignMinioClient;

  private FileObjectStorageService service() {
    // endpoint(내부)와 publicEndpoint(공개)를 분리해 주입한다. 목록은 minioClient, presign은 presignMinioClient 담당.
    MinioProperties props =
        new MinioProperties(
            "http://localhost:9000",
            "http://localhost:9000",
            "us-east-1",
            "k",
            "s",
            "firehub-files",
            300,
            900);
    return new FileObjectStorageService(minioClient, presignMinioClient, props);
  }

  @Test
  void presignedGetUrl_delegatesToPresignClientAndReturnsUrl() throws Exception {
    when(presignMinioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://localhost:9000/firehub-files/a.jpg?sig=abc");

    PresignedUrlResponse resp = service().presignedGetUrl("firehub-files", "a.jpg", 300);

    assertThat(resp.url()).contains("a.jpg");
    assertThat(resp.expiresInSeconds()).isEqualTo(300);
  }

  @Test
  void defaultBucket_returnsConfiguredBucket() {
    assertThat(service().defaultBucket()).isEqualTo("firehub-files");
  }

  /** 오브젝트 3개가 존재하지만 maxKeys=2 인 경우, 결과는 2건으로 잘리고 hasMore=true, nextToken=2번째 키여야 한다. */
  @Test
  void listObjects_capsAtMaxKeysAndReportsHasMore() throws Exception {
    Item item1 = mockItem("a/1.txt", 10L);
    Item item2 = mockItem("a/2.txt", 20L);
    // maxKeys(2) 상한에 걸려 실제로는 소비되지 않는 세 번째 아이템: hasNext()로만 존재가 확인되고
    // objectName()/isDir() 등은 호출되지 않으므로 lenient 스텁으로 등록한다.
    Item item3 = org.mockito.Mockito.mock(Item.class);
    org.mockito.Mockito.lenient().when(item3.isDir()).thenReturn(false);
    org.mockito.Mockito.lenient().when(item3.objectName()).thenReturn("a/3.txt");
    org.mockito.Mockito.lenient().when(item3.size()).thenReturn(30L);
    org.mockito.Mockito.lenient().when(item3.lastModified()).thenReturn(null);
    List<Result<Item>> results =
        List.of(new Result<>(item1), new Result<>(item2), new Result<>(item3));

    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(results);

    ObjectListResponse resp = service().listObjects("firehub-files", "a/", null, 2);

    assertThat(resp.objects()).hasSize(2);
    assertThat(resp.objects().get(0).key()).isEqualTo("a/1.txt");
    assertThat(resp.objects().get(1).key()).isEqualTo("a/2.txt");
    assertThat(resp.hasMore()).isTrue();
    assertThat(resp.nextToken()).isEqualTo("a/2.txt");
  }

  /** 오브젝트가 정확히 2개이고 maxKeys=5 인 경우, 더 가져올 페이지가 없으므로 hasMore=false, nextToken=null 이어야 한다. */
  @Test
  void listObjects_noMoreWhenAllItemsFitWithinMaxKeys() throws Exception {
    Item item1 = mockItem("b/1.txt", 10L);
    Item item2 = mockItem("b/2.txt", 20L);
    List<Result<Item>> results = List.of(new Result<>(item1), new Result<>(item2));

    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(results);

    ObjectListResponse resp = service().listObjects("firehub-files", "b/", null, 5);

    assertThat(resp.objects()).hasSize(2);
    assertThat(resp.hasMore()).isFalse();
    assertThat(resp.nextToken()).isNull();
  }

  /** presignedPutUrl은 반드시 PUT 메서드로 서명하고, 발급된 URL을 그대로 반환해야 한다. */
  @Test
  void presignedPutUrl_usesPutMethodAndReturnsUrl() throws Exception {
    ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
        ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
    when(presignMinioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://localhost:9000/firehub-files/x/1.jpg?sig=put");

    PresignedUrlResponse resp = service().presignedPutUrl("firehub-files", "x/1.jpg", 900);

    verify(presignMinioClient).getPresignedObjectUrl(captor.capture());
    assertThat(captor.getValue().method()).isEqualTo(Method.PUT);
    assertThat(resp.url()).contains("1.jpg");
    assertThat(resp.expiresInSeconds()).isEqualTo(900);
  }

  /** 업로드 만료는 GET(300)과 분리된 설정값(900)을 반환해야 한다. */
  @Test
  void defaultUploadPresignExpiry_returnsConfigured() {
    assertThat(service().defaultUploadPresignExpiry()).isEqualTo(900);
  }

  /**
   * Slice 3 핵심 회귀: presign은 내부 endpoint가 아니라 공개 endpoint(publicEndpoint)로 서명해야 한다.
   * getPresignedObjectUrl은 네트워크 없이 host+path를 로컬 서명하므로, 서로 다른 endpoint로 빌드한 실제
   * MinioClient 2개를 주입해 발급 URL의 host가 공개 호스트인지(내부 호스트가 새지 않는지) 검증한다.
   */
  @Test
  void presign_signsAgainstPublicEndpointNotInternal() {
    MinioClient internal =
        MinioClient.builder()
            .endpoint("http://minio:9000")
            .region("us-east-1")
            .credentials("k", "s")
            .build();
    MinioClient publicClient =
        MinioClient.builder()
            .endpoint("http://public.example:9000")
            .region("us-east-1")
            .credentials("k", "s")
            .build();
    MinioProperties props =
        new MinioProperties(
            "http://minio:9000",
            "http://public.example:9000",
            "us-east-1",
            "k",
            "s",
            "firehub-files",
            300,
            900);
    FileObjectStorageService svc = new FileObjectStorageService(internal, publicClient, props);

    String getUrl = svc.presignedGetUrl("firehub-files", "a.jpg", 300).url();
    String putUrl = svc.presignedPutUrl("firehub-files", "a.jpg", 900).url();

    assertThat(getUrl).startsWith("http://public.example:9000/");
    assertThat(putUrl).startsWith("http://public.example:9000/");
    assertThat(getUrl).doesNotContain("minio:9000");
    assertThat(putUrl).doesNotContain("minio:9000");
  }

  private Item mockItem(String key, long size) {
    Item item = org.mockito.Mockito.mock(Item.class);
    when(item.isDir()).thenReturn(false);
    when(item.objectName()).thenReturn(key);
    when(item.size()).thenReturn(size);
    when(item.lastModified()).thenReturn(null);
    return item;
  }
}
