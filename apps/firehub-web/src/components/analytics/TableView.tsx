interface TableViewProps {
  columns: string[];
  data: Record<string, unknown>[];
  height?: number;
}

export function TableView({ columns, data, height }: TableViewProps) {
  return (
    <div className="rounded-md border overflow-auto" style={{ maxHeight: height ?? '100%' }}>
      <table className="w-full text-sm border-collapse">
        <thead className="sticky top-0 bg-muted/80 backdrop-blur-sm">
          <tr>
            {columns.map((col) => (
              <th
                key={col}
                className="px-3 py-2 text-left font-semibold whitespace-nowrap border-b text-xs"
              >
                {col}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.length === 0 ? (
            <tr>
              <td
                colSpan={columns.length}
                className="px-3 py-8 text-center text-muted-foreground text-sm"
              >
                데이터 없음
              </td>
            </tr>
          ) : (
            data.map((row, i) => (
              <tr key={i} className="hover:bg-muted/40 transition-colors">
                {columns.map((col) => {
                  const val = row[col];
                  return (
                    <td
                      key={col}
                      className="px-3 py-1.5 border-b whitespace-nowrap max-w-[200px] truncate"
                      title={val != null ? String(val) : undefined}
                    >
                      {val == null ? (
                        <span className="text-muted-foreground italic text-xs">NULL</span>
                      ) : (
                        String(val)
                      )}
                    </td>
                  );
                })}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
