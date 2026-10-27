// 폴더 카메라 중계 서버
//  - 현장을 만든 사람(현장 주인)이 자기 OneDrive 또는 구글 드라이브로 로그인하면, 그 권한은 이 서버에만 보관됨
//  - 현장 사람들은 초대 코드만으로 "업로드 주소"를 받아 폰에서 클라우드로 직접 업로드
//  - 기존(관리자 페이지에서 만든) 현장은 관리자 OneDrive의 "폴더카메라/현장이름" 을 그대로 사용
//
// 환경 변수 (Netlify > Project configuration > Environment variables)
//   MS_CLIENT_ID, MS_CLIENT_SECRET   Azure 앱 등록 (OneDrive)
//   MS_AUTHORITY                      (선택) consumers=개인 계정만(기본), common=회사 계정도
//   ADMIN_PASSWORD                    관리자 페이지 비밀번호
//   GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET   구글 클라우드 OAuth 클라이언트 (구글 드라이브)
//   GOOGLE_API_KEY, GOOGLE_APP_ID     (선택) 구글 드라이브 폴더 고르기 창(Picker)용 API 키, 프로젝트 번호

import { getStore } from "@netlify/blobs";

const GRAPH = "https://graph.microsoft.com/v1.0";
const MS_SCOPE = "offline_access Files.ReadWrite User.Read";
const msAuthUrl = (authority) => `https://login.microsoftonline.com/${authority}/oauth2/v2.0`;
const G_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
const G_TOKEN = "https://oauth2.googleapis.com/token";
const G_SCOPE = "openid email profile https://www.googleapis.com/auth/drive.file";
const DRIVE = "https://www.googleapis.com/drive/v3";
const DRIVE_UP = "https://www.googleapis.com/upload/drive/v3";
const FOLDER_MIME = "application/vnd.google-apps.folder";
const ROOT = "폴더카메라"; // 기존 현장: 관리자 OneDrive 안의 최상위 폴더 이름
const BAD_CHARS = /[\\/:*?"<>|#%\u0000-\u001f]/;
const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const IMAGE_EXT = /\.(jpe?g|png|heic|heif|webp)$/i;

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const store = () => getStore({ name: "foldercamera", consistency: "strong" });

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

const enc = (segments) => segments.map(encodeURIComponent).join("/");
const randomKey = (n = 32) => {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  return Array.from(crypto.getRandomValues(new Uint8Array(n)), (b) => chars[b % chars.length]).join("");
};
async function sha(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");
}

function cleanName(raw, what = "이름") {
  const name = String(raw ?? "").trim().replace(/\s+/g, " ");
  if (!name) throw new HttpError(400, `${what}을(를) 입력하세요`);
  if (name.length > 80) throw new HttpError(400, `${what}이(가) 너무 길어요`);
  if (BAD_CHARS.test(name) || name.startsWith(".") || name.endsWith(".")) {
    throw new HttpError(400, `${what}에 쓸 수 없는 문자가 있어요 ( / \\ : * ? " < > | # % )`);
  }
  return name;
}

/** "상위/하위" 폴더 경로 → 이름 배열 (각 이름 검사) */
function cleanPath(raw, what = "폴더") {
  const segs = String(raw ?? "").split("/").map((s) => s.trim()).filter(Boolean);
  if (!segs.length) throw new HttpError(400, `${what} 이름을 입력하세요`);
  if (segs.length > 6) throw new HttpError(400, "폴더가 너무 깊어요 (최대 6단계)");
  return segs.map((s) => cleanName(s, `${what} 이름`));
}
const optPath = (raw) => (String(raw ?? "").trim() ? cleanPath(raw) : []);

function env(key) {
  const value = process.env[key];
  if (!value) throw new HttpError(500, `${key} 환경 변수가 설정되지 않았어요`);
  return value;
}

function requireAdmin(req, url) {
  const given = req.headers.get("x-admin-password") ?? url.searchParams.get("pw") ?? "";
  if (given !== env("ADMIN_PASSWORD")) throw new HttpError(401, "관리자 비밀번호가 틀렸어요");
}

// ───────── 로그인 토큰 ─────────

async function msToken(authority, params) {
  const body = new URLSearchParams({
    client_id: env("MS_CLIENT_ID"),
    client_secret: env("MS_CLIENT_SECRET"),
    scope: MS_SCOPE,
    ...params,
  });
  const res = await fetch(`${msAuthUrl(authority)}/token`, { method: "POST", body });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new HttpError(502, `Microsoft 로그인 오류: ${data.error_description || data.error || res.status}`);
  return data;
}

async function googleToken(params) {
  const body = new URLSearchParams({
    client_id: env("GOOGLE_CLIENT_ID"),
    client_secret: env("GOOGLE_CLIENT_SECRET"),
    ...params,
  });
  const res = await fetch(G_TOKEN, { method: "POST", body });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new HttpError(502, `Google 로그인 오류: ${data.error_description || data.error || res.status}`);
  return data;
}

// 계정: "legacy" = 관리자 페이지에서 연결한 OneDrive (예전 방식), 그 외 = 현장 주인 계정
const acctKey = (id) => (id === "legacy" ? "auth" : `acct:${id}`);
const getAccount = (id) => store().get(acctKey(id), { type: "json" });
const saveAccount = (id, rec) => store().setJSON(acctKey(id), rec);

async function accessTokenFor(id) {
  const rec = await getAccount(id);
  if (!rec?.refresh_token) {
    throw new HttpError(503, id === "legacy"
      ? "관리자가 아직 OneDrive를 연결하지 않았어요"
      : "현장 주인의 클라우드 연결이 끊겼어요. 현장 주인이 다시 로그인해야 해요");
  }
  if (rec.access_token && rec.expires_at > Date.now()) return rec.access_token;
  let data;
  try {
    data = rec.provider === "google"
      ? await googleToken({ grant_type: "refresh_token", refresh_token: rec.refresh_token })
      : await msToken(rec.authority || "consumers", { grant_type: "refresh_token", refresh_token: rec.refresh_token });
  } catch (e) {
    throw new HttpError(503, `클라우드 로그인이 만료되었어요. 현장 주인이 다시 로그인해야 해요 (${e.message})`);
  }
  const next = {
    ...rec,
    access_token: data.access_token,
    refresh_token: data.refresh_token || rec.refresh_token,
    expires_at: Date.now() + (Number(data.expires_in || 3600) - 300) * 1000,
  };
  await saveAccount(id, next);
  return next.access_token;
}

// ───────── OneDrive (Microsoft Graph) ─────────

async function graph(token, method, path, body) {
  const res = await fetch(GRAPH + path, {
    method,
    headers: { authorization: `Bearer ${token}`, ...(body ? { "content-type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  return { res, data };
}

/** 기존 방식: 경로로 폴더를 차례로 만듦 */
async function msEnsurePath(token, segments, from = 0) {
  for (let i = from; i < segments.length; i++) {
    const parent = i === 0 ? "/me/drive/root" : `/me/drive/root:/${enc(segments.slice(0, i))}:`;
    const { res, data } = await graph(token, "POST", `${parent}/children`, {
      name: segments[i], folder: {}, "@microsoft.graph.conflictBehavior": "fail",
    });
    if (!res.ok && res.status !== 409) {
      throw new HttpError(502, `OneDrive 폴더를 만들 수 없어요: ${data?.error?.message || res.status}`);
    }
  }
}

/** 현장 안의 위치를 Graph 주소로 (segs 없으면 현장 폴더 자체) */
function msRef(site, segs = []) {
  if (site.legacy) return `/me/drive/root:/${enc([ROOT, site.name, ...segs])}:`;
  return segs.length ? `/me/drive/items/${site.folderId}:/${enc(segs)}:` : `/me/drive/items/${site.folderId}`;
}

/** 현장 안 폴더 경로(segs)를 없으면 차례로 만듦 */
async function msEnsureSub(token, site, segs) {
  if (site.legacy) return msEnsurePath(token, [ROOT, site.name, ...segs]);
  for (let i = 0; i < segs.length; i++) {
    const { res, data } = await graph(token, "POST", `${msRef(site, segs.slice(0, i))}/children`, {
      name: segs[i], folder: {}, "@microsoft.graph.conflictBehavior": "fail",
    });
    if (res.status === 404) throw new HttpError(404, "현장 폴더를 찾을 수 없어요. 클라우드에서 지워졌는지 확인하세요");
    if (!res.ok && res.status !== 409) throw new HttpError(502, `OneDrive 폴더를 만들 수 없어요: ${data?.error?.message || res.status}`);
  }
}

/** Graph 여러 요청 한 번에 (최대 20개씩) */
async function msBatch(token, urls) {
  const out = [];
  for (let i = 0; i < urls.length; i += 20) {
    const chunk = urls.slice(i, i + 20);
    const { res, data } = await graph(token, "POST", "/$batch", {
      requests: chunk.map((url, j) => ({ id: String(j), method: "GET", url })),
    });
    if (!res.ok) { chunk.forEach(() => out.push(null)); continue; }
    const byId = Object.fromEntries((data.responses || []).map((r) => [r.id, r]));
    chunk.forEach((_, j) => out.push(byId[String(j)]?.status === 200 ? byId[String(j)].body : null));
  }
  return out;
}

// ───────── 구글 드라이브 ─────────

async function gapi(token, method, url, body) {
  const res = await fetch(url, {
    method,
    headers: { authorization: `Bearer ${token}`, ...(body ? { "content-type": "application/json; charset=UTF-8" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  return { res, data };
}
const gErr = (res, data, what = "구글 드라이브 오류") =>
  new HttpError(res.status === 404 ? 404 : 502, `${what}: ${data?.error?.message || res.status}`);
const gq = (s) => String(s).replace(/\\/g, "\\\\").replace(/'/g, "\\'");
const ALL = "supportsAllDrives=true&includeItemsFromAllDrives=true";

async function gList(token, query, fields, extra = "") {
  const url = `${DRIVE}/files?q=${encodeURIComponent(query)}&fields=${encodeURIComponent(`files(${fields})`)}&pageSize=200&${ALL}${extra}`;
  const { res, data } = await gapi(token, "GET", url);
  if (!res.ok) throw gErr(res, data);
  return data.files || [];
}

async function gFindFolder(token, parentId, name) {
  const files = await gList(token,
    `'${gq(parentId)}' in parents and name='${gq(name)}' and mimeType='${FOLDER_MIME}' and trashed=false`, "id,name");
  return files[0]?.id || null;
}

async function gEnsureFolder(token, parentId, name) {
  const found = await gFindFolder(token, parentId, name);
  if (found) return found;
  const { res, data } = await gapi(token, "POST", `${DRIVE}/files?fields=id&supportsAllDrives=true`, {
    name, mimeType: FOLDER_MIME, parents: [parentId],
  });
  if (!res.ok) throw gErr(res, data, "구글 드라이브 폴더를 만들 수 없어요");
  return data.id;
}

/** 현장 폴더에서 경로(segs)를 따라가 폴더 id 찾기. create=true면 없을 때 만듦, false면 없을 때 null */
async function gResolve(token, rootId, segs, create) {
  let id = rootId;
  for (const name of segs) {
    id = create ? await gEnsureFolder(token, id, name) : await gFindFolder(token, id, name);
    if (!id) return null;
  }
  return id;
}

// ───────── 현장 ─────────

async function getSites() {
  return (await store().get("sites", { type: "json" })) || {};
}

async function siteByCode(rawCode) {
  const code = String(rawCode ?? "").trim().toUpperCase();
  const site = (await getSites())[code];
  if (!site) throw new HttpError(404, "초대 코드가 올바르지 않거나 삭제된 현장이에요");
  if (!site.account) return { code, ...site, provider: "ms", account: "legacy", legacy: true };
  return { code, ...site };
}

function newCode(sites) {
  let code;
  do {
    code = Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => CODE_CHARS[b % CODE_CHARS.length]).join("");
  } while (sites[code]);
  return code;
}

const byName = (a, b) => a.localeCompare(b, "ko", { numeric: true });

/** 현장 폴더(또는 그 안의 경로 segs) 아래 폴더 목록 + 각 폴더의 하위 폴더 수 */
async function listFolders(site, segs = []) {
  const token = await accessTokenFor(site.account);
  const notFound = new HttpError(404, segs.length
    ? "폴더를 찾을 수 없어요. 지워졌거나 이름이 바뀌었을 수 있어요"
    : "현장 폴더를 찾을 수 없어요. 클라우드에서 지워졌는지 확인하세요");
  if (site.provider === "google") {
    const parent = await gResolve(token, site.folderId, segs, false);
    if (!parent) throw notFound;
    const files = await gList(token, `'${gq(parent)}' in parents and mimeType='${FOLDER_MIME}' and trashed=false`, "id,name");
    const subs = {};
    for (let i = 0; i < files.length; i += 25) {
      const chunk = files.slice(i, i + 25);
      const q = `(${chunk.map((f) => `'${gq(f.id)}' in parents`).join(" or ")}) and mimeType='${FOLDER_MIME}' and trashed=false`;
      const kids = await gList(token, q, "id,parents");
      for (const f of chunk) subs[f.name] = kids.filter((k) => (k.parents || []).includes(f.id)).length;
    }
    return { folders: files.map((f) => f.name).sort(byName), subs };
  }
  const { res, data } = await graph(token, "GET", `${msRef(site, segs)}/children?$select=id,name,folder&$top=999`);
  if (res.status === 404) {
    if (site.legacy && !segs.length) { await msEnsurePath(token, [ROOT, site.name]); return { folders: [], subs: {} }; }
    throw notFound;
  }
  if (!res.ok) throw new HttpError(502, `OneDrive 목록 오류: ${data?.error?.message || res.status}`);
  const items = (data.value || []).filter((i) => i.folder);
  const subs = {};
  // 하위 항목이 있는 폴더만 한 번에 확인
  const withKids = items.filter((i) => (i.folder.childCount || 0) > 0);
  const bodies = await msBatch(token, withKids.map((i) => `/me/drive/items/${i.id}/children?$select=folder&$top=999`));
  items.forEach((i) => { subs[i.name] = 0; });
  withKids.forEach((i, k) => { subs[i.name] = (bodies[k]?.value || []).filter((c) => c.folder).length; });
  return { folders: items.map((i) => i.name).sort(byName), subs };
}

async function ensureSub(site, segs) {
  const token = await accessTokenFor(site.account);
  if (site.provider === "google") return gResolve(token, site.folderId, segs, true);
  return msEnsureSub(token, site, segs);
}

/** 업로드 주소 발급 (폰이 이 주소로 사진을 직접 보냄). folder = 경로 배열 */
async function createUploadSession(site, folder, fileName, origin) {
  const token = await accessTokenFor(site.account);
  if (site.provider === "google") {
    const parent = await gResolve(token, site.folderId, folder, true);
    const res = await fetch(`${DRIVE_UP}/files?uploadType=resumable&supportsAllDrives=true`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/json; charset=UTF-8",
        ...(origin ? { origin } : {}),     // 브라우저(웹앱)에서 바로 올릴 수 있게
      },
      body: JSON.stringify({ name: fileName, parents: [parent] }),
    });
    const location = res.headers.get("location");
    if (!res.ok || !location) {
      const data = await res.json().catch(() => null);
      throw gErr(res, data, "업로드 준비 실패");
    }
    return location;
  }
  const path = `${msRef(site, [...folder, fileName])}/createUploadSession`;
  const body = { item: { "@microsoft.graph.conflictBehavior": "rename" } };
  let { res, data } = await graph(token, "POST", path, body);
  if (res.status === 404) {
    await msEnsureSub(token, site, folder);
    ({ res, data } = await graph(token, "POST", path, body));
  }
  if (!res.ok || !data?.uploadUrl) throw new HttpError(502, `업로드 준비 실패: ${data?.error?.message || res.status}`);
  return data.uploadUrl;
}

/** 서버를 거쳐 작은 사진 올리기 (브라우저가 직접 못 올릴 때) */
async function uploadSmall(site, folder, fileName, bytes, contentType) {
  const token = await accessTokenFor(site.account);
  if (site.provider === "google") {
    const parent = await gResolve(token, site.folderId, folder, true);
    const boundary = "fc" + randomKey(16);
    const meta = JSON.stringify({ name: fileName, parents: [parent] });
    const body = new Blob([
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n`,
      `--${boundary}\r\nContent-Type: ${contentType}\r\n\r\n`,
      bytes,
      `\r\n--${boundary}--`,
    ]);
    const res = await fetch(`${DRIVE_UP}/files?uploadType=multipart&supportsAllDrives=true`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": `multipart/related; boundary=${boundary}` },
      body,
    });
    if (!res.ok) throw gErr(res, await res.json().catch(() => null), "업로드 실패");
    return;
  }
  const target = `${GRAPH}${msRef(site, [...folder, fileName])}/content?@microsoft.graph.conflictBehavior=rename`;
  const put = () => fetch(target, {
    method: "PUT",
    headers: { authorization: `Bearer ${token}`, "content-type": contentType },
    body: bytes,
  });
  let res = await put();
  if (res.status === 404) {
    await msEnsureSub(token, site, folder);
    res = await put();
  }
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    throw new HttpError(502, `업로드 실패: ${data?.error?.message || res.status}`);
  }
}

/** 폴더 안 사진 목록 (모든 참여자가 올린 것) */
async function listPhotos(site, folder) {
  const token = await accessTokenFor(site.account);
  if (site.provider === "google") {
    const parent = await gResolve(token, site.folderId, folder, false);
    if (!parent) return [];
    const files = await gList(token,
      `'${gq(parent)}' in parents and trashed=false and mimeType contains 'image/'`,
      "id,name,createdTime", "&orderBy=createdTime desc");
    const thumb = (id, s) => `/api/gthumb?code=${encodeURIComponent(site.code)}&id=${encodeURIComponent(id)}&s=${s}`;
    return files.map((f) => ({
      name: f.name, created: f.createdTime,
      small: thumb(f.id, 400), large: thumb(f.id, 1600), full: thumb(f.id, 4000),
    }));
  }
  const { res, data } = await graph(token, "GET", `${msRef(site, folder)}/children?$top=200&$expand=thumbnails`);
  if (res.status === 404) return [];
  if (!res.ok) throw new HttpError(502, `OneDrive 목록 오류: ${data?.error?.message || res.status}`);
  return (data.value || [])
    .filter((item) => item.file)
    .map((item) => {
      const t = (item.thumbnails || [])[0] || {};
      return {
        name: item.name,
        created: item.createdDateTime,
        small: t.medium?.url || t.small?.url || null,
        large: t.large?.url || null,
        full: item["@microsoft.graph.downloadUrl"] || null,
      };
    })
    .sort((a, b) => String(b.created).localeCompare(String(a.created)));
}

/** 빈 폴더만 삭제 (다른 사람 사진 보호) */
async function deleteFolder(site, folder) {
  const token = await accessTokenFor(site.account);
  const full = new HttpError(409, "사진이 들어 있는 폴더는 지울 수 없어요. 현장 주인이 클라우드에서 정리해 주세요");
  if (site.provider === "google") {
    const id = await gResolve(token, site.folderId, folder, false);
    if (!id) return;
    const inside = await gList(token, `'${gq(id)}' in parents and trashed=false`, "id");
    if (inside.length) throw full;
    const { res, data } = await gapi(token, "DELETE", `${DRIVE}/files/${encodeURIComponent(id)}?supportsAllDrives=true`);
    if (!res.ok && res.status !== 404) throw gErr(res, data, "폴더 삭제 실패");
    return;
  }
  const base = msRef(site, folder);
  const { res, data } = await graph(token, "GET", `${base}/children?$top=1&$select=name`);
  if (res.status === 404) return;
  if (!res.ok) throw new HttpError(502, `OneDrive 오류: ${data?.error?.message || res.status}`);
  if ((data.value || []).length > 0) throw full;
  const del = await graph(token, "DELETE", base);
  if (!del.res.ok && del.res.status !== 404) throw new HttpError(502, `폴더 삭제 실패: ${del.data?.error?.message || del.res.status}`);
}

/** 기본 폴더 목록 (한 줄에 하나, "상위/하위" 경로도 가능) → 경로 배열 목록 */
function cleanFolders(list) {
  return [...new Set((list || []).map((f) => String(f).trim()).filter(Boolean))].map((f) => cleanPath(f, `폴더 "${f}"`));
}

// ───────── 현장 주인 (로그인한 사람) ─────────

async function ownerAccount(req) {
  const key = req.headers.get("x-owner-key") || "";
  if (!key) throw new HttpError(401, "로그인이 필요해요");
  const id = await store().get(`owner:${await sha(key)}`, { type: "text" });
  const acct = id && (await getAccount(id));
  if (!acct) throw new HttpError(401, "로그인 정보가 만료되었어요. 다시 로그인하세요");
  return { id, acct };
}

async function finishLogin(provider, tokenData, profile, ret, origin) {
  const userId = String(profile.id || profile.email);
  const id = `${provider}_${(await sha(`${provider}:${userId}`)).slice(0, 20)}`;
  const prev = (await getAccount(id)) || {};
  const ownerKey = randomKey(32);
  await saveAccount(id, {
    ...prev,
    provider,
    authority: provider === "ms" ? (process.env.MS_AUTHORITY || "consumers") : undefined,
    email: profile.email || prev.email || "",
    name: profile.name || prev.name || "",
    access_token: tokenData.access_token,
    refresh_token: tokenData.refresh_token || prev.refresh_token,
    expires_at: Date.now() + (Number(tokenData.expires_in || 3600) - 300) * 1000,
    connected_at: new Date().toISOString(),
  });
  await store().set(`owner:${await sha(ownerKey)}`, id);
  const back = ret && ret.startsWith("/") && !ret.startsWith("//") ? ret : "/host";
  return Response.redirect(`${origin}${back}#key=${ownerKey}`, 302);
}

function loginError(message, status = 400) {
  return new Response(
    `<meta charset="utf-8"><meta name="viewport" content="width=device-width"><p style="font:16px sans-serif;padding:24px">${String(message).replace(/</g, "&lt;")}</p><p style="padding:0 24px;font:16px sans-serif"><a href="/host">돌아가기</a></p>`,
    { status, headers: { "content-type": "text/html; charset=utf-8" } }
  );
}

// ───────── 요청 처리 ─────────

export default async (req) => {
  const url = new URL(req.url);
  const route = url.pathname.replace(/^\/api\/?/, "").replace(/\/+$/, "");
  const msRedirect = `${url.origin}/api/callback`;
  const gRedirect = `${url.origin}/api/callback/google`;

  try {
    const body = req.method === "POST" && route !== "upload-file" ? await req.json().catch(() => ({})) : {};

    switch (`${req.method} ${route}`) {
      // ── 로그인 (현장 주인) ──
      case "GET config":
        return json({
          ms: Boolean(process.env.MS_CLIENT_ID && process.env.MS_CLIENT_SECRET),
          msWork: (process.env.MS_AUTHORITY || "consumers") !== "consumers",
          google: Boolean(process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_SECRET),
          googlePicker: Boolean(process.env.GOOGLE_API_KEY && process.env.GOOGLE_APP_ID),
        });

      case "GET auth/start": {
        const provider = url.searchParams.get("provider") === "google" ? "google" : "ms";
        const state = randomKey(24);
        await store().setJSON(`oauth:${state}`, { provider, at: Date.now(), ret: url.searchParams.get("return") || "/host" });
        if (provider === "google") {
          const q = new URLSearchParams({
            client_id: env("GOOGLE_CLIENT_ID"), redirect_uri: gRedirect, response_type: "code",
            scope: G_SCOPE, access_type: "offline", prompt: "consent select_account",
            include_granted_scopes: "true", state,
          });
          return Response.redirect(`${G_AUTH}?${q}`, 302);
        }
        const q = new URLSearchParams({
          client_id: env("MS_CLIENT_ID"), response_type: "code", redirect_uri: msRedirect,
          response_mode: "query", scope: MS_SCOPE, state, prompt: "select_account",
        });
        return Response.redirect(`${msAuthUrl(process.env.MS_AUTHORITY || "consumers")}/authorize?${q}`, 302);
      }

      // Microsoft 로그인 후 돌아오는 곳 (관리자 연결 + 현장 주인 로그인 공용)
      case "GET callback": {
        const error = url.searchParams.get("error_description") || url.searchParams.get("error");
        if (error) throw new HttpError(400, `Microsoft 로그인이 취소되었거나 실패했어요: ${error}`);
        const state = url.searchParams.get("state") || "";
        const hostState = state && (await store().get(`oauth:${state}`, { type: "json" }));
        if (hostState) {
          await store().delete(`oauth:${state}`);
          if (Date.now() - hostState.at > 15 * 60 * 1000) throw new HttpError(400, "로그인 요청이 만료되었어요. 다시 시도하세요");
          const authority = process.env.MS_AUTHORITY || "consumers";
          const data = await msToken(authority, { grant_type: "authorization_code", code: url.searchParams.get("code"), redirect_uri: msRedirect });
          const me = await graph(data.access_token, "GET", "/me?$select=id,displayName,mail,userPrincipalName");
          return finishLogin("ms", data, {
            id: me.data?.id, email: me.data?.mail || me.data?.userPrincipalName || "", name: me.data?.displayName || "",
          }, hostState.ret, url.origin);
        }
        // 관리자 페이지에서 시작한 예전 방식 연결
        const saved = await store().get("oauth_state", { type: "json" });
        if (!saved || saved.state !== state || Date.now() - saved.at > 15 * 60 * 1000) {
          throw new HttpError(400, "로그인 요청이 만료되었어요. 다시 시도하세요");
        }
        await store().delete("oauth_state");
        const data = await msToken("consumers", { grant_type: "authorization_code", code: url.searchParams.get("code"), redirect_uri: msRedirect });
        const me = await graph(data.access_token, "GET", "/me?$select=displayName,userPrincipalName,mail");
        const prev = (await getAccount("legacy")) || {};
        await saveAccount("legacy", {
          ...prev, provider: "ms", authority: "consumers",
          account: me.data?.mail || me.data?.userPrincipalName || me.data?.displayName || "Microsoft 계정",
          connected_at: new Date().toISOString(),
          access_token: data.access_token, refresh_token: data.refresh_token || prev.refresh_token,
          expires_at: Date.now() + (Number(data.expires_in || 3600) - 300) * 1000,
        });
        await msEnsurePath(data.access_token, [ROOT]);
        return Response.redirect(`${url.origin}/?connected=1`, 302);
      }

      case "GET callback/google": {
        const error = url.searchParams.get("error");
        if (error) throw new HttpError(400, `Google 로그인이 취소되었거나 실패했어요: ${error}`);
        const state = url.searchParams.get("state") || "";
        const hostState = state && (await store().get(`oauth:${state}`, { type: "json" }));
        if (!hostState || Date.now() - hostState.at > 15 * 60 * 1000) throw new HttpError(400, "로그인 요청이 만료되었어요. 다시 시도하세요");
        await store().delete(`oauth:${state}`);
        const data = await googleToken({ grant_type: "authorization_code", code: url.searchParams.get("code"), redirect_uri: gRedirect });
        const info = await fetch("https://openidconnect.googleapis.com/v1/userinfo", {
          headers: { authorization: `Bearer ${data.access_token}` },
        }).then((r) => r.json()).catch(() => ({}));
        return finishLogin("google", data, { id: info.sub, email: info.email, name: info.name }, hostState.ret, url.origin);
      }

      // ── 현장 주인 관리 ──
      case "GET host/me": {
        const { id, acct } = await ownerAccount(req);
        const sites = Object.entries(await getSites())
          .filter(([, s]) => s.account === id)
          .map(([code, s]) => ({ code, name: s.name, folderPath: s.folderPath, created: s.created }))
          .sort((a, b) => byName(a.name, b.name));
        return json({ provider: acct.provider, email: acct.email, name: acct.name, sites });
      }

      // OneDrive 폴더 둘러보기 (폴더 고르기용)
      case "GET host/browse": {
        const { id, acct } = await ownerAccount(req);
        if (acct.provider !== "ms") throw new HttpError(400, "OneDrive 계정에서만 쓸 수 있어요");
        const token = await accessTokenFor(id);
        const itemId = url.searchParams.get("id");
        const base = itemId ? `/me/drive/items/${encodeURIComponent(itemId)}` : "/me/drive/root";
        const item = await graph(token, "GET", `${base}?$select=id,name,parentReference,root`);
        if (!item.res.ok) throw new HttpError(item.res.status === 404 ? 404 : 502, `OneDrive 오류: ${item.data?.error?.message || item.res.status}`);
        const kids = await graph(token, "GET", `${base}/children?$select=id,name,folder&$top=500`);
        if (!kids.res.ok) throw new HttpError(502, `OneDrive 오류: ${kids.data?.error?.message || kids.res.status}`);
        const parentPath = (item.data.parentReference?.path || "").replace(/^\/drive\/root:?/, "");
        const isRoot = Boolean(item.data.root) || !itemId;
        return json({
          id: item.data.id,
          name: isRoot ? "내 OneDrive" : item.data.name,
          path: isRoot ? "/" : `${decodeURIComponent(parentPath)}/${item.data.name}`,
          parentId: isRoot ? null : item.data.parentReference?.id || null,
          folders: (kids.data.value || []).filter((k) => k.folder).map((k) => ({ id: k.id, name: k.name })).sort((a, b) => byName(a.name, b.name)),
        });
      }

      // 새 폴더 만들기 (OneDrive: 지금 보는 폴더 안 / 구글: 내 드라이브 맨 위)
      case "POST host/mkdir": {
        const { id, acct } = await ownerAccount(req);
        const name = cleanName(body.name, "폴더 이름");
        const token = await accessTokenFor(id);
        if (acct.provider === "google") {
          const { res, data } = await gapi(token, "POST", `${DRIVE}/files?fields=id,name&supportsAllDrives=true`, {
            name, mimeType: FOLDER_MIME, parents: [body.parentId || "root"],
          });
          if (!res.ok) throw gErr(res, data, "폴더를 만들 수 없어요");
          return json({ id: data.id, name: data.name, path: `/${data.name}` });
        }
        const parent = body.parentId ? `/me/drive/items/${encodeURIComponent(body.parentId)}` : "/me/drive/root";
        const { res, data } = await graph(token, "POST", `${parent}/children`, {
          name, folder: {}, "@microsoft.graph.conflictBehavior": "fail",
        });
        if (res.status === 409) throw new HttpError(409, "같은 이름의 폴더가 이미 있어요");
        if (!res.ok) throw new HttpError(502, `폴더를 만들 수 없어요: ${data?.error?.message || res.status}`);
        return json({ id: data.id, name: data.name });
      }

      // 구글 드라이브 폴더 고르기 창(Picker)용 정보
      case "GET host/gpicker": {
        const { id, acct } = await ownerAccount(req);
        if (acct.provider !== "google") throw new HttpError(400, "구글 계정에서만 쓸 수 있어요");
        return json({
          accessToken: await accessTokenFor(id),
          apiKey: env("GOOGLE_API_KEY"),
          appId: env("GOOGLE_APP_ID"),
        });
      }

      case "POST host/sites": {
        const { id, acct } = await ownerAccount(req);
        const name = cleanName(body.name, "현장 이름");
        const folders = cleanFolders(body.folders);
        const folderId = String(body.folderId || "").trim();
        if (!folderId) throw new HttpError(400, "사진을 모을 폴더를 고르세요");
        const token = await accessTokenFor(id);
        // 고른 폴더에 접근 가능한지 확인
        if (acct.provider === "google") {
          const { res, data } = await gapi(token, "GET", `${DRIVE}/files/${encodeURIComponent(folderId)}?fields=id,name,mimeType&supportsAllDrives=true`);
          if (!res.ok || data?.mimeType !== FOLDER_MIME) throw new HttpError(400, "고른 폴더에 접근할 수 없어요. 폴더를 다시 골라 주세요");
        } else {
          const { res, data } = await graph(token, "GET", `/me/drive/items/${encodeURIComponent(folderId)}?$select=id,folder`);
          if (!res.ok || !data?.folder) throw new HttpError(400, "고른 폴더에 접근할 수 없어요. 폴더를 다시 골라 주세요");
        }
        const sites = await getSites();
        const code = newCode(sites);
        const site = {
          name, provider: acct.provider, account: id, folderId,
          folderPath: String(body.folderPath || "").slice(0, 300),
          created: new Date().toISOString(),
        };
        for (const f of folders) await ensureSub({ code, ...site }, f);
        sites[code] = site;
        await store().setJSON("sites", sites);
        return json({ code, name });
      }

      case "DELETE host/sites": {
        const { id } = await ownerAccount(req);
        const code = String(url.searchParams.get("code") || "").toUpperCase();
        const sites = await getSites();
        if (!sites[code] || sites[code].account !== id) throw new HttpError(404, "내 현장이 아니에요");
        delete sites[code];
        await store().setJSON("sites", sites);
        return json({ ok: true });
      }

      // ── 관리자 페이지 (예전 방식) ──
      case "GET login": {
        requireAdmin(req, url);
        const state = randomKey(24);
        await store().setJSON("oauth_state", { state, at: Date.now() });
        const q = new URLSearchParams({
          client_id: env("MS_CLIENT_ID"), response_type: "code", redirect_uri: msRedirect,
          response_mode: "query", scope: MS_SCOPE, state, prompt: "select_account",
        });
        return Response.redirect(`${msAuthUrl("consumers")}/authorize?${q}`, 302);
      }

      case "GET admin/status": {
        requireAdmin(req, url);
        const auth = await getAccount("legacy");
        const sites = Object.entries(await getSites())
          .filter(([, s]) => !s.account)
          .map(([code, s]) => ({ code, ...s }))
          .sort((a, b) => byName(a.name, b.name));
        return json({ connected: Boolean(auth?.refresh_token), account: auth?.account || null, root: ROOT, sites });
      }

      case "POST admin/sites": {
        requireAdmin(req, url);
        const name = cleanName(body.name, "현장 이름");
        const folders = cleanFolders(body.folders);
        const sites = await getSites();
        if (Object.values(sites).some((s) => !s.account && s.name === name)) throw new HttpError(409, "같은 이름의 현장이 이미 있어요");
        const token = await accessTokenFor("legacy");
        await msEnsurePath(token, [ROOT, name]);
        for (const folder of folders) await msEnsurePath(token, [ROOT, name, ...folder], 2);
        const code = newCode(sites);
        sites[code] = { name, created: new Date().toISOString() };
        await store().setJSON("sites", sites);
        return json({ code, name });
      }

      case "DELETE admin/sites": {
        requireAdmin(req, url);
        const sites = await getSites();
        delete sites[String(url.searchParams.get("code") || "").toUpperCase()];
        await store().setJSON("sites", sites);
        return json({ ok: true });
      }

      // ── 앱 / 웹앱 ──
      case "POST join": {
        const site = await siteByCode(body.code);
        return json({ code: site.code, name: site.name, provider: site.provider });
      }

      // 폴더 목록: path 가 있으면 그 폴더 안의 하위 폴더 (subs = 각 폴더의 하위 폴더 수)
      case "GET folders": {
        const site = await siteByCode(url.searchParams.get("code"));
        const segs = optPath(url.searchParams.get("path"));
        const { folders, subs } = await listFolders(site, segs);
        return json({ name: site.name, path: segs.join("/"), folders, subs });
      }

      // 새 폴더: parent 가 있으면 그 안에
      case "POST folders": {
        const site = await siteByCode(body.code);
        const name = cleanName(body.name, "폴더 이름");
        const segs = [...optPath(body.parent), name];
        await ensureSub(site, segs);
        return json({ ok: true, name, path: segs.join("/") });
      }

      case "DELETE folders": {
        const site = await siteByCode(url.searchParams.get("code"));
        await deleteFolder(site, cleanPath(url.searchParams.get("name")));
        return json({ ok: true });
      }

      case "GET photos": {
        const site = await siteByCode(url.searchParams.get("code"));
        return json({ photos: await listPhotos(site, cleanPath(url.searchParams.get("folder"))) });
      }

      // 구글 드라이브 사진 미리보기 (로그인 없이 볼 수 있게 서버가 대신 가져옴)
      case "GET gthumb": {
        const site = await siteByCode(url.searchParams.get("code"));
        if (site.provider !== "google") throw new HttpError(400, "구글 드라이브 현장이 아니에요");
        const size = Math.min(4000, Math.max(100, Number(url.searchParams.get("s")) || 400));
        const token = await accessTokenFor(site.account);
        const fileId = encodeURIComponent(url.searchParams.get("id") || "");
        const meta = await gapi(token, "GET", `${DRIVE}/files/${fileId}?fields=thumbnailLink,mimeType&supportsAllDrives=true`);
        if (!meta.res.ok) throw gErr(meta.res, meta.data);
        let img;
        if (meta.data.thumbnailLink) {
          img = await fetch(meta.data.thumbnailLink.replace(/=s\d+$/, `=s${size}`), { headers: { authorization: `Bearer ${token}` } });
        }
        if (!img || !img.ok) {
          img = await fetch(`${DRIVE}/files/${fileId}?alt=media&supportsAllDrives=true`, { headers: { authorization: `Bearer ${token}` } });
        }
        if (!img.ok) throw new HttpError(502, "사진을 불러올 수 없어요");
        return new Response(await img.arrayBuffer(), {
          headers: { "content-type": img.headers.get("content-type") || "image/jpeg", "cache-control": "private, max-age=3600" },
        });
      }

      case "POST upload": {
        const site = await siteByCode(body.code);
        const folder = cleanPath(body.folder);
        const fileName = cleanName(body.fileName, "파일 이름");
        if (!IMAGE_EXT.test(fileName)) throw new HttpError(400, "사진 파일만 올릴 수 있어요");
        const origin = req.headers.get("origin") || "";
        return json({ uploadUrl: await createUploadSession(site, folder, fileName, origin) });
      }

      case "POST upload-file": {
        const site = await siteByCode(url.searchParams.get("code"));
        const folder = cleanPath(url.searchParams.get("folder"));
        const fileName = cleanName(url.searchParams.get("name"), "파일 이름");
        if (!IMAGE_EXT.test(fileName)) throw new HttpError(400, "사진 파일만 올릴 수 있어요");
        const bytes = await req.arrayBuffer();
        if (!bytes.byteLength) throw new HttpError(400, "빈 파일이에요");
        if (bytes.byteLength > 5.5 * 1024 * 1024) throw new HttpError(413, "사진이 너무 커요");
        await uploadSmall(site, folder, fileName, bytes, req.headers.get("content-type") || "image/jpeg");
        return json({ ok: true });
      }

      default:
        throw new HttpError(404, "없는 주소예요");
    }
  } catch (e) {
    const status = e.status || 500;
    if (status >= 500) console.error(e);
    if (route.startsWith("callback") || route === "auth/start") return loginError(e.message, status);
    return json({ error: e.message || "서버 오류" }, status);
  }
};

export const config = { path: "/api/*" };
