const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization"
};
// First Administrator registration is handled only by the separate activation-key setup website.
// Owner enrollment on paired devices is retained only for verification/recovery of an existing owner.
const ENTITY_TYPES = new Set(["product", "sale", "movement", "settings", "customer", "supplier", "purchase", "expense", "app_user"]);

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}
async function readJson(request, maxBytes = 262144) {
  const text = await request.text();
  if (text.length > maxBytes) throw new Error("Request too large");
  return JSON.parse(text);
}
function base64url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
async function sha256(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}
function bearer(request) {
  const value = request.headers.get("Authorization") || "";
  const match = /^Bearer ([A-Za-z0-9_-]{32,160})$/.exec(value);
  return match ? match[1] : null;
}
async function authenticatedDevice(request, env) {
  const token = bearer(request);
  if (!token) return null;
  const tokenHash = await sha256(token);
  const row = await env.DB.prepare(
    "SELECT id, shop_id FROM devices WHERE token_hash = ? AND revoked_at IS NULL"
  ).bind(tokenHash).first();
  if (row) {
    await env.DB.prepare("UPDATE devices SET last_seen_at = CURRENT_TIMESTAMP, token_last_used_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(row.id).run();
  }
  return row || null;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    let diagnosticStage = "request";
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: JSON_HEADERS });
    try {
      if (request.method === "GET" && url.pathname === "/health") {
        const row = await env.DB.prepare(
          "SELECT COUNT(*) AS table_count FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
        ).first();
        return json({
          service: "the-green-grocery-shop-api",
          status: "ok",
          database: "connected",
          schemaTables: Number(row?.table_count || 0),
          apiVersion: 1
        });
      }

      if (request.method === "POST" && url.pathname === "/api/admin/pairing-code") {
        const supplied = request.headers.get("Authorization") || "";
        const expected = typeof env.PAIRING_BOOTSTRAP_SECRET === "string" ? "Bearer " + env.PAIRING_BOOTSTRAP_SECRET : "";
        let mismatch = supplied.length ^ expected.length;
        const max = Math.max(supplied.length, expected.length);
        for (let i = 0; i < max; i++) mismatch |= (supplied.charCodeAt(i) || 0) ^ (expected.charCodeAt(i) || 0);
        if (!expected || mismatch !== 0) return json({ error: "Unauthorized" }, 401);
        diagnosticStage = "setup_find_shop";
        const shop = await env.DB.prepare("SELECT id FROM shops WHERE id = ?").bind("green-grocery-shop-main").first();
        if (!shop) return json({ error: "Shop is not configured" }, 503);
        const deviceCount = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE shop_id = ? AND revoked_at IS NULL").bind(shop.id).first();
        if (Number(deviceCount?.n || 0) > 0) return json({ error: "This shop already has a paired device" }, 409);
        const ownerCount = await env.DB.prepare("SELECT COUNT(*) AS n FROM owner_accounts WHERE shop_id = ?").bind(shop.id).first();
        if (Number(ownerCount?.n || 0) > 0) return json({ error: "Administrator setup is already registered" }, 409);
        const pending = await env.DB.prepare("SELECT code_hash FROM device_pairing_codes WHERE shop_id = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP LIMIT 1").bind(shop.id).first();
        if (pending) return json({ error: "A pairing code is already active. Use the existing code." }, 409);
        const bytes = new Uint8Array(32);
        crypto.getRandomValues(bytes);
        const code = base64url(bytes);
        const codeHash = await sha256(code);
        await env.DB.prepare("INSERT INTO device_pairing_codes (code_hash, shop_id, created_by, expires_at) VALUES (?, ?, ?, datetime('now', '+24 hours'))")
          .bind(codeHash, shop.id, "cloudflare-bootstrap").run();
        return json({ issued: true, code, expires_in_seconds: 86400, shop_id: shop.id });
      }

      if (request.method === "POST" && url.pathname === "/api/admin/setup-key") {
        const supplied = request.headers.get("Authorization") || "";
        const expected = typeof env.PAIRING_BOOTSTRAP_SECRET === "string" ? "Bearer " + env.PAIRING_BOOTSTRAP_SECRET : "";
        let mismatch = supplied.length ^ expected.length;
        const max = Math.max(supplied.length, expected.length);
        for (let i = 0; i < max; i++) mismatch |= (supplied.charCodeAt(i) || 0) ^ (expected.charCodeAt(i) || 0);
        if (!expected || mismatch !== 0) return json({ error: "Unauthorized" }, 401);
        const shop = await env.DB.prepare("SELECT id FROM shops WHERE id = ?").bind("green-grocery-shop-main").first();
        if (!shop) return json({ error: "Shop is not configured" }, 503);
        const owner = await env.DB.prepare("SELECT 1 AS n FROM owner_accounts WHERE shop_id = ?").bind(shop.id).first();
        if (owner) return json({ error: "Administrator setup is already complete" }, 409);
        const pending = await env.DB.prepare("SELECT 1 AS n FROM owner_setup_keys WHERE shop_id = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP LIMIT 1").bind(shop.id).first();
        if (pending) return json({ error: "An active setup key already exists. Use that key." }, 409);
        const bytes = new Uint8Array(32); crypto.getRandomValues(bytes);
        const key = base64url(bytes);
        await env.DB.prepare("INSERT INTO owner_setup_keys (key_hash, shop_id, expires_at) VALUES (?, ?, datetime('now', '+7 days'))")
          .bind(await sha256(key), shop.id).run();
        return json({ issued: true, key, expires_in_seconds: 604800 });
      }

      if (request.method === "POST" && url.pathname === "/api/owner/setup") {
        diagnosticStage = "setup_read_request";
        const body = await readJson(request, 8192);
        const key = typeof body.activation_key === "string" ? body.activation_key.trim() : "";
        const username = typeof body.username === "string" ? body.username.trim() : "";
        const displayName = typeof body.display_name === "string" ? body.display_name.trim() : "";
        const password = typeof body.password === "string" ? body.password : "";
        if (!/^[A-Za-z0-9_-]{32,100}$/.test(key) ||
            !/^[A-Za-z0-9_.-]{3,32}$/.test(username) ||
            displayName.length < 2 || displayName.length > 80 ||
            password.length < 10 || password.length > 128) {
          return json({ error: "Enter a valid activation key, username, name, and password (10–128 characters)." }, 400);
        }
        const shop = await env.DB.prepare("SELECT id FROM shops WHERE id = ?").bind("green-grocery-shop-main").first();
        if (!shop) return json({ error: "Shop is not configured" }, 503);
        diagnosticStage = "setup_check_activation_key";
        const keyHash = await sha256(key);
        const activation = await env.DB.prepare(
          "SELECT key_hash FROM owner_setup_keys WHERE key_hash = ? AND shop_id = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP"
        ).bind(keyHash, shop.id).first();
        if (!activation) return json({ error: "Activation key is invalid, expired, or already used." }, 403);
        diagnosticStage = "setup_check_existing_owner";
        const existing = await env.DB.prepare("SELECT 1 AS n FROM owner_accounts WHERE shop_id = ?").bind(shop.id).first();
        if (existing) return json({ error: "This shop already has its permanent Administrator. Setup is locked." }, 409);
        const saltBytes = new Uint8Array(16); crypto.getRandomValues(saltBytes);
        const salt = Array.from(saltBytes, b => b.toString(16).padStart(2, "0")).join("");
        diagnosticStage = "setup_import_password_key";
        const passwordKey = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), { name: "PBKDF2" }, false, ["deriveBits"]);
        diagnosticStage = "setup_derive_password_bits";
        const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: saltBytes.buffer.slice(saltBytes.byteOffset, saltBytes.byteOffset + saltBytes.byteLength), iterations: 100000, hash: { name: "SHA-1" } }, passwordKey, 256);
        const hash = Array.from(new Uint8Array(bits), b => b.toString(16).padStart(2, "0")).join("");
        const verifier = "pbkdf2$100000$" + salt + "$" + hash;
        const userId = crypto.randomUUID();
        const user = {id:userId,username,display_name:displayName,role:"OWNER",pin_hash:null,password_hash:verifier,is_active:1};
        diagnosticStage = "setup_insert_owner";
        const inserted = await env.DB.prepare(
          "INSERT INTO owner_accounts (shop_id, username, display_name, password_salt, password_hash, password_iterations, enrollment_challenge_hash) VALUES (?, ?, ?, ?, ?, 100000, 'admin-setup-portal-v1') ON CONFLICT(shop_id) DO NOTHING"
        ).bind(shop.id, username, displayName, salt, hash).run();
        if (Number(inserted.meta?.changes || 0) !== 1) return json({ error: "Another Administrator was registered first. Setup is permanently locked." }, 409);
        diagnosticStage = "setup_consume_key";
        const consumed = await env.DB.prepare(
          "UPDATE owner_setup_keys SET consumed_at = CURRENT_TIMESTAMP WHERE key_hash = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP"
        ).bind(keyHash).run();
        if (Number(consumed.meta?.changes || 0) !== 1) {
          await env.DB.prepare("DELETE FROM owner_accounts WHERE shop_id = ? AND username = ? COLLATE NOCASE").bind(shop.id, username).run();
          return json({ error: "Activation key was already used. Request a new key." }, 409);
        }
        diagnosticStage = "setup_write_sync_record";
        await env.DB.prepare(
          "INSERT INTO sync_records (shop_id, entity_type, entity_id, payload_json, version, deleted, updated_at) VALUES (?, 'app_user', ?, ?, 1, 0, CURRENT_TIMESTAMP)"
        ).bind(shop.id, userId, JSON.stringify(user)).run();
        return json({created:true, message:"The shop's first Administrator has been registered. This setup page is now locked."});
      }

      if (request.method === "POST" && url.pathname === "/api/auth/login") {
        const body = await readJson(request, 8192);
        const username = typeof body.username === "string" ? body.username.trim() : "";
        const secret = typeof body.secret === "string" ? body.secret : "";
        if (!username || !secret || username.length > 32 || secret.length > 128) return json({error:"Enter your username and password or PIN."},400);
        const shop = await env.DB.prepare("SELECT id FROM shops WHERE id = ?").bind("green-grocery-shop-main").first();
        if (!shop) return json({error:"Shop is not configured"},503);
        let user = null;
        const owner = await env.DB.prepare("SELECT username, display_name, password_salt, password_hash, password_iterations FROM owner_accounts WHERE shop_id = ? AND username = ? COLLATE NOCASE").bind(shop.id,username).first();
        async function verify(encoded, supplied) {
          const parts = String(encoded || "").split("$");
          if (parts.length !== 4 || parts[0] !== "pbkdf2" || !/^\d+$/.test(parts[1]) || !/^[a-f0-9]{32}$/i.test(parts[2]) || !/^[a-f0-9]{64}$/i.test(parts[3])) return false;
          const salt = Uint8Array.from(parts[2].match(/../g), x => parseInt(x,16));
          const key = await crypto.subtle.importKey("raw",new TextEncoder().encode(supplied),{name:"PBKDF2"},false,["deriveBits"]);
          const bits = await crypto.subtle.deriveBits({name:"PBKDF2",salt,iterations:Number(parts[1]),hash:"SHA-1"},key,256);
          const actual = Array.from(new Uint8Array(bits),b=>b.toString(16).padStart(2,"0")).join("");
          let diff = actual.length ^ parts[3].length;
          for(let i=0;i<Math.max(actual.length,parts[3].length);i++) diff |= (actual.charCodeAt(i)||0) ^ (parts[3].charCodeAt(i)||0);
          return diff === 0;
        }
        if (owner && await verify("pbkdf2$"+owner.password_iterations+"$"+owner.password_salt+"$"+owner.password_hash,secret)) {
          user = {id:"",username:owner.username,display_name:owner.display_name,role:"OWNER",pin_hash:null,password_hash:"pbkdf2$"+owner.password_iterations+"$"+owner.password_salt+"$"+owner.password_hash,is_active:1};
          const row = await env.DB.prepare("SELECT entity_id,payload_json FROM sync_records WHERE shop_id = ? AND entity_type='app_user' AND deleted=0").bind(shop.id).all();
          for (const item of (row.results||[])) { try { const value=JSON.parse(item.payload_json); if(value.role==="OWNER" && String(value.username).toLowerCase()===owner.username.toLowerCase()) {user={...value};break;} } catch(_){} }
        } else {
          const rows = await env.DB.prepare("SELECT entity_id,payload_json FROM sync_records WHERE shop_id = ? AND entity_type='app_user' AND deleted=0").bind(shop.id).all();
          for (const item of (rows.results||[])) {
            try {
              const value=JSON.parse(item.payload_json);
              if (value.role==="CASHIER" && value.is_active===1 && String(value.username||"").toLowerCase()===username.toLowerCase() && await verify(value.pin_hash,secret)) {user=value;break;}
            } catch(_){}
          }
        }
        if (!user || user.is_active !== 1) return json({error:"Incorrect login details or inactive account."},401);
        const deviceId=crypto.randomUUID(), tokenBytes=new Uint8Array(32); crypto.getRandomValues(tokenBytes);
        const token=base64url(tokenBytes), tokenHash=await sha256(token);
        await env.DB.prepare("INSERT INTO devices (id,shop_id,label,token_hash,token_created_at,last_seen_at,token_last_used_at) VALUES (?,?,?, ?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
          .bind(deviceId,shop.id,"Android POS device",tokenHash).run();
        return json({authenticated:true,shop_id:shop.id,device_id:deviceId,token,user});
      }

      if (request.method === "POST" && url.pathname === "/api/pair") {
        const body = await readJson(request, 4096);
        const code = typeof body.code === "string" ? body.code.trim() : "";
        const label = typeof body.device_label === "string" ? body.device_label.trim().slice(0, 80) : "Android device";
        if (!/^[A-Za-z0-9_-]{20,100}$/.test(code)) return json({ error: "Invalid or expired pairing code" }, 400);
        const codeHash = await sha256(code);
        const pairing = await env.DB.prepare(
          "SELECT shop_id FROM device_pairing_codes WHERE code_hash = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP"
        ).bind(codeHash).first();
        if (!pairing) return json({ error: "Invalid or expired pairing code" }, 400);

        const deviceId = crypto.randomUUID();
        const tokenBytes = new Uint8Array(32);
        crypto.getRandomValues(tokenBytes);
        const token = base64url(tokenBytes);
        const tokenHash = await sha256(token);

        // devices.id is referenced by device_pairing_codes.consumed_by_device.
        // Create the device first so the pairing-code update satisfies the FK.
        await env.DB.prepare(
          "INSERT INTO devices (id, shop_id, label, token_hash, token_created_at, last_seen_at, token_last_used_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
        ).bind(deviceId, pairing.shop_id, label || "Android device", tokenHash).run();

        try {
          const consume = await env.DB.prepare(
            "UPDATE device_pairing_codes SET consumed_at = CURRENT_TIMESTAMP, consumed_by_device = ? WHERE code_hash = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP"
          ).bind(deviceId, codeHash).run();
          if (Number(consume.meta?.changes || 0) !== 1) {
            await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(deviceId).run();
            return json({ error: "Pairing code has already been used or expired" }, 409);
          }
        } catch (error) {
          await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(deviceId).run();
          throw error;
        }
        return json({ paired: true, device_id: deviceId, shop_id: pairing.shop_id, token });
      }


      if (request.method === "GET" && url.pathname === "/api/shop/status") {
        const shop = await env.DB.prepare("SELECT id FROM shops WHERE id = ?").bind("green-grocery-shop-main").first();
        if (!shop) return json({ configured: false, owner_registered: null }, 503);
        const owner = await env.DB.prepare("SELECT 1 AS registered FROM owner_accounts WHERE shop_id = ? LIMIT 1").bind(shop.id).first();
        return json({ configured: true, owner_registered: !!owner });
      }

      if (request.method === "POST" && url.pathname === "/api/owner/password") {
        const device = await authenticatedDevice(request, env);
        if (!device) return json({ error: "This device is not paired or its access has been revoked." }, 401);
        const body = await readJson(request, 4096);
        const username = typeof body.username === "string" ? body.username.trim() : "";
        const encoded = typeof body.password_verifier === "string" ? body.password_verifier : "";
        const parts = encoded.split("$");
        if (!/^[A-Za-z0-9_.-]{3,32}$/.test(username) || parts.length !== 4 ||
            parts[0] !== "pbkdf2" || Number(parts[1]) !== 100000 ||
            !/^[a-f0-9]{32}$/i.test(parts[2]) || !/^[a-f0-9]{64}$/i.test(parts[3])) {
          return json({ error: "Invalid new password verifier." }, 400);
        }
        const owner = await env.DB.prepare("SELECT username FROM owner_accounts WHERE shop_id = ?").bind(device.shop_id).first();
        if (!owner || owner.username.toLowerCase() !== username.toLowerCase()) {
          return json({ error: "Administrator account was not found for this shop." }, 403);
        }
        const updated = await env.DB.prepare(
          "UPDATE owner_accounts SET password_salt = ?, password_hash = ?, password_iterations = ? WHERE shop_id = ? AND username = ? COLLATE NOCASE"
        ).bind(parts[2], parts[3], Number(parts[1]), device.shop_id, owner.username).run();
        if (Number(updated.meta?.changes || 0) !== 1) return json({ error: "Password update was not saved." }, 409);
        const row = await env.DB.prepare("SELECT entity_id, payload_json FROM sync_records WHERE shop_id = ? AND entity_type = 'app_user' AND deleted = 0").bind(device.shop_id).all();
        for (const item of (row.results || [])) {
          try {
            const payload = JSON.parse(item.payload_json);
            if (payload.role === "OWNER" && String(payload.username || "").toLowerCase() === owner.username.toLowerCase()) {
              payload.password_hash = encoded;
              await env.DB.prepare("UPDATE sync_records SET payload_json = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE shop_id = ? AND entity_type = 'app_user' AND entity_id = ?")
                .bind(JSON.stringify(payload), device.shop_id, item.entity_id).run();
            }
          } catch (_) {}
        }
        return json({ changed: true, message: "Administrator password updated for this shop." });
      }

      if (request.method === "POST" && url.pathname === "/api/owner/enroll") {
        // Only a currently paired, non-revoked device can claim the first Administrator.
        // owner_accounts.shop_id is the permanent shop-wide registration lock.
        const device = await authenticatedDevice(request, env);
        if (!device) return json({ error: "This device is not paired or its access has been revoked. Administrator registration is locked to the shop's paired device." }, 401);
        const body = await readJson(request, 8192);
        const username = typeof body.username === "string" ? body.username.trim() : "";
        const displayName = typeof body.display_name === "string" ? body.display_name.trim() : "";
        const password = typeof body.password === "string" ? body.password : "";
        const verifierSalt = typeof body.password_salt === "string" ? body.password_salt : "";
        const verifierHash = typeof body.password_hash === "string" ? body.password_hash : "";
        const verifierIterations = Number(body.password_iterations || 100000);
        const hasVerifier = /^[a-f0-9]{32}$/i.test(verifierSalt) && /^[a-f0-9]{64}$/i.test(verifierHash) && verifierIterations === 100000;
        if (!/^[A-Za-z0-9_.-]{3,32}$/.test(username) || displayName.length < 2 || displayName.length > 80 ||
            (!hasVerifier && (password.length < 10 || password.length > 128))) {
          return json({ error: "Enter a valid username, Administrator name, and password." }, 400);
        }
        const existing = await env.DB.prepare(
          "SELECT username, password_salt, password_hash, password_iterations FROM owner_accounts WHERE shop_id = ?"
        ).bind(device.shop_id).first();
        if (!existing) return json({ error: "First Administrator registration is available only through the separate secure setup website." }, 403);
        const iterations = 100000;
        async function deriveOwnerPassword(secret, saltHex, rounds) {
          const salt = Uint8Array.from(saltHex.match(/../g) || [], x => parseInt(x, 16));
          const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "PBKDF2" }, false, ["deriveBits"]);
          const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: rounds, hash: "SHA-1" }, key, 256);
          return Array.from(new Uint8Array(bits), b => b.toString(16).padStart(2, "0")).join("");
        }
        if (existing) {
          if (existing.username.toLowerCase() !== username.toLowerCase()) {
            return json({ error: "An Administrator is already registered for this shop. A second Administrator cannot be created." }, 409);
          }
          const candidate = hasVerifier ? (verifierSalt === existing.password_salt && verifierHash === existing.password_hash && verifierIterations === Number(existing.password_iterations) ? verifierHash : "") : await deriveOwnerPassword(password, existing.password_salt, Number(existing.password_iterations));
          if (candidate !== existing.password_hash) {
            return json({ error: "This shop already has an Administrator. The one-time registration is permanently locked." }, 409);
          }
          return json({ enrolled: true, retry: true, message: "Existing Administrator verified. Finish local sign-in setup." });
        }
        const saltBytes = new Uint8Array(16);
        crypto.getRandomValues(saltBytes);
        const generatedSalt = Array.from(saltBytes, b => b.toString(16).padStart(2, "0")).join("");
        const salt = hasVerifier ? verifierSalt : generatedSalt;
        const passwordHash = hasVerifier ? verifierHash : await deriveOwnerPassword(password, salt, iterations);
        await env.DB.prepare(
          "INSERT INTO owner_accounts (shop_id, username, display_name, password_salt, password_hash, password_iterations, enrollment_challenge_hash) VALUES (?, ?, ?, ?, ?, ?, 'paired-device-first-owner-v1') ON CONFLICT(shop_id) DO NOTHING"
        ).bind(device.shop_id, username, displayName, salt, passwordHash, hasVerifier ? verifierIterations : iterations).run();
        const owner = await env.DB.prepare(
          "SELECT username, password_salt, password_hash, password_iterations FROM owner_accounts WHERE shop_id = ?"
        ).bind(device.shop_id).first();
        if (!owner || owner.username.toLowerCase() !== username.toLowerCase()) {
          return json({ error: "An Administrator was registered first. Only one Administrator is allowed for this shop." }, 409);
        }
        // A queued offline enrollment sends the locally derived PBKDF2 verifier,
        // not the plaintext password. Verify that exact verifier after the insert.
        // Direct online enrollment instead verifies the supplied password.
        const verified = hasVerifier
          ? (verifierSalt === owner.password_salt &&
             verifierHash === owner.password_hash &&
             verifierIterations === Number(owner.password_iterations))
          : (await deriveOwnerPassword(password, owner.password_salt, Number(owner.password_iterations))) === owner.password_hash;
        if (!verified) {
          return json({ error: "An Administrator was registered first. This registration is permanently locked." }, 409);
        }
        return json({ enrolled: true, message: "First Administrator registered and shop-wide registration lock activated." });
      }

      if (request.method === "POST" && url.pathname === "/api/sync/push") {
        const device = await authenticatedDevice(request, env);
        if (!device) return json({ error: "Unauthorized device" }, 401);
        const body = await readJson(request, 524288);
        if (!Array.isArray(body.operations) || body.operations.length > 100) return json({ error: "operations must contain at most 100 entries" }, 400);
        const results = [];
        for (const op of body.operations) {
          if (!op || typeof op.operation_id !== "string" || op.operation_id.length > 100 ||
              !ENTITY_TYPES.has(op.entity_type) || typeof op.entity_id !== "string" || op.entity_id.length > 160 ||
              !["INSERT", "UPDATE", "DELETE"].includes(op.operation) ||
              !Number.isInteger(op.base_version) || op.base_version < 0) {
            results.push({ operation_id: op?.operation_id || null, status: "REJECTED", reason: "Invalid operation" });
            continue;
          }
          const previous = await env.DB.prepare(
            "SELECT status, resulting_version FROM sync_operations WHERE operation_id = ? AND shop_id = ?"
          ).bind(op.operation_id, device.shop_id).first();
          if (previous) {
            results.push({ operation_id: op.operation_id, status: previous.status, version: previous.resulting_version });
            continue;
          }
          const deleted = op.operation === "DELETE" ? 1 : 0;
          const payloadObject = op.payload && typeof op.payload === "object" ? op.payload : {};
          if (op.entity_type === "app_user" && !deleted) {
            const role = payloadObject.role;
            const username = typeof payloadObject.username === "string" ? payloadObject.username.trim() : "";
            if (payloadObject.id !== op.entity_id || !/^[A-Za-z0-9_.-]{3,32}$/.test(username) ||
                !["OWNER", "CASHIER"].includes(role)) {
              results.push({ operation_id: op.operation_id, status: "REJECTED", reason: "Invalid account record" });
              continue;
            }
            const ownerLock = await env.DB.prepare(
              "SELECT username, password_salt, password_hash, password_iterations FROM owner_accounts WHERE shop_id = ?"
            ).bind(device.shop_id).first();
            if (!ownerLock) {
              results.push({ operation_id: op.operation_id, status: "REJECTED", reason: "First Administrator must be registered before account sync" });
              continue;
            }
            if (role === "OWNER") {
              const encoded = typeof payloadObject.password_hash === "string" ? payloadObject.password_hash.split("$") : [];
              if (username.toLowerCase() !== ownerLock.username.toLowerCase() || encoded.length !== 4 ||
                  encoded[2] !== ownerLock.password_salt || encoded[3] !== ownerLock.password_hash ||
                  Number(encoded[1]) !== Number(ownerLock.password_iterations)) {
                results.push({ operation_id: op.operation_id, status: "REJECTED", reason: "Administrator account does not match the permanent shop registration" });
                continue;
              }
            } else if (typeof payloadObject.pin_hash !== "string" || !/^pbkdf2\$120000\$[a-f0-9]{32}\$[a-f0-9]{64}$/i.test(payloadObject.pin_hash)) {
              results.push({ operation_id: op.operation_id, status: "REJECTED", reason: "Cashier credential verifier is invalid" });
              continue;
            }
          }
          const payload = JSON.stringify(payloadObject);
          const current = await env.DB.prepare(
            "SELECT version, payload_json, updated_by_device, deleted FROM sync_records WHERE shop_id = ? AND entity_type = ? AND entity_id = ?"
          ).bind(device.shop_id, op.entity_type, op.entity_id).first();
          const currentVersion = Number(current?.version || 0);
          if (currentVersion !== op.base_version) {
            if (currentVersion === op.base_version + 1 &&
                current?.updated_by_device === device.id &&
                current?.payload_json === payload &&
                Number(current?.deleted || 0) === deleted) {
              await env.DB.prepare(
                "INSERT OR IGNORE INTO sync_operations (operation_id, shop_id, device_id, entity_type, entity_id, operation, base_version, resulting_version, payload_json, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPLIED')"
              ).bind(op.operation_id, device.shop_id, device.id, op.entity_type, op.entity_id, op.operation, op.base_version, currentVersion, payload).run();
              results.push({ operation_id: op.operation_id, status: "APPLIED", version: currentVersion });
              continue;
            }
            await env.DB.prepare(
              "INSERT OR IGNORE INTO sync_operations (operation_id, shop_id, device_id, entity_type, entity_id, operation, base_version, payload_json, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CONFLICT')"
            ).bind(op.operation_id, device.shop_id, device.id, op.entity_type, op.entity_id, op.operation, op.base_version, payload).run();
            results.push({ operation_id: op.operation_id, status: "CONFLICT", current_version: currentVersion });
            continue;
          }
          const write = await env.DB.prepare(
            "INSERT INTO sync_records (shop_id, entity_type, entity_id, payload_json, version, deleted, updated_by_device, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT(shop_id, entity_type, entity_id) DO UPDATE SET payload_json = excluded.payload_json, version = sync_records.version + 1, deleted = excluded.deleted, updated_by_device = excluded.updated_by_device, updated_at = CURRENT_TIMESTAMP WHERE sync_records.version = ?"
          ).bind(device.shop_id, op.entity_type, op.entity_id, payload, deleted, device.id, op.base_version).run();
          const applied = Number(write.meta?.changes || 0) === 1;
          const resultingVersion = applied ? op.base_version + 1 : null;
          const status = applied ? "APPLIED" : "CONFLICT";
          await env.DB.prepare(
            "INSERT OR IGNORE INTO sync_operations (operation_id, shop_id, device_id, entity_type, entity_id, operation, base_version, resulting_version, payload_json, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
          ).bind(op.operation_id, device.shop_id, device.id, op.entity_type, op.entity_id, op.operation, op.base_version, resultingVersion, payload, status).run();
          results.push({ operation_id: op.operation_id, status, version: resultingVersion });
        }
        return json({ accepted: results.filter(x => x.status === "APPLIED").length, results });
      }

      if (request.method === "GET" && url.pathname === "/api/sync/pull") {
        const device = await authenticatedDevice(request, env);
        if (!device) return json({ error: "Unauthorized device" }, 401);
        const rows = await env.DB.prepare(
          "SELECT entity_type, entity_id, payload_json, version, deleted, updated_at FROM sync_records WHERE shop_id = ? ORDER BY updated_at, entity_type, entity_id"
        ).bind(device.shop_id).all();
        const records = (rows.results || []).map(row => ({
          entity_type: row.entity_type,
          entity_id: row.entity_id,
          payload: JSON.parse(row.payload_json),
          version: Number(row.version),
          deleted: Number(row.deleted) === 1,
          updated_at: row.updated_at
        }));
        return json({ shop_id: device.shop_id, records });
      }

      return json({ error: "Not found" }, 404);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unexpected server error";
      console.error("Worker request failed", { path: url.pathname, method: request.method, stage: diagnosticStage, error: message });
      return json({ error: message === "Request too large" ? message : "Server error", support_code: diagnosticStage }, message === "Request too large" ? 413 : 500);
    }
  }
};