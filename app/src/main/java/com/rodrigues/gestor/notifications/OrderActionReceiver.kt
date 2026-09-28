package com.rodrigues.gestor.notifications
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.SupabaseOrdersApi
class OrderActionReceiver: BroadcastReceiver(){
 override fun onReceive(context: Context,intent: Intent){
  val id=intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
  if(intent.action==ACTION_SILENCE){OrderRingService.stop(context);return}
  if(intent.action!=ACTION_ACCEPT||id.isBlank()||id=="gestor-test")return
  GestorCredentials.load(context)
  val pending=goAsync()
  SupabaseOrdersApi.updateStatus(id,"CONFIRMADO",{
   OrderRingService.stop(context);NotificationHelper.cancelOrder(context,id);pending.finish()
  },{error->
   NotificationHelper.showMessage(context,"Pedido não confirmado",error.message?:"Abra o Gestor para conferir.",id);pending.finish()
  })
 }
 companion object {
  const val ACTION_ACCEPT="com.rodrigues.gestor.ACCEPT_ORDER"
  const val ACTION_SILENCE="com.rodrigues.gestor.SILENCE_ORDER"
  const val EXTRA_ORDER_ID="order_id"
 }
}
