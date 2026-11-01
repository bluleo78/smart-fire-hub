import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import { pipelinesApi } from '../../api/pipelines';
import { proactiveApi } from '../../api/proactive';
import { extractApiError } from '../../lib/api-error';
import { parseUtcDate } from '../../lib/formatters';
import { useDebounceValue } from '../useDebounceValue';

/** 입력이 멈춘 뒤 서버에 묻기까지 기다리는 시간(ms) — 타이핑마다 요청하지 않게 한다. */
export const CRON_PREVIEW_DEBOUNCE_MS = 300;

/**
 * 미리보기를 어느 엔드포인트에 물을지. 계산은 같고 권한만 다르다 —
 * 파이프라인 트리거(trigger:read)는 `/triggers/cron-preview`, 스마트 작업(proactive:read)은 `/proactive/jobs/cron-preview`.
 */
export type CronPreviewScope = 'trigger' | 'proactive';

interface UseCronPreviewOptions {
  /** 몇 개를 받을지(서버 상한 10). 기본 5 */
  count?: number;
  /** false 면 묻지 않는다 — 화면이 이미 형식 오류를 보이고 있을 때 등 */
  enabled?: boolean;
}

export interface CronPreviewState {
  /** 다음 발화 시각들. 계산 전·실패 시 빈 배열 */
  times: Date[];
  /** 입력 대기(디바운스) 중이거나 서버 응답을 기다리는 중 */
  isLoading: boolean;
  /** 서버가 거부했거나(400 문구) 요청이 실패했을 때의 안내 문구 */
  error: string | null;
}

/**
 * cron 식의 다음 실행 시각을 서버에서 받아 온다 (WD-391).
 *
 * 예전에는 화면이 cron-parser 로 따로 계산해 서머타임 전환일에 실제 실행(서버 스케줄러)과 달랐다 —
 * 해제일엔 두 번 도는 작업이 한 번으로, 시작일엔 건너뛰는 실행이 그대로 보였다. 이제 스케줄러와 같은
 * 계산기를 쓰는 서버 미리보기 API 만 본다.
 *
 * - 입력 중에는 300ms 디바운스 후 한 번만 묻는다. 대기 중에는 isLoading 을 올려 옛 결과가 새 입력의 결과처럼 보이지 않게 한다.
 * - 400(형식 오류 등)은 다시 물어도 같으므로 재시도하지 않는다.
 */
export function useCronPreview(
  scope: CronPreviewScope,
  cron: string,
  timezone: string,
  { count = 5, enabled = true }: UseCronPreviewOptions = {},
): CronPreviewState {
  const trimmed = cron.trim();
  const debouncedCron = useDebounceValue(trimmed, CRON_PREVIEW_DEBOUNCE_MS);
  const debouncedTimezone = useDebounceValue(timezone, CRON_PREVIEW_DEBOUNCE_MS);
  const settled = debouncedCron === trimmed && debouncedTimezone === timezone;
  const active = enabled && trimmed.length > 0;

  const query = useQuery({
    queryKey: ['cron-preview', scope, debouncedCron, debouncedTimezone, count],
    queryFn: () => {
      const params = { cron: debouncedCron, timezone: debouncedTimezone, count };
      const request =
        scope === 'trigger' ? pipelinesApi.getTriggerCronPreview(params) : proactiveApi.getCronPreview(params);
      return request.then((r) => r.data);
    },
    // 입력이 멈춘 값으로만 묻는다 — 디바운스 전 값으로 요청이 나가지 않게
    enabled: active && settled && debouncedCron.length > 0,
    retry: false,
  });

  const times = useMemo(
    () => (query.data ? query.data.nextFireTimes.map((t) => parseUtcDate(t)) : []),
    [query.data],
  );

  if (!active) return { times: [], isLoading: false, error: null };
  if (!settled || query.isPending) return { times: [], isLoading: true, error: null };
  if (query.isError) {
    return { times: [], isLoading: false, error: extractApiError(query.error, '다음 실행 시각을 불러오지 못했습니다') };
  }
  return { times, isLoading: false, error: null };
}
