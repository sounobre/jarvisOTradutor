import axios from 'axios'


const backendBase = import.meta.env.VITE_BACKEND_URL || 'http://localhost:8080'
export const backendApi = axios.create({ baseURL: backendBase, withCredentials: false })