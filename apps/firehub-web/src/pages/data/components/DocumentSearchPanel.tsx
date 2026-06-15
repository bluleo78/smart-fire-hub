import { useState } from 'react';
import { toast } from 'sonner';

import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Tabs, TabsList, TabsTrigger } from '../../../components/ui/tabs';
import { useSearchDocuments } from '../../../hooks/queries/useDocuments';
import type { DocumentSearchHit, DocumentSearchMode } from '../../../types/document';

interface DocumentSearchPanelProps {
  datasetId: number;
}

/** 데이터셋 범위 의미검색. 전역 검색은 #282로 deferred. */
export function DocumentSearchPanel({ datasetId }: DocumentSearchPanelProps) {
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<DocumentSearchHit[]>([]);
  // 검색 모드: 기본 하이브리드(HYBRID). 의미(SEMANTIC)=벡터, 키워드(KEYWORD)=트라이그램.
  const [mode, setMode] = useState<DocumentSearchMode>('HYBRID');
  // 실제 검색에 사용된 모드 — 결과 점수 표기에 사용 (토글을 검색 후 바꿔도 결과 표기는 유지).
  const [searchedMode, setSearchedMode] = useState<DocumentSearchMode>('HYBRID');
  // 확장된 청크 ID 집합 — 클릭 시 content를 펼쳐서 전문을 보여주는 accordion 패턴 (#291).
  const [expandedChunks, setExpandedChunks] = useState<Set<number>>(new Set());
  const search = useSearchDocuments();

  /** 청크 accordion 토글 — 이미 열려 있으면 접고, 닫혀 있으면 펼친다. */
  const toggleChunk = (chunkId: number) => {
    setExpandedChunks((prev) => {
      const next = new Set(prev);
      if (next.has(chunkId)) {
        next.delete(chunkId);
      } else {
        next.add(chunkId);
      }
      return next;
    });
  };

  const handleSearch = () => {
    const q = query.trim();
    if (!q) return;
    setSearchedMode(mode);
    search.mutate(
      { query: q, datasetIds: [datasetId], topK: 5, mode },
      {
        onSuccess: (data) => setHits(data),
        onError: () => toast.error('검색에 실패했습니다. 임베딩 서비스 상태를 확인하세요.'),
      },
    );
  };

  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">문서 검색</h2>
      {/* 검색 모드 토글 — 하이브리드(기본)/의미/키워드. 서버는 생략 시 HYBRID. */}
      <Tabs value={mode} onValueChange={(v) => setMode(v as DocumentSearchMode)}>
        <TabsList>
          <TabsTrigger value="HYBRID">하이브리드</TabsTrigger>
          <TabsTrigger value="SEMANTIC">의미</TabsTrigger>
          <TabsTrigger value="KEYWORD">키워드</TabsTrigger>
        </TabsList>
      </Tabs>
      <div className="flex gap-2">
        {/* shadcn Input 사용 — 다크모드 배경·포커스링·border-input 토큰 일관성 보장 (#288) */}
        <Input
          className="flex-1"
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
        {hits.map((h, index) => {
          const isExpanded = expandedChunks.has(h.chunkId);
          return (
            /* 클릭 시 청크 내용을 accordion 방식으로 펼침/접기 (#291) */
            <li
              key={h.chunkId}
              role="button"
              tabIndex={0}
              aria-expanded={isExpanded}
              className="rounded-md border p-3 cursor-pointer hover:bg-accent/50 transition-colors"
              onClick={() => toggleChunk(h.chunkId)}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleChunk(h.chunkId); } }}
            >
              <div className="flex justify-between text-xs text-muted-foreground">
                <span>{h.fileName} · 청크 #{h.chunkIndex}</span>
                {/* HYBRID의 score는 RRF 점수라 백분율 의미가 없어 순위로 표기. SEMANTIC/KEYWORD는 0~1이라 % 유지. */}
                {searchedMode === 'HYBRID' ? (
                  <span>관련도 {index + 1}위</span>
                ) : (
                  <span>유사도 {(h.score * 100).toFixed(1)}%</span>
                )}
              </div>
              {/* 접힌 상태: 첫 줄만 표시. 펼친 상태: 전문 표시. */}
              <p className={`mt-1 text-sm whitespace-pre-wrap ${isExpanded ? '' : 'line-clamp-1'}`}>
                {h.content}
              </p>
              {/* 펼침 상태 힌트 */}
              <p className="mt-1 text-xs text-muted-foreground/70 select-none">
                {isExpanded ? '▲ 접기' : '▼ 펼치기'}
              </p>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
