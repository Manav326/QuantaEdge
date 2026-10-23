export type CsvRow = Record<string, string>;

export function parseCsv(input: string, label = 'CSV'): CsvRow[] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;

  for (let index = 0; index < input.length; index += 1) {
    const char = input[index];
    if (quoted) {
      if (char === '"') {
        if (input[index + 1] === '"') {
          cell += '"';
          index += 1;
        } else {
          quoted = false;
        }
      } else {
        cell += char;
      }
      continue;
    }
    if (char === '"' && cell.length === 0) quoted = true;
    else if (char === ',') {
      row.push(cell);
      cell = '';
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && input[index + 1] === '\n') index += 1;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else {
      cell += char;
    }
  }
  if (quoted) throw new Error(label + ' contains an unterminated quoted field.');
  if (cell.length > 0 || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }

  const headers = rows.shift();
  if (!headers?.length || headers.some(header => !header.trim())) {
    throw new Error(label + ' is missing a valid header row.');
  }
  if (new Set(headers).size !== headers.length) {
    throw new Error(label + ' contains duplicate column names.');
  }

  return rows
    .filter(values => values.some(value => value.length > 0))
    .map((values, rowIndex) => {
      if (values.length !== headers.length) {
        throw new Error(label + ' row ' + (rowIndex + 2) + ' has ' + values.length +
          ' fields; expected ' + headers.length + '.');
      }
      return Object.fromEntries(headers.map((header, columnIndex) =>
        [header, values[columnIndex] ?? '']));
    });
}
