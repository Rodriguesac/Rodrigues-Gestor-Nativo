import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import './styles.css'

const isNative = Boolean(globalThis.AndroidGestor)
if (!isNative && 'serviceWorker' in navigator && /^https?:$/.test(location.protocol)) {
  addEventListener('load', () => navigator.serviceWorker.register('./sw.js').catch(() => {}))
}

createRoot(document.getElementById('root')).render(
  <React.StrictMode><App /></React.StrictMode>
)
