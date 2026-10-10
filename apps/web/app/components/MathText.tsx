'use client';

/** Renders stored a/b fraction notation as an accessible stacked fraction. */
export default function MathText({ text, className }: { text: string | number | null | undefined; className?: string }) {
  const source = String(text ?? '');
  const fractionPattern = /(?<![\w.])(\d+)\s*\/\s*(\d+)(?![\w.])/g;
  const output: React.ReactNode[] = [];
  let cursor = 0;
  for (const match of source.matchAll(fractionPattern)) {
    const index = match.index ?? 0;
    if (index > cursor) output.push(source.slice(cursor, index));
    const numerator = match[1];
    const denominator = match[2];
    output.push(
      <span className="qe-inline-fraction" role="math" aria-label={numerator + ' over ' + denominator} key={index + '-' + numerator + '-' + denominator}>
        <span className="qe-inline-fraction__numerator" aria-hidden="true">{numerator}</span>
        <span className="qe-inline-fraction__denominator" aria-hidden="true">{denominator}</span>
      </span>,
    );
    cursor = index + match[0].length;
  }
  if (output.length === 0) return <span className={className}>{source}</span>;
  if (cursor < source.length) output.push(source.slice(cursor));
  return <span className={className}>{output}</span>;
}
