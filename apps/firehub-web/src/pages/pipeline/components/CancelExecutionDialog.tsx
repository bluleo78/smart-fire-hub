import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';

interface CancelExecutionDialogProps {
  /** 열림 여부 — 실행 상세 헤더와 실행 이력 목록이 같은 창을 쓰도록 페이지에서 제어한다. */
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 「실행 취소」 확정 — 이 때만 취소 API 를 부른다. */
  onConfirm: () => void;
  /** 취소할 실행 id — 제목 아래에 표시해 어떤 실행을 멈추는지 분명히 한다. */
  executionId: number | null;
}

/**
 * 파이프라인 실행 취소 확인 창(WD-153).
 *
 * 왜 확인을 받는가: 취소는 되돌릴 수 없고, 즉시 멈추는 것이 아니라 "진행 중인 작업이 끝난 뒤 멈추는" 협력적 취소라
 * 결과(남는 데이터)가 스텝 종류·적재 방식에 따라 다르다. 사용자가 그 의미를 알고 누르도록 설명을 함께 보여 준다.
 *
 * 안내 문구는 서버 취소 처리(firehub-api PipelineAsyncRunner·ApiCallExecutor·AiClassifyExecutor, WD-153)와 맞춘다(WD-209) —
 * 예전 문구 「교체(REPLACE) 방식 스텝은 기존 데이터가 그대로 유지」는 이미 끝난 스텝까지 원본이 지켜지는 것처럼 읽혀 「취소=아무것도
 * 안 바뀜」으로 오해하게 했다. 실제 동작:
 * - 이미 완료된 스텝: 결과를 되돌리지 않는다(협조적 취소 설계 — 완료 스텝은 롤백하지 않는다).
 * - 진행 중인 SQL 스텝: 체크포인트가 문장 실행 직전에만 있어, 이미 보낸 문장은 끝까지 실행되고(출력 비우기·적재가 한 트랜잭션으로 커밋)
 *   결과가 반영된다. 문장을 보내기 전(준비 단계)에 닿은 취소는 그 체크포인트에서 멈춰 아무것도 쓰지 않는다.
 * - 진행 중인 Python·API 호출·AI 분류 스텝: 실행 중인 호출(API 는 페이지, AI 는 배치)을 마친 뒤 멈춘다. REPLACE 는 임시 테이블을
 *   버려 그 스텝의 교체를 반영하지 않고(기존 데이터 유지), APPEND 는 멈추기 전까지 적재된 행이 남는다. 마지막 체크포인트 뒤(적재·맞바꿈
 *   도중)에 닿은 취소는 그 스텝을 멈추지 못해 결과가 반영된다 — 그래서 문구를 「이렇게 멈춘 스텝은」으로 한정한다.
 * - 아직 시작하지 않은 스텝: 실행하지 않는다(SKIPPED).
 *
 * 왜 페이지 수준 한 곳에 두는가: 실행 이력 행은 클릭 시 상세로 이동하는데, React 합성 이벤트는 포털을 거쳐서도 부모로
 * 전파되므로 행 안에 창을 두면 창 버튼 클릭이 행 이동으로 이어진다. 목록 밖에 하나만 두고 대상 실행 id 로 제어한다.
 *
 * 닫기 버튼을 「취소」 대신 「계속 실행」으로 둔 이유: 확정 버튼 「실행 취소」 옆에 「취소」가 있으면 어느 쪽이 실행을
 * 멈추는지 헷갈린다.
 */
export function CancelExecutionDialog({ open, onOpenChange, onConfirm, executionId }: CancelExecutionDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      {/* 행 클릭(상세 이동)으로 이벤트가 번지지 않도록 막는다 — DeleteConfirmDialog 와 같은 처리 */}
      <AlertDialogContent onClick={(e) => e.stopPropagation()}>
        <AlertDialogHeader>
          <AlertDialogTitle>실행을 취소할까요?</AlertDialogTitle>
          {/* 목록을 담으므로 <p> 대신 <div> 로 렌더한다(asChild) — <p> 안의 <ul> 은 잘못된 중첩이다 */}
          <AlertDialogDescription asChild>
            <div className="space-y-2" data-testid="cancel-execution-description">
              <p>
                {executionId !== null && <>실행 #{executionId}을(를) 멈춥니다. </>}
                취소해도 이미 반영된 데이터는 되돌리지 않습니다.
              </p>
              <ul className="list-disc space-y-1 pl-4 text-left">
                <li>이미 완료된 스텝의 결과는 그대로 남습니다.</li>
                <li>이미 실행을 시작한 SQL 문장은 끝까지 실행되어 결과가 반영됩니다.</li>
                <li>
                  진행 중인 Python·API 호출·AI 분류 스텝은 지금 하는 호출을 마친 뒤 멈춥니다. 이렇게 멈춘 스텝은 교체(REPLACE)
                  방식이면 교체를 반영하지 않아 기존 데이터가 유지되고, 추가(APPEND) 방식이면 멈추기 전까지 적재된 행이 남습니다.
                </li>
                <li>아직 시작하지 않은 스텝은 실행하지 않고 건너뜁니다.</li>
              </ul>
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>계속 실행</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onConfirm}>
            실행 취소
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
