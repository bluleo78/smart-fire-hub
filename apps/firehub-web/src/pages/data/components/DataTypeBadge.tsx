import { Badge } from '../../../components/ui/badge';

const COLOR_MAP: Record<string, 'default' | 'secondary' | 'outline'> = {
  TEXT: 'default',
  VARCHAR: 'default',
  INTEGER: 'secondary',
  DECIMAL: 'secondary',
  BOOLEAN: 'outline',
  DATE: 'outline',
  TIMESTAMP: 'outline',
};

interface DataTypeBadgeProps {
  dataType: string;
  maxLength?: number | null;
}

export function DataTypeBadge({ dataType, maxLength }: DataTypeBadgeProps) {
  const displayType = dataType === 'VARCHAR' && maxLength ? `VARCHAR(${maxLength})` : dataType;
  return <Badge variant={COLOR_MAP[dataType] ?? 'default'}>{displayType}</Badge>;
}
