import { backendApi } from '@/services/api'
import type { BookpairInboxItem, InboxStatus, Page, SeriesMeta, BookMeta } from '@/types/inbox'

const BASE = '/api'

function toQuery(params: Record<string, unknown>) {
  const sp = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') sp.append(k, String(v))
  })
  return sp.toString()
}

export async function listBookpairInbox(params: {
  status?: InboxStatus
  seriesId?: number
  bookId?: number
  sourceTag?: string
  q?: string
  qualityMin?: number
  qualityMax?: number
  page?: number
  size?: number
  sort?: string
}): Promise<Page<BookpairInboxItem>> {
  const query = toQuery(params)
  const { data } = await backendApi.get<Page<BookpairInboxItem>>(`${BASE}/inbox/bookpairs${query ? `?${query}` : ''}`)
  return data
}

export async function approveBookpair(id: number, body?: { reviewer?: string; note?: string }) {
  const { data } = await backendApi.post(`${BASE}/inbox/bookpairs/${id}/approve`, body ?? {})
  return data as { ok: boolean; id: number; newStatus: InboxStatus; reviewedAt: string }
}

export async function rejectBookpair(id: number, body?: { reviewer?: string; note?: string }) {
  const { data } = await backendApi.post(`${BASE}/inbox/bookpairs/${id}/reject`, body ?? {})
  return data as { ok: boolean; id: number; newStatus: InboxStatus; reviewedAt: string }
}

export async function bulkApprove(ids: number[], reviewer?: string, note?: string) {
  const { data } = await backendApi.post(`${BASE}/inbox/bookpairs/bulk-approve`, { ids, reviewer, note })
  return data as { ok: boolean; count: number }
}

export async function bulkReject(ids: number[], reviewer?: string, note?: string) {
  const { data } = await backendApi.post(`${BASE}/inbox/bookpairs/bulk-reject`, { ids, reviewer, note })
  return data as { ok: boolean; count: number }
}

export async function consolidateApproved() {
  const { data } = await backendApi.post(`${BASE}/inbox/bookpairs/consolidate`)
  return data as { ok: boolean; tmUpserts: number; occInserted: number; embUpserts: number }
}

// Optional metadata helpers
export async function getSeries(): Promise<SeriesMeta[]> {
  const { data } = await backendApi.get<SeriesMeta[]>(`${BASE}/series`)
  return data ?? []
}

export async function getBooks(seriesId?: number): Promise<BookMeta[]> {
  const { data } = await backendApi.get<BookMeta[]>(`${BASE}/books`, { params: { seriesId } })
  return data ?? []
}

export async function suggestSourceTags(suggest?: string): Promise<string[]> {
  const { data } = await backendApi.get<string[]>(`${BASE}/inbox/bookpairs/source-tags`, { params: { suggest } })
  return data ?? []
}

