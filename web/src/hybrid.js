const bridge = () => globalThis.AndroidGestor

export const hybrid = {
  isNative: () => Boolean(bridge()?.isNative?.()),
  getNativePin: () => {
    try { return String(bridge()?.getPin?.() || '') } catch { return '' }
  },
  vibrate: (ms=80) => {
    try {
      if (bridge()?.vibrate) bridge().vibrate(Number(ms)||80)
      else navigator.vibrate?.(Number(ms)||80)
    } catch {}
  },
  ring: order => {
    try { bridge()?.ring?.(String(order.id||''), String(order.number||''), String(order.clientName||'')) } catch {}
  },
  stopRing: () => { try { bridge()?.stopRing?.() } catch {} },
  keepAwake: enabled => { try { bridge()?.keepAwake?.(Boolean(enabled)) } catch {} },
  printHtml: html => {
    try {
      if (bridge()?.printHtml) { bridge().printHtml(String(html||'')); return true }
    } catch {}
    const w=window.open('','_blank');
    if(w){w.document.write(html);w.document.close();setTimeout(()=>w.print(),250);return true}
    return false
  },
  notificationSettings: () => { try { bridge()?.openNotificationSettings?.() } catch {} },
  syncVoiceOrders: orders => {
    try { bridge()?.syncVoiceOrders?.(JSON.stringify(Array.isArray(orders)?orders:[])) } catch {}
  },
  setVoiceActiveOrder: id => {
    try {
      if(id) bridge()?.setVoiceActiveOrder?.(String(id))
      else bridge()?.clearVoiceActiveOrder?.()
    } catch {}
  },
  startVoice: () => { try { bridge()?.startVoiceAssistant?.() } catch {} },
  stopVoice: () => { try { bridge()?.stopVoiceAssistant?.() } catch {} },
  voiceRunning: () => {
    try { return Boolean(bridge()?.isVoiceAssistantRunning?.()) } catch { return false }
  }
}
