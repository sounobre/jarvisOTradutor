import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { useToast } from '@/components/ui/use-toast'
import { consolidateApproved } from '@/services/api-inbox'

export default function InboxConsolidate() {
  const { toast } = useToast()
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<{ tmUpserts: number; occInserted: number; embUpserts: number } | null>(null)

  async function onConsolidate() {
    setLoading(true)
    setResult(null)
    try {
      const res = await consolidateApproved()
      setResult({ tmUpserts: res.tmUpserts, occInserted: res.occInserted, embUpserts: res.embUpserts })
      toast({ title: 'Consolidação concluída' })
    } catch (e) {
      toast({ variant: 'destructive', title: 'Falha ao consolidar', description: e instanceof Error ? e.message : undefined })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className='space-y-4'>
      <h1 className='text-xl font-semibold'>Consolidação de Aprovados</h1>

      <Card>
        <CardHeader>
          <CardTitle>Enviar itens approved para TM/Occurrences/Embeddings</CardTitle>
        </CardHeader>
        <CardContent className='space-y-3'>
          <p className='text-sm text-muted-foreground'>Esta ação processa todos os itens com status <b>approved</b> na Inbox.</p>
          <Button onClick={() => void onConsolidate()} disabled={loading}>{loading ? 'Consolidando…' : 'Consolidar agora'}</Button>
          {result && (
            <div className='rounded-md border p-3 text-sm'>
              <div><b>TM upserts:</b> {result.tmUpserts}</div>
              <div><b>Occurrences inseridas:</b> {result.occInserted}</div>
              <div><b>Embeddings upserts:</b> {result.embUpserts}</div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

