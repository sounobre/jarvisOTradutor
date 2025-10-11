import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { useToast } from '@/components/ui/use-toast'
import { backendApi } from '@/services/api'

const LookupSchema = z.object({ src: z.string().min(1) })

const LearnSchema = z.object({
  src: z.string().min(1),
  tgt: z.string().min(1),
  quality: z.number().min(0).max(1),
})

type LookupInput = z.infer<typeof LookupSchema>
type LearnInput = z.infer<typeof LearnSchema>

export default function TMSearchLearn() {
  const lookupForm = useForm<LookupInput>({ resolver: zodResolver(LookupSchema) })
  const learnForm = useForm<LearnInput>({
    resolver: zodResolver(LearnSchema),
    defaultValues: { quality: 0.95 },
  })
  const { toast } = useToast()

  async function onLookup(values: LookupInput) {
    try {
      const response = await backendApi.get('/tm/lookup', { params: { src: values.src } })
      toast({ title: 'Resultado', description: response.data?.tgt ?? 'Sem resultado' })
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : 'Busca falhou.'
      toast({ variant: 'destructive', title: 'Erro', description: message })
    }
  }

  async function onLearn(values: LearnInput) {
    try {
      const params = new URLSearchParams({
        src: values.src,
        tgt: values.tgt,
        quality: String(values.quality),
      })
      await backendApi.post('/tm/learn', params)
      toast({ title: 'Aprendido', description: 'Par adicionado à TM' })
      learnForm.reset({ ...values, tgt: '', src: '' })
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : 'Não foi possível aprender o par.'
      toast({ variant: 'destructive', title: 'Erro', description: message })
    }
  }

  return (
    <div className='grid md:grid-cols-2 gap-6'>
      <Card>
        <CardHeader>
          <CardTitle>TM Lookup</CardTitle>
        </CardHeader>
        <CardContent>
          <Form {...lookupForm}>
            <form onSubmit={lookupForm.handleSubmit(onLookup)} className='space-y-4'>
              <FormField
                control={lookupForm.control}
                name='src'
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Texto origem</FormLabel>
                    <FormControl>
                      <input
                        className='border rounded-md h-9 px-2 w-full'
                        placeholder='The dragon...'
                        {...field}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <Button type='submit'>Buscar</Button>
            </form>
          </Form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>TM Learn (Online)</CardTitle>
        </CardHeader>
        <CardContent>
          <Form {...learnForm}>
            <form onSubmit={learnForm.handleSubmit(onLearn)} className='space-y-4'>
              <FormField
                control={learnForm.control}
                name='src'
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Fonte (src)</FormLabel>
                    <FormControl>
                      <input className='border rounded-md h-9 px-2 w-full' {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={learnForm.control}
                name='tgt'
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Destino (tgt)</FormLabel>
                    <FormControl>
                      <input className='border rounded-md h-9 px-2 w-full' {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={learnForm.control}
                name='quality'
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Qualidade [0..1]</FormLabel>
                    <FormControl>
                      <input
                        type='number'
                        step='0.01'
                        min={0}
                        max={1}
                        className='border rounded-md h-9 px-2 w-full'
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

              <Button type='submit'>Aprender</Button>
            </form>
          </Form>
        </CardContent>
      </Card>
    </div>
  )
}
