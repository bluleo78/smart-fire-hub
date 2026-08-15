package com.smartfirehub.file.service;

import static com.smartfirehub.jooq.Tables.UPLOADED_FILES;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileCleanupService {

  private final DSLContext dsl;
  private final TenantScopedRunner tenantScopedRunner;
  private final TransactionTemplate transactionTemplate;

  /**
   * 기동 시 1회 정리.
   *
   * <p>{@link #cleanupExpiredFiles()} 가 스스로 테넌트를 순회하고 DB 구간을 트랜잭션으로 감싸므로
   * 여기서 따로 배선할 것이 없다 — 위임만 한다(배선을 두 곳에 복제하면 한쪽만 고치는 사고가 난다).
   */
  @PostConstruct
  public void cleanupOnStartup() {
    log.info("Running file cleanup on startup...");
    cleanupExpiredFiles();
  }

  /**
   * 만료된 업로드 파일을 정리한다.
   *
   * <p>디스크 파일 삭제에 성공한 파일만 추적하여 해당 레코드만 삭제한다. 디스크 삭제 실패 시 경고 로그만 남기고 DB 레코드는 유지하여 이후 재시도가 가능하도록 한다.
   * 이렇게 함으로써 디스크에 파일이 남아있지만 DB 추적이 사라지는 고아 파일(orphan) 문제를 방지한다.
   *
   * <p><b>테넌트 순회(P2-b)</b>: 스케줄러에는 원 HTTP 요청이 없어 승계할 테넌트가 없다 — ACTIVE
   * 테넌트를 순회한다. 순회하지 않으면 RLS 가 uploaded_files 를 전부 차단해 만료 파일이 예외도 로그도
   * 없이 영구히 쌓인다.
   *
   * <p><b>트랜잭션 경계</b>: 이 메서드는 리포지토리를 거치지 않고 {@code DSLContext} 를 직접 쓰므로,
   * 순회만으로는 GUC(app.tenant_id)가 주입되지 않는다(GUC 는 {@code
   * TenantAwareTransactionManager.doBegin} 에서만 주입된다). 그래서 DB 구간을 {@code
   * TransactionTemplate} 으로 감싸되, <b>느린 디스크 삭제는 트랜잭션 밖</b>에 남긴다 — 파일 수가 많으면
   * 커넥션을 오래 점유해 풀이 고갈된다.
   */
  @Scheduled(fixedRate = 3_600_000)
  public void cleanupExpiredFiles() {
    tenantScopedRunner.forEachActiveTenant(tenantId -> cleanupExpiredFilesForTenant());
  }

  /** 한 테넌트 범위의 만료 파일 정리. 호출 시점에 TenantContext 가 설정돼 있어야 한다. */
  private void cleanupExpiredFilesForTenant() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    // 1) 읽기 — 트랜잭션 안(GUC 필요). id 를 함께 읽어 2단계 삭제가 경로 문자열이 아니라 PK 로
    // 이뤄지게 한다. 경로로 지우면 두 구간 사이에 같은 경로가 재사용/갱신됐을 때 만료되지 않은
    // 행을 지울 수 있다.
    List<Record2<Long, String>> expired =
        transactionTemplate.execute(
            status ->
                dsl.select(UPLOADED_FILES.ID, UPLOADED_FILES.STORAGE_PATH)
                    .from(UPLOADED_FILES)
                    .where(UPLOADED_FILES.EXPIRES_AT.lt(now))
                    .fetch());

    if (expired == null || expired.isEmpty()) {
      return;
    }

    // 2) 디스크 삭제 — 트랜잭션 밖. 삭제 성공한 파일의 id 만 모은다(실패분은 DB 유지 → 다음 주기 재시도).
    List<Long> deletableIds = new ArrayList<>();
    for (Record2<Long, String> row : expired) {
      String storagePath = row.value2();
      try {
        Files.deleteIfExists(Path.of(storagePath));
        deletableIds.add(row.value1());
      } catch (IOException e) {
        log.warn(
            "디스크 파일 삭제 실패 — DB 레코드를 유지하여 다음 정리 사이클에서 재시도 가능: {}: {}", storagePath, e.getMessage());
      }
    }

    // 3) 삭제 — 다시 트랜잭션 안. 만료 조건을 한 번 더 걸어, 읽기 이후 갱신돼 더는 만료가 아닌 행을
    // 지우지 않게 한다.
    int dbDeleted = 0;
    if (!deletableIds.isEmpty()) {
      Integer result =
          transactionTemplate.execute(
              status ->
                  dsl.deleteFrom(UPLOADED_FILES)
                      .where(UPLOADED_FILES.ID.in(deletableIds))
                      .and(UPLOADED_FILES.EXPIRES_AT.lt(now))
                      .execute());
      dbDeleted = result == null ? 0 : result;
    }

    int failedCount = expired.size() - deletableIds.size();
    log.info(
        "만료 업로드 파일 정리 완료 (전체: {}, 파일 삭제 성공: {}, DB 레코드 삭제: {}, 실패(DB 유지): {})",
        expired.size(),
        deletableIds.size(),
        dbDeleted,
        failedCount);
  }
}
