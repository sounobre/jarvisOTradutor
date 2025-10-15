export type InboxStatus = 'pending' | 'approved' | 'rejected'

export interface BookpairInboxItem {
  id: number
  src: string
  tgt: string
  langSrc: string
  langTgt: string
  quality: number | null
  seriesId?: number | null
  bookId?: number | null
  chapter?: string | null
  location?: string | null
  sourceTag?: string | null
  status: InboxStatus
  reviewer?: string | null
  reviewedAt?: string | null
  createdAt: string
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface SeriesMeta { id: number; name: string }
export interface BookMeta { id: number; title: string }

