import { toast as sonnerToast, type ExternalToast } from 'sonner'

type ToastVariant = 'default' | 'destructive'

interface ToastOptions {
  title?: string
  description?: string
  variant?: ToastVariant
  duration?: number
}

const variantClassName: Record<ToastVariant, string | undefined> = {
  default: undefined,
  destructive: 'bg-destructive text-destructive-foreground',
}

export function useToast() {
  function toast({ title, description, variant = 'default', duration }: ToastOptions) {
    const message = title ?? description ?? ''
    const options: ExternalToast = {
      description: title && description ? description : undefined,
      className: variantClassName[variant],
      duration,
    }

    return sonnerToast(message, options)
  }

  return { toast }
}
