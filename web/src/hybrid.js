const bridge=()=>globalThis.AndroidGestor;
let audioContext,timer,stopTimer;
const readWeb=()=>{try{return JSON.parse(localStorage.getItem('gestor-alert-settings'))||{enabled:true,vibration:true,volume:100}}catch{return {enabled:true,vibration:true,volume:100}}};
function stop(){clearInterval(timer);clearTimeout(stopTimer);timer=null;try{bridge()?.stopRing?.()}catch{}}
function beep(){const settings=readWeb();if(!settings.enabled)return;audioContext||=new(window.AudioContext||window.webkitAudioContext)();audioContext.resume();const oscillator=audioContext.createOscillator(),gain=audioContext.createGain();oscillator.type='sine';oscillator.frequency.value=880;gain.gain.value=(settings.volume??100)/500;oscillator.connect(gain);gain.connect(audioContext.destination);oscillator.start();oscillator.stop(audioContext.currentTime+.3);if(settings.vibration)navigator.vibrate?.([200,100,200])}
export const hybrid={
 isNative:()=>Boolean(bridge()?.isNative?.()),
 getNativePin:()=>{try{return String(bridge()?.getPin?.()||'')}catch{return ''}},
 savePin:pin=>{try{bridge()?.savePin?.(pin)}catch{}},
 setSession:token=>{try{bridge()?.setSession?.(token)}catch{}},
 vibrate:(ms=80)=>{try{if(bridge()?.vibrate)bridge().vibrate(ms);else navigator.vibrate?.(ms)}catch{}},
 ring:o=>{try{bridge()?.ring?.(String(o.id||''),String(o.number||''),String(o.clientName||''))}catch{}},
 stopRing:stop,
 testWebRing:()=>{stop();try{beep();timer=setInterval(beep,1500);stopTimer=setTimeout(stop,15000)}catch{}},
 webNewOrder:o=>{stop();try{beep();timer=setInterval(beep,15000);stopTimer=setTimeout(stop,300000);if('Notification' in window&&Notification.permission==='granted')new Notification('Novo pedido #'+o.number,{body:o.clientName,tag:o.id})}catch{}},
 preferences:()=>{try{if(bridge()?.getPreferences)return JSON.parse(bridge().getPreferences())}catch{}return readWeb()},
 setPreferences:patch=>{try{if(bridge()?.setPreferences)bridge().setPreferences(JSON.stringify(patch));localStorage.setItem('gestor-alert-settings',JSON.stringify({...readWeb(),...patch}))}catch{}},
 chooseSound:()=>{try{bridge()?.chooseSound?.()}catch{}},
 keepAwake:enabled=>{try{bridge()?.keepAwake?.(Boolean(enabled))}catch{}},
 printHtml:html=>{try{if(bridge()?.printHtml){bridge().printHtml(String(html));return true}}catch{}const w=window.open('','_blank');if(w){w.document.write(html);w.document.close();w.onload=()=>w.print();setTimeout(()=>w.print(),400);return true}return false},
 notificationSettings:()=>{try{if(bridge()?.openNotificationSettings)bridge().openNotificationSettings();else if('Notification' in window)Notification.requestPermission()}catch{}},
 exit:()=>{try{bridge()?.exit?.()}catch{}}
};
