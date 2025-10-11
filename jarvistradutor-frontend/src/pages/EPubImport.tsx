import { useState } from 'react'
import { useForm, type DefaultValues } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { useToast } from '@/components/ui/use-toast'
import { backendApi } from '@/services/api'

const ImportSchema = z.object({
  file: z.instanceof(File, { message: 'Selecione um arquivo EPUB' }),
  level: z.enum(['paragraph', 'sentence']),
  mode: z.enum(['length', 'embedding']),
  srcLang: z.string().min(2, 'Informe a língua de origem'),
  tgtLang: z.string().min(2, 'Informe a língua de destino'),
  minQuality: z.number().min(0).max(1),
  seriesId: z.string().optional(),
  bookId: z.string().optional(),
  sourceTag: z.string().optional(),
})

type ImportInput = z.infer<typeof ImportSchema>

const defaultValues: DefaultValues<ImportInput> = {
  level: 'paragraph',
  mode: 'length',
  minQuality: 0.9,
  srcLang: 'en',
  tgtLang: 'pt-BR',
  sourceTag: 'epub-import',
}

export default function EPubImport() {
  const form = useForm<ImportInput>({ resolver: zodResolver(ImportSchema), defaultValues })
  const { toast } = useToast()
  const [isSubmitting, setSubmitting] = useState(false)

  async function onSubmit(values: ImportInput) {
    try {
      setSubmitting(true)
      const formData = new FormData()
      formData.append('file', values.file)
      formData.append('level', values.level)
      formData.append('mode', values.mode)
      formData.append('srcLang', values.srcLang)
      formData.append('tgtLang', values.tgtLang)
      formData.append('minQuality', String(values.minQuality))

      const trimmedSeries = values.seriesId?.trim()
      if (trimmedSeries) {
        formData.append('seriesId', trimmedSeries)
      }
      const trimmedBook = values.bookId?.trim()
      if (trimmedBook) {
        formData.append('bookId', trimmedBook)
      }
      const trimmedTag = values.sourceTag?.trim()
      if (trimmedTag) {
        formData.append('sourceTag', trimmedTag)
      }

      const response = await backendApi.post('/epub/import', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })

      toast({
        title: 'EPUB enviado',
        description: response.data?.message ?? 'Processamento iniciado com sucesso.',
      })
      form.reset(defaultValues)
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : 'Falha ao processar EPUB.'
      toast({ variant: 'destructive', title: 'Erro no upload', description: message })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Importar EPUB Alinhado</CardTitle>
      </CardHeader>
      <CardContent>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className='grid gap-4 md:grid-cols-2'>
            <FormField
              control={form.control}
              name='file'
              render={({ field }) => (
                <FormItem className='md:col-span-2'>
                  <FormLabel>Arquivo EPUB</FormLabel>
                  <FormControl>
                    <Input
                      type='file'
                      accept='.epub'
                      onChange={(event) => field.onChange(event.target.files?.[0])}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='level'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Nível</FormLabel>
                  <FormControl>
                    <select className='border rounded-md h-9 px-2 w-full' {...field}>
                      <option value='paragraph'>Parágrafo</option>
                      <option value='sentence'>Sentença</option>
                    </select>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='mode'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Modo</FormLabel>
                  <FormControl>
                    <select className='border rounded-md h-9 px-2 w-full' {...field}>
                      <option value='length'>Length</option>
                      <option value='embedding'>Embedding</option>
                    </select>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='srcLang'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Lang Origem</FormLabel>
                  <FormControl>
                    <Input placeholder='en' {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='tgtLang'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Lang Destino</FormLabel>
                  <FormControl>
                    <Input placeholder='pt-BR' {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='minQuality'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Qualidade Mínima</FormLabel>
                  <FormControl>
                    <Input
                      type='number'
                      min={0}
                      max={1}
                      step='0.01'
                      value={field.value ?? ''}
                      onChange={(event) => {
                        const value = event.target.valueAsNumber
                        field.onChange(Number.isNaN(value) ? undefined : value)
                      }}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='seriesId'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Series ID (opcional)</FormLabel>
                  <FormControl>
                    <Input
                      type='number'
                      value={field.value ?? ''}
                      onChange={(event) => field.onChange(event.target.value)}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='bookId'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Book ID (opcional)</FormLabel>
                  <FormControl>
                    <Input
                      type='number'
                      value={field.value ?? ''}
                      onChange={(event) => field.onChange(event.target.value)}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='sourceTag'
              render={({ field }) => (
                <FormItem className='md:col-span-2'>
                  <FormLabel>Source Tag (opcional)</FormLabel>
                  <FormControl>
                    <Input
                      placeholder='fan-translation-v1'
                      value={field.value ?? ''}
                      onChange={(event) => field.onChange(event.target.value)}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className='md:col-span-2'>
              <Button type='submit' disabled={isSubmitting}>
                {isSubmitting ? 'Processando…' : 'Processar'}
              </Button>
            </div>
          </form>
        </Form>
      </CardContent>
    </Card>
  )
}
