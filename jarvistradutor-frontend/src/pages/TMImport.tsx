import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { useToast } from '@/components/ui/use-toast'
import { backendApi } from '@/services/api'

const Schema = z.object({
  file: z.instanceof(File, { message: 'Selecione um arquivo CSV/TSV' }),
  delimiter: z.string(),
})

type Input = z.infer<typeof Schema>

export default function TMImport() {
  const form = useForm<Input>({ resolver: zodResolver(Schema), defaultValues: { delimiter: '\t' } })
  const { toast } = useToast()

  async function onSubmit(values: Input) {
    try {
      const formData = new FormData()
      formData.append('file', values.file)

      const delimiter = values.delimiter === '\t' ? '\t' : values.delimiter
      const response = await backendApi.post(`/tm/import?delimiter=${encodeURIComponent(delimiter)}`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })

      toast({ title: 'TM importada', description: `Linhas: ${response.data?.rows ?? 'ok'}` })
      form.reset({ delimiter: values.delimiter })
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : 'Não foi possível importar a TM.'
      toast({ variant: 'destructive', title: 'Erro na importação', description: message })
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Importação de TM (CSV/TSV)</CardTitle>
      </CardHeader>
      <CardContent>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className='space-y-4'>
            <FormField
              control={form.control}
              name='file'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Arquivo</FormLabel>
                  <FormControl>
                    <input
                      type='file'
                      accept='.csv,.tsv,.txt'
                      onChange={(event) => field.onChange(event.target.files?.[0])}
                    />
                  </FormControl>
                  <FormDescription>
                    Formato: src\ttgt\tlang_src\tlang_tgt\tquality ...
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='delimiter'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Delimitador</FormLabel>
                  <FormControl>
                    <select className='border rounded-md h-9 px-2' {...field}>
                      <option value='\t'>TAB (TSV)</option>
                      <option value=','>Vírgula (CSV)</option>
                      <option value=';'>Ponto e vírgula</option>
                    </select>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Button type='submit'>Importar</Button>
          </form>
        </Form>
      </CardContent>
    </Card>
  )
}
