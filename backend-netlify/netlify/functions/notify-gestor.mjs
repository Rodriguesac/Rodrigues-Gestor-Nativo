import admin from 'firebase-admin';

const TOPIC = 'gestor-pedidos';
const NEW_STATUSES = new Set(['AGUARDANDO_CONFIRMACAO', 'RECEBIDO', 'PENDENTE', 'NOVO', 'NOVO_PEDIDO']);

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

    const firebase = getAdmin();
    const pedidoSnapshot = await firebase.firestore().collection('pedidos').doc(pedidoId).get();
    if (!pedidoSnapshot.exists) {
      return new Response(JSON.stringify({ ok: false, error: 'pedido_nao_encontrado' }), { status: 404 });
    }

    const pedido = pedidoSnapshot.data() || {};
    const status = String(pedido.status || pedido.statusPedido || pedido.statusLoja || '').toUpperCase();
    if (!NEW_STATUSES.has(status)) {
      return new Response(JSON.stringify({ ok: false, error: 'pedido_nao_aguarda_confirmacao', status }), { status: 409 });
    }

    const numeroPedido = String(pedido.numeroPedido || pedido.codigoPedido || body.numeroPedido || body.number || pedidoId.slice(-6).toUpperCase());
    const clienteNome = String(pedido.cliente?.nome || pedido.clienteNome || pedido.nomeCliente || body.clienteNome || body.clientName || 'Cliente');
    const eventId = String(body.eventId || `novo_${pedidoId}`);
    const data = {
      type: 'NEW_ORDER', eventId,
      pedidoId, orderId: pedidoId,
      numeroPedido, number: numeroPedido,
      clienteNome, clientName: clienteNome,
      body: `Novo pedido #${numeroPedido} • ${clienteNome}`
    };

    const devices = await firebase.firestore().collection('gestor_dispositivos').where('ativo', '==', true).get().catch(() => null);
    const tokens = [...new Set((devices?.docs || []).map(d => String(d.data()?.token || '').trim()).filter(Boolean))].slice(0, 100);
    const direct = await Promise.allSettled(tokens.map(token =>
      firebase.messaging().send({ token, android: { priority: 'high', ttl: 300000 }, data })
    ));
    const directOk = direct.filter(result => result.status === 'fulfilled').length;
    const topicResult = await firebase.messaging().send({
      topic: TOPIC,
      android: { priority: 'high', ttl: 300000 },
      data
    }).catch(() => '');

    if (!topicResult && directOk === 0) throw new Error('FCM falhou em token direto e tópico.');
    return new Response(JSON.stringify({ ok: true, direct: directOk, registered: tokens.length, topic: Boolean(topicResult) }), {
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
