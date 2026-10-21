import { useState } from 'react';
import { toast } from 'sonner';

import { Button } from '../../../components/ui/button';
import { Label } from '../../../components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import { useDatasetPermissions } from '../../../hooks/queries/useDatasets';
import { useBindOntology, useOntologyList } from '../../../hooks/queries/useMapping';
import { handleApiError } from '../../../lib/api-error';

interface OntologyBindingCardProps {
  datasetId: number;
}

/**
 * 미바인딩 데이터셋에 온톨로지를 연결한다.
 * 바인딩이 없으면 백엔드가 매핑 저장·활성화를 거부하므로, 매핑 편집의 선행 단계다.
 */
export function OntologyBindingCard({ datasetId }: OntologyBindingCardProps) {
  // 연결은 서버가 dataset:write 로 막는다 — 권한이 없으면 선택·연결 대신 안내만 보인다(WD-131).
  // 매핑 탭·문서 탭이 함께 쓰는 카드라 권한 판정을 카드 안에 둔다.
  // 권한을 불러오는 동안은 선택·연결도 안내도 그리지 않는다 — 조회 전용 사용자에게 연결 버튼이 잠깐 보였다 사라지지 않게(WD-139, 매핑 탭과 같은 규칙).
  const { canWriteDataset: canEdit, isLoading: isPermissionsLoading } = useDatasetPermissions();
  const { data: ontologies, isLoading } = useOntologyList();
  const bindOntology = useBindOntology(datasetId);
  // Select는 문자열 값만 다루므로 ID를 문자열로 보관하고 전송 직전에 숫자로 바꾼다.
  const [selected, setSelected] = useState('');

  const handleBind = async () => {
    if (!selected) return;
    try {
      await bindOntology.mutateAsync(Number(selected));
      toast.success('온톨로지를 연결했습니다.');
    } catch (error) {
      handleApiError(error, '온톨로지 연결에 실패했습니다.');
    }
  };

  return (
    <div className="rounded-md border p-6 space-y-4" data-testid="ontology-binding-card">
      <div>
        <h2 className="text-xl leading-7 font-semibold">온톨로지 연결</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          이 데이터셋을 지식그래프로 투영하려면 기준이 될 온톨로지를 먼저 연결해야 합니다.
        </p>
      </div>

      {isPermissionsLoading ? null : !canEdit ? (
        <p className="text-sm text-muted-foreground" data-testid="ontology-binding-readonly">
          온톨로지가 아직 연결되지 않았습니다. 연결하려면 데이터셋 쓰기 권한이 필요합니다. 관리자에게 권한을 요청하세요.
        </p>
      ) : (
        <>
      <div className="space-y-2 max-w-sm">
        <Label htmlFor="ontology-select">온톨로지 *</Label>
        <Select value={selected} onValueChange={setSelected}>
          <SelectTrigger id="ontology-select" data-testid="ontology-select">
            <SelectValue placeholder="온톨로지를 선택하세요" />
          </SelectTrigger>
          <SelectContent>
            {(ontologies ?? []).map((o) => (
              <SelectItem key={o.id} value={String(o.id)}>
                {o.domain} (v{o.schemaVersion})
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <Button onClick={handleBind} disabled={!selected || isLoading || bindOntology.isPending}>
        {bindOntology.isPending ? '연결 중...' : '연결'}
      </Button>
        </>
      )}
    </div>
  );
}
