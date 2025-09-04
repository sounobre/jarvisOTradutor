const BASE = import.meta.env.VITE_BACKEND_BASE_URL || 'http://localhost:8080'
export async function sendFile(file: File){
const form = new FormData(); form.append('file', file)
const res = await fetch(`${BASE}/api/translate`, { method:'POST', body: form })
if(!res.ok) throw new Error('Falha no upload')
return res.json() as Promise<{status:string, outputName:string}>
}
export function downloadUrl(filename: string){
return `${BASE}/api/translate/download/${filename}`
}