import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../../components/ui/select';

interface ColumnTypeSelectProps {
  value?: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}

const DATA_TYPES = [
  { value: 'TEXT', label: '텍스트' },
  { value: 'VARCHAR', label: '문자열(크기지정)' },
  { value: 'INTEGER', label: '정수' },
  { value: 'DECIMAL', label: '소수' },
  { value: 'BOOLEAN', label: '참/거짓' },
  { value: 'DATE', label: '날짜' },
  { value: 'TIMESTAMP', label: '일시' },
  // 지오메트리(POINT 등) — GeoJSON 문자열 입출력. 데이터셋 지도 탭 노출 조건. (#118)
  { value: 'GEOMETRY', label: '지오메트리(좌표)' },
] as const;

export function ColumnTypeSelect({ value, onChange, disabled }: ColumnTypeSelectProps) {
  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger>
        <SelectValue placeholder="데이터 타입 선택" />
      </SelectTrigger>
      <SelectContent>
        {DATA_TYPES.map((type) => (
          <SelectItem key={type.value} value={type.value}>
            {type.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
