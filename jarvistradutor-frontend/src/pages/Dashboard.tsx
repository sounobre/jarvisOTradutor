// --- FILE: src/pages/Dashboard.tsx ---
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'


export default function Dashboard(){
return (
<div className='space-y-6'>
<div>
<h1 className='text-2xl font-bold'>Bem-vindo ao JarvisTradutor</h1>
<p className='text-muted-foreground'>Admin UI para TM, Glossário, Série/Livros e importações EPUB.</p>
</div>
<div className='grid md:grid-cols-2 xl:grid-cols-3 gap-4'>
<Card>
<CardHeader><CardTitle>Fluxo Rápido</CardTitle></CardHeader>
<CardContent className='space-y-2 text-sm'>
<ol className='list-decimal pl-5'>
<li>Cadastre Série/Livro em <b>Séries & Livros</b>.</li>
<li>Faça <b>Glossário Bulk</b> (opcional).</li>
<li>Importe <b>TM</b> (CSV/TSV) ou <b>EPUB</b> alinhado.</li>
<li>Use <b>TM Lookup/Learn</b> para testar.</li>
</ol>
</CardContent>
</Card>
<Card>
<CardHeader><CardTitle>Status dos Serviços</CardTitle></CardHeader>
<CardContent>
<ServiceHealth />
</CardContent>
</Card>
<Card>
<CardHeader><CardTitle>Dicas</CardTitle></CardHeader>
<CardContent className='text-sm space-y-2'>
<p>Envie TSV com <code>TAB</code> (delimiter=%09) para maior confiabilidade.</p>
<Separator/>
<p>Para embeddings, configure <code>VITE_EMBED_URL</code> e o backend chamará /embed.</p>
</CardContent>
</Card>
</div>
</div>
)
}


function ServiceHealth(){
// Simplificado: apenas mostra URLs configuradas
const backend = import.meta.env.VITE_BACKEND_URL
const embed = import.meta.env.VITE_EMBED_URL
return (
<div className='space-y-2 text-sm'>
<div><b>Backend:</b> {backend || 'não configurado'}</div>
<div><b>Embeddings API:</b> {embed || 'não configurado'}</div>
<div className='text-muted-foreground'>* Health real pode ser adicionado depois com SWR/queries.</div>
</div>
)
}