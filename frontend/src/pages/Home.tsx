import { useState } from 'react'
import { Button } from '../components/ui/button'
import { sendFile, downloadUrl } from '../services/api'


export default function Home(){
const [file, setFile] = useState<File|null>(null)
const [status, setStatus] = useState<string>('')
const [outName, setOutName] = useState<string>('')


const onUpload = async ()=>{
if(!file) return
setStatus('Enviando e traduzindo…')
try{
const data = await sendFile(file)
setOutName(data.outputName)
setStatus('Pronto! Baixe o arquivo traduzido abaixo.')
}catch(e:any){
setStatus('Falha ao traduzir: ' + (e?.message||'erro'))
}
}


return (
<div className="min-h-screen flex flex-col items-center justify-center gap-6 p-6">
<h1 className="text-2xl font-bold">Tradutor de Livros (EN → PT‑BR)</h1>
<input type="file" accept=".epub,.txt" onChange={e=>setFile(e.target.files?.[0]||null)} />
<Button onClick={onUpload} disabled={!file}>Traduzir</Button>
<p className="text-sm text-gray-600">{status}</p>
{outName && (
<a className="underline" href={downloadUrl(outName)}>Baixar traduzido</a>
)}
<p className="text-xs text-gray-500 max-w-xl text-center">Use apenas obras em domínio público/sem DRM ou conteúdo próprio.</p>
</div>
)
}