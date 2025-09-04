import * as React from 'react'
export function Button({ className = '', ...props }: React.ButtonHTMLAttributes<HTMLButtonElement>) {
return (
<button className={`px-4 py-2 rounded-2xl shadow text-white bg-black hover:opacity-90 ${className}`} {...props} />
)
}