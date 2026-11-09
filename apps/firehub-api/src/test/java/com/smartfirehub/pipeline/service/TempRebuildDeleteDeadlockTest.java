package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * WD-670 — 러너 TEMP 재생성·되돌리기·버리기·맞춤의 잠금 순서. 규칙은 「데이터셋 행 → 구조 잠금 → 테넌트 동기화 → 테이블」 하나다.
 *
 * <p><b>왜(40P01).</b> 데이터셋 삭제는 데이터셋 행 FOR UPDATE → 테넌트 동기화 잠금 순서로 잡는다. 예전 재생성·되돌리기·버리기는 저장소 DDL(보관
 * 이름 바꾸기·되돌리기·보관본 삭제)에서 테넌트 동기화 잠금을 먼저 잡은 뒤, 컬럼 메타 삭제(FK → 데이터셋 행 KEY SHARE)·등급 갱신(데이터셋 행 UPDATE)으로
 * 데이터셋 행을 잡았다 — 같은 TEMP 의 삭제와 겹치면 서로 거꾸로 기다려 교착(40P01)했다. 이제 네 경로 모두 무엇보다 먼저 데이터셋 행(+ 구조 잠금)을 잡는다.
 *
 * <p><b>수정 전에는 실패한다.</b> 수정 전에는 공용 잠금 층을 부르지 않았으므로, 첫 잠금이 던지는 표지 예외 대신 데이터셋 조회 실패(404)가 나거나 저장소 DDL
 * 이 먼저 불린다.
 */
@ExtendWith(MockitoExtension.class)
class TempRebuildDeleteDeadlockTest {

  /** 첫 잠금에서 멈췄다는 표지 — 이 예외가 나면 그 앞에 아무 일도 하지 않았다는 뜻이다. */
  static final class LockedFirst extends RuntimeException {}

  @Mock private DatasetRepository datasetRepository;
  @Mock private DatasetColumnRepository columnRepository;
  @Mock private DataTableService dataTableService;
  @Mock private DatasetSecurityService datasetSecurityService;
  @Mock private DatasetDataWriteLock writeLock;
  @InjectMocks private DatasetService datasetService;

  private static final long ID = 77L;

  private static final DatasetService.PipelineTempRebuild REBUILD =
      new DatasetService.PipelineTempRebuild(ID, "ptmp_1_s", "ptmp_1_s_bak", 123L, List.of(), 1L);

  @Test
  void 재생성은_무엇보다_먼저_데이터셋_행과_구조_잠금을_잡는다() {
    when(writeLock.lockForStructureChange(ID)).thenThrow(new LockedFirst());

    assertThatThrownBy(() -> datasetService.rebuildPipelineTempTable(ID, List.of()))
        .isInstanceOf(LockedFirst.class);

    verifyNoInteractions(dataTableService, columnRepository, datasetSecurityService);
  }

  @Test
  void 되돌리기는_테넌트_동기화_DDL_보다_먼저_데이터셋_행과_구조_잠금을_잡는다() {
    when(writeLock.lockForStructureChange(ID)).thenThrow(new LockedFirst());

    assertThatThrownBy(() -> datasetService.restorePipelineTempTable(REBUILD, 1L))
        .isInstanceOf(LockedFirst.class);

    verifyNoInteractions(dataTableService, columnRepository, datasetSecurityService);
  }

  @Test
  void 보관본_버리기는_테넌트_동기화_잠금보다_먼저_데이터셋_행과_구조_잠금을_잡는다() {
    when(writeLock.lockForStructureChange(ID)).thenThrow(new LockedFirst());

    assertThatThrownBy(() -> datasetService.discardPipelineTempBackup(REBUILD))
        .isInstanceOf(LockedFirst.class);

    verifyNoInteractions(dataTableService, columnRepository);
  }

  @Test
  void 맞춤은_저장소_맞춤보다_먼저_데이터셋_행을_잡되_구조_잠금은_잡지_않는다() {
    when(writeLock.lockRowForStructureChange(ID)).thenThrow(new LockedFirst());

    assertThatThrownBy(() -> datasetService.reconcilePipelineTempTable(ID))
        .isInstanceOf(LockedFirst.class);

    verify(writeLock).lockRowForStructureChange(ID);
    verifyNoInteractions(dataTableService, columnRepository);
    // 맞춤은 매 실행 쓰기 직전에 불린다 — 구조 잠금을 잡으면 같은 TEMP 에 쓰는 겹친 실행 때문에 409 가 날 뿐이다.
    org.mockito.Mockito.verify(writeLock, org.mockito.Mockito.never()).lockForStructureChange(ID);
  }
}
