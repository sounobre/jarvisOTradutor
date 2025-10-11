// --- FILE: src/pages/Settings.tsx ---
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'


export default function Settings(){
return (
<Card>
<CardHeader><CardTitle>Settings</CardTitle></CardHeader>
<CardContent className='text-sm space-y-2'>
<p>Defina as variáveis <code>VITE_BACKEND_URL</code> e <code>VITE_EMBED_URL</code> em <code>.env.local</code>.</p>
<pre className='bg-muted p-3 rounded-md whitespace-pre-wrap'>{`VITE_BACKEND_URL=http://localhost:8080
VITE_EMBED_URL=http://localhost:8001`}</pre>
<p>shadcn/ui: após <code>shadcn init</code>, adicione os componentes usados (button, input, card, form, textarea, toast...).</p>
</CardContent>
</Card>
)
}