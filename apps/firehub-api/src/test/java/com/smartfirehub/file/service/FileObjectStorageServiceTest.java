package com.smartfirehub.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.smartfirehub.file.config.MinioProperties;
import com.smartfirehub.file.dto.ObjectListResponse;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** presigned URL 발급이 MinioClient에 올바르게 위임되는지 검증(빠른 단위 테스트). */
@ExtendWith(MockitoExtension.class)
class FileObjectStorageServiceTest {

  @Mock MinioClient minioClient;

  private FileObjectStorageService service() {
    MinioProperties props =
        new MinioProperties("http://localhost:9000", "k", "s", "firehub-files", 300);
    return new FileObjectStorageService(minioClient, props);
  }

  @Test
  void presignedGetUrl_delegatesToMinioClientAndReturnsUrl() throws Exception {
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
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

  private Item mockItem(String key, long size) {
    Item item = org.mockito.Mockito.mock(Item.class);
    when(item.isDir()).thenReturn(false);
    when(item.objectName()).thenReturn(key);
    when(item.size()).thenReturn(size);
    when(item.lastModified()).thenReturn(null);
    return item;
  }
}
