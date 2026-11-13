SET ROLE service_role;
SELECT public.submit_whatsapp_builder_order(
  '{
    "submission_id":"manual-55555555-5555-4555-8555-555555555555",
    "customer_name":"Cliente de teste simultâneo",
    "phone":"67991112222",
    "fulfillment":"RETIRADA",
    "payment_method":"DINHEIRO",
    "address":{},
    "delivery_fee":0,
    "customer_note":"Teste de idempotência simultânea",
    "items":[{"name":"Açaí 300ml","quantity":1,"unit_price":9.50,
      "modifiers":{"linhasMontagem":["Leite ninho"]},"notes":""}],
    "subtotal":9.50,
    "total":9.50
  }'::jsonb
);
