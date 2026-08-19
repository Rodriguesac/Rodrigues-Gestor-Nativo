import admin from 'firebase-admin';

function getAdmin() {
  if (admin.apps.length) return admin;
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (!raw) throw new Error('FIREBASE_SERVICE_ACCOUNT_JSON não configurado no Netlify.');
  const serviceAccount = JSON.parse(raw);
  if (serviceAccount.private_key) serviceAccount.private_key = serviceAccount.private_key.replace(/\\n/g, '\n');
  admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
  return admin;
}

export default async (request) => {
  if (request.method !== 'POST') {
    return new Response(JSON.stringify({ ok: false, error: 'method_not_allowed' }), { status: 405 });
  }
  try {
    const secret = process.env.GESTOR_NOTIFY_SECRET || '';
    if (secret && request.headers.get('x-gestor-secret') !== secret) {
      return new Response(JSON.stringify({ ok: false, error: 'unauthorized' }), { status: 401 });
    }
    const body = await request.json();
    const pedidoId = String(body.pedidoId || body.orderId || '').trim();
    if (!pedidoId) throw new Error('pedidoId obrigatório');
    const numeroPedido = String(body.numeroPedido || body.number || pedidoId.slice(-6).toUpperCase());
    const clienteNome = String(body.clienteNome || body.clientName || 'Cliente');

    const firebase = getAdmin();
    const result = await firebase.messaging().send({
      topic: 'gestor-pedidos',
      android: { priority: 'high' },
      data: {
        type: 'NEW_ORDER',
        pedidoId,
        orderId: pedidoId,
        numeroPedido,
        number: numeroPedido,
        clienteNome,
        clientName: clienteNome
      }
    });
    return new Response(JSON.stringify({ ok: true, messageId: result }), {
      status: 200,
      headers: { 'content-type': 'application/json; charset=utf-8' }
    });
  } catch (error) {
    console.error('[notify-gestor]', error);
    return new Response(JSON.stringify({ ok: false, error: error?.message || String(error) }), {
      status: 500,
      headers: { 'content-type': 'application/json; charset=utf-8' }
    });
  }
};
