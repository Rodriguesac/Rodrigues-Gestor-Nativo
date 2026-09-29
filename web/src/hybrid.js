const bridge=()=>globalThis.AndroidGestor;
let audioContext,timer,stopTimer;
const readWeb=()=>{try{return JSON.parse(localStorage.getItem('gestor-alert-settings'))||{enabled:true,vibration:true,volume:100,preset:'old_phone'}}catch{return {enabled:true,vibration:true,volume:100,preset:'old_phone'}}};
function stop(){clearInterval(timer);clearTimeout(stopTimer);timer=null;try{bridge()?.stopRing?.()}catch{}}
function tone(freq=880,duration=.28,volume=.2){const settings=readWeb();if(!settings.enabled)return;audioContext||=new(window.AudioContext||window.webkitAudioContext)();audioContext.resume();const o=audioContext.createOscillator(),g=audioContext.createGain();o.type='sine';o.frequency.value=freq;g.gain.value=Math.max(.02,Math.min(.35,(settings.volume??100)/500))*volume/.2;o.connect(g);g.connect(audioContext.destination);o.start();o.stop(audioContext.currentTime+duration)}
function playPreset(id=readWeb().preset||'old_phone'){try{if(id==='old_phone'){tone(440,.32);setTimeout(()=>tone(480,.32),360);setTimeout(()=>tone(440,.32),720)}else if(id==='chime'){tone(660,.22);setTimeout(()=>tone(880,.3),230)}else if(id==='beep_short'){tone(950,.16)}else{tone(780,.25);setTimeout(()=>tone(1040,.25),300);setTimeout(()=>tone(780,.35),600)}if(readWeb().vibration)navigator.vibrate?.([220,120,320])}catch{}}
export const hybrid={
 isNative:()=>Boolean(bridge()?.isNative?.()),
 getNativePin:()=>{try{return String(bridge()?.getPin?.()||'')}catch{return ''}},
 savePin:pin=>{try{bridge()?.savePin?.(String(pin||''))}catch{}},
 setSession:token=>{try{bridge()?.setSession?.(String(token||''))}catch{}},
 vibrate:(ms=80)=>{try{if(bridge()?.vibrate)bridge().vibrate(ms);else navigator.vibrate?.(ms)}catch{}},
 ring:o=>{try{if(bridge()?.ring)bridge().ring(String(o.id||''),String(o.number||''),String(o.clientName||''));else playPreset()}catch{}},
 stopRing:stop,
 testWebRing:()=>{stop();playPreset();timer=setInterval(()=>playPreset(),1700);stopTimer=setTimeout(stop,8000)},
 webNewOrder:o=>{stop();playPreset();timer=setInterval(()=>playPreset(),15000);stopTimer=setTimeout(stop,300000);if('Notification' in window&&Notification.permission==='granted')new Notification('Novo pedido #'+o.number,{body:o.clientName,tag:o.id})},
 preferences:()=>{try{if(bridge()?.getPreferences)return JSON.parse(bridge().getPreferences())}catch{}return readWeb()},
 setPreferences:patch=>{try{if(bridge()?.setPreferences)bridge().setPreferences(JSON.stringify(patch));localStorage.setItem('gestor-alert-settings',JSON.stringify({...readWeb(),...patch}))}catch{}},
 soundPreset:()=>{try{return String(bridge()?.getSoundPreset?.()||readWeb().preset||'old_phone')}catch{return readWeb().preset||'old_phone'}},
 setSoundPreset:id=>{try{const preset=String(id||'old_phone');localStorage.setItem('gestor-alert-settings',JSON.stringify({...readWeb(),preset}));bridge()?.setSoundPreset?.(preset)}catch{}},
 testSoundPreset:id=>{try{const preset=String(id||'old_phone');if(bridge()?.testSoundPreset)bridge().testSoundPreset(preset);else{localStorage.setItem('gestor-alert-settings',JSON.stringify({...readWeb(),preset}));playPreset(preset)}}catch{}},
 chooseSound:()=>{try{bridge()?.chooseSound?.()}catch{}},
 keepAwake:enabled=>{try{bridge()?.keepAwake?.(Boolean(enabled))}catch{}},
 printHtml:html=>{try{if(bridge()?.printHtml){bridge().printHtml(String(html));return true}}catch{}const w=window.open('','_blank');if(w){w.document.write(html);w.document.close();w.onload=()=>w.print();setTimeout(()=>w.print(),400);return true}return false},
 notificationSettings:()=>{try{if(bridge()?.openNotificationSettings)bridge().openNotificationSettings();else if('Notification' in window)Notification.requestPermission()}catch{}},
 syncVoiceOrders:orders=>{try{bridge()?.syncVoiceOrders?.(JSON.stringify(Array.isArray(orders)?orders:[]))}catch{}},
 setVoiceActiveOrder:id=>{try{if(id)bridge()?.setVoiceActiveOrder?.(String(id));else bridge()?.clearVoiceActiveOrder?.()}catch{}},
 startVoice:()=>{try{bridge()?.startVoiceAssistant?.()}catch{}},
 stopVoice:()=>{try{bridge()?.stopVoiceAssistant?.()}catch{}},
 voiceRunning:()=>{try{return Boolean(bridge()?.isVoiceAssistantRunning?.())}catch{return false}},
 exit:()=>{try{bridge()?.exit?.()}catch{}}
};