package com.smartfirehub.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.smartfirehub.document.dto.DocumentFileResponse;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DocumentFileRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-712). document_file 은 테넌트 정책 아래 있다. 상태
 * 전이·4000자 절단· 빈 문자열(Tibero 는 NULL)·체크섬 중복 감지·최신순 정렬·다른 테넌트 차단을 고정한다.
 */
public abstract class DocumentFileRepositoryContractTest extends TenantPairContractBase {

  protected abstract DocumentFileRepository repository();

  private long userA;

  @BeforeEach
  void setUpUser() {
    userA = fx.createUser(prefix + "-u", null);
  }

  private long create(long ds, String name, String checksum) {
    return inA(
        () ->
            repository()
                .create(
                    ds,
                    name,
                    "application/pdf",
                    2048L,
                    "docs/" + prefix + "/" + name,
                    checksum,
                    userA));
  }

  private DocumentFileResponse get(long id) {
    return inA(() -> repository().findById(id)).orElseThrow();
  }

  private static LocalDateTime utcNow() {
    return LocalDateTime.now();
  }

  @Test
  @DisplayName("create 는 PENDING 으로 만들고 필드·생성 시각이 그대로 읽힌다")
  void create_roundTrip() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long id = create(ds, "계약서 한글.pdf", "abc123");

    DocumentFileResponse r = get(id);

    assertThat(r.id()).isEqualTo(id);
    assertThat(r.datasetId()).isEqualTo(ds);
    assertThat(r.originalName()).isEqualTo("계약서 한글.pdf");
    assertThat(r.mimeType()).isEqualTo("application/pdf");
    assertThat(r.fileSize()).isEqualTo(2048L);
    assertThat(r.status()).isEqualTo("PENDING");
    assertThat(r.pageCount()).isNull();
    assertThat(r.chunkCount()).isNull();
    assertThat(r.errorDetail()).isNull();
    assertThat(r.uploadedBy()).isEqualTo(userA);
    assertThat(r.createdAt()).isCloseTo(utcNow(), within(3, ChronoUnit.MINUTES));
    assertThat(r.completedAt()).isNull();
    assertThat(inA(() -> repository().findStoragePath(id)))
        .isEqualTo("docs/" + prefix + "/계약서 한글.pdf");
    assertThat(inA(() -> repository().findStoragePath(-1L))).isNull();
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
    assertThat(fx.countTenantRows("document_file", tenantA)).isEqualTo(1);
  }

  @Test
  @DisplayName("체크섬 없이도(NULL) 만들 수 있다")
  void create_nullChecksum() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long id = create(ds, "n.pdf", null);

    assertThat(get(id).status()).isEqualTo("PENDING");
    assertThat(inA(() -> repository().existsByChecksum(ds, null))).isFalse();
  }

  @Test
  @DisplayName("updateStatus 와 markCompleted 가 상태·페이지/청크 수·완료 시각을 바꾼다(페이지 수 NULL 허용)")
  void statusTransitions() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long id = create(ds, "a.pdf", "c1");

    runA(() -> repository().updateStatus(id, "PARSING"));
    assertThat(get(id).status()).isEqualTo("PARSING");

    runA(() -> repository().markCompleted(id, 12, 34));
    DocumentFileResponse done = get(id);
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(done.pageCount()).isEqualTo(12);
    assertThat(done.chunkCount()).isEqualTo(34);
    assertThat(done.completedAt()).isCloseTo(utcNow(), within(3, ChronoUnit.MINUTES));

    long id2 = create(ds, "b.pdf", "c2");
    runA(() -> repository().markCompleted(id2, null, 0));
    DocumentFileResponse noPages = get(id2);
    assertThat(noPages.pageCount()).isNull();
    assertThat(noPages.chunkCount()).isZero();
  }

  @Test
  @DisplayName("markFailed 는 오류를 4000자로 자르고 null 이면 unknown 을 저장한다")
  void markFailed_truncatesAndDefaults() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long id = create(ds, "a.pdf", "c1");
    String longError = "오류".repeat(2500);

    runA(() -> repository().markFailed(id, longError));
    DocumentFileResponse failed = get(id);
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.errorDetail()).isEqualTo(longError.substring(0, 4000));

    runA(() -> repository().markFailed(id, null));
    assertThat(get(id).errorDetail()).isEqualTo("unknown");
  }

  @Test
  @DisplayName("빈 문자열 오류·체크섬 — PG 는 그대로, Tibero 는 NULL 로 돌아온다")
  void emptyStrings() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long id = create(ds, "a.pdf", "");
    runA(() -> repository().markFailed(id, ""));

    DocumentFileResponse r = get(id);

    assertThat(r.errorDetail()).isEqualTo(emptyStringIsNull() ? null : "");
    // 빈 체크섬은 PG 에서만 일치한다(Tibero 는 NULL 비교라 거짓).
    assertThat(inA(() -> repository().existsByChecksum(ds, ""))).isEqualTo(!emptyStringIsNull());
  }

  @Test
  @DisplayName("existsByChecksum 은 같은 데이터셋의 같은 체크섬만 참이다")
  void existsByChecksum_scopedToDataset() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long other = fx.createDataset(tenantA, prefix + " other");
    create(ds, "a.pdf", "same");

    assertThat(inA(() -> repository().existsByChecksum(ds, "same"))).isTrue();
    assertThat(inA(() -> repository().existsByChecksum(ds, "diff"))).isFalse();
    assertThat(inA(() -> repository().existsByChecksum(other, "same"))).isFalse();
  }

  @Test
  @DisplayName("findByDataset 은 최신순이고 다른 데이터셋 행을 섞지 않는다")
  void findByDataset_newestFirst() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long other = fx.createDataset(tenantA, prefix + " other");
    long first = create(ds, "1.pdf", "c1");
    long second = create(ds, "2.pdf", "c2");
    long third = create(ds, "3.pdf", "c3");
    create(other, "x.pdf", "cx");

    List<DocumentFileResponse> rows = inA(() -> repository().findByDataset(ds));

    assertThat(rows).extracting(DocumentFileResponse::id).containsExactly(third, second, first);
    assertThat(inA(() -> repository().findByDataset(-1L))).isEmpty();
  }

  @Test
  @DisplayName("delete 는 그 행만 지운다")
  void delete_removesOnlyThatRow() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long a = create(ds, "a.pdf", "c1");
    long b = create(ds, "b.pdf", "c2");

    runA(() -> repository().delete(a));

    assertThat(inA(() -> repository().findById(a))).isEmpty();
    assertThat(inA(() -> repository().findById(b))).isPresent();
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트에서는 조회·확인이 비고 상태 변경·삭제도 행에 닿지 않는다")
  void otherTenantIsBlocked() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long id = create(ds, "a.pdf", "c1");

    assertThat(inB(() -> repository().findById(id))).isEmpty();
    assertThat(inB(() -> repository().findByDataset(ds))).isEmpty();
    assertThat(inB(() -> repository().findStoragePath(id))).isNull();
    assertThat(inB(() -> repository().existsByChecksum(ds, "c1"))).isFalse();
    inB(
        () -> {
          repository().updateStatus(id, "FAILED");
          repository().markFailed(id, "x");
          repository().delete(id);
          return null;
        });

    assertThat(get(id).status()).isEqualTo("PENDING");
  }
}
