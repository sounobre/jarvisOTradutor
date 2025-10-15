import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useToast } from '@/components/ui/use-toast'
import { InboxToolbar } from '@/components/inbox/InboxToolbar'
import { InboxTable } from '@/components/inbox/InboxTable'
import { ItemDetailsDrawer } from '@/components/inbox/ItemDetailsDrawer'
import { ConfirmDialog } from '@/components/inbox/ConfirmDialog'
import type { BookpairInboxItem, InboxStatus, Page } from '@/types/inbox'
import { approveBookpair, bulkApprove, bulkReject, getBooks, getSeries, listBookpairInbox, rejectBookpair } from '@/services/api-inbox'

function getParam<T>(sp: URLSearchParams, key: string, parser: (v: string) => T | undefined): T | undefined {
  const v = sp.get(key)
  if (v === null) return undefined
  return parser(v)
}

function parseFilters(sp: URLSearchParams) {
  const status = (sp.get('status') as InboxStatus) || 'pending'
  return {
    status,
    seriesId: getParam(sp, 'seriesId', (v) => (v ? Number(v) : undefined)),
    bookId: getParam(sp, 'bookId', (v) => (v ? Number(v) : undefined)),
    sourceTag: sp.get('sourceTag') ?? undefined,
    q: sp.get('q') ?? undefined,
    qualityMin: getParam(sp, 'qualityMin', (v) => (v ? Number(v) : undefined)),
    qualityMax: getParam(sp, 'qualityMax', (v) => (v ? Number(v) : undefined)),
    page: getParam(sp, 'page', (v) => (v ? Number(v) : 1)) ?? 1,
    size: getParam(sp, 'size', (v) => (v ? Number(v) : 20)) ?? 20,
    sort: sp.get('sort') ?? 'createdAt,DESC',
  }
}

function setParams(sp: URLSearchParams, patch: Record<string, unknown>) {
  const next = new URLSearchParams(sp)
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined || v === null || v === '') next.delete(k)
    else next.set(k, String(v))
  }
  return next
}

export default function InboxBookpairs() {
  const { toast } = useToast()
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = useMemo(() => parseFilters(searchParams), [searchParams])

  const [pageData, setPageData] = useState<Page<BookpairInboxItem>>({ items: [], page: 1, size: filters.size, totalItems: 0, totalPages: 0 })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const [detailsItem, setDetailsItem] = useState<BookpairInboxItem | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [confirmMode, setConfirmMode] = useState<'approve' | 'reject'>('approve')
  const [confirmNote, setConfirmNote] = useState('')
  const [confirmLoading, setConfirmLoading] = useState(false)

  const fetchList = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await listBookpairInbox(filters)
      setPageData(data)
      // Deselect ids not present on this page
      setSelectedIds((prev) => prev.filter((id) => data.items.some((it) => it.id === id)))
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Erro ao carregar inbox'
      setError(msg)
      toast({ variant: 'destructive', title: 'Erro', description: msg })
    } finally {
      setLoading(false)
    }
  }, [filters])

  useEffect(() => { void fetchList() }, [fetchList])

  function patchFilters(patch: Partial<typeof filters>) {
    // Changing filters resets page to 1
    const next = { ...patch }
    if ('status' in patch || 'seriesId' in patch || 'bookId' in patch || 'sourceTag' in patch || 'q' in patch || 'qualityMin' in patch || 'qualityMax' in patch) {
      next.page = 1
    }
    setSearchParams(setParams(searchParams, next))
  }

  function onToggleAll(checked: boolean) {
    setSelectedIds(checked ? pageData.items.map((i) => i.id) : [])
  }
  function onToggleOne(id: number, checked: boolean) {
    setSelectedIds((prev) => (checked ? [...new Set([...prev, id])] : prev.filter((x) => x !== id)))
  }

  async function doApprove(id: number) {
    const idx = pageData.items.findIndex((i) => i.id === id)
    if (idx === -1) return
    const prev = pageData.items[idx]
    const optimistic = { ...prev, status: 'approved' as InboxStatus, reviewer: prev.reviewer ?? 'you', reviewedAt: new Date().toISOString() }
    setPageData((p) => ({ ...p, items: p.items.map((i) => (i.id === id ? optimistic : i)) }))
    try {
      await approveBookpair(id)
      toast({ title: 'Aprovado', description: `Item #${id} aprovado.` })
      if (filters.status === 'pending') setPageData((p) => ({ ...p, items: p.items.filter((i) => i.id !== id) }))
    } catch (e) {
      setPageData((p) => ({ ...p, items: p.items.map((i) => (i.id === id ? prev : i)) }))
      toast({ variant: 'destructive', title: 'Falha ao aprovar' })
    }
  }

  async function doReject(id: number) {
    const idx = pageData.items.findIndex((i) => i.id === id)
    if (idx === -1) return
    const prev = pageData.items[idx]
    const optimistic = { ...prev, status: 'rejected' as InboxStatus, reviewer: prev.reviewer ?? 'you', reviewedAt: new Date().toISOString() }
    setPageData((p) => ({ ...p, items: p.items.map((i) => (i.id === id ? optimistic : i)) }))
    try {
      await rejectBookpair(id)
      toast({ title: 'Rejeitado', description: `Item #${id} rejeitado.` })
      if (filters.status === 'pending') setPageData((p) => ({ ...p, items: p.items.filter((i) => i.id !== id) }))
    } catch (e) {
      setPageData((p) => ({ ...p, items: p.items.map((i) => (i.id === id ? prev : i)) }))
      toast({ variant: 'destructive', title: 'Falha ao rejeitar' })
    }
  }

  function openBulkApprove() {
    setConfirmMode('approve')
    setConfirmNote('')
    setConfirmOpen(true)
  }
  function openBulkReject() {
    setConfirmMode('reject')
    setConfirmNote('')
    setConfirmOpen(true)
  }
  async function onConfirmBulk() {
    setConfirmLoading(true)
    try {
      if (confirmMode === 'approve') {
        await bulkApprove(selectedIds, undefined, confirmNote || undefined)
        toast({ title: 'Lote aprovado', description: `${selectedIds.length} itens aprovados.` })
      } else {
        await bulkReject(selectedIds, undefined, confirmNote || undefined)
        toast({ title: 'Lote rejeitado', description: `${selectedIds.length} itens rejeitados.` })
      }
      setConfirmOpen(false)
      setSelectedIds([])
      await fetchList()
    } catch (e) {
      toast({ variant: 'destructive', title: 'Falha na operação em massa' })
    } finally {
      setConfirmLoading(false)
    }
  }

  const empty = !loading && pageData.items.length === 0

  return (
    <div className='space-y-4'>
      <h1 className='text-xl font-semibold'>Inbox de Pares (Livros)</h1>

      <InboxToolbar
        status={filters.status}
        seriesId={filters.seriesId}
        bookId={filters.bookId}
        sourceTag={filters.sourceTag}
        q={filters.q}
        qualityMin={filters.qualityMin}
        qualityMax={filters.qualityMax}
        selectedCount={selectedIds.length}
        onChange={patchFilters}
        onBulkApprove={openBulkApprove}
        onBulkReject={openBulkReject}
        onReset={() => setSearchParams(new URLSearchParams({ status: 'pending' }))}
        loadSeries={getSeries}
        loadBooks={getBooks}
      />

      {loading && <div className='rounded-md border p-6 text-sm text-muted-foreground'>Carregando…</div>}
      {error && <div className='rounded-md border p-6 text-sm text-red-600'>Erro: {error}</div>}
      {empty && <div className='rounded-md border p-6 text-sm'>Nenhum item encontrado com os filtros atuais.</div>}

      {!loading && !empty && (
        <InboxTable
          items={pageData.items}
          selectedIds={selectedIds}
          page={filters.page}
          size={filters.size}
          totalPages={pageData.totalPages}
          sort={filters.sort}
          onToggleAll={onToggleAll}
          onToggleOne={onToggleOne}
          onApprove={doApprove}
          onReject={doReject}
          onDetails={(it) => setDetailsItem(it)}
          onPageChange={(p) => patchFilters({ page: p })}
          onSizeChange={(s) => patchFilters({ size: s, page: 1 })}
          onSortChange={(s) => patchFilters({ sort: s })}
        />
      )}

      <ItemDetailsDrawer
        open={!!detailsItem}
        item={detailsItem}
        loading={false}
        onClose={() => setDetailsItem(null)}
        onApprove={doApprove}
        onReject={doReject}
      />

      <ConfirmDialog
        open={confirmOpen}
        title={confirmMode === 'approve' ? 'Aprovar selecionados' : 'Rejeitar selecionados'}
        description={`${selectedIds.length} itens serão ${confirmMode === 'approve' ? 'aprovados' : 'rejeitados'}.`}
        confirmLabel={confirmMode === 'approve' ? 'Aprovar' : 'Rejeitar'}
        noteEnabled
        note={confirmNote}
        onNoteChange={setConfirmNote}
        loading={confirmLoading}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => void onConfirmBulk()}
      />
    </div>
  )
}

