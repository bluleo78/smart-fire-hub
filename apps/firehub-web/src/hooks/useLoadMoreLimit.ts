import { useCallback, useState } from 'react';

interface UseLoadMoreLimitOptions {
  /** 처음 보여 줄 건수 (기본 20) */
  initial?: number;
  /** 「더 보기」 한 번에 늘릴 건수 (기본 20) */
  step?: number;
}

/** settle 에 넘기는 조회 상태 — react-query 결과의 같은 이름 필드(refetchPending 만 따로 — 재조회가 끝나지 않음(isFetching·오프라인 멈춤)) (WD-1019) */
export interface SettleStatus {
  isError: boolean;
  isSuccess: boolean;
  isPlaceholderData?: boolean;
  refetchPending?: boolean;
}

/**
 * 「더 보기」로 limit 을 늘려 같은 목록을 다시 조회하는 화면의 공용 훅 (WD-956).
 *
 * limit 이 키에 들어가는 조회는 「더 보기」가 실패하면 새 키에 데이터가 없어 보던 목록이 통째로 사라지고
 * 오류 안내만 남는다. 그래서 실패하면 limit 을 마지막으로 성공한 값으로 되돌려(캐시된 보던 목록을 다시 보이게) 하고,
 * failed 로 실패를 알려 화면이 안내와 「다시 시도」를 그리게 한다.
 * - settle: 화면이 조회 상태를 렌더 중에 넘긴다. 성공이면 지금 limit 을 마지막 성공 값으로 기록한다.
 * - failed: 새 limit 의 조회가 성공할 때 settle 이 지운다. 되돌린 limit 의 성공·폴링은 지우지 않는다.
 */
export function useLoadMoreLimit({ initial = 20, step = 20 }: UseLoadMoreLimitOptions = {}) {
  const [limit, setLimit] = useState(initial);
  // 마지막으로 조회에 성공한 limit — 실패 시 되돌릴 곳. 렌더에서도 읽으므로 ref 가 아닌 state.
  const [lastSuccessLimit, setLastSuccessLimit] = useState(initial);
  const [failed, setFailed] = useState(false);

  /**
   * 「더 보기」·「다시 시도」 공용 — limit 만 한 단계 늘린다. failed 는 지우지 않는다.
   * 왜: failed 를 바로 지우면 실패 블록이 사라져 키보드 초점이 body 로 가므로(WD-637/678),
   * 새 limit 조회가 성공한 뒤(settle)에만 failed 를 지운다.
   */
  const loadMore = useCallback(() => {
    setLimit((l) => l + step);
  }, [step]);

  /** 재시도 조회 중 여부 — 실패 상태에서 limit 이 마지막 성공값보다 크면 재시도 조회가 진행 중이다. */
  const pending = failed && limit > lastSuccessLimit;

  /**
   * 조회 결과를 반영한다 — 성공이면 기록, 「더 보기」 실패면 마지막 성공 limit 으로 되돌리고 failed 로 둔다.
   * 화면이 렌더 중에 직접 부른다(React 의 「렌더 중 setState」 패턴 — 이전 렌더 정보 저장과 같은 방식).
   * useEffect 로 부르면 오류 렌더가 한 번 그려진 뒤 보던 행이 돌아와 깜빡이지만, 렌더 중에 되돌리면 React 가 그 렌더를
   * 버리고 바로 다시 그려 화면에 오류 렌더가 나오지 않는다. 값이 실제로 바뀔 때만 setState 하므로 무한 렌더가 없다.
   * 자리표시(keepPreviousData) 데이터는 status 가 success 라도 새 limit 의 성공이 아니므로 isPlaceholderData 면 성공으로 세지 않는다.
   * refetchPending(선택, 기본 false — isFetching || isPaused)을 넘기면 낡은 캐시로 먼저 보인 새 limit 을 재조회가 끝난 뒤에야 성공으로 센다 — 재조회 실패가 묻히지 않게 (WD-1019).
   */
  const settle = ({ isError, isSuccess, isPlaceholderData = false, refetchPending = false }: SettleStatus) => {
    if (isSuccess && !isPlaceholderData && !refetchPending) {
      if (lastSuccessLimit !== limit) {
        setLastSuccessLimit(limit);
        // 새 limit 의 성공일 때만 실패를 지운다 — 같은 값이면 React 가 건너뛴다.
        setFailed(false);
      }
    } else if (isError && !refetchPending && limit > lastSuccessLimit) {
      // 재조회 중(refetchPending)의 isError 는 지난 실패가 남은 것이다 — 데이터가 있는 오류 캐시를 다시 열면 첫 렌더가
      // isError·isFetching 을 함께 내므로, 여기서 되돌리면 「다시 시도」 요청이 나가기도 전에 접힌다 (WD-1019 리뷰)
      setLimit(lastSuccessLimit);
      setFailed(true);
    }
  };

  return { limit, loadMore, pending, failed, settle };
}
