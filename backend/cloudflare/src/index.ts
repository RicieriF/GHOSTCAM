export interface Env {
  DB: D1Database;
  ADMIN_TOKEN: string;
  PUBLIC_APP_URL: string;
  SQUARE_ENVIRONMENT: string;
  SQUARE_LOCATION_ID: string;
  SQUARE_VERSION: string;
  SQUARE_ACCESS_TOKEN: string;
  SQUARE_WEBHOOK_SIGNATURE_KEY: string;
  SQUARE_WEBHOOK_URL: string;
}

type PlanCode = "DAY" | "THREE_DAY" | "WEEK";

type Plan = {
  code: PlanCode;
  label: string;
  amountCents: number;
  durationHours: number;
};

const PLANS: Record<PlanCode, Plan> = {
  DAY: { code: "DAY", label: "DIÁRIO", amountCents: 800, durationHours: 24 },
  THREE_DAY: { code: "THREE_DAY", label: "3 DIAS", amountCents: 2200, durationHours: 72 },
  WEEK: { code: "WEEK", label: "SEMANAL", amountCents: 5000, durationHours: 168 },
};

const jsonHeaders = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

const corsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "content-type, authorization",
  "access-control-allow-methods": "GET, POST, OPTIONS",
};

const nowIso = () => new Date().toISOString();

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...jsonHeaders, ...corsHeaders },
  });
}

function html(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

async function bodyJson<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    throw new HttpError(400, "JSON inválido");
  }
}

class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

function requireAdmin(request: Request, env: Env): void {
  const auth = request.headers.get("authorization") || "";
  if (!env.ADMIN_TOKEN || auth !== `Bearer ${env.ADMIN_TOKEN}`) {
    throw new HttpError(401, "Não autorizado");
  }
}

function planOf(input: unknown): Plan {
  if (typeof input !== "string" || !(input in PLANS)) {
    throw new HttpError(400, "Plano inválido");
  }
  return PLANS[input as PlanCode];
}

function cleanString(input: unknown, max = 120): string | null {
  if (typeof input !== "string") return null;
  const value = input.trim();
  if (!value) return null;
  return value.slice(0, max);
}

function addHours(iso: string, hours: number): string {
  return new Date(new Date(iso).getTime() + hours * 60 * 60 * 1000).toISOString();
}

function remainingSeconds(expiresAt: string | null): number {
  if (!expiresAt) return 0;
  return Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
}

function normalizeLicenseKey(value: unknown): string {
  if (typeof value !== "string") throw new HttpError(400, "Licença ausente");
  const key = value.trim().toUpperCase();
  if (!/^GHOST-[A-Z0-9-]{8,40}$/.test(key)) {
    throw new HttpError(400, "Formato de licença inválido");
  }
  return key;
}

function randomChunk(length = 4): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join("");
}

function makeLicenseKey(): string {
  return `GHOST-${randomChunk()}-${randomChunk()}-${randomChunk()}`;
}

function makeCheckoutId(): string {
  return `CHK-${crypto.randomUUID()}`;
}

async function ensureDevice(env: Env, payload: Record<string, unknown>): Promise<string> {
  const deviceId = cleanString(payload.deviceId, 100);
  if (!deviceId) throw new HttpError(400, "deviceId obrigatório");
  const now = nowIso();
  await env.DB.prepare(
    `INSERT INTO devices(device_id, device_name, model, android_version, app_version, created_at, last_seen_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(device_id) DO UPDATE SET
       device_name=COALESCE(excluded.device_name, devices.device_name),
       model=COALESCE(excluded.model, devices.model),
       android_version=COALESCE(excluded.android_version, devices.android_version),
       app_version=COALESCE(excluded.app_version, devices.app_version),
       last_seen_at=excluded.last_seen_at`,
  )
    .bind(
      deviceId,
      cleanString(payload.deviceName, 80),
      cleanString(payload.model, 100),
      cleanString(payload.androidVersion, 50),
      cleanString(payload.appVersion, 50),
      now,
      now,
    )
    .run();
  return deviceId;
}

async function licensePayload(env: Env, row: any, requestedDeviceId?: string) {
  const now = nowIso();
  let status = row.status as string;
  if (status === "ACTIVE" && row.expires_at && remainingSeconds(row.expires_at) <= 0) {
    status = "EXPIRED";
    await env.DB.prepare("UPDATE licenses SET status='EXPIRED', last_check=? WHERE id=?")
      .bind(now, row.id)
      .run();
  } else {
    await env.DB.prepare("UPDATE licenses SET last_check=? WHERE id=?").bind(now, row.id).run();
  }

  const deviceMatches = !requestedDeviceId || !row.device_id || row.device_id === requestedDeviceId;
  const active = status === "ACTIVE" && remainingSeconds(row.expires_at) > 0 && deviceMatches;

  return {
    active,
    status,
    licenseKey: row.license_key,
    plan: row.plan,
    deviceId: row.device_id,
    deviceMatches,
    activatedAt: row.activated_at,
    expiresAt: row.expires_at,
    remainingSeconds: remainingSeconds(row.expires_at),
    serverTime: now,
  };
}

async function activateLicense(env: Env, licenseKey: string, deviceId: string) {
  const row = await env.DB.prepare("SELECT * FROM licenses WHERE license_key=?").bind(licenseKey).first<any>();
  if (!row) throw new HttpError(404, "Licença não encontrada");
  if (row.status === "SUSPENDED" || row.status === "REVOKED") {
    throw new HttpError(403, `Licença ${String(row.status).toLowerCase()}`);
  }
  if (row.device_id && row.device_id !== deviceId) {
    throw new HttpError(409, "Esta licença já está vinculada a outro dispositivo");
  }

  const plan = planOf(row.plan);
  const now = nowIso();
  if (!row.activated_at) {
    const expiresAt = addHours(now, plan.durationHours);
    await env.DB.prepare(
      "UPDATE licenses SET device_id=?, activated_at=?, expires_at=?, status='ACTIVE', last_check=? WHERE id=?",
    )
      .bind(deviceId, now, expiresAt, now, row.id)
      .run();
  } else if (!row.device_id) {
    await env.DB.prepare("UPDATE licenses SET device_id=?, last_check=? WHERE id=?")
      .bind(deviceId, now, row.id)
      .run();
  }

  const updated = await env.DB.prepare("SELECT * FROM licenses WHERE id=?").bind(row.id).first<any>();
  return licensePayload(env, updated, deviceId);
}

async function createCheckout(env: Env, deviceId: string, plan: Plan): Promise<any> {
  if (!env.SQUARE_ACCESS_TOKEN || !env.SQUARE_LOCATION_ID) {
    throw new HttpError(503, "Square ainda não configurado no servidor");
  }

  const checkoutId = makeCheckoutId();
  const idempotencyKey = crypto.randomUUID();
  const apiBase = env.SQUARE_ENVIRONMENT === "sandbox"
    ? "https://connect.squareupsandbox.com"
    : "https://connect.squareup.com";

  const redirectBase = (env.PUBLIC_APP_URL || "").replace(/\/$/, "");
  const requestBody = {
    idempotency_key: idempotencyKey,
    order: {
      location_id: env.SQUARE_LOCATION_ID,
      reference_id: checkoutId,
      line_items: [
        {
          name: `GHOSTCAM ${plan.label}`,
          quantity: "1",
          base_price_money: { amount: plan.amountCents, currency: "USD" },
        },
      ],
    },
    checkout_options: redirectBase
      ? { redirect_url: `${redirectBase}/payment-complete?checkout=${encodeURIComponent(checkoutId)}` }
      : undefined,
    pre_populated_data: {},
    payment_note: `GHOSTCAM ${plan.label} / ${checkoutId}`,
  };

  const response = await fetch(`${apiBase}/v2/online-checkout/payment-links`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${env.SQUARE_ACCESS_TOKEN}`,
      "content-type": "application/json",
      "square-version": env.SQUARE_VERSION || "2026-07-16",
    },
    body: JSON.stringify(requestBody),
  });
  const data: any = await response.json();
  if (!response.ok || !data.payment_link?.url || !data.payment_link?.order_id) {
    console.error("Square create payment link failed", response.status, JSON.stringify(data));
    throw new HttpError(502, "Não foi possível criar o checkout Square");
  }

  const now = nowIso();
  await env.DB.prepare(
    `INSERT INTO checkouts(checkout_id, device_id, plan, amount_cents, square_order_id, square_payment_link_id, status, created_at)
     VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)`,
  )
    .bind(
      checkoutId,
      deviceId,
      plan.code,
      plan.amountCents,
      data.payment_link.order_id,
      data.payment_link.id || null,
      now,
    )
    .run();

  return {
    checkoutId,
    url: data.payment_link.url,
    plan: plan.code,
    amountCents: plan.amountCents,
  };
}

async function verifySquareSignature(env: Env, requestUrl: string, rawBody: string, signature: string | null) {
  if (!env.SQUARE_WEBHOOK_SIGNATURE_KEY) return false;
  if (!signature) return false;
  const notificationUrl = env.SQUARE_WEBHOOK_URL || requestUrl;
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(env.SQUARE_WEBHOOK_SIGNATURE_KEY),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", key, encoder.encode(notificationUrl + rawBody));
  const expected = btoa(String.fromCharCode(...new Uint8Array(mac)));
  if (expected.length !== signature.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ signature.charCodeAt(i);
  return diff === 0;
}

async function fulfillOrder(env: Env, orderId: string): Promise<void> {
  const checkout = await env.DB.prepare("SELECT * FROM checkouts WHERE square_order_id=?")
    .bind(orderId)
    .first<any>();
  if (!checkout || checkout.status === "PAID") return;

  const plan = planOf(checkout.plan);
  const licenseKey = makeLicenseKey();
  const now = nowIso();
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO licenses(license_key, plan, status, device_id, created_at, source, square_order_id)
       VALUES (?, ?, 'ACTIVE', ?, ?, 'SQUARE', ?)`,
    ).bind(licenseKey, plan.code, checkout.device_id, now, orderId),
    env.DB.prepare(
      "UPDATE checkouts SET status='PAID', license_key=?, paid_at=? WHERE checkout_id=?",
    ).bind(licenseKey, now, checkout.checkout_id),
  ]);
}

async function squareWebhook(request: Request, env: Env): Promise<Response> {
  const raw = await request.text();
  const signature = request.headers.get("x-square-hmacsha256-signature");
  if (!(await verifySquareSignature(env, request.url, raw, signature))) {
    return json({ ok: false, error: "Assinatura Square inválida" }, 401);
  }

  let event: any;
  try {
    event = JSON.parse(raw);
  } catch {
    return json({ ok: false }, 400);
  }

  if (event?.type === "payment.updated" || event?.type === "payment.created") {
    const payment = event?.data?.object?.payment;
    if (payment?.status === "COMPLETED" && payment?.order_id) {
      await fulfillOrder(env, payment.order_id);
    }
  }
  return json({ ok: true });
}

function adminShell(): string {
  return `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>GHOSTCAM Admin</title>
<style>
:root{color-scheme:dark;--bg:#08090b;--card:#111318;--line:#262a31;--text:#f5f7fa;--muted:#9aa1aa;--red:#ff3131;--ok:#36d17c;--warn:#ffc857}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.4 system-ui,-apple-system,Segoe UI,sans-serif}.wrap{max-width:1200px;margin:auto;padding:28px}.top{display:flex;gap:16px;align-items:center;justify-content:space-between;margin-bottom:22px}.brand{font-size:24px;font-weight:800;letter-spacing:.04em}.brand b{color:var(--red)}.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin-bottom:16px}.login{max-width:440px;margin:80px auto}.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}input,select,button{border-radius:9px;border:1px solid var(--line);background:#0b0d10;color:var(--text);padding:10px 12px}input{min-width:190px}button{cursor:pointer;font-weight:700}button.primary{background:var(--red);border-color:var(--red);color:white}button.small{padding:6px 9px;font-size:12px}.muted{color:var(--muted)}.stats{display:grid;grid-template-columns:repeat(4,minmax(130px,1fr));gap:12px;margin-bottom:16px}.stat{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px}.stat strong{font-size:22px;display:block}table{width:100%;border-collapse:collapse;min-width:900px}th,td{text-align:left;padding:11px 9px;border-bottom:1px solid var(--line);vertical-align:middle}th{color:var(--muted);font-size:12px;text-transform:uppercase}.scroll{overflow:auto}.pill{display:inline-block;padding:4px 8px;border-radius:999px;border:1px solid var(--line);font-size:12px}.ACTIVE{color:var(--ok)}.EXPIRED,.SUSPENDED{color:var(--warn)}.REVOKED{color:var(--red)}.hidden{display:none}.notice{padding:10px 0;color:var(--muted)}@media(max-width:700px){.stats{grid-template-columns:1fr 1fr}.wrap{padding:16px}}
</style>
</head>
<body>
<div id="login" class="wrap login"><div class="card"><div class="brand">GHOST<b>CAM</b></div><p class="muted">Dashboard administrativo</p><div class="row"><input id="token" type="password" placeholder="Token administrativo"><button class="primary" onclick="enter()">Entrar</button></div><div id="loginMsg" class="notice"></div></div></div>
<div id="app" class="wrap hidden">
 <div class="top"><div><div class="brand">GHOST<b>CAM</b></div><div class="muted">Licenças e dispositivos</div></div><button onclick="logout()">Sair</button></div>
 <div class="stats"><div class="stat"><span class="muted">Ativas</span><strong id="sActive">0</strong></div><div class="stat"><span class="muted">Expiradas</span><strong id="sExpired">0</strong></div><div class="stat"><span class="muted">Dispositivos</span><strong id="sDevices">0</strong></div><div class="stat"><span class="muted">Relatórios 7d</span><strong id="sReports">0</strong></div></div>
 <div class="card"><div class="row"><select id="newPlan"><option value="DAY">DIÁRIO — US$8</option><option value="THREE_DAY">3 DIAS — US$22</option><option value="WEEK">SEMANAL — US$50</option></select><input id="contact" placeholder="Contato (opcional)"><button class="primary" onclick="generate()">Gerar licença</button><span id="generated" class="muted"></span></div></div>
 <div class="card"><div class="row"><input id="q" placeholder="Buscar licença/dispositivo" oninput="render()"><button onclick="loadAll()">Atualizar</button></div></div>
 <div class="card scroll"><table><thead><tr><th>Dispositivo</th><th>Licença</th><th>Plano</th><th>Dias restantes</th><th>Status</th><th>Último check</th><th>Ações</th></tr></thead><tbody id="rows"></tbody></table></div>
 <div class="card"><strong>Compatibilidade — relatórios recentes</strong><div id="reports" class="notice">Carregando…</div></div>
</div>
<script>
let token=localStorage.getItem('ghostcam_admin')||'';let licenses=[];
function headers(){return {'content-type':'application/json','authorization':'Bearer '+token}}
function esc(v){return String(v??'').replace(/[&<>\"']/g,s=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[s]))}
async function api(path,opt={}){const r=await fetch(path,{...opt,headers:{...headers(),...(opt.headers||{})}});const d=await r.json().catch(()=>({}));if(!r.ok)throw new Error(d.error||('HTTP '+r.status));return d}
async function enter(){token=document.getElementById('token').value.trim();try{await api('/api/admin/summary');localStorage.setItem('ghostcam_admin',token);document.getElementById('login').classList.add('hidden');document.getElementById('app').classList.remove('hidden');loadAll()}catch(e){document.getElementById('loginMsg').textContent=e.message}}
function logout(){localStorage.removeItem('ghostcam_admin');location.reload()}
function planLabel(p){return p==='DAY'?'DIÁRIO':p==='THREE_DAY'?'3 DIAS':'SEMANAL'}
function days(sec){if(!sec)return '0';const d=sec/86400;return d<1?(d*24).toFixed(1)+' h':d.toFixed(1)+' d'}
function render(){const q=document.getElementById('q').value.toLowerCase();const f=licenses.filter(x=>!q||JSON.stringify(x).toLowerCase().includes(q));document.getElementById('rows').innerHTML=f.map(x=>`<tr><td><b>${esc(x.device_id||'—')}</b><br><span class="muted">${esc(x.device_name||x.model||'')}</span></td><td>${esc(x.license_key)}</td><td>${planLabel(x.plan)}</td><td>${days(x.remaining_seconds)}</td><td><span class="pill ${x.status}">${esc(x.status)}</span></td><td>${esc(x.last_check||'—')}</td><td><div class="row"><button class="small" onclick="act(${x.id},'RENEW',1)">+1d</button><button class="small" onclick="act(${x.id},'RENEW',3)">+3d</button><button class="small" onclick="act(${x.id},'RENEW',7)">+7d</button><button class="small" onclick="act(${x.id},'RESET_DEVICE')">Reset</button><button class="small" onclick="act(${x.id},'SUSPEND')">Susp.</button><button class="small" onclick="act(${x.id},'REVOKE')">Revogar</button></div></td></tr>`).join('')||'<tr><td colspan="7" class="muted">Nenhuma licença</td></tr>'}
async function loadAll(){try{const [s,l,r]=await Promise.all([api('/api/admin/summary'),api('/api/admin/licenses'),api('/api/admin/reports')]);document.getElementById('sActive').textContent=s.active;document.getElementById('sExpired').textContent=s.expired;document.getElementById('sDevices').textContent=s.devices;document.getElementById('sReports').textContent=s.reports7d;licenses=l.items;render();document.getElementById('reports').innerHTML=r.items.map(x=>`<div>${esc(x.target_package)} ${esc(x.target_version_name||'')} — ${esc(x.count)} relato(s)</div>`).join('')||'Nenhum relatório recente.'}catch(e){alert(e.message)}}
async function generate(){try{const d=await api('/api/admin/licenses/generate',{method:'POST',body:JSON.stringify({plan:document.getElementById('newPlan').value,customerContact:document.getElementById('contact').value})});document.getElementById('generated').textContent='Criada: '+d.licenseKey;loadAll()}catch(e){alert(e.message)}}
async function act(id,action,days){if((action==='REVOKE'||action==='RESET_DEVICE')&&!confirm('Confirmar '+action+'?'))return;try{await api('/api/admin/licenses/action',{method:'POST',body:JSON.stringify({id,action,days})});loadAll()}catch(e){alert(e.message)}}
if(token){document.getElementById('token').value=token;enter()}
</script></body></html>`;
}

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;

  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });
  if (path === "/health") return json({ ok: true, service: "GHOSTCAM API", time: nowIso() });
  if (path === "/admin" && request.method === "GET") return html(adminShell());
  if (path === "/payment-complete" && request.method === "GET") {
    return html(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><style>body{font-family:system-ui;background:#08090b;color:white;text-align:center;padding:60px}b{color:#ff3131}</style><h1>GHOST<b>CAM</b></h1><p>Pagamento recebido. Volte ao aplicativo e toque em “Verificar pagamento”.</p>`);
  }
  if (path === "/api/square/webhook" && request.method === "POST") return squareWebhook(request, env);

  if (path === "/api/device/register" && request.method === "POST") {
    const payload = await bodyJson<Record<string, unknown>>(request);
    const deviceId = await ensureDevice(env, payload);
    return json({ ok: true, deviceId });
  }

  if (path === "/api/license/activate" && request.method === "POST") {
    const payload = await bodyJson<Record<string, unknown>>(request);
    const deviceId = await ensureDevice(env, payload);
    const licenseKey = normalizeLicenseKey(payload.licenseKey);
    return json(await activateLicense(env, licenseKey, deviceId));
  }

  if (path === "/api/license/status" && request.method === "POST") {
    const payload = await bodyJson<Record<string, unknown>>(request);
    const deviceId = await ensureDevice(env, payload);
    const licenseKey = normalizeLicenseKey(payload.licenseKey);
    const row = await env.DB.prepare("SELECT * FROM licenses WHERE license_key=?").bind(licenseKey).first<any>();
    if (!row) throw new HttpError(404, "Licença não encontrada");
    return json(await licensePayload(env, row, deviceId));
  }

  if (path === "/api/plans" && request.method === "GET") {
    return json({ plans: Object.values(PLANS) });
  }

  if (path === "/api/checkout" && request.method === "POST") {
    const payload = await bodyJson<Record<string, unknown>>(request);
    const deviceId = await ensureDevice(env, payload);
    const plan = planOf(payload.plan);
    return json(await createCheckout(env, deviceId, plan), 201);
  }

  if (path === "/api/checkout/status" && request.method === "GET") {
    const checkoutId = cleanString(url.searchParams.get("id"), 100);
    const deviceId = cleanString(url.searchParams.get("deviceId"), 100);
    if (!checkoutId || !deviceId) throw new HttpError(400, "checkout e deviceId obrigatórios");
    const row = await env.DB.prepare("SELECT * FROM checkouts WHERE checkout_id=? AND device_id=?")
      .bind(checkoutId, deviceId)
      .first<any>();
    if (!row) throw new HttpError(404, "Checkout não encontrado");
    return json({
      checkoutId,
      status: row.status,
      plan: row.plan,
      licenseKey: row.status === "PAID" ? row.license_key : null,
      paidAt: row.paid_at,
    });
  }

  if (path === "/api/compatibility/check" && request.method === "GET") {
    const pkg = cleanString(url.searchParams.get("package"), 180);
    const versionCode = Number(url.searchParams.get("versionCode") || "0");
    if (!pkg) throw new HttpError(400, "package obrigatório");
    const rule = await env.DB.prepare(
      `SELECT * FROM compatibility_rules WHERE target_package=?
       AND (min_version_code IS NULL OR min_version_code<=?)
       AND (max_version_code IS NULL OR max_version_code>=?)
       ORDER BY updated_at DESC LIMIT 1`,
    ).bind(pkg, versionCode, versionCode).first<any>();
    if (!rule) return json({ status: "UNKNOWN", message: "Sem alerta conhecido para esta versão." });
    return json({ status: rule.status, message: rule.message, updatedAt: rule.updated_at });
  }

  if (path === "/api/compatibility/report" && request.method === "POST") {
    const payload = await bodyJson<Record<string, unknown>>(request);
    const deviceId = await ensureDevice(env, payload);
    const targetPackage = cleanString(payload.targetPackage, 180);
    const summary = cleanString(payload.summary, 500);
    if (!targetPackage || !summary) throw new HttpError(400, "Pacote e resumo obrigatórios");
    await env.DB.prepare(
      `INSERT INTO compatibility_reports(device_id,target_package,target_version_name,target_version_code,ghostcam_version,android_version,device_model,summary,created_at)
       VALUES (?,?,?,?,?,?,?,?,?)`,
    ).bind(
      deviceId,
      targetPackage,
      cleanString(payload.targetVersionName, 80),
      cleanString(payload.targetVersionCode, 40),
      cleanString(payload.appVersion, 50),
      cleanString(payload.androidVersion, 50),
      cleanString(payload.model, 100),
      summary,
      nowIso(),
    ).run();
    return json({ ok: true }, 201);
  }

  if (path === "/api/version" && request.method === "GET") {
    const latest = await env.DB.prepare("SELECT * FROM releases ORDER BY id DESC LIMIT 1").first<any>();
    return json({ latest: latest || null });
  }

  if (path.startsWith("/api/admin/")) requireAdmin(request, env);

  if (path === "/api/admin/summary" && request.method === "GET") {
    const active = await env.DB.prepare("SELECT COUNT(*) c FROM licenses WHERE status='ACTIVE' AND expires_at > ?").bind(nowIso()).first<any>();
    const expired = await env.DB.prepare("SELECT COUNT(*) c FROM licenses WHERE status='EXPIRED' OR (expires_at IS NOT NULL AND expires_at <= ?)").bind(nowIso()).first<any>();
    const devices = await env.DB.prepare("SELECT COUNT(*) c FROM devices").first<any>();
    const since = new Date(Date.now() - 7 * 86400000).toISOString();
    const reports = await env.DB.prepare("SELECT COUNT(*) c FROM compatibility_reports WHERE created_at >= ?").bind(since).first<any>();
    return json({ active: active?.c || 0, expired: expired?.c || 0, devices: devices?.c || 0, reports7d: reports?.c || 0 });
  }

  if (path === "/api/admin/licenses" && request.method === "GET") {
    const rows = await env.DB.prepare(
      `SELECT l.*, d.device_name, d.model
       FROM licenses l LEFT JOIN devices d ON d.device_id=l.device_id
       ORDER BY l.id DESC LIMIT 1000`,
    ).all<any>();
    const items = (rows.results || []).map((r: any) => ({ ...r, remaining_seconds: remainingSeconds(r.expires_at) }));
    return json({ items });
  }

  if (path === "/api/admin/licenses/generate" && request.method === "POST") {
    const payload = await bodyJson<Record<string, unknown>>(request);
    const plan = planOf(payload.plan);
    const licenseKey = makeLicenseKey();
    await env.DB.prepare(
      `INSERT INTO licenses(license_key,plan,status,customer_name,customer_contact,created_at,source)
       VALUES (?,?,'ACTIVE',?,?,?,'ADMIN')`,
    ).bind(
      licenseKey,
      plan.code,
      cleanString(payload.customerName, 120),
      cleanString(payload.customerContact, 160),
      nowIso(),
    ).run();
    return json({ licenseKey, plan: plan.code }, 201);
  }

  if (path === "/api/admin/licenses/action" && request.method === "POST") {
    const payload = await bodyJson<Record<string, unknown>>(request);
    const id = Number(payload.id);
    const action = cleanString(payload.action, 40);
    if (!Number.isInteger(id) || id <= 0 || !action) throw new HttpError(400, "Ação inválida");
    const row = await env.DB.prepare("SELECT * FROM licenses WHERE id=?").bind(id).first<any>();
    if (!row) throw new HttpError(404, "Licença não encontrada");

    if (action === "RESET_DEVICE") {
      await env.DB.prepare("UPDATE licenses SET device_id=NULL WHERE id=?").bind(id).run();
    } else if (action === "SUSPEND") {
      await env.DB.prepare("UPDATE licenses SET status='SUSPENDED' WHERE id=?").bind(id).run();
    } else if (action === "REVOKE") {
      await env.DB.prepare("UPDATE licenses SET status='REVOKED' WHERE id=?").bind(id).run();
    } else if (action === "ACTIVATE") {
      await env.DB.prepare("UPDATE licenses SET status='ACTIVE' WHERE id=?").bind(id).run();
    } else if (action === "RENEW") {
      const days = Number(payload.days);
      if (![1, 3, 7].includes(days)) throw new HttpError(400, "Renovação deve ser 1, 3 ou 7 dias");
      const base = row.expires_at && new Date(row.expires_at).getTime() > Date.now() ? row.expires_at : nowIso();
      const expires = addHours(base, days * 24);
      await env.DB.prepare("UPDATE licenses SET expires_at=?, status='ACTIVE' WHERE id=?").bind(expires, id).run();
    } else {
      throw new HttpError(400, "Ação desconhecida");
    }
    return json({ ok: true });
  }

  if (path === "/api/admin/reports" && request.method === "GET") {
    const since = new Date(Date.now() - 30 * 86400000).toISOString();
    const rows = await env.DB.prepare(
      `SELECT target_package, target_version_name, target_version_code, COUNT(*) count, MAX(created_at) latest
       FROM compatibility_reports WHERE created_at >= ?
       GROUP BY target_package, target_version_name, target_version_code
       ORDER BY latest DESC LIMIT 100`,
    ).bind(since).all<any>();
    return json({ items: rows.results || [] });
  }

  if (path === "/api/admin/compatibility/rule" && request.method === "POST") {
    const payload = await bodyJson<Record<string, unknown>>(request);
    const targetPackage = cleanString(payload.targetPackage, 180);
    const status = cleanString(payload.status, 20);
    const message = cleanString(payload.message, 500);
    if (!targetPackage || !status || !message || !["OK", "WARNING", "BROKEN"].includes(status)) {
      throw new HttpError(400, "Regra inválida");
    }
    await env.DB.prepare(
      `INSERT INTO compatibility_rules(target_package,min_version_code,max_version_code,status,message,updated_at)
       VALUES (?,?,?,?,?,?)`,
    ).bind(
      targetPackage,
      payload.minVersionCode == null ? null : Number(payload.minVersionCode),
      payload.maxVersionCode == null ? null : Number(payload.maxVersionCode),
      status,
      message,
      nowIso(),
    ).run();
    return json({ ok: true }, 201);
  }

  if (path === "/api/admin/releases" && request.method === "POST") {
    const payload = await bodyJson<Record<string, unknown>>(request);
    const versionName = cleanString(payload.versionName, 60);
    if (!versionName) throw new HttpError(400, "versionName obrigatório");
    await env.DB.prepare(
      `INSERT INTO releases(version_name,version_code,minimum_supported,mandatory,download_url,notes,created_at)
       VALUES (?,?,?,?,?,?,?)`,
    ).bind(
      versionName,
      payload.versionCode == null ? null : Number(payload.versionCode),
      payload.minimumSupported ? 1 : 0,
      payload.mandatory ? 1 : 0,
      cleanString(payload.downloadUrl, 500),
      cleanString(payload.notes, 1000),
      nowIso(),
    ).run();
    return json({ ok: true }, 201);
  }

  return json({ error: "Rota não encontrada" }, 404);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (error) {
      if (error instanceof HttpError) return json({ error: error.message }, error.status);
      console.error(error);
      return json({ error: "Erro interno" }, 500);
    }
  },
};
