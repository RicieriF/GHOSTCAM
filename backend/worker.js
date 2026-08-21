const PLANS = {
  daily: { label: 'DIÁRIO', days: 1, amount: 800 },
  '3days': { label: '3 DIAS', days: 3, amount: 2200 },
  weekly: { label: 'SEMANAL', days: 7, amount: 5000 },
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === 'OPTIONS') return cors(new Response(null, { status: 204 }));

    try {
      if (url.pathname === '/health') return json({ ok: true, service: 'ghostcam' });
      if (url.pathname === '/admin') return new Response(adminHtml(), { headers: { 'content-type': 'text/html; charset=utf-8' } });
      if (url.pathname === '/payment-return') return new Response(paymentReturnHtml(url.searchParams.get('purchase_id') || ''), { headers: { 'content-type': 'text/html; charset=utf-8' } });

      if (url.pathname === '/api/license/activate' && request.method === 'POST') return cors(await activate(request, env));
      if (url.pathname === '/api/purchase/create' && request.method === 'POST') return cors(await createPurchase(request, env));
      if (url.pathname === '/api/purchase/status' && request.method === 'GET') return cors(await purchaseStatus(url, env));
      if (url.pathname === '/api/report' && request.method === 'POST') return cors(await reportCompatibility(request, env));
      if (url.pathname === '/api/square/webhook' && request.method === 'POST') return await squareWebhook(request, env);

      if (url.pathname === '/api/admin/devices' && request.method === 'GET') return cors(await adminDevices(request, env));
      if (url.pathname === '/api/admin/reports' && request.method === 'GET') return cors(await adminReports(request, env));
      if (url.pathname === '/api/admin/license/create' && request.method === 'POST') return cors(await adminCreateLicense(request, env));
      if (url.pathname === '/api/admin/license/reset' && request.method === 'POST') return cors(await adminResetDevice(request, env));
      if (url.pathname === '/api/admin/license/status' && request.method === 'POST') return cors(await adminSetStatus(request, env));
      if (url.pathname === '/api/admin/license/extend' && request.method === 'POST') return cors(await adminExtend(request, env));

      return json({ ok: false, message: 'Not found' }, 404);
    } catch (error) {
      console.error(error);
      return cors(json({ ok: false, message: 'Internal error' }, 500));
    }
  }
};

async function activate(request, env) {
  const body = await request.json();
  const key = String(body.license_key || '').trim().toUpperCase();
  const deviceId = String(body.device_id || '').trim();
  if (!key || !deviceId) return json({ ok: false, message: 'Chave e dispositivo são obrigatórios.' }, 400);

  const license = await env.DB.prepare('SELECT * FROM licenses WHERE license_key = ?').bind(key).first();
  if (!license) return json({ ok: false, message: 'Chave inválida.' }, 404);
  if (license.status !== 'ACTIVE') return json({ ok: false, message: `Licença ${license.status.toLowerCase()}.` }, 403);

  const now = Date.now();
  const expiry = Date.parse(license.expires_at);
  if (!Number.isFinite(expiry) || expiry <= now) return json({ ok: false, message: 'Licença expirada.' }, 403);
  if (license.device_id && license.device_id !== deviceId) {
    return json({ ok: false, message: 'Esta licença já está vinculada a outro dispositivo.' }, 409);
  }

  const activatedAt = license.activated_at || new Date().toISOString();
  await env.DB.prepare('UPDATE licenses SET device_id = ?, activated_at = ?, last_check = ? WHERE id = ?')
    .bind(deviceId, activatedAt, new Date().toISOString(), license.id).run();

  return json({
    ok: true,
    message: 'Licença ativa.',
    license_key: key,
    plan: license.plan,
    expires_at: expiry,
    device_id: deviceId,
  });
}

async function createPurchase(request, env) {
  requireSquare(env);
  const body = await request.json();
  const deviceId = String(body.device_id || '').trim();
  const planId = String(body.plan || '').trim();
  const plan = PLANS[planId];
  if (!deviceId || !plan) return json({ ok: false, message: 'Plano ou dispositivo inválido.' }, 400);

  const purchaseId = crypto.randomUUID();
  const redirect = `${env.PUBLIC_BASE_URL.replace(/\/$/, '')}/payment-return?purchase_id=${encodeURIComponent(purchaseId)}`;
  const payload = {
    idempotency_key: crypto.randomUUID(),
    description: `GHOSTCAM ${plan.label} - ${deviceId}`,
    quick_pay: {
      name: `GHOSTCAM ${plan.label}`,
      price_money: { amount: plan.amount, currency: 'USD' },
      location_id: env.SQUARE_LOCATION_ID,
    },
    checkout_options: { redirect_url: redirect },
  };

  const square = await fetch('https://connect.squareup.com/v2/online-checkout/payment-links', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${env.SQUARE_ACCESS_TOKEN}`,
      'Square-Version': env.SQUARE_VERSION || '2026-07-15',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await square.json();
  if (!square.ok || !data.payment_link?.url || !data.payment_link?.order_id) {
    console.error('Square create payment link failed', data);
    return json({ ok: false, message: 'Square não conseguiu criar o pagamento.' }, 502);
  }

  await env.DB.prepare(`INSERT INTO purchases
      (id, device_id, plan, amount_cents, status, square_order_id, square_payment_link_id, created_at)
      VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)`)
    .bind(purchaseId, deviceId, planId, plan.amount, data.payment_link.order_id, data.payment_link.id || null, new Date().toISOString()).run();

  return json({ ok: true, purchase_id: purchaseId, checkout_url: data.payment_link.url });
}

async function purchaseStatus(url, env) {
  const id = String(url.searchParams.get('purchase_id') || '');
  if (!id) return json({ ok: false, message: 'purchase_id ausente.' }, 400);
  const purchase = await env.DB.prepare('SELECT id, status, plan, license_key FROM purchases WHERE id = ?').bind(id).first();
  if (!purchase) return json({ ok: false, message: 'Compra não encontrada.' }, 404);
  return json({ ok: true, status: purchase.status, plan: purchase.plan, license_key: purchase.license_key || '' });
}

async function squareWebhook(request, env) {
  const raw = await request.text();
  const signature = request.headers.get('x-square-hmacsha256-signature') || '';
  if (env.SQUARE_WEBHOOK_SIGNATURE_KEY) {
    const valid = await verifySquareSignature(raw, signature, env.SQUARE_WEBHOOK_SIGNATURE_KEY, env.SQUARE_WEBHOOK_URL);
    if (!valid) return new Response('invalid signature', { status: 401 });
  }

  const event = JSON.parse(raw || '{}');
  if (event.type !== 'payment.updated') return new Response('ok');
  const payment = event.data?.object?.payment;
  if (!payment || payment.status !== 'COMPLETED' || !payment.order_id) return new Response('ok');

  const purchase = await env.DB.prepare('SELECT * FROM purchases WHERE square_order_id = ?').bind(payment.order_id).first();
  if (!purchase || purchase.status === 'PAID') return new Response('ok');
  const plan = PLANS[purchase.plan];
  if (!plan) return new Response('ok');

  const now = new Date();
  const expires = new Date(now.getTime() + plan.days * 24 * 60 * 60 * 1000);
  const licenseKey = makeLicenseKey();
  const licenseId = crypto.randomUUID();

  await env.DB.batch([
    env.DB.prepare(`INSERT INTO licenses
      (id, license_key, plan, status, created_at, activated_at, expires_at, max_devices, device_id, last_check, notes)
      VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, 1, ?, ?, ?)`)
      .bind(licenseId, licenseKey, plan.label, now.toISOString(), now.toISOString(), expires.toISOString(), purchase.device_id, now.toISOString(), `Square purchase ${purchase.id}`),
    env.DB.prepare(`UPDATE purchases SET status='PAID', square_payment_id=?, license_key=?, paid_at=? WHERE id=?`)
      .bind(payment.id || null, licenseKey, now.toISOString(), purchase.id),
  ]);

  return new Response('ok');
}

async function reportCompatibility(request, env) {
  const body = await request.json();
  const deviceId = String(body.device_id || '').trim();
  const targetPackage = String(body.target_package || '').trim().slice(0, 200);
  const note = String(body.note || '').trim().slice(0, 2000);
  const appVersion = String(body.app_version || '').trim().slice(0, 100);
  if (!deviceId || !targetPackage) return json({ ok: false, message: 'Dispositivo e app são obrigatórios.' }, 400);
  await env.DB.prepare(`INSERT INTO compatibility_reports (id, device_id, target_package, note, app_version, created_at)
    VALUES (?, ?, ?, ?, ?, ?)`)
    .bind(crypto.randomUUID(), deviceId, targetPackage, note, appVersion, new Date().toISOString()).run();
  return json({ ok: true });
}

async function adminDevices(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, message: 'Unauthorized' }, 401);
  const { results } = await env.DB.prepare(`SELECT id, license_key, device_id, plan, status, created_at, activated_at, expires_at, last_check, customer_name, customer_contact
    FROM licenses ORDER BY created_at DESC LIMIT 500`).all();
  const now = Date.now();
  const rows = results.map(r => ({
    ...r,
    days_remaining: Math.max(0, Math.ceil((Date.parse(r.expires_at) - now) / 86400000)),
  }));
  return json({ ok: true, rows });
}

async function adminReports(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, message: 'Unauthorized' }, 401);
  const { results } = await env.DB.prepare(`SELECT target_package, COUNT(*) AS reports, MAX(created_at) AS last_report
    FROM compatibility_reports GROUP BY target_package ORDER BY reports DESC, last_report DESC LIMIT 200`).all();
  return json({ ok: true, rows: results });
}

async function adminCreateLicense(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, message: 'Unauthorized' }, 401);
  const body = await request.json();
  const planId = String(body.plan || 'daily');
  const plan = PLANS[planId];
  if (!plan) return json({ ok: false, message: 'Plano inválido.' }, 400);
  const now = new Date();
  const expires = new Date(now.getTime() + plan.days * 86400000);
  const key = makeLicenseKey();
  await env.DB.prepare(`INSERT INTO licenses
    (id, license_key, customer_name, customer_contact, plan, status, created_at, expires_at, max_devices, notes)
    VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 1, ?)`)
    .bind(crypto.randomUUID(), key, String(body.customer_name || '').slice(0, 200), String(body.customer_contact || '').slice(0, 200), plan.label, now.toISOString(), expires.toISOString(), String(body.notes || '').slice(0, 1000)).run();
  return json({ ok: true, license_key: key });
}

async function adminResetDevice(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, message: 'Unauthorized' }, 401);
  const body = await request.json();
  await env.DB.prepare('UPDATE licenses SET device_id=NULL, activated_at=NULL, last_check=NULL WHERE id=?').bind(String(body.id || '')).run();
  return json({ ok: true });
}

async function adminSetStatus(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, message: 'Unauthorized' }, 401);
  const body = await request.json();
  const status = String(body.status || '').toUpperCase();
  if (!['ACTIVE', 'SUSPENDED', 'REVOKED'].includes(status)) return json({ ok: false, message: 'Status inválido.' }, 400);
  await env.DB.prepare('UPDATE licenses SET status=? WHERE id=?').bind(status, String(body.id || '')).run();
  return json({ ok: true });
}

async function adminExtend(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, message: 'Unauthorized' }, 401);
  const body = await request.json();
  const days = Number(body.days || 0);
  if (![1, 3, 7].includes(days)) return json({ ok: false, message: 'Use 1, 3 ou 7 dias.' }, 400);
  const license = await env.DB.prepare('SELECT expires_at FROM licenses WHERE id=?').bind(String(body.id || '')).first();
  if (!license) return json({ ok: false, message: 'Licença não encontrada.' }, 404);
  const base = Math.max(Date.now(), Date.parse(license.expires_at));
  const expires = new Date(base + days * 86400000).toISOString();
  await env.DB.prepare("UPDATE licenses SET expires_at=?, status='ACTIVE' WHERE id=?").bind(expires, String(body.id || '')).run();
  return json({ ok: true, expires_at: expires });
}

function isAdmin(request, env) {
  const auth = request.headers.get('authorization') || '';
  return Boolean(env.ADMIN_TOKEN) && auth === `Bearer ${env.ADMIN_TOKEN}`;
}

function requireSquare(env) {
  if (!env.SQUARE_ACCESS_TOKEN || !env.SQUARE_LOCATION_ID || !env.PUBLIC_BASE_URL) {
    throw new Error('Square/backend environment not configured');
  }
}

function makeLicenseKey() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const segment = () => Array.from({ length: 4 }, () => alphabet[Math.floor(Math.random() * alphabet.length)]).join('');
  return `GHOST-${segment()}-${segment()}-${segment()}`;
}

async function verifySquareSignature(body, signature, key, notificationUrl) {
  if (!signature || !key || !notificationUrl) return false;
  const cryptoKey = await crypto.subtle.importKey('raw', new TextEncoder().encode(key), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signed = await crypto.subtle.sign('HMAC', cryptoKey, new TextEncoder().encode(notificationUrl + body));
  const actual = btoa(String.fromCharCode(...new Uint8Array(signed)));
  return timingSafeEqual(actual, signature);
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function cors(response) {
  const headers = new Headers(response.headers);
  headers.set('Access-Control-Allow-Origin', '*');
  headers.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  headers.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  return new Response(response.body, { status: response.status, headers });
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json; charset=utf-8' } });
}

function paymentReturnHtml(purchaseId) {
  const safeId = purchaseId.replace(/[^a-zA-Z0-9-]/g, '');
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>GHOSTCAM</title><style>${baseCss()}</style></head><body><main class="card"><h1>GHOSTCAM</h1><p id="status">Confirmando pagamento…</p><div id="key" class="key"></div><p class="muted">Quando a chave aparecer, copie e cole no aplicativo.</p></main><script>
  const id=${JSON.stringify(safeId)};
  async function poll(){try{const r=await fetch('/api/purchase/status?purchase_id='+encodeURIComponent(id));const j=await r.json();if(j.status==='PAID'&&j.license_key){document.getElementById('status').textContent='Pagamento confirmado.';document.getElementById('key').textContent=j.license_key;return;}document.getElementById('status').textContent='Pagamento recebido. Aguardando confirmação…';}catch(e){document.getElementById('status').textContent='Aguardando confirmação…';}setTimeout(poll,2000)}poll();
</script></body></html>`;
}

function adminHtml() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>GHOSTCAM Admin</title><style>${baseCss()} table{width:100%;border-collapse:collapse}th,td{padding:10px;border-bottom:1px solid #292929;text-align:left;font-size:13px}button{background:#ff2028;color:white;border:0;border-radius:8px;padding:8px 10px;margin:2px;cursor:pointer}input,select{background:#151515;color:#fff;border:1px solid #333;border-radius:8px;padding:10px}.toolbar{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}.pill{padding:4px 8px;border-radius:99px;background:#222}.scroll{overflow:auto}</style></head><body><main class="wide"><h1>GHOSTCAM Admin</h1><div class="toolbar"><input id="token" type="password" placeholder="Admin token"><button onclick="load()">Entrar / Atualizar</button><select id="plan"><option value="daily">Diário</option><option value="3days">3 dias</option><option value="weekly">Semanal</option></select><button onclick="createLicense()">Gerar licença</button></div><p id="msg" class="muted"></p><div class="scroll"><table><thead><tr><th>Dispositivo</th><th>Licença</th><th>Plano</th><th>Dias</th><th>Status</th><th>Último check</th><th>Ações</th></tr></thead><tbody id="rows"></tbody></table></div><h2>Compatibilidade</h2><div class="scroll"><table><thead><tr><th>App/pacote</th><th>Relatórios</th><th>Último</th></tr></thead><tbody id="reports"></tbody></table></div></main><script>
  const token=()=>document.getElementById('token').value;const headers=()=>({'Authorization':'Bearer '+token(),'Content-Type':'application/json'});const esc=s=>String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#039;'}[c]));
  async function api(path,opts={}){opts.headers={...(opts.headers||{}),...headers()};const r=await fetch(path,opts);const j=await r.json();if(!r.ok)throw new Error(j.message||'Erro');return j}
  async function load(){try{const j=await api('/api/admin/devices');document.getElementById('rows').innerHTML=j.rows.map(r=>`<tr><td>${esc(r.device_id||'—')}</td><td>${esc(r.license_key)}</td><td>${esc(r.plan)}</td><td>${r.days_remaining}</td><td><span class="pill">${esc(r.status)}</span></td><td>${esc(r.last_check||'—')}</td><td><button onclick="act('reset','${r.id}')">Reset</button><button onclick="extend('${r.id}',1)">+1d</button><button onclick="extend('${r.id}',3)">+3d</button><button onclick="extend('${r.id}',7)">+7d</button><button onclick="statusSet('${r.id}','SUSPENDED')">Suspender</button><button onclick="statusSet('${r.id}','ACTIVE')">Ativar</button></td></tr>`).join('');const rr=await api('/api/admin/reports');document.getElementById('reports').innerHTML=rr.rows.map(r=>`<tr><td>${esc(r.target_package)}</td><td>${r.reports}</td><td>${esc(r.last_report)}</td></tr>`).join('');document.getElementById('msg').textContent='Atualizado.';}catch(e){document.getElementById('msg').textContent=e.message}}
  async function act(kind,id){await api('/api/admin/license/'+kind,{method:'POST',body:JSON.stringify({id})});load()}
  async function extend(id,days){await api('/api/admin/license/extend',{method:'POST',body:JSON.stringify({id,days})});load()}
  async function statusSet(id,status){await api('/api/admin/license/status',{method:'POST',body:JSON.stringify({id,status})});load()}
  async function createLicense(){try{const j=await api('/api/admin/license/create',{method:'POST',body:JSON.stringify({plan:document.getElementById('plan').value})});document.getElementById('msg').textContent='Nova licença: '+j.license_key;load()}catch(e){document.getElementById('msg').textContent=e.message}}
</script></body></html>`;
}

function baseCss() {
  return `*{box-sizing:border-box}body{margin:0;background:#090909;color:#fff;font-family:Inter,system-ui,sans-serif}main.card{max-width:520px;margin:8vh auto;background:#151515;padding:28px;border-radius:18px;border:1px solid #292929}main.wide{max-width:1200px;margin:30px auto;padding:20px}h1{letter-spacing:.06em}h2{margin-top:34px}.key{font-size:24px;font-weight:800;color:#ff2028;letter-spacing:.08em;margin:18px 0;word-break:break-all}.muted{color:#aaa}`;
}
