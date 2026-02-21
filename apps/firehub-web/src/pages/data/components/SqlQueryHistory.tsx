import { useQueryHistory } from '../../../hooks/queries/useDatasets';
import { Button } from '../../../components/ui/button';
import { Badge } from '../../../components/ui/badge';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '../../../components/ui/popover';
import { History, Loader2 } from 'lucide-react';
import { formatDate } from '../../../lib/formatters';

interface SqlQueryHistoryProps {
  datasetId: number;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSelect: (sql: string) => void;
}

const queryTypeBadgeVariant = (queryType: string) => {
  switch (queryType) {
    case 'SELECT':
      return 'secondary';
    case 'INSERT':
      return 'default';
    case 'UPDATE':
      return 'outline';
    case 'DELETE':
      return 'destructive';
    default:
      return 'secondary';
  }
};

export function SqlQueryHistory({ datasetId, open, onOpenChange, onSelect }: SqlQueryHistoryProps) {
  const { data: historyData, isLoading } = useQueryHistory(datasetId, 0, 20);

  const items = historyData?.content ?? [];

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm">
          <History className="mr-1 h-4 w-4" />
          이력
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-96 p-0" align="start">
        <div className="p-3 border-b">
          <p className="text-sm font-medium">쿼리 이력</p>
        </div>
        <div className="max-h-[300px] overflow-y-auto">
          {isLoading ? (
            <div className="flex items-center justify-center py-6">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            </div>
          ) : items.length === 0 ? (
            <p className="text-sm text-muted-foreground text-center py-6">이력이 없습니다.</p>
          ) : (
            <div className="divide-y">
              {items.map((item) => (
                <button
                  key={item.id}
                  className="w-full text-left px-3 py-2 hover:bg-muted/50 transition-colors"
                  onClick={() => onSelect(item.sql)}
                >
                  <div className="flex items-center gap-2 mb-1">
                    <Badge variant={queryTypeBadgeVariant(item.queryType) as 'default' | 'secondary' | 'destructive' | 'outline'} className="text-[10px] px-1.5 py-0">
                      {item.queryType}
                    </Badge>
                    {item.success ? (
                      <span className="text-xs text-muted-foreground">
                        {item.affectedRows}행 | {item.executionTimeMs}ms
                      </span>
                    ) : (
                      <span className="text-xs text-destructive">실패</span>
                    )}
                    <span className="text-[10px] text-muted-foreground ml-auto">
                      {formatDate(item.executedAt)}
                    </span>
                  </div>
                  <p className="text-xs font-mono text-muted-foreground truncate">
                    {item.sql}
                  </p>
                  {item.error && (
                    <p className="text-[10px] text-destructive truncate mt-0.5">{item.error}</p>
                  )}
                </button>
              ))}
            </div>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
