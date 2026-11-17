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

  /** name은 prefix를 제외한 상대경로여야 한다(폴더 구조 노출 — ③). key는 전체 키를 유지한다. */
  @Test
  void listObjects_nameIsRelativePathUnderPrefix() throws Exception {
    Item nested = mockItem("equip/site-A/2026/img.jpg", 10L);
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(List.of(new Result<>(nested)));

    ObjectListResponse resp = service().listObjects("firehub-files", "equip/", null, 10);

    assertThat(resp.objects().get(0).key()).isEqualTo("equip/site-A/2026/img.jpg");
    assertThat(resp.objects().get(0).name()).isEqualTo("site-A/2026/img.jpg");
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
   * getPresignedObjectUrl은 네트워크 없이 host+path를 로컬 서명하므로, 서로 다른 endpoint로 빌드한 실제 MinioClient 2개를 주입해
   * 발급 URL의 host가 공개 호스트인지(내부 호스트가 새지 않는지) 검증한다.
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

  /**
   * 스펙 §4.4 — disposition 은 서명된 쿼리 파라미터(response-content-disposition)로 들어간다. 서명 대상이라 클라이언트가 inline
   * 을 attachment 로 바꾸면 서명이 깨진다. 3-인자 호출은 기존 호환을 위해 inline 이다.
   */
  @Test
  void presignedGetUrl_signsContentDisposition() {
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

    String att = svc.presignedGetUrl("firehub-files", "dir/a.jpg", 300, "attachment").url();
    String inl = svc.presignedGetUrl("firehub-files", "dir/a.jpg", 300, "inline").url();
    String legacy = svc.presignedGetUrl("firehub-files", "dir/a.jpg", 300).url();

    assertThat(att).contains("response-content-disposition=attachment");
    assertThat(inl).contains("response-content-disposition=inline");
    assertThat(legacy).contains("response-content-disposition=inline");
    // 서명 대상 파라미터라야 변조가 서명 불일치가 된다 — 서명은 disposition 에 따라 달라진다.
    assertThat(signature(att)).isNotEqualTo(signature(inl));
  }

  /** recursive 모드(기존 listObjects)는 그대로 재귀(평면) 목록을 요청한다 — AI 에이전트·채팅 위젯 호환. */
  @Test
  void listObjects_requestsRecursiveListing() throws Exception {
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of());

    ObjectListResponse resp = service().listObjects("firehub-files", "equip/", null, 10);

    ListObjectsArgs args = captureListArgs();
    assertThat(args.recursive()).isTrue();
    assertThat(args.prefix()).isEqualTo("equip/");
    // 기존 3인자 응답은 폴더 없음·잘림 없음으로 직렬화된다.
    assertThat(resp.folders()).isEmpty();
    assertThat(resp.truncated()).isFalse();
  }

  // ---------------------------------------------------------------------------------------
  // 폴더 모드(WD-761): 지정 폴더 바로 아래만 delimiter '/' 로 읽어 하위 폴더와 파일을 나눈다.
  // ---------------------------------------------------------------------------------------

  /**
   * 폴더 모드는 prefix=&lt;데이터셋 prefix&gt;&lt;폴더&gt;, recursive(false), delimiter('/') 로 부르고 폴더/파일을 나눈다.
   */
  @Test
  void listFolder_listsOneLevelAndSplitsFoldersAndFiles() throws Exception {
    givenListing(item("equip/제품/Tibero7/", 0L, true), item("equip/제품/readme.txt", 5L, false));

    ObjectListResponse resp = service().listFolder("firehub-files", "equip/", "제품/", null, 10);

    ListObjectsArgs args = captureListArgs();
    assertThat(args.bucket()).isEqualTo("firehub-files");
    assertThat(args.prefix()).isEqualTo("equip/제품/");
    assertThat(args.recursive()).isFalse();
    assertThat(args.delimiter()).isEqualTo("/");
    assertThat(args.startAfter()).isNull();

    // 폴더: path = prefix 제외 상대 경로(끝 '/'), name = 마지막 세그먼트.
    assertThat(resp.folders()).hasSize(1);
    assertThat(resp.folders().get(0).path()).isEqualTo("제품/Tibero7/");
    assertThat(resp.folders().get(0).name()).isEqualTo("Tibero7");
    // 파일: key 는 전체 키, name 은 모든 모드에서 prefix 제외 전체 상대 경로.
    assertThat(resp.objects()).hasSize(1);
    assertThat(resp.objects().get(0).key()).isEqualTo("equip/제품/readme.txt");
    assertThat(resp.objects().get(0).name()).isEqualTo("제품/readme.txt");
    assertThat(resp.hasMore()).isFalse();
    assertThat(resp.nextToken()).isNull();
    assertThat(resp.truncated()).isFalse();
  }

  /** 루트 폴더("")는 데이터셋 prefix 자체를 delimiter 로 읽는다. */
  @Test
  void listFolder_rootUsesDatasetPrefix() throws Exception {
    givenListing(item("equip/a/", 0L, true), item("equip/b.txt", 1L, false));

    ObjectListResponse resp = service().listFolder("firehub-files", "equip/", "", null, 10);

    assertThat(captureListArgs().prefix()).isEqualTo("equip/");
    assertThat(resp.folders()).extracting("path").containsExactly("a/");
    assertThat(resp.folders()).extracting("name").containsExactly("a");
    assertThat(resp.objects()).extracting("name").containsExactly("b.txt");
  }

  /**
   * 페이지 경계가 폴더에 걸리면 토큰 = 그 폴더 prefix 다. S3/MinIO 는 startAfter 가 폴더 prefix 와 같으면 그 폴더를 다시 돌려주므로 토큰과
   * 같은 항목은 건너뛰어 같은 폴더가 두 페이지에 나오지 않게 한다.
   */
  @Test
  void listFolder_skipsEntryEqualToToken() throws Exception {
    givenListing(
        item("equip/a/", 0L, true), item("equip/b/", 0L, true), item("equip/c.txt", 1L, false));

    ObjectListResponse resp = service().listFolder("firehub-files", "equip/", "", "equip/a/", 10);

    assertThat(captureListArgs().startAfter()).isEqualTo("equip/a/");
    assertThat(resp.folders()).extracting("path").containsExactly("b/");
    assertThat(resp.objects()).extracting("key").containsExactly("equip/c.txt");
  }

  /** 현재 폴더 자신을 가리키는 표식 오브젝트(0바이트 "a/")와 이름이 빈 폴더("a//")는 빈 줄로 나오지 않게 건너뛴다. */
  @Test
  void listFolder_skipsSelfMarkerAndEmptyNames() throws Exception {
    givenListing(
        item("equip/a/", 0L, false), item("equip/a//", 0L, true), item("equip/a/x.txt", 3L, false));

    ObjectListResponse resp = service().listFolder("firehub-files", "equip/", "a/", null, 10);

    assertThat(resp.folders()).isEmpty();
    assertThat(resp.objects()).extracting("name").containsExactly("a/x.txt");
  }

  /** 폴더와 파일 모두 페이지 size 에 센다. 더 남으면 hasMore, nextToken = 마지막 항목 키(파일). */
  @Test
  void listFolder_countsFoldersAndFilesTowardPageSize() throws Exception {
    givenListing(
        item("equip/a/", 0L, true), item("equip/b.txt", 1L, false), item("equip/c.txt", 1L, false));

    ObjectListResponse resp = service().listFolder("firehub-files", "equip/", "", null, 2);

    assertThat(resp.folders()).hasSize(1);
    assertThat(resp.objects()).hasSize(1);
    assertThat(resp.hasMore()).isTrue();
    assertThat(resp.nextToken()).isEqualTo("equip/b.txt");
  }

  /** 페이지 마지막 항목이 폴더면 nextToken 은 그 폴더 prefix 다(다음 요청이 그 폴더를 건너뛴다). */
  @Test
  void listFolder_tokenIsFolderPrefixWhenPageEndsOnFolder() throws Exception {
    givenListing(item("equip/a/", 0L, true), item("equip/b.txt", 1L, false));

    ObjectListResponse resp = service().listFolder("firehub-files", "equip/", "", null, 1);

    assertThat(resp.folders()).extracting("path").containsExactly("a/");
    assertThat(resp.objects()).isEmpty();
    assertThat(resp.hasMore()).isTrue();
    assertThat(resp.nextToken()).isEqualTo("equip/a/");
  }

  // ---------------------------------------------------------------------------------------
  // 검색 모드(WD-761): 데이터셋 prefix 전체를 재귀로 훑으며 상대 경로에 대해 대소문자 무시 부분 일치.
  // ---------------------------------------------------------------------------------------

  /** 검색은 데이터셋 prefix 를 재귀로 훑고, prefix 를 제외한 상대 경로에 대해 대소문자 무시로 부분 일치한다. */
  @Test
  void searchObjects_caseInsensitiveSubstringOnRelativePath() throws Exception {
    givenListing(
        item("equip/제품/Tibero7/README.md", 1L, false),
        item("equip/other/x.txt", 1L, false),
        item("equip/TIBERO-notes.txt", 1L, false));

    ObjectListResponse resp =
        service().searchObjects("firehub-files", "equip/", "tibero", null, 10, 100);

    ListObjectsArgs args = captureListArgs();
    assertThat(args.prefix()).isEqualTo("equip/");
    assertThat(args.recursive()).isTrue();
    assertThat(resp.objects())
        .extracting("name")
        .containsExactly("제품/Tibero7/README.md", "TIBERO-notes.txt");
    // 검색 결과에는 폴더 행이 없다.
    assertThat(resp.folders()).isEmpty();
    assertThat(resp.hasMore()).isFalse();
    assertThat(resp.nextToken()).isNull();
    assertThat(resp.truncated()).isFalse();
  }

  /** 데이터셋 prefix 자체는 이름이 아니므로 검색 대상이 아니다 — prefix 문자열로 검색해도 모든 파일이 걸리지 않는다. */
  @Test
  void searchObjects_doesNotMatchDatasetPrefix() throws Exception {
    givenListing(item("equip/a.txt", 1L, false), item("equip/b.txt", 1L, false));

    ObjectListResponse resp =
        service().searchObjects("firehub-files", "equip/", "equip", null, 10, 100);

    assertThat(resp.objects()).isEmpty();
  }

  /** 검색어는 정규식·LIKE 패턴이 아니라 단순 문자열이다('%'·'.*' 를 글자 그대로 찾는다). 한글도 그대로. */
  @Test
  void searchObjects_treatsQueryAsLiteralText() throws Exception {
    givenListing(
        item("equip/100%.txt", 1L, false),
        item("equip/abc.txt", 1L, false),
        item("equip/보고서.md", 1L, false));

    assertThat(service().searchObjects("firehub-files", "equip/", "%", null, 10, 100).objects())
        .extracting("name")
        .containsExactly("100%.txt");
  }

  /** 검색 결과에 한글 검색어가 부분 일치한다. */
  @Test
  void searchObjects_matchesKorean() throws Exception {
    givenListing(item("equip/abc.txt", 1L, false), item("equip/월간보고서.md", 1L, false));

    assertThat(service().searchObjects("firehub-files", "equip/", "보고", null, 10, 100).objects())
        .extracting("name")
        .containsExactly("월간보고서.md");
  }

  /** 폴더 표식 오브젝트("tibero/" 0바이트)와 데이터셋 루트 표식("equip/")은 파일이 아니므로 검색 결과에서 뺀다. */
  @Test
  void searchObjects_skipsFolderMarkers() throws Exception {
    givenListing(
        item("equip/", 0L, false),
        item("equip/tibero/", 0L, false),
        item("equip/tibero/a.txt", 1L, false));

    assertThat(
            service().searchObjects("firehub-files", "equip/", "tibero", null, 10, 100).objects())
        .extracting("name")
        .containsExactly("tibero/a.txt");
  }

  /** 페이지가 size 로 차면 hasMore = 이터레이터에 더 있음, nextToken = 마지막으로 훑은 키. 토큰은 startAfter 로 넘긴다. */
  @Test
  void searchObjects_fillsPageThenReportsHasMoreAndToken() throws Exception {
    givenListing(item("equip/a1.txt", 1L, false), item("equip/a2.txt", 1L, false));

    ObjectListResponse resp =
        service().searchObjects("firehub-files", "equip/", "a", "equip/a0.txt", 1, 100);

    assertThat(captureListArgs().startAfter()).isEqualTo("equip/a0.txt");
    assertThat(resp.objects()).extracting("key").containsExactly("equip/a1.txt");
    assertThat(resp.hasMore()).isTrue();
    assertThat(resp.nextToken()).isEqualTo("equip/a1.txt");
    assertThat(resp.truncated()).isFalse();
  }

  /** 훑는 오브젝트 수 상한에 닿았는데 더 남았으면 truncated=true 이고, 이어 보기는 없다(hasMore=false, nextToken=null). */
  @Test
  void searchObjects_truncatedWhenScanLimitReachedWithMoreLeft() throws Exception {
    givenListingWithPeek(
        item("equip/x1.txt", 1L, false),
        item("equip/x2.txt", 1L, false),
        item("equip/a.txt", 1L, false));

    ObjectListResponse resp = service().searchObjects("firehub-files", "equip/", "a", null, 10, 2);

    assertThat(resp.objects()).isEmpty();
    assertThat(resp.truncated()).isTrue();
    assertThat(resp.hasMore()).isFalse();
    assertThat(resp.nextToken()).isNull();
  }

  /** WD-783: 객체가 딱 scanLimit 개이고 페이지 미달이면 엿보기(maxKeys 1)가 빈 결과라 남은 게 없으므로 truncated=false 다. */
  @Test
  void searchExactlyScanLimitObjectsIsNotTruncated() throws Exception {
    givenListingWithPeek(item("equip/x1.txt", 1L, false), item("equip/a.txt", 1L, false));

    ObjectListResponse resp = service().searchObjects("firehub-files", "equip/", "a", null, 10, 2);

    assertThat(resp.objects()).extracting("name").containsExactly("a.txt");
    assertThat(resp.truncated()).isFalse();
    assertThat(resp.hasMore()).isFalse();
  }

  /** WD-783: scanLimit+1 개면 엿보기가 남은 하나를 돌려주므로 truncated=true 다. */
  @Test
  void searchMoreThanScanLimitIsTruncated() throws Exception {
    givenListingWithPeek(
        item("equip/x1.txt", 1L, false),
        item("equip/a.txt", 1L, false),
        item("equip/x3.txt", 1L, false));

    ObjectListResponse resp = service().searchObjects("firehub-files", "equip/", "a", null, 10, 2);

    assertThat(resp.truncated()).isTrue();
    assertThat(resp.hasMore()).isFalse();
  }

  /** 상한보다 적게 훑고 목록이 끝났으면 잘린 게 아니다. */
  @Test
  void searchObjects_notTruncatedWhenListingEndsBeforeScanLimit() throws Exception {
    givenListing(item("equip/x1.txt", 1L, false), item("equip/a.txt", 1L, false));

    ObjectListResponse resp = service().searchObjects("firehub-files", "equip/", "a", null, 10, 3);

    assertThat(resp.objects()).extracting("name").containsExactly("a.txt");
    assertThat(resp.truncated()).isFalse();
    assertThat(resp.hasMore()).isFalse();
  }

  /**
   * WD-783: 페이지를 못 채우고 상한에 닿으면 정말 남았는지 따로 maxKeys=1 엿보기 요청(startAfter=마지막으로 훑은 키)으로 묻는다 — 본 이터레이터의
   * hasNext() 는 다음 S3 쪽(최대 1000 키)을 통째로 받아 오므로 쓰지 않는다(WD-776 이 없앤 낭비). 남은 게 있으면 truncated=true 이고
   * 이어 보기는 없다.
   */
  @Test
  void searchObjects_notFullPageAtScanLimitPeeksWithSingleKeyRequest() throws Exception {
    Item[] all = {
      item("equip/x1.txt", 1L, false),
      item("equip/x2.txt", 1L, false),
      item("equip/x3.txt", 1L, false),
      item("equip/x4.txt", 1L, false)
    };
    PagedListing listing = new PagedListing(2, all);
    List<ListObjectsArgs> calls = new java.util.ArrayList<>();
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenAnswer(
            inv -> {
              ListObjectsArgs args = inv.getArgument(0);
              calls.add(args);
              return args.maxKeys() == 1 ? peekResults(args, all) : listing;
            });

    ObjectListResponse resp = service().searchObjects("firehub-files", "equip/", "a", null, 10, 2);

    // 본 목록은 첫 S3 쪽만 받고, 남았는지는 1 키 엿보기 요청 한 번으로 안다.
    assertThat(listing.pagesFetched).isEqualTo(1);
    assertThat(calls).hasSize(2);
    ListObjectsArgs peek = calls.get(1);
    assertThat(peek.maxKeys()).isEqualTo(1);
    assertThat(peek.startAfter()).isEqualTo("equip/x2.txt");
    assertThat(peek.prefix()).isEqualTo("equip/");
    assertThat(peek.recursive()).isTrue();
    assertThat(resp.objects()).isEmpty();
    assertThat(resp.truncated()).isTrue();
    assertThat(resp.hasMore()).isFalse();
    assertThat(resp.nextToken()).isNull();
  }

  /**
   * WD-783: 엿보기 요청이 실패해도 검색 전체를 실패시키지 않는다 — 이미 모은 결과를 돌려주고, 남았는지 모르므로 보수적으로 truncated=true 로 알린다.
   */
  @Test
  void searchObjects_peekFailureReturnsCollectedResultsAsTruncated() throws Exception {
    List<Result<Item>> main =
        List.of(
            new Result<>(item("equip/a1.txt", 1L, false)),
            new Result<>(item("equip/x2.txt", 1L, false)));
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenAnswer(
            inv -> {
              ListObjectsArgs args = inv.getArgument(0);
              if (args.maxKeys() == 1) throw new IllegalStateException("peek boom");
              return main;
            });

    ObjectListResponse resp = service().searchObjects("firehub-files", "equip/", "a", null, 10, 2);

    assertThat(resp.objects()).extracting("key").containsExactly("equip/a1.txt");
    assertThat(resp.truncated()).isTrue();
    assertThat(resp.hasMore()).isFalse();
    assertThat(resp.nextToken()).isNull();
  }

  /**
   * WD-776: 페이지가 찬 순간이 상한과 겹쳐도 다음 S3 쪽을 받지 않는다 — 「더 있을 수 있음」으로 이어 보기(hasMore·nextToken=마지막으로 훑은 키)를
   * 준다. 정말 끝이었다면 다음 요청이 빈 쪽 + hasMore=false 로 끝난다.
   */
  @Test
  void searchObjects_pageFullAtScanLimitOffersContinuationWithoutPeeking() throws Exception {
    PagedListing listing =
        new PagedListing(
            2,
            item("equip/a1.txt", 1L, false),
            item("equip/a2.txt", 1L, false),
            item("equip/a3.txt", 1L, false));
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(listing);

    ObjectListResponse resp = service().searchObjects("firehub-files", "equip/", "a", null, 2, 2);

    assertThat(listing.pagesFetched).isEqualTo(1);
    assertThat(resp.objects()).extracting("key").containsExactly("equip/a1.txt", "equip/a2.txt");
    assertThat(resp.hasMore()).isTrue();
    assertThat(resp.nextToken()).isEqualTo("equip/a2.txt");
    assertThat(resp.truncated()).isFalse();
  }

  /** 상한 전에 페이지가 차면 지금처럼 hasNext() 로 정확히 판정한다 — 더 없으면 이어 보기를 주지 않는다. */
  @Test
  void searchObjects_pageFullBeforeScanLimitPeeksForExactHasMore() throws Exception {
    PagedListing listing =
        new PagedListing(2, item("equip/a1.txt", 1L, false), item("equip/a2.txt", 1L, false));
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(listing);

    ObjectListResponse resp = service().searchObjects("firehub-files", "equip/", "a", null, 2, 100);

    assertThat(resp.objects()).hasSize(2);
    assertThat(resp.hasMore()).isFalse();
    assertThat(resp.nextToken()).isNull();
  }

  // ---------------------------------------------------------------------------------------
  // WD-776: 폴더 이름 보존 — 목록에 보이는 폴더는 들어갈 수 있어야 한다.
  // ---------------------------------------------------------------------------------------

  /**
   * '\' 가 든 폴더·공백뿐인 폴더는 이름 그대로 보이고(들어갈 때 서버가 그대로 prefix 로 쓴다), 폴더 경로 정규화가 거부하거나 다른 값으로 바꾸는 이름은 모두
   * 뺀다 — 목록에 보이면 반드시 열려야 한다. '.'·'..'(MinIO SDK 가 presign 을 거부하고 URL 점 세그먼트가 접힌다), '\' 로 나눈 조각이
   * '..' 인 이름, 제어문자가 든 이름이 그렇다(검색으로는 찾을 수 있다).
   */
  @Test
  void listFolder_hidesFoldersThatNormalizeFolderPathWouldNotOpen() throws Exception {
    givenListing(
        item("equip/ /", 0L, true),
        item("equip/./", 0L, true),
        item("equip/../", 0L, true),
        item("equip/a\\..\\b/", 0L, true),
        item("equip/bad\u0001name/", 0L, true),
        item("equip/report\\2024/", 0L, true));

    ObjectListResponse resp = service().listFolder("firehub-files", "equip/", "", null, 10);

    assertThat(resp.folders()).extracting("path").containsExactly(" /", "report\\2024/");
    assertThat(resp.folders()).extracting("name").containsExactly(" ", "report\\2024");
  }

  /** 그런 폴더 안 목록은 정규화한 폴더 값을 그대로 이어 붙인 prefix 로 S3 에 묻는다. */
  @Test
  void listFolder_usesBackslashAndBlankFolderAsPrefix() throws Exception {
    givenListing();

    service().listFolder("firehub-files", "equip/", "report\\2024/ /", null, 10);

    assertThat(captureListArgs().prefix()).isEqualTo("equip/report\\2024/ /");
  }

  /** listObjects 에 넘긴 인자를 꺼낸다(호출이 정확히 한 번이어야 한다). */
  private ListObjectsArgs captureListArgs() {
    ArgumentCaptor<ListObjectsArgs> captor = ArgumentCaptor.forClass(ListObjectsArgs.class);
    verify(minioClient).listObjects(captor.capture());
    return captor.getValue();
  }

  /**
   * MinIO listObjects 가 주어진 항목을 차례로 돌려주게 스텁한다. 항목 mock 은 인자로 먼저 만들어진 뒤 스텁하므로 when(...) 안에서 다른 mock
   * 을 스텁하는 UnfinishedStubbing 이 생기지 않는다.
   */
  private void givenListing(Item... items) {
    List<Result<Item>> results = java.util.Arrays.stream(items).map(Result::new).toList();
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(results);
  }

  /**
   * 검색의 잘림 판정 엿보기(WD-783)까지 흉내 내는 스텁 — maxKeys=1 요청에는 startAfter 뒤 첫 항목 하나(없으면 빈 목록)를, 그 밖의 요청에는
   * 전체를 돌려준다. 같은 목록을 모든 호출에 돌려주면 엿보기가 늘 「남았음」으로 보여 딱 상한 개수인 경우를 가릴 수 없다.
   */
  private void givenListingWithPeek(Item... items) {
    List<Result<Item>> results = java.util.Arrays.stream(items).map(Result::new).toList();
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenAnswer(
            inv -> {
              ListObjectsArgs args = inv.getArgument(0);
              return args.maxKeys() == 1 ? peekResults(args, items) : results;
            });
  }

  /**
   * 엿보기 요청 응답 — 목록 순서에서 startAfter 키 바로 다음 항목 하나만 돌려준다(WD-783). 픽스처 키가 사전순이 아닌 경우가 있어(x1 다음 a) 문자열
   * 비교가 아니라 주어진 목록 순서(S3 가 돌려주는 순서로 본다)를 따른다. startAfter 가 없으면 첫 항목이다.
   */
  private static List<Result<Item>> peekResults(ListObjectsArgs args, Item[] items) {
    String after = args.startAfter();
    int from = 0;
    if (after != null) {
      from = items.length;
      for (int i = 0; i < items.length; i++) {
        if (items[i].objectName().equals(after)) {
          from = i + 1;
          break;
        }
      }
    }
    return java.util.Arrays.stream(items).skip(from).limit(1).map(Result::new).toList();
  }

  /**
   * 목록 항목 mock. 폴더(common prefix)는 isDir=true. 어떤 게터가 불릴지는 모드마다 다르고, 페이지가 차서 hasNext 로만 존재가 확인되는
   * 항목도 있어 lenient 로 스텁한다.
   */
  private static Item item(String key, long size, boolean dir) {
    Item item = org.mockito.Mockito.mock(Item.class);
    org.mockito.Mockito.lenient().when(item.isDir()).thenReturn(dir);
    org.mockito.Mockito.lenient().when(item.objectName()).thenReturn(key);
    org.mockito.Mockito.lenient().when(item.size()).thenReturn(size);
    org.mockito.Mockito.lenient().when(item.lastModified()).thenReturn(null);
    return item;
  }

  /**
   * MinIO SDK listObjects 처럼 S3 쪽(page)을 지연으로 받는 목록(WD-776). 버퍼가 비었을 때 hasNext() 를 부르면 남은 항목이 있으면
   * 다음 쪽을 받고(pagesFetched 증가), 없으면 받지 않고 false 다(S3 가 isTruncated=false 를 이미 알려 준 것과 같다).
   */
  private static final class PagedListing implements Iterable<Result<Item>> {
    private final List<Item> all;
    private final int pageSize;
    int pagesFetched = 0;

    PagedListing(int pageSize, Item... items) {
      this.all = List.of(items);
      this.pageSize = pageSize;
    }

    @Override
    public java.util.Iterator<Result<Item>> iterator() {
      return new java.util.Iterator<>() {
        private int fetchedUpTo = 0; // 받아 온 항목 수
        private int cursor = 0; // 소비한 항목 수

        @Override
        public boolean hasNext() {
          if (cursor < fetchedUpTo) return true;
          if (fetchedUpTo >= all.size()) return false;
          // 버퍼가 비었고 더 있다 → 다음 S3 쪽을 받는다.
          fetchedUpTo = Math.min(fetchedUpTo + pageSize, all.size());
          pagesFetched++;
          return true;
        }

        @Override
        public Result<Item> next() {
          if (!hasNext()) throw new java.util.NoSuchElementException();
          return new Result<>(all.get(cursor++));
        }
      };
    }
  }

  private static String signature(String url) {
    return url.replaceAll(".*X-Amz-Signature=([0-9a-f]+).*", "$1");
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
