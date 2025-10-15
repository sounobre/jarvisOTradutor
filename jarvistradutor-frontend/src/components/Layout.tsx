// --- FILE: src/components/Layout.tsx ---
import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import type { LucideIcon } from 'lucide-react'
import { BookOpen, Book, Boxes, FileUp, FolderPlus, Search, Settings as Cog, Inbox, Shuffle } from 'lucide-react'

import { cn } from '@/lib/utils'

interface NavItemProps {
  to: string
  icon: LucideIcon
  label: string
}

function NavItem({ to, icon: Icon, label }: NavItemProps) {
  const { pathname } = useLocation()
  return (
    <Link
      to={to}
      className={cn(
        'flex items-center gap-2 px-3 py-2 rounded-xl hover:bg-muted transition',
        pathname === to && 'bg-muted font-semibold',
      )}
    >
      <Icon className='w-4 h-4' />
      {label}
    </Link>
  )
}

export function Layout({ children }: { children: ReactNode }) {
  return (
    <div className='min-h-dvh grid grid-cols-1 lg:grid-cols-[260px_1fr]'>
      <aside className='border-r p-4 space-y-3'>
        <div className='text-xl font-bold flex items-center gap-2'>
          <BookOpen /> JarvisTradutor
        </div>
        <nav className='flex flex-col gap-1'>
          <NavItem to='/' icon={BookOpen} label='Dashboard' />
          <NavItem to='/inbox/bookpairs' icon={Inbox} label='Inbox (Pares)' />
          <NavItem to='/inbox/consolidate' icon={Shuffle} label='Consolidação' />
          <NavItem to='/glossary/bulk' icon={Boxes} label='Glossário Bulk' />
          <NavItem to='/tm/import' icon={FileUp} label='TM Import' />
          <NavItem to='/tm/tools' icon={Search} label='TM Lookup/Learn' />
          <NavItem to='/epub/import' icon={Book} label='EPUB Import' />
          <NavItem to='/catalog' icon={FolderPlus} label='Séries & Livros' />
          <NavItem to='/settings' icon={Cog} label='Settings' />
        </nav>
        <div className='text-xs text-muted-foreground pt-4'>
          v0.1.0 — React+TS+Tailwind+Shadcn
        </div>
      </aside>
      <main className='p-4 lg:p-8 bg-background'>{children}</main>
    </div>
  )
}
