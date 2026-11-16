package com.smartfirehub.file.service;

import com.smartfirehub.file.config.MinioProperties;
import com.smartfirehub.file.dto.ObjectFolderResponse;
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
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** MinIO(S3 호환) 오브젝트 스토리지 접근 서비스. 대량 파일 바이트는 앱을 통과하지 않으며, 목록 조회와 presigned GET URL 발급만 담당한다. */
@Service
public class FileObjectStorageService {

  private final MinioClient minioClient; // 내부 통신용(목록/조회)
  private final MinioClient presignMinioClient; // presign 서명용(공개 엔드포인트로 서명)
  private final MinioProperties props;

  public FileObjectStorageService(
      MinioClient minioClient,
      @Qualifier("presignMinioClient") MinioClient presignMinioClient,
      MinioProperties props) {
    this.minioClient = minioClient;
    this.presignMinioClient = presignMinioClient;
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
        items.add(toItem(item, prefix));
      }
      // 현재 페이지를 채운 뒤에도 이터레이터에 원소가 남아 있으면 다음 페이지가 존재하는 것이다.
      hasMore = it.hasNext();
    } catch (Exception e) {
      throw new RuntimeException("오브젝트 목록 조회 실패: " + e.getMessage(), e);
    }

    String nextToken = hasMore ? lastKey : null;
    return new ObjectListResponse(items, nextToken, hasMore);
  }

  /**
   * 폴더 모드 목록(WD-761): 데이터셋 prefix + folderPath 바로 아래만 한 단계로 읽어 하위 폴더와 파일을 나눈다.
   *
   * <p>S3 delimiter('/') 목록이라 하위 폴더는 common prefix(isDir) 로 오고, 그 안의 파일은 오지 않는다 — 수만 개 파일이 있어도 지금
   * 폴더의 항목만 읽는다. 폴더와 파일 모두 페이지 size 에 세며, 토큰은 기존처럼 startAfter = 마지막 항목 키(폴더면 그 prefix)다.
   *
   * @param folderPath {@link ObjectKeyGenerator#normalizeFolderPath} 결과("" 또는 "a/b/")
   */
  public ObjectListResponse listFolder(
      String bucket, String prefix, String folderPath, String continuationToken, int maxKeys) {
    String listPrefix = prefix + folderPath;
    boolean hasToken = continuationToken != null && !continuationToken.isBlank();
    // 건너뛸 수 있는 항목(토큰과 같은 폴더·현재 폴더 표식)은 목록 맨 앞에 최대 1개라, S3 한 페이지에 그만큼 + 다음 존재 확인 1개 여유를 둔다.
    ListObjectsArgs.Builder builder =
        ListObjectsArgs.builder()
            .bucket(bucket)
            .prefix(listPrefix)
            .maxKeys(Math.min(maxKeys + 2, 1000))
            .recursive(false)
            .delimiter("/");
    if (hasToken) {
      builder.startAfter(continuationToken);
    }

    List<ObjectItemResponse> items = new ArrayList<>();
    List<ObjectFolderResponse> folders = new ArrayList<>();
    String lastKey = null;
    boolean hasMore;
    try {
      Iterator<Result<Item>> it = minioClient.listObjects(builder.build()).iterator();
      while (it.hasNext() && items.size() + folders.size() < maxKeys) {
        Item item = it.next().get();
        String key = item.objectName();
        // S3/MinIO 는 startAfter 가 폴더 prefix 와 같으면 그 폴더(common prefix)를 다시 돌려준다 — 이전 페이지 마지막 항목이므로
        // 건너뛴다.
        if (hasToken && key.equals(continuationToken)) continue;
        // 현재 폴더 자신을 가리키는 키(0바이트 폴더 표식 오브젝트 "a/")는 이름이 빈 항목이라 빈 줄이 되지 않게 건너뛴다.
        if (key.equals(listPrefix)) continue;
        String relative = relativeName(key, prefix);
        if (item.isDir()) {
          String name = lastSegment(relative);
          // "a//" 처럼 빈 세그먼트로 끝나는 prefix 는 이름이 없어 들어갈 수 없는 빈 줄이 된다 — 건너뛴다(검색으로는 찾을 수 있다).
          if (name.isEmpty()) continue;
          folders.add(new ObjectFolderResponse(relative, name));
        } else {
          items.add(toItem(item, prefix));
        }
        lastKey = key;
      }
      // 토큰 폴더·현재 폴더 표식은 맨 앞에만 오므로, 페이지를 채운 뒤 남은 원소가 있으면 다음 페이지가 있다.
      // (남은 것이 이름 빈 "a//" 뿐인 드문 경우엔 다음 페이지가 비어 오고 hasMore=false 로 끝난다.)
      hasMore = it.hasNext();
    } catch (Exception e) {
      throw new RuntimeException("오브젝트 목록 조회 실패: " + e.getMessage(), e);
    }

    String nextToken = hasMore ? lastKey : null;
    return new ObjectListResponse(items, folders, nextToken, hasMore, false);
  }

  /**
   * 검색 모드 목록(WD-761): 데이터셋 prefix 전체를 재귀로 훑으며 상대 경로(prefix 제외)에 검색어가 들어간 파일을 찾는다.
   *
   * <p>S3 에는 이름 검색이 없어 목록을 훑어 거른다. 대소문자 무시(Locale.ROOT)의 단순 부분 일치라 정규식·'%' 는 글자 그대로다. 한 요청에서 훑는
   * 오브젝트 수를 scanLimit 으로 막는다 — 파일이 아주 많은 데이터셋에서 한 요청이 끝없이 길어지지 않게 하기 위해서다.
   *
   * <ul>
   *   <li>페이지가 maxKeys 로 차면 hasMore = 더 남았는지, nextToken = 마지막으로 훑은 키(다음 요청은 새 상한으로 이어 훑는다).
   *   <li>페이지를 못 채우고 상한에 닿았는데 더 남았으면 truncated=true, 이어 보기는 없다(hasMore=false, nextToken=null).
   * </ul>
   *
   * @param query 공백이 아닌 검색어(컨트롤러가 앞뒤 공백을 뗀다)
   * @param scanLimit 한 요청에서 훑을 오브젝트 수 상한
   */
  public ObjectListResponse searchObjects(
      String bucket,
      String prefix,
      String query,
      String continuationToken,
      int maxKeys,
      int scanLimit) {
    String needle = query.toLowerCase(Locale.ROOT);
    // 걸러낼 항목이 많으므로 S3 한 번에 최대치(1000)씩 읽어 왕복을 줄인다(SDK 가 다음 페이지를 지연으로 따라간다).
    ListObjectsArgs.Builder builder =
        ListObjectsArgs.builder().bucket(bucket).prefix(prefix).maxKeys(1000).recursive(true);
    if (continuationToken != null && !continuationToken.isBlank()) {
      builder.startAfter(continuationToken);
    }

    List<ObjectItemResponse> items = new ArrayList<>();
    String lastScannedKey = null;
    int scanned = 0;
    boolean remaining;
    try {
      Iterator<Result<Item>> it = minioClient.listObjects(builder.build()).iterator();
      // 상한 검사를 hasNext() 보다 먼저 한다 — 상한(1000 의 배수)에 닿은 뒤 hasNext() 가 다음 S3 페이지를 한 번 더 받지 않게.
      while (scanned < scanLimit && items.size() < maxKeys && it.hasNext()) {
        Item item = it.next().get();
        scanned++;
        String key = item.objectName();
        lastScannedKey = key;
        if (item.isDir()) continue;
        String relative = relativeName(key, prefix);
        // 이름이 빈 루트 표식("prefix/")·폴더 표식("a/")은 파일이 아니므로 결과에 넣지 않는다(훑은 수에는 센다).
        if (relative.isEmpty() || relative.endsWith("/")) continue;
        if (relative.toLowerCase(Locale.ROOT).contains(needle)) {
          items.add(toItem(item, prefix));
        }
      }
      remaining = it.hasNext();
    } catch (Exception e) {
      throw new RuntimeException("오브젝트 검색 실패: " + e.getMessage(), e);
    }

    // 페이지가 찼으면 이어 보기 — 상한과 동시에 닿았어도 다음 요청이 그 뒤부터 새 상한으로 훑으면 되므로 잘림이 아니다.
    if (items.size() >= maxKeys) {
      return new ObjectListResponse(
          items, List.of(), remaining ? lastScannedKey : null, remaining, false);
    }
    // 페이지를 못 채운 채 상한에 닿았는데 더 남았으면 일부만 찾은 것이다 — 이어 보기를 주지 않고 잘림만 알린다.
    boolean truncated = scanned >= scanLimit && remaining;
    return new ObjectListResponse(items, List.of(), null, false, truncated);
  }

  /**
   * 목록 항목 → 응답 한 줄. 세 목록 모드(평면·폴더·검색)가 같은 표시명·수정일 규칙을 쓰게 한곳에 둔다. 표시명은 prefix 를 뺀 상대 경로(폴더 구조 노출)다.
   */
  private static ObjectItemResponse toItem(Item item, String prefix) {
    String key = item.objectName();
    String modified = item.lastModified() != null ? item.lastModified().toString() : null;
    return new ObjectItemResponse(key, relativeName(key, prefix), item.size(), modified);
  }

  /** 데이터셋 prefix 를 뺀 상대 경로. 항상 prefix 하위를 조회하므로 보통 startsWith 가 참이다. */
  private static String relativeName(String key, String prefix) {
    return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
  }

  /** "a/b/" 같은 폴더 상대 경로의 마지막 세그먼트("b"). 끝 '/' 하나를 떼고 마지막 '/' 뒤를 취한다. */
  private static String lastSegment(String folderRelative) {
    String trimmed =
        folderRelative.endsWith("/")
            ? folderRelative.substring(0, folderRelative.length() - 1)
            : folderRelative;
    return trimmed.substring(trimmed.lastIndexOf('/') + 1);
  }

  /**
   * 오브젝트 단건에 대한 단기 presigned GET URL을 발급한다(브라우저가 MinIO에서 직접 GET). 키의 마지막 세그먼트가 원본 파일명이므로(S3 방식),
   * 다운로드 저장명은 URL 경로에서 자연히 원본명이 된다.
   */
  public PresignedUrlResponse presignedGetUrl(String bucket, String objectKey, int expirySeconds) {
    return presignedGetUrl(bucket, objectKey, expirySeconds, "inline");
  }

  /**
   * disposition("inline" 미리보기 / "attachment" 다운로드)을 <b>서명된</b> response-content-disposition 쿼리
   * 파라미터로 넣는다 — 클라이언트가 값을 바꾸면 서명이 깨진다. attachment 는 내보내기 판정(requireExport)을 거친 호출자만 요청한다(스펙 §4.4).
   */
  public PresignedUrlResponse presignedGetUrl(
      String bucket, String objectKey, int expirySeconds, String disposition) {
    // 키의 마지막 세그먼트가 원본 파일명(S3 방식) — RFC 5987 로 인코딩해 한글 파일명도 그대로 저장되게 한다.
    String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);
    String encoded =
        java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
    try {
      // presign은 공개 엔드포인트 클라이언트로 서명해야 브라우저가 직접 GET할 수 있다.
      String url =
          presignMinioClient.getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(Method.GET)
                  .bucket(bucket)
                  .object(objectKey)
                  .expiry(expirySeconds)
                  .extraQueryParams(
                      java.util.Map.of(
                          "response-content-disposition",
                          disposition + "; filename*=UTF-8''" + encoded))
                  .build());
      return new PresignedUrlResponse(url, expirySeconds);
    } catch (Exception e) {
      throw new RuntimeException("presigned URL 발급 실패: " + e.getMessage(), e);
    }
  }

  /** 업로드용 presigned URL 기본 만료(초). 업로드는 GET 썸네일보다 느릴 수 있어 별도 설정을 사용한다. */
  public int defaultUploadPresignExpiry() {
    return props.uploadPresignExpirySeconds();
  }

  /** 오브젝트 단건에 대한 단기 presigned PUT URL을 발급한다(클라이언트가 MinIO로 직접 업로드, 앱 미경유). */
  public PresignedUrlResponse presignedPutUrl(String bucket, String objectKey, int expirySeconds) {
    try {
      // presign은 공개 엔드포인트 클라이언트로 서명해야 브라우저가 직접 PUT할 수 있다.
      String url =
          presignMinioClient.getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(Method.PUT)
                  .bucket(bucket)
                  .object(objectKey)
                  .expiry(expirySeconds)
                  .build());
      return new PresignedUrlResponse(url, expirySeconds);
    } catch (Exception e) {
      throw new RuntimeException("presigned PUT URL 발급 실패: " + e.getMessage(), e);
    }
  }
}
