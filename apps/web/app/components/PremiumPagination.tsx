'use client';

import type { CSSProperties } from 'react';

export type PremiumPaginationProps = {
  page: number;
  total: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  loading?: boolean;
  itemLabel?: string;
  pageSizeOptions?: number[];
};

const palette = {
  ink: '#24334b',
  muted: '#778398',
  border: '#e1e7f0',
  soft: '#f7f9fc',
  active: '#24334b',
};

const buttonBase: CSSProperties = {
  display: 'inline-flex',
  minHeight: 36,
  alignItems: 'center',
  justifyContent: 'center',
  gap: 7,
  border: '1px solid ' + palette.border,
  borderRadius: 10,
  padding: '0 12px',
  background: '#fff',
  color: palette.ink,
  font: 'inherit',
  fontSize: 12,
  fontWeight: 700,
  cursor: 'pointer',
  whiteSpace: 'nowrap',
};

export default function PremiumPagination({
  page,
  total,
  pageSize,
  onPageChange,
  onPageSizeChange,
  loading = false,
  itemLabel = 'records',
  pageSizeOptions = [10, 20, 30, 50],
}: PremiumPaginationProps) {
  const safeTotal = Math.max(0, Number(total) || 0);
  const safeSize = Math.max(1, Number(pageSize) || 10);
  const pageCount = Math.max(1, Math.ceil(safeTotal / safeSize));
  const safePage = Math.min(Math.max(0, page), pageCount - 1);
  const first = safeTotal === 0 ? 0 : safePage * safeSize + 1;
  const last = Math.min(safeTotal, (safePage + 1) * safeSize);
  const visiblePages = new Set<number>([0, pageCount - 1]);
  for (let index = Math.max(0, safePage - 1); index <= Math.min(pageCount - 1, safePage + 1); index += 1) visiblePages.add(index);
  const sortedPages = [...visiblePages].sort((a, b) => a - b);
  const pages: Array<number | 'ellipsis-left' | 'ellipsis-right'> = [];
  sortedPages.forEach((index, position) => {
    if (position > 0 && index - sortedPages[position - 1] > 1) {
      pages.push(position === 1 ? 'ellipsis-left' : 'ellipsis-right');
    }
    pages.push(index);
  });

  const shell: CSSProperties = {
    display: 'flex',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 14,
    width: '100%',
    marginTop: 18,
    padding: '14px 16px',
    border: '1px solid ' + palette.border,
    borderRadius: 16,
    background: 'linear-gradient(135deg,#fff 0%,#fbfcfe 100%)',
    boxShadow: '0 5px 18px rgba(28,42,65,.035)',
    color: palette.ink,
    boxSizing: 'border-box',
  };
  const mutedText: CSSProperties = { color: palette.muted, fontSize: 11, lineHeight: 1.5 };
  const tinyLabel: CSSProperties = { display: 'grid', gap: 5, color: palette.muted, fontSize: 10, fontWeight: 800, letterSpacing: '.45px' };
  const selectStyle: CSSProperties = {
    minHeight: 36,
    minWidth: 76,
    border: '1px solid ' + palette.border,
    borderRadius: 10,
    padding: '0 28px 0 10px',
    background: '#fff',
    color: palette.ink,
    font: 'inherit',
    fontSize: 12,
    fontWeight: 750,
    outlineOffset: 2,
  };
  const pageButton = (active = false, square = false): CSSProperties => ({
    ...buttonBase,
    minWidth: square ? 36 : undefined,
    padding: square ? '0 8px' : buttonBase.padding,
    borderColor: active ? palette.active : palette.border,
    background: active ? palette.active : '#fff',
    color: active ? '#fff' : palette.ink,
    boxShadow: active ? '0 4px 10px rgba(36,51,75,.16)' : 'none',
  });
  const disabledButton: CSSProperties = { ...buttonBase, color: '#aab2c0', background: palette.soft, cursor: 'not-allowed' };

  return (
    <nav aria-label={'Pagination for ' + itemLabel} style={shell}>
      <div style={{ display: 'grid', gap: 3, minWidth: 160 }}>
        <strong aria-live="polite" style={{ fontSize: 12, fontWeight: 800, letterSpacing: '-.1px' }}>
          {safeTotal === 0 ? 'No ' + itemLabel : 'Showing ' + first.toLocaleString() + '–' + last.toLocaleString() + ' of ' + safeTotal.toLocaleString()}
        </strong>
        <span style={mutedText}>{safeTotal === 1 ? '1 item' : safeTotal.toLocaleString() + ' ' + itemLabel}</span>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap', marginLeft: 'auto' }}>
        <div style={tinyLabel}>
          ROWS PER PAGE
          <select
            aria-label="Rows per page"
            value={safeSize}
            disabled={loading}
            onChange={event => onPageSizeChange(Number(event.target.value))}
            style={selectStyle}
          >
            {pageSizeOptions.map(size => <option value={size} key={size}>{size}</option>)}
          </select>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5, flexWrap: 'wrap' }}>
          <button
            type="button"
            aria-label="Previous page"
            title="Previous page"
            disabled={loading || safePage <= 0}
            onClick={() => onPageChange(Math.max(0, safePage - 1))}
            style={loading || safePage <= 0 ? disabledButton : buttonBase}
          >‹ <span style={{ fontSize: 11 }}>Previous</span></button>

          {pages.map((entry, index) => typeof entry === 'number'
            ? <button
                key={entry}
                type="button"
                aria-label={'Page ' + (entry + 1)}
                aria-current={entry === safePage ? 'page' : undefined}
                disabled={loading}
                onClick={() => onPageChange(entry)}
                style={pageButton(entry === safePage, true)}
              >{entry + 1}</button>
            : <span key={entry} aria-hidden="true" style={{ padding: '0 3px', color: palette.muted, fontSize: 12 }}>···</span>
          )}

          <button
            type="button"
            aria-label="Next page"
            title="Next page"
            disabled={loading || safePage >= pageCount - 1}
            onClick={() => onPageChange(Math.min(pageCount - 1, safePage + 1))}
            style={loading || safePage >= pageCount - 1 ? disabledButton : buttonBase}
          ><span style={{ fontSize: 11 }}>Next</span> ›</button>
        </div>
      </div>
    </nav>
  );
}
