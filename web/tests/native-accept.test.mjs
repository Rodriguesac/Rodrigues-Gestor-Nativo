import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";

test("aceite por notificação de pedido Supabase passa pelo backend autenticado",()=>{
 const source=readFileSync(new URL("../../app/src/main/java/com/rodrigues/gestor/notifications/OrderActionReceiver.kt",import.meta.url),"utf8");
 const api=readFileSync(new URL("../../app/src/main/java/com/rodrigues/gestor/data/SupabaseOrdersApi.kt",import.meta.url),"utf8");
 assert.match(source,/orderId\.matches\(Regex\("\^\[0-9a-fA-F\]\{8\}/,"UUIDs seguem ramo Supabase");
 assert.match(source,/GestorCredentials\.load\(context\)/);
 assert.match(source,/SupabaseOrdersApi\.updateStatus\(/,"notificação usa endpoint canônico");
 assert.match(source,/status = "CONFIRMADO"/);
 assert.match(api,/fun updateStatus\(/);
 assert.match(api,/setRequestProperty\("x-gestor-pin", pin\)/);
 const remoteCall=source.indexOf("SupabaseOrdersApi.updateStatus(");
 const legacyWrite=source.indexOf('db.collection("pedidos").document(orderId)');
 assert.ok(remoteCall>=0 && legacyWrite>remoteCall,"Firestore só serve para pedidos legados");
});
