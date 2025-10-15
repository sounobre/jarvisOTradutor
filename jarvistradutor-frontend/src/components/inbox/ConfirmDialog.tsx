import { useEffect, useRef } from 'react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'

interface ConfirmDialogProps {
  open: boolean
  title: string
  description?: string
  confirmLabel?: string
  cancelLabel?: string
  loading?: boolean
  noteEnabled?: boolean
  note?: string
  onNoteChange?: (v: string) => void
  onConfirm: () => void
  onCancel: () => void
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirmar',
  cancelLabel = 'Cancelar',
  loading,
  noteEnabled,
  note,
  onNoteChange,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const dialogRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (!open) return
      if (e.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onCancel])

  if (!open) return null

  return (
    <div
      role='dialog'
      aria-modal='true'
      aria-labelledby='confirm-title'
      className='fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4'
    >
      <div ref={dialogRef} className='w-full max-w-md rounded-lg bg-background p-4 shadow-md'>
        <h2 id='confirm-title' className='text-lg font-semibold'>{title}</h2>
        {description && <p className='mt-1 text-sm text-muted-foreground'>{description}</p>}
        {noteEnabled && (
          <div className='mt-3'>
            <label className='text-sm font-medium'>Nota (opcional)</label>
            <Textarea
              value={note ?? ''}
              onChange={(e) => onNoteChange?.(e.target.value)}
              placeholder='Motivo ou observações'
              className='mt-1'
            />
          </div>
        )}
        <div className='mt-4 flex justify-end gap-2'>
          <Button variant='secondary' onClick={onCancel} disabled={!!loading}>{cancelLabel}</Button>
          <Button onClick={onConfirm} disabled={!!loading}>
            {loading ? 'Processando…' : confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  )
}

