import { useQuery } from '@tanstack/react-query';

import { ontologyApi } from '@/api/ontology';

// 온톨로지 스키마(정적) — 캐시 오래 유지.
export const useOntologySchema = () =>
  useQuery({ queryKey: ['ontology'], queryFn: () => ontologyApi.getOntology().then((r) => r.data), staleTime: 5 * 60 * 1000 });

// 전체 지식그래프.
export const useOntologyGraph = () =>
  useQuery({ queryKey: ['ontology', 'graph'], queryFn: () => ontologyApi.getGraph().then((r) => r.data) });
