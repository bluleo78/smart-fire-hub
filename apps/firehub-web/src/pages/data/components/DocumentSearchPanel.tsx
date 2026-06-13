import { useState } from 'react';
import { toast } from 'sonner';

import { Button } from '../../../components/ui/button';
import { useSearchDocuments } from '../../../hooks/queries/useDocuments';
import type { DocumentSearchHit } from '../../../types/document';

interface DocumentSearchPanelProps {
  datasetId: number;
}

/** 데이터셋 범위 의미검색. 전역 검색은 #282로 deferred. */
export function DocumentSearchPanel({ datasetId }: DocumentSearchPanelProps) {
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<DocumentSearchHit[]>([]);
  const search = useSearchDocuments();

  const handleSearch = () => {
    const q = query.trim();
    if (!q) return;
    search.mutate(
      { query: q, datasetIds: [datasetId], topK: 5 },
      {
        onSuccess: (data) => setHits(data),
        onError: () => toast.error('검색에 실패했습니다. 임베딩 서비스 상태를 확인하세요.'),
      },
    );
  };

  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">의미검색</h2>
      <div className="flex gap-2">
        <input
          className="flex-1 rounded-md border px-3 py-2 text-sm"
          placeholder="검색어를 입력하세요"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') handleSearch(); }}
        />
        <Button type="button" disabled={!query.trim() || search.isPending} onClick={handleSearch}>
          {search.isPending ? '검색 중...' : '검색'}
        </Button>
      </div>

      {search.isSuccess && hits.length === 0 && (
        <p className="text-sm text-muted-foreground">검색 결과가 없습니다.</p>
      )}
      <ul className="space-y-2">
        {hits.map((h) => (
          <li key={h.chunkId} className="rounded-md border p-3">
            <div className="flex justify-between text-xs text-muted-foreground">
              <span>{h.fileName} · 청크 #{h.chunkIndex}</span>
              <span>유사도 {(h.score * 100).toFixed(1)}%</span>
            </div>
            <p className="mt-1 text-sm whitespace-pre-wrap">{h.content}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}
