import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Textarea } from '@/components/ui/textarea'
import { useToast } from '@/components/ui/use-toast'
import { backendApi } from '@/services/api'

const RowSchema = z.object({
  src: z.string().min(1),
  tgt: z.string().min(1),
  note: z.string().optional(),
  approved: z.boolean().optional(),
  priority: z.coerce.number().int().optional(),
})

const BulkSchema = z.object({
  payload: z.string().min(3, 'Cole pelo menos 1 linha'),
  format: z.enum(['json', 'csv']),
})

type BulkInput = z.infer<typeof BulkSchema>

const defaultPayload = `[
 {"src":"dragon rider","tgt":"cavaleiro de dragão","priority":10},
 {"src":"outpost","tgt":"guarnição"}
]`

export default function GlossaryBulk() {
  const form = useForm<BulkInput>({
    resolver: zodResolver(BulkSchema),
    defaultValues: { format: 'json', payload: defaultPayload },
  })
  const { toast } = useToast()

  async function onSubmit(values: BulkInput) {
    try {
      let items: unknown

      if (values.format === 'json') {
        items = JSON.parse(values.payload)
      } else {
        items = values.payload
          .split(/\n+/)
          .filter(Boolean)
          .map((line) => {
            const [src, tgt, note, approved, priority] = line.split(',')
            return {
              src,
              tgt,
              note,
              approved: approved ? approved === 'true' : undefined,
              priority: priority ? Number(priority) : undefined,
            }
          })
      }

      const parsed = z.array(RowSchema).parse(items)
      const response = await backendApi.post('/glossary/bulk', parsed)

      toast({
        title: 'Glossário atualizado',
        description: `Itens afetados: ${response.data?.affected ?? parsed.length}`,
      })
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : 'Falha ao enviar dados.'
      toast({ variant: 'destructive', title: 'Erro ao enviar', description: message })
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Glossário — Upsert em Lote</CardTitle>
      </CardHeader>
      <CardContent>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className='space-y-4'>
            <FormField
              name='format'
              control={form.control}
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Formato</FormLabel>
                  <FormControl>
                    <select className='border rounded-md h-9 px-2' {...field}>
                      <option value='json'>JSON</option>
                      <option value='csv'>CSV (src,tgt,note,approved,priority)</option>
                    </select>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              name='payload'
              control={form.control}
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Conteúdo</FormLabel>
                  <FormControl>
                    <Textarea rows={10} placeholder='Cole aqui...' {...field} />
                  </FormControl>
                  <FormDescription>
                    Envie JSON array ou CSV simples. Prioridade maior vence em conflitos.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Button type='submit'>Enviar</Button>
          </form>
        </Form>
      </CardContent>
    </Card>
  )
}
