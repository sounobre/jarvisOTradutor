import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { useToast } from '@/components/ui/use-toast'
import { backendApi } from '@/services/api'

interface SeriesResponse {
  id: number
  name: string
  slug: string
  description?: string
  author?: string
  authorCountry?: string
  activePeriod?: string
}

interface AuthorResponse {
  id: number
  name: string
}

interface BookResponse {
  id: number
  series?: { id: number; name: string }
  seriesId?: number
  volumeNumber?: number
  originalTitleEn?: string
  titlePtBr?: string
  type?: BookType
  yearOriginal?: number
  yearBr?: number
  publisherBr?: string
  translatorBr?: string
  isbn13Br?: string
  downloaded?: boolean
  pathEn?: string
  pathPt?: string
  pairsImported?: boolean
}

const bookTypes = [
  'ROMANCE',
  'NOVELA',
  'CONTO',
  'ANTOLOGIA',
  'OMNIBUS',
  'SPINOFF',
  'EXTRA',
  'OUTRO',
] as const

type BookType = (typeof bookTypes)[number]

interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

const PAGE_SIZE = 50

const SeriesSchema = z.object({
  name: z.string().min(1, 'Informe o nome'),
  slug: z.string().min(1, 'Informe o slug'),
  description: z.string().optional(),
  author: z.string().optional(),
  authorCountry: z.string().optional(),
  activePeriod: z.string().optional(),
})

type SeriesInput = z.infer<typeof SeriesSchema>

const BookSchema = z.object({
  seriesId: z.string().optional(),
  volumeNumber: z.string().optional(),
  originalTitleEn: z.string().optional(),
  titlePtBr: z.string().optional(),
  type: z.enum(bookTypes).optional(),
  yearOriginal: z.string().optional(),
  yearBr: z.string().optional(),
  publisherBr: z.string().optional(),
  translatorBr: z.string().optional(),
  isbn13Br: z.string().optional(),
  downloaded: z.boolean(),
  pathEn: z.string().optional(),
  pathPt: z.string().optional(),
  pairsImported: z.boolean(),
})

type BookInput = z.infer<typeof BookSchema>

interface EditableBookEntry {
  id?: number
  localId: string
  form: BookInput
}

interface PairImportInfo {
  fileEn: File | null
  filePt: File | null
  isImporting: boolean
}

export default function SeriesBooks() {
  const { toast } = useToast()
  const [series, setSeries] = useState<SeriesResponse[]>([])
  const [authors, setAuthors] = useState<AuthorResponse[]>([])
  const [selectedAuthorId, setSelectedAuthorId] = useState<string>('')
  const [seriesLookupSelection, setSeriesLookupSelection] = useState('')
  const [selectedSeriesIdForEdit, setSelectedSeriesIdForEdit] = useState<number | null>(null)
  const [editableBooks, setEditableBooks] = useState<EditableBookEntry[]>([])
  const [activeEditableBookIndex, setActiveEditableBookIndex] = useState(0)
  const [excelFile, setExcelFile] = useState<File | null>(null)
  const [isImporting, setIsImporting] = useState(false)
  const [isExporting, setIsExporting] = useState(false)
  const [savingBookIndex, setSavingBookIndex] = useState<number | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [pairImportState, setPairImportState] = useState<Record<string, PairImportInfo>>({})
  const pairFileInputsRef = useRef<Record<string, { en: HTMLInputElement | null; pt: HTMLInputElement | null }>>({})

  const defaultPairImportState: PairImportInfo = { fileEn: null, filePt: null, isImporting: false }

  function getEntryKey(entry: EditableBookEntry) {
    return entry.localId
  }

  function getPairState(key: string): PairImportInfo {
    return pairImportState[key] ?? defaultPairImportState
  }

  function clearPairFileInputs(key: string) {
    const refs = pairFileInputsRef.current[key]
    if (refs?.en) refs.en.value = ''
    if (refs?.pt) refs.pt.value = ''
  }

  const defaultSeriesValues: SeriesInput = {
    name: '',
    slug: '',
    description: '',
    author: '',
    authorCountry: '',
    activePeriod: '',
  }

  const defaultBookValues: BookInput = {
    seriesId: '',
    volumeNumber: '',
    originalTitleEn: '',
    titlePtBr: '',
    type: 'ROMANCE',
    yearOriginal: '',
    yearBr: '',
    publisherBr: '',
    translatorBr: '',
    isbn13Br: '',
    downloaded: false,
    pathEn: '',
    pathPt: '',
    pairsImported: false,
  }

  const seriesForm = useForm<SeriesInput>({
    resolver: zodResolver(SeriesSchema),
    defaultValues: defaultSeriesValues,
  })

  const generatedSlugRef = useRef('')
  const watchedSeriesName = seriesForm.watch('name')
  const watchedSlug = seriesForm.watch('slug')

  const bookForm = useForm<BookInput>({
    resolver: zodResolver(BookSchema),
    defaultValues: defaultBookValues,
  })
  const fileInputsRef = useRef<Record<string, HTMLInputElement | null>>({})

  async function fetchSeries(options: { authorId?: number } = {}) {
    const { authorId } = options

    try {
      const endpoint = authorId !== undefined ? `/api/series/author/${authorId}` : '/api/series'
      const response = await backendApi.get<SeriesResponse[] | SeriesResponse>(endpoint)
      const data = response.data

      if (Array.isArray(data)) {
        setSeries(data)
      } else if (data) {
        setSeries([data])
      } else {
        setSeries([])
      }
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : 'Falha ao carregar series'
      toast({ variant: 'destructive', title: 'Erro', description: message })
    }
  }

  async function fetchAuthors() {
    try {
      const response = await backendApi.get<AuthorResponse[]>('/api/authors/list')
      setAuthors(response.data ?? [])
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : 'Falha ao carregar autores'
      toast({ variant: 'destructive', title: 'Erro', description: message })
    }
  }

  async function loadSeriesDetail(seriesId: number) {
    try {
      const [seriesResponse, booksResponse] = await Promise.all([
        backendApi.get<SeriesResponse>(`/api/series/${seriesId}`),
        backendApi.get<PageResponse<BookResponse> | BookResponse[]>('/api/books', {
          params: { page: 0, size: PAGE_SIZE, seriesId },
        }),
      ])

      const seriesData = seriesResponse.data
      seriesForm.reset({
        name: seriesData?.name ?? '',
        slug: seriesData?.slug ?? '',
        description: seriesData?.description ?? '',
        author: seriesData?.author ?? '',
        authorCountry: seriesData?.authorCountry ?? '',
        activePeriod: seriesData?.activePeriod ?? '',
      })
      generatedSlugRef.current = seriesData?.slug ?? ''

      const rawBooks = Array.isArray(booksResponse.data)
        ? booksResponse.data
        : isPageResponse<BookResponse>(booksResponse.data)
          ? booksResponse.data.content ?? []
          : []

      const mappedBooks = rawBooks.map((book) => ({
        id: book.id,
        localId: book.id !== undefined ? `remote-${book.id}` : generateLocalId(),
        form: {
          seriesId: book.seriesId ? String(book.seriesId) : book.series ? String(book.series.id) : '',
          volumeNumber: book.volumeNumber !== undefined ? String(book.volumeNumber) : '',
          originalTitleEn: book.originalTitleEn ?? '',
          titlePtBr: book.titlePtBr ?? '',
          type: book.type ?? 'ROMANCE',
          yearOriginal: book.yearOriginal !== undefined ? String(book.yearOriginal) : '',
          yearBr: book.yearBr !== undefined ? String(book.yearBr) : '',
          publisherBr: book.publisherBr ?? '',
          translatorBr: book.translatorBr ?? '',
          isbn13Br: book.isbn13Br ?? '',
          downloaded: Boolean(book.downloaded),
          pathEn: book.pathEn ?? '',
          pathPt: book.pathPt ?? '',
          pairsImported: Boolean(book.pairsImported),
        },
      }))

      const sortedEditableBooks = sortEditableEntries(mappedBooks)

      setEditableBooks(sortedEditableBooks)
      setPairImportState({})
      pairFileInputsRef.current = {}
      setActiveEditableBookIndex(0)
      setSelectedSeriesIdForEdit(seriesId)
    } catch (error: unknown) {
      const message = parseApiError(error)
      toast({ variant: 'destructive', title: 'Erro ao carregar série', description: message })
    }
  }

  useEffect(() => {
    void fetchAuthors()
    void fetchSeries()
  }, [])

  function resetSeriesEditing() {
    setSeriesLookupSelection('')
    setSelectedSeriesIdForEdit(null)
    setEditableBooks([])
    setPairImportState({})
    pairFileInputsRef.current = {}
    setActiveEditableBookIndex(0)
    seriesForm.reset(defaultSeriesValues)
    generatedSlugRef.current = ''
    bookForm.reset(defaultBookValues)
  }

  async function handleSeriesLookupChange(value: string) {
    setSeriesLookupSelection(value)

    if (!value) {
      resetSeriesEditing()
      return
    }

    const parsedId = parseSeriesId(value)
    if (parsedId === undefined) {
      resetSeriesEditing()
      return
    }

    await loadSeriesDetail(parsedId)
  }

  async function handleAuthorChange(value: string) {
    setSelectedAuthorId(value)
    resetSeriesEditing()

    const parsedId = parseAuthorId(value)
    if (parsedId === undefined) {
      await fetchSeries()
      return
    }

    await fetchSeries({ authorId: parsedId })
  }

  function appendEditableBook(initial?: BookInput) {
    const newEntry: EditableBookEntry = {
      id: undefined,
      localId: generateLocalId(),
      form: {
        ...defaultBookValues,
        ...initial,
        seriesId: String(selectedSeriesIdForEdit ?? ''),
      },
    }

    setEditableBooks((prev) => {
      const sorted = sortEditableEntries([...prev, newEntry])
      const newIndex = sorted.findIndex((entry) => entry.localId === newEntry.localId)
      setActiveEditableBookIndex(newIndex === -1 ? sorted.length - 1 : newIndex)
      return sorted
    })

    setPairImportState((prev) => ({
      ...prev,
      [newEntry.localId]: { ...defaultPairImportState },
    }))
  }

  function updateEditableBookField(index: number, field: keyof BookInput, value: string | boolean) {
    setEditableBooks((prev) => {
      let updatedEntry: EditableBookEntry | null = null
      const updated = prev.map((entry, currentIndex) => {
        if (currentIndex !== index) return entry
        updatedEntry = { ...entry, form: { ...entry.form, [field]: value } }
        return updatedEntry
      })
      const sorted = sortEditableEntries(updated)
      if (updatedEntry) {
        const nextIndex = sorted.findIndex((entry) => entry.localId === updatedEntry.localId)
        if (nextIndex !== -1) {
          setActiveEditableBookIndex(nextIndex)
        }
      }
      return sorted
    })
  }

  function removeEditableBook(index: number) {
    setEditableBooks((prev) => {
      const removedEntry = prev[index]
      const next = prev.filter((_, currentIndex) => currentIndex !== index)
      setActiveEditableBookIndex((current) => {
        if (next.length === 0) return 0
        if (current > index) return Math.min(current - 1, next.length - 1)
        if (current === index) return Math.min(index, next.length - 1)
        return Math.min(current, next.length - 1)
      })
      setSavingBookIndex((current) => {
        if (current === null) return null
        if (current === index) return null
        if (current > index) return current - 1
        return current
      })
      if (removedEntry) {
        setPairImportState((state) => {
          if (!(removedEntry.localId in state)) return state
          const { [removedEntry.localId]: _removed, ...rest } = state
          return rest
        })
        delete pairFileInputsRef.current[removedEntry.localId]
      }
      return next
    })
  }

  async function saveEditableBook(index: number) {
    const entry = editableBooks[index]
    if (!entry) return
    if (selectedSeriesIdForEdit === null) return

    const seriesIdValue = toNumber(entry.form.seriesId)
    const payload = cleanPayload({
      seriesId: seriesIdValue ?? selectedSeriesIdForEdit ?? undefined,
      volumeNumber: toNumber(entry.form.volumeNumber),
      originalTitleEn: optionalString(entry.form.originalTitleEn),
      titlePtBr: optionalString(entry.form.titlePtBr),
      type: entry.form.type,
      yearOriginal: toNumber(entry.form.yearOriginal),
      yearBr: toNumber(entry.form.yearBr),
      publisherBr: optionalString(entry.form.publisherBr),
      translatorBr: optionalString(entry.form.translatorBr),
      isbn13Br: optionalString(entry.form.isbn13Br),
      downloaded: Boolean(entry.form.downloaded),
      pathEn: optionalString(entry.form.pathEn),
      pathPt: optionalString(entry.form.pathPt),
      pairsImported: Boolean(entry.form.pairsImported),
    })

    try {
      setSavingBookIndex(index)
      if (entry.id) {
        await backendApi.patch(`/api/books/${entry.id}`, payload)
        toast({ title: 'Livro atualizado', description: payload.titlePtBr || payload.originalTitleEn || 'Dados atualizados.' })
      } else {
        await backendApi.post('/api/books', payload)
        toast({ title: 'Livro criado', description: payload.titlePtBr || payload.originalTitleEn || 'Livro adicionado à série.' })
      }

      await loadSeriesDetail(selectedSeriesIdForEdit)
    } catch (error: unknown) {
      const message = parseApiError(error)
      toast({ variant: 'destructive', title: 'Erro ao salvar livro', description: message })
    } finally {
      setSavingBookIndex(null)
    }
  }

  function openFileDialog(index: number, field: 'pathEn' | 'pathPt') {
    const inputKey = `${index}-${field}`
    const existing = fileInputsRef.current[inputKey]

    if (existing) {
      existing.click()
      return
    }

    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.epub'
    input.className = 'hidden'
    input.onchange = (event) => {
      const files = (event.target as HTMLInputElement).files
      if (!files || files.length === 0) return
      const file = files[0]
      updateEditableBookField(index, field, file.name)
    }

    fileInputsRef.current[inputKey] = input
    input.click()
  }

  async function onSubmitSeries(values: SeriesInput) {
    const payload = cleanPayload({
      name: values.name,
      slug: values.slug,
      description: optionalString(values.description),
      author: optionalString(values.author),
      authorCountry: optionalString(values.authorCountry),
      activePeriod: optionalString(values.activePeriod),
    })

    try {
      if (selectedSeriesIdForEdit === null) {
        const response = await backendApi.post<SeriesResponse>('/api/series', payload)
        toast({ title: 'Série cadastrada', description: payload.name })
        seriesForm.reset(defaultSeriesValues)
        generatedSlugRef.current = ''
        await fetchSeries({ authorId: parseAuthorId(selectedAuthorId) })
        if (response.data?.id) {
          const newId = response.data.id
          setSeriesLookupSelection(String(newId))
          await loadSeriesDetail(newId)
        }
      } else {
        await backendApi.put(`/api/series/${selectedSeriesIdForEdit}`, payload)
        toast({ title: 'Série atualizada', description: payload.name })
        await fetchSeries({ authorId: parseAuthorId(selectedAuthorId) })
        await loadSeriesDetail(selectedSeriesIdForEdit)
      }
    } catch (error: unknown) {
      const message = parseApiError(error)
      toast({ variant: 'destructive', title: 'Erro ao salvar série', description: message })
    }
  }

  async function onCreateBook(values: BookInput) {
    const payload = cleanPayload({
      seriesId: toNumber(values.seriesId),
      volumeNumber: toNumber(values.volumeNumber),
      originalTitleEn: optionalString(values.originalTitleEn),
      titlePtBr: optionalString(values.titlePtBr),
      type: values.type,
      yearOriginal: toNumber(values.yearOriginal),
      yearBr: toNumber(values.yearBr),
      publisherBr: optionalString(values.publisherBr),
      translatorBr: optionalString(values.translatorBr),
      isbn13Br: optionalString(values.isbn13Br),
      downloaded: Boolean(values.downloaded),
      pathEn: optionalString(values.pathEn),
      pathPt: optionalString(values.pathPt),
      pairsImported: Boolean(values.pairsImported),
    })

    try {
      await backendApi.post('/api/books', payload)
      toast({ title: 'Livro cadastrado', description: payload.titlePtBr || payload.originalTitleEn || 'Cadastro concluído' })
      bookForm.reset()
      if (selectedSeriesIdForEdit !== null) {
        await loadSeriesDetail(selectedSeriesIdForEdit)
      }
    } catch (error: unknown) {
      const message = parseApiError(error)
      toast({ variant: 'destructive', title: 'Erro ao criar livro', description: message })
    }
  }

  async function handleExcelImport() {
    if (!excelFile) {
      fileInputRef.current?.click()
      return
    }

    const formData = new FormData()
    formData.append('file', excelFile)

    try {
      setIsImporting(true)
      const response = await backendApi.post<{ created: number; updated: number; skipped: number }>('/api/catalog/import-excel', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      toast({
        title: 'Importação concluída',
        description: `Criados: ${response.data?.created ?? 0} ? Atualizados: ${response.data?.updated ?? 0} ? Pulados: ${response.data?.skipped ?? 0}`,
      })
      setExcelFile(null)
      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
      await fetchSeries({ authorId: parseAuthorId(selectedAuthorId) })
      if (selectedSeriesIdForEdit !== null) {
        await loadSeriesDetail(selectedSeriesIdForEdit)
      }
    } catch (error: unknown) {
      const message = parseApiError(error)
      toast({ variant: 'destructive', title: 'Erro na importação', description: message })
    } finally {
      setIsImporting(false)
    }
  }

  async function handleExcelExport() {
    try {
      setIsExporting(true)
      const response = await backendApi.get<Blob>('/api/catalog/export-excel', {
        responseType: 'blob',
        headers: {
          Accept: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        },
      })

      const blob = new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = 'catalogo-jarvis.xlsx'
      link.click()
      URL.revokeObjectURL(url)

      toast({ title: 'Exportação iniciada', description: 'Download do catálogo gerado.' })
    } catch (error: unknown) {
      const message = parseApiError(error)
      toast({ variant: 'destructive', title: 'Erro na exportação', description: message })
    } finally {
      setIsExporting(false)
    }
  }

  function handlePairFileChange(entry: EditableBookEntry | null, index: number, field: 'fileEn' | 'filePt', file: File | null) {
    if (!entry) return

    const entryKey = getEntryKey(entry)

    setPairImportState((prev) => ({
      ...prev,
      [entryKey]: { ...(prev[entryKey] ?? { ...defaultPairImportState }), [field]: file },
    }))

    if (file) {
      const targetField = field === 'fileEn' ? 'pathEn' : 'pathPt'
      const currentPath = entry.form[targetField] ?? ''
      const nextPath = buildPathWithFilename(currentPath, file.name)
      updateEditableBookField(index, targetField, nextPath)
    }
  }

  async function handlePairImport(entry: EditableBookEntry) {
    const entryKey = getEntryKey(entry)
    const pairState = getPairState(entryKey)
    const fileEn = pairState.fileEn
    const filePt = pairState.filePt

    if (!entry.id) {
      toast({ variant: 'destructive', title: 'Livro não salvo', description: 'Salve o livro antes de importar os pares.' })
      return
    }

    if (!fileEn || !filePt) {
      toast({ variant: 'destructive', title: 'Arquivos necessários', description: 'Selecione os arquivos EN e PT-BR antes de importar.' })
      return
    }

    const derivedSeriesId = selectedSeriesIdForEdit ?? toNumber(entry.form.seriesId)
    if (derivedSeriesId === undefined) {
      toast({ variant: 'destructive', title: 'Série não definida', description: 'Associe uma série ao livro antes de importar os pares.' })
      return
    }

    const formData = new FormData()
    formData.append('file_en', fileEn)
    formData.append('file_pt', filePt)
    formData.append('seriesId', String(derivedSeriesId))
    formData.append('bookId', String(entry.id))

    try {
      setPairImportState((prev) => ({
        ...prev,
        [entryKey]: { ...pairState, isImporting: true },
      }))

      await backendApi.post('/tm/import/epub-pair', formData, {
        params: {
          level: 'paragraph',
          mode: 'embedding',
          srcLang: 'en',
          tgtLang: 'pt',
          minQuality: 0.6,
        },
        headers: { 'Content-Type': 'multipart/form-data' },
      })

      toast({ title: 'Importação iniciada', description: 'Processando pares do EPUB selecionado.' })

      setPairImportState((prev) => ({
        ...prev,
        [entryKey]: { ...defaultPairImportState },
      }))
      clearPairFileInputs(entryKey)
    } catch (error: unknown) {
      const message = parseApiError(error)
      toast({ variant: 'destructive', title: 'Erro ao importar pares', description: message })
      setPairImportState((prev) => ({
        ...prev,
        [entryKey]: { ...(prev[entryKey] ?? { ...defaultPairImportState }), fileEn, filePt, isImporting: false },
      }))
    }
  }

  const activeEditableBook = editableBooks[activeEditableBookIndex] ?? null
  const editableBooksCount = editableBooks.length
  const isFirstEditableBook = activeEditableBookIndex === 0
  const isLastEditableBook = editableBooksCount === 0 ? true : activeEditableBookIndex >= editableBooksCount - 1
  const activeEntryKey = activeEditableBook ? getEntryKey(activeEditableBook) : null
  const activePairState = activeEntryKey ? getPairState(activeEntryKey) : defaultPairImportState

  return (
    <div className='space-y-6'>
      <div className='grid gap-6 lg:grid-cols-2'>
        <Card>
          <CardHeader>
            <CardTitle>Nova Série / Universo</CardTitle>
          </CardHeader>
          <CardContent>
            <Form {...seriesForm}>
              <form onSubmit={seriesForm.handleSubmit(onSubmitSeries)} className='grid gap-4'>
                <div className='grid gap-2'>
                  <label className='text-sm font-medium'>Selecionar autor</label>
                  <select
                    className='border rounded-md h-9 px-2 text-sm'
                    value={selectedAuthorId}
                    onChange={(event) => {
                      void handleAuthorChange(event.target.value)
                    }}
                  >
                    <option value=''>Todos os autores</option>
                    {authors.map((author) => (
                      <option key={author.id} value={author.id}>
                        {author.name}
                      </option>
                    ))}
                  </select>
                </div>

                <div className='grid gap-2'>
                  <label className='text-sm font-medium'>Procurar série existente</label>
                  <div className='flex flex-wrap items-center gap-2'>
                    <select
                      className='border rounded-md h-9 px-2 text-sm'
                      value={seriesLookupSelection}
                      onChange={(event) => handleSeriesLookupChange(event.target.value)}
                    >
                      <option value=''>Selecione...</option>
                      {series.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                    {selectedSeriesIdForEdit !== null && (
                      <Button
                        type='button'
                        variant='outline'
                        size='sm'
                        onClick={() => {
                          resetSeriesEditing()
                        }}
                      >
                        Limpar seleção
                      </Button>
                    )}
                  </div>
                </div>

                <FormField
                  control={seriesForm.control}
                  name='name'
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Nome</FormLabel>
                      <FormControl>
                        <Input placeholder='Stormlight Archive' {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={seriesForm.control}
                  name='slug'
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Slug</FormLabel>
                      <FormControl>
                        <Input placeholder='stormlight-archive' {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={seriesForm.control}
                  name='description'
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Descrição</FormLabel>
                      <FormControl>
                        <Textarea rows={3} placeholder='Saga de alta fantasia...' {...field} />
                      </FormControl>
                    </FormItem>
                  )}
                />

                <div className='grid gap-4 sm:grid-cols-2'>
                  <FormField
                    control={seriesForm.control}
                    name='author'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Autor</FormLabel>
                        <FormControl>
                          <Input placeholder='Brandon Sanderson' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={seriesForm.control}
                    name='authorCountry'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>País do Autor</FormLabel>
                        <FormControl>
                          <Input placeholder='EUA' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />
                </div>

                <FormField
                  control={seriesForm.control}
                  name='activePeriod'
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Período Ativo</FormLabel>
                      <FormControl>
                        <Input placeholder='1997–presente' {...field} />
                      </FormControl>
                    </FormItem>
                  )}
                />

                <div className='flex items-center gap-2'>
                  <Button type='submit'>
                    {selectedSeriesIdForEdit === null ? 'Criar série' : 'Atualizar série'}
                  </Button>
                  {selectedSeriesIdForEdit !== null && (
                    <Button type='button' variant='ghost' onClick={() => resetSeriesEditing()}>
                      Cancelar edição
                    </Button>
                  )}
                </div>
              </form>
            </Form>
          </CardContent>
        </Card>

      <Card>
        <CardHeader>
          <CardTitle>Novo Livro</CardTitle>
        </CardHeader>
        <CardContent>
          {selectedSeriesIdForEdit !== null ? (
            <div className='space-y-4'>
              {editableBooksCount === 0 ? (
                <p className='text-sm text-muted-foreground'>Nenhum livro cadastrado para esta série. Adicione abaixo.</p>
              ) : activeEditableBook ? (
                <>
                  {editableBooksCount > 1 && (
                    <div className='flex items-center justify-between gap-3 rounded-md border px-3 py-2 text-xs text-muted-foreground'>
                      <Button
                        type='button'
                        variant='outline'
                        size='sm'
                        onClick={() => setActiveEditableBookIndex((current) => Math.max(current - 1, 0))}
                        disabled={isFirstEditableBook}
                      >
                        Anterior
                      </Button>
                      <span>Livro {activeEditableBookIndex + 1} de {editableBooksCount}</span>
                      <Button
                        type='button'
                        variant='outline'
                        size='sm'
                        onClick={() => setActiveEditableBookIndex((current) => Math.min(current + 1, editableBooksCount - 1))}
                        disabled={isLastEditableBook}
                      >
                        Próximo
                      </Button>
                    </div>
                  )}

                  <div className='rounded-lg border p-4 space-y-4'>
                    <div className='grid gap-4 sm:grid-cols-2'>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Número na série</label>
                        <Input
                          type='number'
                          value={activeEditableBook.form.volumeNumber ?? ''}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'volumeNumber', event.target.value)}
                          placeholder='1'
                        />
                      </div>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Tipo</label>
                        <select
                          className='border rounded-md h-9 px-2 w-full'
                          value={activeEditableBook.form.type ?? ''}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'type', event.target.value)}
                        >
                          <option value=''>Selecione...</option>
                          {bookTypes.map((type) => (
                            <option key={type} value={type}>
                              {type}
                            </option>
                          ))}
                        </select>
                      </div>
                    </div>

                    <div className='grid gap-4 sm:grid-cols-2'>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Título Original (EN)</label>
                        <Input
                          value={activeEditableBook.form.originalTitleEn ?? ''}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'originalTitleEn', event.target.value)}
                          placeholder='The Way of Kings'
                        />
                      </div>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Título PT-BR</label>
                        <Input
                          value={activeEditableBook.form.titlePtBr ?? ''}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'titlePtBr', event.target.value)}
                          placeholder='O Caminho dos Reis'
                        />
                      </div>
                    </div>

                    <div className='grid gap-4 sm:grid-cols-2'>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Ano publicação (Original)</label>
                        <Input
                          type='number'
                          value={activeEditableBook.form.yearOriginal ?? ''}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'yearOriginal', event.target.value)}
                          placeholder='2010'
                        />
                      </div>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Ano publicação (BR)</label>
                        <Input
                          type='number'
                          value={activeEditableBook.form.yearBr ?? ''}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'yearBr', event.target.value)}
                          placeholder='2014'
                        />
                      </div>
                    </div>

                    <div className='grid gap-4 sm:grid-cols-2'>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Editora BR</label>
                        <Input
                          value={activeEditableBook.form.publisherBr ?? ''}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'publisherBr', event.target.value)}
                          placeholder='Intrínseca'
                        />
                      </div>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Tradutor BR</label>
                        <Input
                          value={activeEditableBook.form.translatorBr ?? ''}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'translatorBr', event.target.value)}
                          placeholder='Fulano'
                        />
                      </div>
                    </div>

                    <div className='space-y-2'>
                      <label className='text-sm font-medium'>ISBN-13 BR</label>
                      <Input
                        value={activeEditableBook.form.isbn13Br ?? ''}
                        onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'isbn13Br', event.target.value)}
                        placeholder='978...'
                      />
                    </div>

                    <div className='grid gap-4 sm:grid-cols-2'>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Caminho EN</label>
                        <div className='flex flex-wrap items-center gap-2'>
                          <Input
                            value={activeEditableBook.form.pathEn ?? ''}
                            onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'pathEn', event.target.value)}
                            placeholder='/books/en/arquivo.epub'
                          />
                          <Button
                            type='button'
                            variant='outline'
                            size='sm'
                            onClick={() => openFileDialog(activeEditableBookIndex, 'pathEn')}
                          >
                            Escolher arquivo
                          </Button>
                        </div>
                      </div>
                      <div className='space-y-2'>
                        <label className='text-sm font-medium'>Caminho PT-BR</label>
                        <div className='flex flex-wrap items-center gap-2'>
                          <Input
                            value={activeEditableBook.form.pathPt ?? ''}
                            onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'pathPt', event.target.value)}
                            placeholder='/books/ptbr/arquivo.epub'
                          />
                          <Button
                            type='button'
                            variant='outline'
                            size='sm'
                            onClick={() => openFileDialog(activeEditableBookIndex, 'pathPt')}
                          >
                            Escolher arquivo
                          </Button>
                        </div>
                      </div>
                    </div>

                    <div className='grid gap-4 sm:grid-cols-2'>
                      <label className='flex items-center gap-3 border rounded-md px-3 py-2 text-sm'>
                        <input
                          type='checkbox'
                          className='h-4 w-4'
                          checked={activeEditableBook.form.downloaded ?? false}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'downloaded', event.target.checked)}
                        />
                        Arquivo baixado
                      </label>
                      <label className='flex items-center gap-3 border rounded-md px-3 py-2 text-sm'>
                        <input
                          type='checkbox'
                          className='h-4 w-4'
                          checked={activeEditableBook.form.pairsImported ?? false}
                          onChange={(event) => updateEditableBookField(activeEditableBookIndex, 'pairsImported', event.target.checked)}
                        />
                        Pares importados
                      </label>
                    </div>

                    <div className='space-y-3 rounded-md border p-3'>
                      <div>
                        <p className='text-sm font-semibold'>Importar pares de EPUB</p>
                        <p className='text-xs text-muted-foreground'>Selecione os arquivos EN e PT-BR para iniciar a importação dos pares.</p>
                      </div>
                      <div className='grid gap-3 sm:grid-cols-2'>
                        <div className='space-y-2'>
                          <label className='text-sm font-medium'>Arquivo EN (.epub)</label>
                          <input
                            ref={(element) => {
                              if (!activeEntryKey) return
                              pairFileInputsRef.current[activeEntryKey] = {
                                ...(pairFileInputsRef.current[activeEntryKey] ?? { en: null, pt: null }),
                                en: element,
                              }
                            }}
                            type='file'
                            accept='.epub'
                            className='hidden'
                            onChange={(event) => {
                              if (!activeEditableBook) return
                              handlePairFileChange(activeEditableBook, activeEditableBookIndex, 'fileEn', event.target.files?.[0] ?? null)
                            }}
                          />
                          <div className='flex items-center gap-2'>
                            <Button
                              type='button'
                              variant='outline'
                              size='sm'
                              onClick={() => activeEntryKey && pairFileInputsRef.current[activeEntryKey]?.en?.click()}
                              disabled={activePairState.isImporting}
                            >
                              Selecionar EN
                            </Button>
                            {activePairState.fileEn && (
                              <span className='text-xs text-muted-foreground truncate max-w-[180px]'>{activePairState.fileEn.name}</span>
                            )}
                          </div>
                        </div>
                        <div className='space-y-2'>
                          <label className='text-sm font-medium'>Arquivo PT-BR (.epub)</label>
                          <input
                            ref={(element) => {
                              if (!activeEntryKey) return
                              pairFileInputsRef.current[activeEntryKey] = {
                                ...(pairFileInputsRef.current[activeEntryKey] ?? { en: null, pt: null }),
                                pt: element,
                              }
                            }}
                            type='file'
                            accept='.epub'
                            className='hidden'
                            onChange={(event) => {
                              if (!activeEditableBook) return
                              handlePairFileChange(activeEditableBook, activeEditableBookIndex, 'filePt', event.target.files?.[0] ?? null)
                            }}
                          />
                          <div className='flex items-center gap-2'>
                            <Button
                              type='button'
                              variant='outline'
                              size='sm'
                              onClick={() => activeEntryKey && pairFileInputsRef.current[activeEntryKey]?.pt?.click()}
                              disabled={activePairState.isImporting}
                            >
                              Selecionar PT-BR
                            </Button>
                            {activePairState.filePt && (
                              <span className='text-xs text-muted-foreground truncate max-w-[180px]'>{activePairState.filePt.name}</span>
                            )}
                          </div>
                        </div>
                      </div>
                      <div className='flex flex-wrap items-center gap-2'>
                        <Button
                          type='button'
                          size='sm'
                          onClick={() => activeEditableBook && handlePairImport(activeEditableBook)}
                          disabled={
                            activePairState.isImporting ||
                            !activeEditableBook?.id ||
                            !activePairState.fileEn ||
                            !activePairState.filePt
                          }
                        >
                          {activePairState.isImporting ? 'Importando pares…' : 'Importar pares'}
                        </Button>
                        {!activeEditableBook?.id && (
                          <span className='text-xs text-muted-foreground'>Salve o livro antes de importar os pares.</span>
                        )}
                      </div>
                    </div>

                    <div className='flex flex-wrap items-center gap-2'>
                      <Button
                        type='button'
                        onClick={() => saveEditableBook(activeEditableBookIndex)}
                        disabled={savingBookIndex === activeEditableBookIndex}
                      >
                        {savingBookIndex === activeEditableBookIndex ? 'Salvando...' : activeEditableBook.id ? 'Salvar alterações' : 'Adicionar livro'}
                      </Button>
                      {activeEditableBook.id === undefined && (
                        <Button
                          type='button'
                          variant='ghost'
                          size='sm'
                          onClick={() => removeEditableBook(activeEditableBookIndex)}
                        >
                          Remover rascunho
                        </Button>
                      )}
                    </div>
                  </div>
                </>
              ) : null}

              <Button
                type='button'
                variant='outline'
                onClick={() => appendEditableBook()}
              >
                Adicionar novo livro na série
              </Button>
            </div>
          ) : (
            <Form {...bookForm}>
              <form onSubmit={bookForm.handleSubmit(onCreateBook)} className='grid gap-4'>
                <div className='grid gap-4 sm:grid-cols-2'>
                  <FormField
                    control={bookForm.control}
                    name='seriesId'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Série (opcional)</FormLabel>
                        <FormControl>
                          <select className='border rounded-md h-9 px-2 w-full' {...field}>
                            <option value=''>Sem série vinculada</option>
                            {series.map((item) => (
                              <option key={item.id} value={item.id}>
                                {item.name}
                              </option>
                            ))}
                          </select>
                        </FormControl>
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={bookForm.control}
                    name='volumeNumber'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Volume</FormLabel>
                        <FormControl>
                          <Input type='number' min={0} placeholder='1' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />
                </div>

                <FormField
                  control={bookForm.control}
                  name='originalTitleEn'
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Título Original (EN)</FormLabel>
                      <FormControl>
                        <Input placeholder='The Way of Kings' {...field} />
                      </FormControl>
                    </FormItem>
                  )}
                />

                <FormField
                  control={bookForm.control}
                  name='titlePtBr'
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Título PT-BR</FormLabel>
                      <FormControl>
                        <Input placeholder='O Caminho dos Reis' {...field} />
                      </FormControl>
                    </FormItem>
                  )}
                />

                <div className='grid gap-4 sm:grid-cols-2'>
                  <FormField
                    control={bookForm.control}
                    name='type'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Tipo</FormLabel>
                        <FormControl>
                          <select className='border rounded-md h-9 px-2 w-full' {...field}>
                            <option value=''>Selecione...</option>
                            {bookTypes.map((type) => (
                              <option key={type} value={type}>
                                {type}
                              </option>
                            ))}
                          </select>
                        </FormControl>
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={bookForm.control}
                    name='isbn13Br'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>ISBN-13 BR</FormLabel>
                        <FormControl>
                          <Input placeholder='978...' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />
                </div>

                <div className='grid gap-4 sm:grid-cols-2'>
                  <FormField
                    control={bookForm.control}
                    name='yearOriginal'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Ano publicação (Original)</FormLabel>
                        <FormControl>
                          <Input type='number' min={0} placeholder='2010' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={bookForm.control}
                    name='yearBr'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Ano publicação (BR)</FormLabel>
                        <FormControl>
                          <Input type='number' min={0} placeholder='2014' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />
                </div>

                <div className='grid gap-4 sm:grid-cols-2'>
                  <FormField
                    control={bookForm.control}
                    name='publisherBr'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Editora BR</FormLabel>
                        <FormControl>
                          <Input placeholder='Intrínseca' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={bookForm.control}
                    name='translatorBr'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Tradutor BR</FormLabel>
                        <FormControl>
                          <Input placeholder='Fulano' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />
                </div>

                <div className='grid gap-4 sm:grid-cols-2'>
                  <FormField
                    control={bookForm.control}
                    name='pathEn'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Caminho EN</FormLabel>
                        <FormControl>
                          <Input placeholder='/books/en/way_of_kings.epub' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={bookForm.control}
                    name='pathPt'
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Caminho PT-BR</FormLabel>
                        <FormControl>
                          <Input placeholder='/books/ptbr/o_caminho_dos_reis.epub' {...field} />
                        </FormControl>
                      </FormItem>
                    )}
                  />
                </div>

                <div className='grid gap-4 sm:grid-cols-2'>
                  <FormField
                    control={bookForm.control}
                    name='downloaded'
                    render={({ field }) => (
                      <FormItem className='flex items-center gap-3 border rounded-md px-3 py-2'>
                        <FormControl>
                          <input
                            type='checkbox'
                            className='h-4 w-4'
                            checked={field.value ?? false}
                            onChange={(event) => field.onChange(event.target.checked)}
                          />
                        </FormControl>
                        <FormLabel className='mt-0 font-normal'>Arquivo baixado</FormLabel>
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={bookForm.control}
                    name='pairsImported'
                    render={({ field }) => (
                      <FormItem className='flex items-center gap-3 border rounded-md px-3 py-2'>
                        <FormControl>
                          <input
                            type='checkbox'
                            className='h-4 w-4'
                            checked={field.value ?? false}
                            onChange={(event) => field.onChange(event.target.checked)}
                          />
                        </FormControl>
                        <FormLabel className='mt-0 font-normal'>Pares importados</FormLabel>
                      </FormItem>
                    )}
                  />
                </div>

                <Button type='submit'>Criar Livro</Button>
              </form>
            </Form>
          )}
        </CardContent>
      </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Importação / Exportação Excel</CardTitle>
        </CardHeader>
        <CardContent className='space-y-4'>
          <div className='space-y-2'>
            <label className='text-sm font-medium'>Arquivo Excel (.xlsx)</label>
            <input
              ref={fileInputRef}
              type='file'
              accept='.xlsx'
              onChange={(event) => setExcelFile(event.target.files?.[0] ?? null)}
              className='hidden'
            />
            <div className='flex flex-wrap items-center gap-2'>
              <Button
                variant='outline'
                type='button'
                onClick={() => fileInputRef.current?.click()}
                disabled={isImporting}
              >
                {excelFile ? 'Trocar arquivo' : 'Selecionar arquivo (.xlsx)'}
              </Button>
              {excelFile && (
                <span className='text-xs text-muted-foreground truncate max-w-[220px]'>
                  {excelFile.name}
                </span>
              )}
            </div>
            <Button type='button' onClick={handleExcelImport} disabled={isImporting}>
              {isImporting ? 'Importando…' : 'Importar catálogo'}
            </Button>
          </div>

          <div className='border-t pt-4'>
            <Button variant='outline' onClick={handleExcelExport} disabled={isExporting}>
              {isExporting ? 'Gerando…' : 'Exportar catálogo (.xlsx)'}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

function generateLocalId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `local-${Math.random().toString(36).slice(2)}-${Date.now()}`
}

function sortEditableEntries(entries: EditableBookEntry[]): EditableBookEntry[] {
  return [...entries].sort((a, b) => {
    const aVolume = toNumber(a.form.volumeNumber)
    const bVolume = toNumber(b.form.volumeNumber)

    if (aVolume !== undefined && bVolume !== undefined) {
      if (aVolume !== bVolume) return aVolume - bVolume
    } else if (aVolume !== undefined) {
      return -1
    } else if (bVolume !== undefined) {
      return 1
    }

    const aId = a.id ?? Number.POSITIVE_INFINITY
    const bId = b.id ?? Number.POSITIVE_INFINITY
    if (aId !== bId) return aId - bId

    const aTitle = a.form.titlePtBr || a.form.originalTitleEn || ''
    const bTitle = b.form.titlePtBr || b.form.originalTitleEn || ''
    const titleComparison = aTitle.localeCompare(bTitle, undefined, { sensitivity: 'accent', numeric: true })
    if (titleComparison !== 0) return titleComparison

    return a.localId.localeCompare(b.localId)
  })
}

function parseAuthorId(value: string) {
  if (!value) return undefined
  const parsed = Number(value)
  if (Number.isNaN(parsed)) return undefined
  return parsed
}

function parseSeriesId(value: string) {
  if (!value) return undefined
  const parsed = Number(value)
  if (Number.isNaN(parsed)) return undefined
  return parsed
}

function isPageResponse<T>(data: unknown): data is PageResponse<T> {
  if (!data || typeof data !== 'object') return false
  return Array.isArray((data as { content?: unknown }).content)
}

function slugify(input: string) {
  return input
    .normalize('NFD')
    .replace(/[^\w\s-]/g, '')
    .trim()
    .replace(/[\s_-]+/g, '-')
    .toLowerCase()
}

function toNumber(value?: string) {
  if (!value) return undefined
  const parsed = Number(value)
  return Number.isNaN(parsed) ? undefined : parsed
}

function optionalString(value?: string) {
  if (!value) return undefined
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}

function buildPathWithFilename(currentPath: string, fileName: string) {
  if (!currentPath) return fileName
  const base = currentPath.replace(/[^\\/]*$/, '')
  return `${base}${fileName}`
}

function cleanPayload<T extends Record<string, unknown>>(payload: T): T {
  const cleanedEntries = Object.entries(payload).filter(([, value]) => value !== undefined)
  return Object.fromEntries(cleanedEntries) as T
}

function parseApiError(error: unknown) {
  if (error && typeof error === 'object' && 'response' in error) {
    const response = (error as { response?: { data?: unknown } }).response
    const data = response?.data

    if (typeof data === 'string') return data
    if (data && typeof data === 'object') {
      if ('message' in data && typeof (data as { message?: unknown }).message === 'string') {
        return (data as { message: string }).message
      }
      if ('error' in data && typeof (data as { error?: unknown }).error === 'string') {
        return (data as { error: string }).error
      }
    }
  }
  return error instanceof Error ? error.message : 'Falha ao comunicar com o servidor.'
}
