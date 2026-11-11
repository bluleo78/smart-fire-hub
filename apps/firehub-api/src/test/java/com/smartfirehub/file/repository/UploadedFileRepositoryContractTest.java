package com.smartfirehub.file.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.file.repository.UploadedFileRepository.ExpiredFile;
import com.smartfirehub.file.repository.UploadedFileRepository.UploadedFile;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UploadedFileRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-712). uploaded_files 는 테넌트 정책 아래 있다. 만료 경계
 * 시각(정확히 같은 시각은 만료 아님)·1000개 넘는 id 목록·다른 테넌트 차단을 고정한다.
 */
public abstract class UploadedFileRepositoryContractTest extends TenantPairContractBase {

  protected abstract UploadedFileRepository repository();

  private long userA;
  private long otherUserA;

  @BeforeEach
  void setUpUsers() {
    userA = fx.createUser(prefix + "-u", null);
    otherUserA = fx.createUser(prefix + "-o", null);
  }

  private static OffsetDateTime nowSec() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
  }

  /** 테넌트 A 로 한 건 넣고 id 를 돌려준다(호출마다 별도 트랜잭션). */
  private long insert(String name, long user, OffsetDateTime expiresAt) {
    return inA(
        () ->
            repository()
                .insert(
                    name,
                    "stored-" + name,
                    "text/plain",
                    1234L,
                    "DOCUMENT",
                    "uploads/" + prefix + "/" + name,
                    user,
                    nowSec().minusHours(1),
                    expiresAt));
  }

  @Test
  @DisplayName("insert 한 파일이 필드 그대로(한글 이름·생성 시각 왕복) 업로더로 조회된다")
  void insert_roundTrip() {
    OffsetDateTime created = nowSec().minusHours(1);
    long id = insert("보고서 한글.txt", userA, nowSec().plusDays(1));

    UploadedFile f = inA(() -> repository().findByIdAndUploader(id, userA)).orElseThrow();

    assertThat(f.id()).isEqualTo(id);
    assertThat(f.originalName()).isEqualTo("보고서 한글.txt");
    assertThat(f.mimeType()).isEqualTo("text/plain");
    assertThat(f.fileSize()).isEqualTo(1234L);
    assertThat(f.fileCategory()).isEqualTo("DOCUMENT");
    assertThat(f.storagePath()).isEqualTo("uploads/" + prefix + "/보고서 한글.txt");
    // 시각은 같은 순간이어야 한다(JVM 시간대 무관). 호출 사이에 초가 넘어갈 수 있어 몇 초 오차를 둔다.
    assertThat(f.createdAt().toInstant())
        .isBetween(created.toInstant().minusSeconds(5), created.toInstant().plusSeconds(5));
    assertThat(fx.countTenantRows("uploaded_files", tenantA)).isEqualTo(1);
  }

  @Test
  @DisplayName("남의 파일·없는 id 는 비어 돌아온다")
  void findByIdAndUploader_otherUserOrMissing() {
    long id = insert("a.txt", userA, nowSec().plusDays(1));

    assertThat(inA(() -> repository().findByIdAndUploader(id, otherUserA))).isEmpty();
    assertThat(inA(() -> repository().findByIdAndUploader(-1L, userA))).isEmpty();
  }

  @Test
  @DisplayName("findExpired 는 만료 시각이 now 보다 앞선 행만(정확히 같은 시각은 제외) 돌려준다")
  void findExpired_boundary() {
    OffsetDateTime now = nowSec();
    long past = insert("past.txt", userA, now.minusSeconds(1));
    long exact = insert("exact.txt", userA, now);
    insert("future.txt", userA, now.plusSeconds(1));

    List<ExpiredFile> expired = inA(() -> repository().findExpired(now));

    assertThat(expired).extracting(ExpiredFile::id).containsExactly(past);
    assertThat(expired.get(0).storagePath()).isEqualTo("uploads/" + prefix + "/past.txt");
    // now 를 1초 뒤로 옮기면 정확히 같던 행도 만료가 된다.
    assertThat(inA(() -> repository().findExpired(now.plusSeconds(1))))
        .extracting(ExpiredFile::id)
        .containsExactlyInAnyOrder(past, exact);
  }

  @Test
  @DisplayName("deleteExpiredByIds 는 목록 중 아직 만료인 행만 지운다 — 더는 만료가 아닌 행·빈 목록은 건드리지 않는다")
  void deleteExpiredByIds_onlyExpired() {
    OffsetDateTime now = nowSec();
    long expired1 = insert("e1.txt", userA, now.minusSeconds(10));
    long expired2 = insert("e2.txt", userA, now.minusSeconds(5));
    long alive = insert("alive.txt", userA, now.plusSeconds(60));
    long boundary = insert("b.txt", userA, now);

    assertThat(inA(() -> repository().deleteExpiredByIds(List.of(), now))).isZero();
    int deleted =
        inA(() -> repository().deleteExpiredByIds(List.of(expired1, alive, boundary), now));

    assertThat(deleted).isEqualTo(1);
    assertThat(inA(() -> repository().findByIdAndUploader(expired1, userA))).isEmpty();
    assertThat(inA(() -> repository().findByIdAndUploader(expired2, userA))).isPresent();
    assertThat(inA(() -> repository().findByIdAndUploader(alive, userA))).isPresent();
    assertThat(inA(() -> repository().findByIdAndUploader(boundary, userA))).isPresent();
  }

  @Test
  @DisplayName("id 목록이 1001개여도(Tibero IN 1000개 상한) 만료 행만 지운다")
  void deleteExpiredByIds_over1000Ids() {
    OffsetDateTime now = nowSec();
    long expired = insert("old.txt", userA, now.minusSeconds(10));
    long alive = insert("new.txt", userA, now.plusSeconds(60));
    List<Long> ids = new ArrayList<>();
    for (long i = 1; i <= 999; i++) {
      ids.add(-i);
    }
    ids.add(expired);
    ids.add(alive);
    assertThat(ids).hasSize(1001);

    int deleted = inA(() -> repository().deleteExpiredByIds(ids, now));

    assertThat(deleted).isEqualTo(1);
    assertThat(inA(() -> repository().findByIdAndUploader(alive, userA))).isPresent();
    assertThat(inA(() -> repository().findByIdAndUploader(expired, userA))).isEmpty();
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트에서는 A 의 파일이 조회·만료 목록·삭제 어디에도 닿지 않는다")
  void otherTenantIsBlocked() {
    OffsetDateTime now = nowSec();
    long expired = insert("old.txt", userA, now.minusSeconds(10));

    Optional<UploadedFile> seen = inB(() -> repository().findByIdAndUploader(expired, userA));
    assertThat(seen).isEmpty();
    assertThat(inB(() -> repository().findExpired(now)))
        .extracting(ExpiredFile::id)
        .doesNotContain(expired);
    assertThat(inB(() -> repository().deleteExpiredByIds(List.of(expired), now))).isZero();
    // A 쪽 행은 그대로다.
    assertThat(inA(() -> repository().findByIdAndUploader(expired, userA))).isPresent();
  }
}
