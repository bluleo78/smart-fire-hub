package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetWriteTarget;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데이터 테이블 쓰기의 공용 잠금 층(WD-666) — 「데이터셋 행을 잠그고, 그 다음에 데이터 테이블에 쓴다」를 모든 쓰기 경로가 한 규칙으로 따르게 한다.
 *
 * <p><b>왜.</b> WD-551·WD-566 은 데이터 탭 행 쓰기에만 데이터셋 행 잠금을 걸었다. 같은 테이블에 쓰는 가져오기·파이프라인
 * 적재(API_CALL·AI_CLASSIFY· 로컬 Python)·사용자 SQL DML 은 잠금 없이 써서, truncate·삭제·NOT NULL 필드 추가와 겹치면 여전히
 * 교착(409)·무결성 오류·「테이블 없음」이 났다.
 *
 * <p><b>잠금 모드(사다리).</b>
 *
 * <ul>
 *   <li>데이터 쓰기: {@code FOR KEY SHARE} — 쓰기끼리, 그리고 아래 NO KEY UPDATE 단계와 서로 막지 않는다. 긴 적재가 이름 변경을 붙잡지
 *       않는 이유다.
 *   <li>정보 수정(이름·설명)·필드 순서 변경: {@code FOR NO KEY UPDATE} — 같은 단계끼리는 줄 서지만 데이터 테이블 모양을 바꾸지 않으므로 데이터
 *       쓰기는 막지 않는다. 이 단계는 이 클래스가 아니라 {@link DatasetService} 가 직접 잡는다({@code
 *       DatasetRepository#lockUpdatedAtById}·{@code #lockForColumnReorder}).
 *   <li>필드 추가·변경·삭제, 데이터 탭의 비우기·교체(사용자 truncate·replace), 데이터셋 삭제: {@code FOR UPDATE} — 데이터 쓰기와 충돌해
 *       진행 중인 쓰기가 커밋될 때까지 기다리고, 뒤에 온 쓰기는 구조 변경 커밋 뒤의 모양(또는 삭제됨 → 404)을 본다.
 * </ul>
 *
 * <p><b>가져오기 REPLACE 는 데이터 쓰기(KEY SHARE)다(WD-674).</b> 파일 가져오기의 교체(PK 없는 삽입 스트림의 첫 배치 truncate, PK
 * 있는 staging 교체 promote)도 테이블을 비우지만 사용자 truncate 와 달리 FOR UPDATE 가 필요 없다.
 *
 * <ul>
 *   <li>사용자 truncate 가 FOR UPDATE 인 이유는 「행 수를 센 뒤(ACCESS SHARE) 같은 트랜잭션에서 TRUNCATE(ACCESS
 *       EXCLUSIVE)로 격상」하기 때문이다 — 둘이 겹치면 격상 교착이 나므로 데이터셋 행에서 서로 배타로 줄 세운다({@link
 *       #lockForStructureChange}).
 *   <li>가져오기 REPLACE 는 그 트랜잭션에서 대상 테이블에 닿는 첫 문장이 곧 TRUNCATE 다(행 수는 비운 뒤에 센다). 격상이 없으니 다른 가져오기
 *       REPLACE·데이터 쓰기와는 테이블 잠금에서 차례로 기다릴 뿐 교착하지 않는다(PG). Tibero 는 TRUNCATE 대신 DELETE 라 행을 하나씩
 *       잠그므로, 여러 행을 다른 순서로 잠그는 데이터 탭 쓰기와 겹치면 교착이 날 수 있다 — DB 가 교착을 감지해 한쪽을 실패시킨다(잠금 단계는 예전과 같음).
 *   <li>필드 변경·데이터셋 삭제(FOR UPDATE)와는 KEY SHARE 로도 충돌하므로 컬럼 ALTER·DROP 과 겹치지 않는다 — 구조 변경 쪽 직렬화는 그대로다.
 *   <li>PK 없는 교체는 파일 전체를 삽입하는 동안 잠금을 쥔다. FOR UPDATE 로 올리면 그동안 이름·설명 수정까지 막혀, 이 사다리가 피하려던 「긴 적재가 이름
 *       변경을 붙잡는」 일이 다시 생긴다.
 * </ul>
 *
 * <p><b>범위(입도).</b> 잠금은 데이터셋 하나·쓰기 트랜잭션 하나 단위다. 한 트랜잭션으로 묶인 쓰기(행 쓰기, 가져오기 APPEND·REPLACE, promote,
 * 맞바꿈, SQL DML)는 커밋까지 잠금을 쥔다. 페이지·배치마다 커밋하는 파이프라인 APPEND 는 배치마다 짧은 트랜잭션에서 잡고 놓는다 — 외부 API·LLM 호출 동안
 * 잠금을 쥐지 않게 하려는 것이다. 그래서 배치 사이에 구조 변경이 끼어들 수 있지만, 그 다음 배치는 새 모양을 보고 쓰거나(삭제됐으면) 「출력 데이터셋 없음」으로 깔끔히
 * 실패한다. 스테이징·임시 테이블 쓰기는 다른 테이블이라 잠그지 않는다 — 실제 테이블에 닿는 promote·맞바꿈에서 잠근다.
 *
 * <p><b>순서.</b> 항상 「데이터셋 행 → (테넌트 동기화 잠금) → 데이터 테이블」이다. 여러 데이터셋을 잠글 때는 id 오름차순으로 잡아 서로 거꾸로 기다리지 않게
 * 한다. 데이터셋 삭제 잠금(FOR UPDATE)은 증표 {@link DatasetDeleteLock} 이 잡는다(WD-675 — 규칙은 그 클래스 문서).
 *
 * <p><b>트랜잭션.</b> 잠금은 현재 Spring 트랜잭션에 묶여 커밋·롤백 때 풀린다. 트랜잭션 밖에서 잡으면 문장이 끝나자마자 풀려 아무것도 막지 못하므로 {@link
 * IllegalStateException} 으로 거부한다. 데이터 저장소가 플랫폼 DB 와 따로인 설치본(WD-494)에서는 잠금이 플랫폼 트랜잭션에 있고 쓰기는 저장소
 * 연결에서 일어난다 — 잠금은 플랫폼 트랜잭션 커밋까지 유지되므로 직렬화는 같지만, 두 커밋이 원자적이지는 않다.
 */
@Component
public class DatasetDataWriteLock {

  private final DatasetRepository datasetRepository;

  /** 잠금 + 쓰기를 한 트랜잭션으로 묶는 템플릿(기본 전파 REQUIRED — 바깥 트랜잭션이 있으면 거기에 합류해 그 커밋까지 쥔다). */
  private final TransactionTemplate tx;

  public DatasetDataWriteLock(
      DatasetRepository datasetRepository, PlatformTransactionManager transactionManager) {
    this.datasetRepository = datasetRepository;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * 데이터 쓰기 잠금(KEY SHARE)을 잡고 쓰기 대상을 같은 문장으로 읽는다.
   *
   * @throws DatasetNotFoundException 데이터셋이 없거나 기다리는 동안 삭제됐을 때(404)
   */
  public DatasetWriteTarget lockForDataWrite(Long datasetId) {
    requireTransaction();
    return datasetRepository.lockForDataWrite(datasetId).orElseThrow(DatasetNotFoundException::new);
  }

  /**
   * 구조 변경 잠금(FOR UPDATE)을 잡고 쓰기 대상을 같은 문장으로 읽는다 — 데이터 탭의 비우기·교체(사용자 truncate·replace)용. 파일 가져오기의
   * REPLACE 는 이 잠금이 아니라 데이터 쓰기 잠금을 쓴다(이유는 클래스 문서, WD-674).
   *
   * <p>truncate 는 행 수를 세며(공유 잠금) 테이블을 쥔 채 TRUNCATE(배타 잠금)로 격상한다. 두 truncate 가 겹치면 격상 교착이 나므로, 데이터
   * 테이블을 만지기 전에 데이터셋 행에서 서로 배타로 줄 세운다(WD-551).
   *
   * @throws DatasetNotFoundException 데이터셋이 없거나 기다리는 동안 삭제됐을 때(404)
   */
  public DatasetWriteTarget lockForStructureChange(Long datasetId) {
    requireTransaction();
    return datasetRepository
        .lockForStructureWrite(datasetId)
        .orElseThrow(DatasetNotFoundException::new);
  }

  /**
   * 출력 테이블 이름으로 데이터 쓰기 잠금을 잡는다 — 출력 테이블 이름만 아는 파이프라인 적재·맞바꿈용.
   *
   * @throws DatasetNotFoundException 그 테이블의 데이터셋이 없을 때(삭제됨)
   */
  public DatasetWriteTarget lockOutputForWrite(String tableName) {
    requireTransaction();
    return datasetRepository
        .lockForDataWriteByTableName(tableName)
        .orElseThrow(DatasetNotFoundException::new);
  }

  /**
   * 파이프라인 적재 대상이 실제 출력 테이블이면 데이터 쓰기 잠금을 잡고, 이번 실행의 임시 테이블({@link
   * DataTableService#TEMP_TABLE_PREFIX} — 사용자 테이블이 쓸 수 없는 예약 접두)이면 아무것도 하지 않는다. 같은 적재 함수가
   * APPEND(출력에 직접)와 REPLACE(임시 테이블)에 함께 쓰이기 때문이다 — 임시 테이블은 다른 테이블이라 잠글 필요가 없고, 출력에 닿는 맞바꿈이 따로 잠근다.
   *
   * @throws DatasetNotFoundException 실제 출력 테이블인데 데이터셋이 없을 때(삭제됨)
   */
  public void lockLoadTarget(String tableName) {
    if (tableName.startsWith(DataTableService.TEMP_TABLE_PREFIX)) {
      return;
    }
    lockOutputForWrite(tableName);
  }

  /**
   * 여러 데이터셋에 데이터 쓰기 잠금을 id 오름차순으로 잡는다 — 사용자 SQL DML 처럼 한 문장이 여러 데이터셋에 쓸 수 있는 경로용. 오름차순이라 두 DML 이 서로
   * 거꾸로 기다리지 않는다.
   *
   * @throws DatasetNotFoundException 쓰기 대상 중 하나가 없을 때(기다리는 동안 삭제됨)
   */
  public void lockAllForDataWrite(Collection<Long> datasetIds) {
    if (datasetIds.isEmpty()) {
      return;
    }
    requireTransaction();
    datasetIds.stream().sorted().forEach(this::lockForDataWrite);
  }

  /**
   * 트랜잭션(없으면 새로, 있으면 합류)을 열어 출력 테이블의 데이터 쓰기 잠금을 잡은 뒤 {@code body} 를 실행한다. 페이지·배치마다 커밋하는 파이프라인 적재와
   * 맞바꿈이 쓴다 — 잠금과 쓰기가 같은 트랜잭션에 있어야 잠금이 쓰기 커밋까지 유지된다.
   */
  public <T> T writeToOutput(String tableName, Supplier<T> body) {
    return tx.execute(
        status -> {
          lockOutputForWrite(tableName);
          return body.get();
        });
  }

  /** {@link #writeToOutput(String, Supplier)} 의 반환값 없는 판. */
  public void writeToOutput(String tableName, Runnable body) {
    writeToOutput(
        tableName,
        () -> {
          body.run();
          return null;
        });
  }

  /**
   * 트랜잭션(없으면 새로, 있으면 합류)을 열어 데이터셋 id 로 데이터 쓰기 잠금을 잡은 뒤 {@code body} 를 실행한다(WD-672) — 가져오기의 대상 테이블
   * 쓰기(APPEND·REPLACE 삽입 스트림, UPSERT·REPLACE promote)가 쓴다. {@link #writeToOutput} 의 id 판이다. 가져오기
   * REPLACE 도 데이터 쓰기 잠금(KEY SHARE)이면 충분하다 — 이유는 클래스 문서(WD-674).
   *
   * <p>WD-674: 잠금을 잡을 때 데이터셋이 없으면 {@code onGone} 이 만든 예외를 던진다. 이 변환은 잠금 한 문장에만 걸린다 — {@code body}
   * 안에서 난 {@link DatasetNotFoundException} 은 그대로 올라가, 무관한 「없음」이 「쓰는 중 삭제됨」으로 잘못 안내되지 않는다.
   *
   * @param onGone 잠금 시점에 데이터셋이 없을 때(없거나 기다리는 동안 삭제됨) 던질 예외를 만든다 — {@code body} 는 실행되지 않는다. null 을
   *     돌려주면 호출자 버그이므로 그 사실을 담은 {@link NullPointerException} 이다(WD-675)
   */
  public <T> T writeToDataset(
      Long datasetId,
      Function<DatasetNotFoundException, ? extends RuntimeException> onGone,
      Supplier<T> body) {
    return tx.execute(
        status -> {
          try {
            lockForDataWrite(datasetId);
          } catch (DatasetNotFoundException e) {
            // WD-675: onGone 이 null 을 돌려주면 `throw null` 이 원인 없는 NPE 가 된다.
            // 호출자 버그를 숨기지 않고 무엇이 잘못됐는지 남긴다.
            throw Objects.requireNonNull(
                onGone.apply(e), "writeToDataset 의 onGone 이 던질 예외를 만들지 않았습니다(null)");
          }
          return body.get();
        });
  }

  /** 잠금이 문장 끝에서 바로 풀리는 실수를 막는다 — 트랜잭션 밖 호출은 버그다. */
  static void requireTransaction() {
    requireTransaction("데이터셋 쓰기 잠금은 트랜잭션 안에서만 잡을 수 있습니다");
  }

  /**
   * {@link #requireTransaction()} 의 거부 문구를 호출자가 고르는 판 — 삭제 잠금 증표(DatasetDeleteLock)가 같은 확인을 쓰면서 「쓰기
   * 잠금」이 아닌 자기 이름으로 안내하게 한다(WD-675·WD-680).
   */
  static void requireTransaction(String outsideTransactionMessage) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(outsideTransactionMessage);
    }
  }
}
