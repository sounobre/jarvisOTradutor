import { useMemo } from 'react'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import type { BookpairInboxItem } from '@/types/inbox'

function qualityBadgeColor(q: number | null) {
  if (q == null) return 'bg-gray-200 text-gray-800'
  if (q >= 0.85) return 'bg-green-200 text-green-900'
  if (q >= 0.7) return 'bg-yellow-200 text-yellow-900'
  return 'bg-gray-200 text-gray-800'
}

interface InboxTableProps {
  items: BookpairInboxItem[]
  selectedIds: number[]
  page: number
  size: number
  totalPages: number
  sort?: string
  onToggleAll: (checked: boolean) => void
  onToggleOne: (id: number, checked: boolean) => void
  onApprove: (id: number) => void
  onReject: (id: number) => void
  onDetails: (item: BookpairInboxItem) => void
  onPageChange: (page: number) => void
  onSizeChange: (size: number) => void
  onSortChange: (sort: string) => void
}

export function InboxTable(props: InboxTableProps) {
  const { items, selectedIds, page, size, totalPages, sort } = props
  const allSelected = items.length > 0 && items.every((i) => selectedIds.includes(i.id))

  const [sortField, sortDir] = useMemo(() => {
    if (!sort) return [undefined, undefined]
    const [f, d] = sort.split(',')
    return [f, d]
  }, [sort]) as [string | undefined, string | undefined]

  function toggleSort(field: string) {
    if (sortField !== field) props.onSortChange(`${field},DESC`)
    else props.onSortChange(`${field},${sortDir === 'DESC' ? 'ASC' : 'DESC'}`)
  }

  return (
    <div className='rounded-lg border'>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className='w-8'>
              <input
                aria-label='Selecionar página'
                type='checkbox'
                checked={allSelected}
                onChange={(e) => props.onToggleAll(e.target.checked)}
              />
            </TableHead>
            <TableHead onClick={() => toggleSort('quality')} className='cursor-pointer select-none'>Quality {sortField==='quality' ? (sortDir==='DESC'?'↓':'↑') : ''}</TableHead>
            <TableHead>Src</TableHead>
            <TableHead>Tgt</TableHead>
            <TableHead>Series/Book</TableHead>
            <TableHead>SourceTag</TableHead>
            <TableHead onClick={() => toggleSort('createdAt')} className='cursor-pointer select-none'>Created {sortField==='createdAt' ? (sortDir==='DESC'?'↓':'↑') : ''}</TableHead>
            <TableHead>Ações</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((it) => (
            <TableRow key={it.id}>
              <TableCell className='w-8'>
                <input
                  role='checkbox'
                  aria-label={`Selecionar ${it.id}`}
                  type='checkbox'
                  checked={selectedIds.includes(it.id)}
                  onChange={(e) => props.onToggleOne(it.id, e.target.checked)}
                />
              </TableCell>
              <TableCell>
                <span className={`inline-flex rounded px-2 py-0.5 text-xs ${qualityBadgeColor(it.quality)}`}>
                  {it.quality ?? '—'}
                </span>
              </TableCell>
              <TableCell title={it.src} className='max-w-[320px] truncate font-mono text-xs'>{it.src}</TableCell>
              <TableCell title={it.tgt} className='max-w-[320px] truncate font-mono text-xs'>{it.tgt}</TableCell>
              <TableCell className='text-xs'>{it.seriesId ?? '—'} / {it.bookId ?? '—'}</TableCell>
              <TableCell className='text-xs'>{it.sourceTag ?? '—'}</TableCell>
              <TableCell className='text-xs'>{new Date(it.createdAt).toLocaleString()}</TableCell>
              <TableCell className='space-x-2'>
                <Button size='sm' onClick={() => props.onApprove(it.id)} aria-label={`Approve ${it.id}`}>Approve</Button>
                <Button size='sm' variant='destructive' onClick={() => props.onReject(it.id)} aria-label={`Reject ${it.id}`}>Reject</Button>
                <Button size='sm' variant='secondary' onClick={() => props.onDetails(it)} aria-label={`Detalhes ${it.id}`}>Detalhes</Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <div className='flex items-center justify-between border-t p-2 text-sm'>
        <div className='flex items-center gap-2'>
          <span>Por página</span>
          <select
            className='h-8 rounded-md border bg-background px-2'
            value={size}
            onChange={(e) => props.onSizeChange(Number(e.target.value))}
          >
            {[10,20,50,100].map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
        </div>
        <div className='flex items-center gap-2'>
          <Button variant='secondary' onClick={() => props.onPageChange(Math.max(1, page-1))} disabled={page<=1}>Anterior</Button>
          <span>Página {page} de {Math.max(1, totalPages)}</span>
          <Button variant='secondary' onClick={() => props.onPageChange(Math.min(totalPages || 1, page+1))} disabled={totalPages>0 ? page>=totalPages : items.length===0}>Próxima</Button>
        </div>
      </div>
    </div>
  )
}

