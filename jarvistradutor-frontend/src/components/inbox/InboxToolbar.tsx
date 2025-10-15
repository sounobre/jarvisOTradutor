import { useEffect, useState } from 'react'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { InboxStatus, SeriesMeta, BookMeta } from '@/types/inbox'

interface InboxToolbarProps {
  status: InboxStatus
  seriesId?: number
  bookId?: number
  sourceTag?: string
  q?: string
  qualityMin?: number
  qualityMax?: number
  selectedCount: number
  onChange: (patch: Partial<{
    status: InboxStatus
    seriesId?: number | undefined
    bookId?: number | undefined
    sourceTag?: string
    q?: string
    qualityMin?: number
    qualityMax?: number
  }>) => void
  onBulkApprove: () => void
  onBulkReject: () => void
  onReset: () => void
  loadSeries: () => Promise<SeriesMeta[]>
  loadBooks: (seriesId?: number) => Promise<BookMeta[]>
}

export function InboxToolbar(props: InboxToolbarProps) {
  const { status, seriesId, bookId, sourceTag, q, qualityMin, qualityMax, selectedCount } = props
  const [seriesOptions, setSeriesOptions] = useState<SeriesMeta[]>([])
  const [bookOptions, setBookOptions] = useState<BookMeta[]>([])
  const [localQ, setLocalQ] = useState(q ?? '')

  useEffect(() => {
    void props.loadSeries().then(setSeriesOptions)
  }, [])

  useEffect(() => {
    setLocalQ(q ?? '')
  }, [q])

  useEffect(() => {
    if (seriesId) void props.loadBooks(seriesId).then(setBookOptions)
    else setBookOptions([])
  }, [seriesId])

  useEffect(() => {
    const id = setTimeout(() => {
      if (localQ !== (q ?? '')) props.onChange({ q: localQ })
    }, 400)
    return () => clearTimeout(id)
  }, [localQ])

  return (
    <div className='flex flex-col gap-3 rounded-lg border p-3'>
      <div className='flex items-center justify-between'>
        <Tabs value={status} onValueChange={(v) => props.onChange({ status: v as InboxStatus })}>
          <TabsList>
            <TabsTrigger value='pending'>Pending</TabsTrigger>
            <TabsTrigger value='approved'>Approved</TabsTrigger>
            <TabsTrigger value='rejected'>Rejected</TabsTrigger>
          </TabsList>
        </Tabs>
        <div className='text-sm text-muted-foreground'>Selecionados: {selectedCount}</div>
      </div>

      <div className='grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3'>
        <div className='flex flex-col'>
          <label className='text-xs text-muted-foreground mb-1'>Series</label>
          <select
            className={cn('h-9 rounded-md border bg-background px-2')}
            value={seriesId ?? ''}
            onChange={(e) => props.onChange({ seriesId: e.target.value ? Number(e.target.value) : undefined, bookId: undefined })}
          >
            <option value=''>Todas</option>
            {seriesOptions.map((s) => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </select>
        </div>
        <div className='flex flex-col'>
          <label className='text-xs text-muted-foreground mb-1'>Book</label>
          <select
            className={cn('h-9 rounded-md border bg-background px-2')}
            value={bookId ?? ''}
            onChange={(e) => props.onChange({ bookId: e.target.value ? Number(e.target.value) : undefined })}
            disabled={!seriesId}
          >
            <option value=''>Todos</option>
            {bookOptions.map((b) => (
              <option key={b.id} value={b.id}>{b.title}</option>
            ))}
          </select>
        </div>
        <div className='flex flex-col'>
          <label className='text-xs text-muted-foreground mb-1'>Source Tag</label>
          <Input placeholder='epub-pair:...' value={sourceTag ?? ''} onChange={(e) => props.onChange({ sourceTag: e.target.value })} />
        </div>
        <div className='flex flex-col'>
          <label className='text-xs text-muted-foreground mb-1'>Buscar (src/tgt)</label>
          <Input placeholder='trecho...' value={localQ} onChange={(e) => setLocalQ(e.target.value)} />
        </div>
        <div className='flex flex-col'>
          <label className='text-xs text-muted-foreground mb-1'>Quality Min</label>
          <Input type='number' min={0} max={1} step={0.01} value={qualityMin ?? ''} onChange={(e) => props.onChange({ qualityMin: e.target.value === '' ? undefined : Number(e.target.value) })} />
        </div>
        <div className='flex flex-col'>
          <label className='text-xs text-muted-foreground mb-1'>Quality Max</label>
          <Input type='number' min={0} max={1} step={0.01} value={qualityMax ?? ''} onChange={(e) => props.onChange({ qualityMax: e.target.value === '' ? undefined : Number(e.target.value) })} />
        </div>
      </div>

      <div className='flex items-center justify-between'>
        <div className='flex gap-2'>
          <Button onClick={props.onBulkApprove} disabled={selectedCount === 0}>Bulk Approve</Button>
          <Button variant='destructive' onClick={props.onBulkReject} disabled={selectedCount === 0}>Bulk Reject</Button>
        </div>
        <Button variant='secondary' onClick={props.onReset}>Reset filtros</Button>
      </div>
    </div>
  )
}
