import { Button } from '@/components/ui/button'

import type { BookpairInboxItem } from '@/types/inbox'

interface ItemDetailsDrawerProps {
  open: boolean
  item?: BookpairInboxItem | null
  loading?: boolean
  onClose: () => void
  onApprove: (id: number) => void
  onReject: (id: number) => void
}

export function ItemDetailsDrawer({ open, item, loading, onClose, onApprove, onReject }: ItemDetailsDrawerProps) {
  if (!open || !item) return null

  const fields: [string, string | number | null | undefined][] = [
    ['Quality', item.quality ?? null],
    ['SeriesId', item.seriesId],
    ['BookId', item.bookId],
    ['SourceTag', item.sourceTag],
    ['Chapter', item.chapter],
    ['Location', item.location],
    ['Langs', `${item.langSrc} → ${item.langTgt}`],
    ['CreatedAt', item.createdAt],
    ['Reviewer', item.reviewer ?? '—'],
    ['ReviewedAt', item.reviewedAt ?? '—'],
  ]

  return (
    <div className='fixed inset-0 z-50 flex'>
      <div className='flex-1 bg-black/50' onClick={onClose} />
      <aside className='w-full max-w-xl h-full bg-background border-l p-4 overflow-y-auto'>
        <div className='flex items-center justify-between'>
          <h2 className='text-lg font-semibold'>Detalhes do item #{item.id}</h2>
          <Button variant='secondary' onClick={onClose}>Fechar</Button>
        </div>
        <div className='mt-4 grid gap-3'>
          <div>
            <div className='text-xs uppercase text-muted-foreground'>Source</div>
            <pre className='mt-1 whitespace-pre-wrap break-words rounded-md bg-muted/40 p-2 font-mono text-sm'>{item.src}</pre>
          </div>
          <div>
            <div className='text-xs uppercase text-muted-foreground'>Target</div>
            <pre className='mt-1 whitespace-pre-wrap break-words rounded-md bg-muted/40 p-2 font-mono text-sm'>{item.tgt}</pre>
          </div>
          <div className='grid grid-cols-2 gap-2'>
            {fields.map(([k, v]) => (
              <div key={k} className='rounded-md border p-2 text-sm'>
                <div className='text-xs text-muted-foreground'>{k}</div>
                <div>{v ?? '—'}</div>
              </div>
            ))}
          </div>
        </div>
        <div className='mt-6 flex gap-2'>
          <Button aria-label={`Approve ${item.id}`} onClick={() => onApprove(item.id)} disabled={!!loading}>Approve</Button>
          <Button aria-label={`Reject ${item.id}`} variant='destructive' onClick={() => onReject(item.id)} disabled={!!loading}>Reject</Button>
        </div>
      </aside>
    </div>
  )
}

