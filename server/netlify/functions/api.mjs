// 폴더 카메라 중계 서버
//  - 관리자(OneDrive 주인)가 한 번 로그인하면, 그 권한은 이 서버에만 보관됨
//  - 현장 사람들은 초대 코드만으로 "업로드 주소"를 받아 폰에서 OneDrive로 직접 업로드
//
// 필요한 환경 변수 (Netlify > Site configuration > Environment variables)
//   MS_CLIENT_ID      Azure 앱 등록의 "애플리케이션(클라이언트) ID"
//   MS_CLIENT_SECRET  Azure 앱 등록의 "클라이언트 암호" 값
//   ADMIN_PASSWORD    관리자 페이지 비밀번호 (마음대로 정하기)

import { getStore } from "@netlify/blobs";

const AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
const GRAPH = "https://graph.microsoft.com/v1.0";
const SCOPE = "offline_access Files.ReadWrite User.Read";
const ROOT = "폴더카메라"; // OneDrive 안의 최상위 폴더 이름
const BAD_CHARS = /[\\/:*?"<>|#%\u0000-\u001f]/;
const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

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

function cleanName(raw, what = "이름") {
  const name = String(raw ?? "").trim().replace(/\s+/g, " ");
  if (!name) throw new HttpError(400, `${what}을(를) 입력하세요`);
  if (name.length > 80) throw new HttpError(400, `${what}이(가) 너무 길어요`);
  if (BAD_CHARS.test(name) || name.startsWith(".") || name.endsWith(".")) {
    throw new HttpError(400, `${what}에 쓸 수 없는 문자가 있어요 ( / \\ : * ? " < > | # % )`);
  }
  return name;
}

function env(key) {
  const value = process.env[key];
  if (!value) throw new HttpError(500, `${key} 환경 변수가 설정되지 않았어요`);
  return value;
}

function requireAdmin(req, url) {
  const given = req.headers.get("x-admin-password") ?? url.searchParams.get("pw") ?? "";
  if (given !== env("ADMIN_PASSWORD")) throw new HttpError(401, "관리자 비밀번호가 틀렸어요");
}

// ───────── Microsoft 로그인 / 토큰 ─────────

async function tokenRequest(params) {
  const body = new URLSearchParams({
    client_id: env("MS_CLIENT_ID"),
    client_secret: env("MS_CLIENT_SECRET"),
    scope: SCOPE,
    ...params,
  });
  const res = await fetch(`${AUTH}/token`, { method: "POST", body });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new HttpError(502, `Microsoft 로그인 오류: ${data.error_description || data.error || res.status}`);
  }
  return data;
}

async function saveToken(data, extra = {}) {
  const s = store();
  const prev = (await s.get("auth", { type: "json" })) || {};
  const record = {
    ...prev,
    ...extra,
    access_token: data.access_token,
    refresh_token: data.refresh_token || prev.refresh_token,
    expires_at: Date.now() + (Number(data.expires_in || 3600) - 300) * 1000,
  };
  await s.setJSON("auth", record);
  return record;
}

async function accessToken() {
  const record = await store().get("auth", { type: "json" });
  if (!record?.refresh_token) throw new HttpError(503, "관리자가 아직 OneDrive를 연결하지 않았어요");
  if (record.access_token && record.expires_at > Date.now()) return record.access_token;
  const data = await tokenRequest({ grant_type: "refresh_token", refresh_token: record.refresh_token });
  return (await saveToken(data)).access_token;
}

async function graph(method, path, body, token) {
  const auth = token || (await accessToken());
  const res = await fetch(GRAPH + path, {
    method,
    headers: {
      authorization: `Bearer ${auth}`,
      ...(body ? { "content-type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  return { res, data };
}

/** OneDrive에 폴더 경로를 (없으면) 차례로 만듦. segments[from]부터 생성 시도 */
async function ensureFolder(segments, from = 0) {
  for (let i = from; i < segments.length; i++) {
    const parent = i === 0 ? "/me/drive/root" : `/me/drive/root:/${enc(segments.slice(0, i))}:`;
    const { res, data } = await graph("POST", `${parent}/children`, {
      name: segments[i],
      folder: {},
      "@microsoft.graph.conflictBehavior": "fail",
    });
    if (!res.ok && res.status !== 409) {
      throw new HttpError(502, `OneDrive 폴더를 만들 수 없어요: ${data?.error?.message || res.status}`);
    }
  }
}

// ───────── 현장(초대 코드) ─────────

async function getSites() {
  return (await store().get("sites", { type: "json" })) || {};
}

async function siteByCode(rawCode) {
  const code = String(rawCode ?? "").trim().toUpperCase();
  const site = (await getSites())[code];
  if (!site) throw new HttpError(404, "초대 코드가 올바르지 않거나 삭제된 현장이에요");
  return { code, ...site };
}

function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return Array.from(bytes, (b) => CODE_CHARS[b % CODE_CHARS.length]).join("");
}

async function listFolders(site) {
  const path = `/me/drive/root:/${enc([ROOT, site.name])}:/children?$select=name,folder&$top=999`;
  const { res, data } = await graph("GET", path);
  if (res.status === 404) {
    await ensureFolder([ROOT, site.name]);
    return [];
  }
  if (!res.ok) throw new HttpError(502, `OneDrive 목록 오류: ${data?.error?.message || res.status}`);
  return (data.value || [])
    .filter((item) => item.folder)
    .map((item) => item.name)
    .sort((a, b) => a.localeCompare(b, "ko", { numeric: true }));
}

async function createUploadSession(site, folder, fileName) {
  const path = `/me/drive/root:/${enc([ROOT, site.name, folder, fileName])}:/createUploadSession`;
  const body = { item: { "@microsoft.graph.conflictBehavior": "rename" } };
  let { res, data } = await graph("POST", path, body);
  if (res.status === 404) {
    await ensureFolder([ROOT, site.name, folder]);
    ({ res, data } = await graph("POST", path, body));
  }
  if (!res.ok || !data?.uploadUrl) {
    throw new HttpError(502, `업로드 준비 실패: ${data?.error?.message || res.status}`);
  }
  return data.uploadUrl;
}

// ───────── 요청 처리 ─────────

export default async (req) => {
  const url = new URL(req.url);
  const route = url.pathname.replace(/^\/api\/?/, "").replace(/\/+$/, "");
  const redirectUri = `${url.origin}/api/callback`;

  try {
    const body = req.method === "POST" && route !== "upload-file" ? await req.json().catch(() => ({})) : {};

    switch (`${req.method} ${route}`) {
      // 관리자: OneDrive 연결 시작
      case "GET login": {
        requireAdmin(req, url);
        const state = crypto.randomUUID();
        await store().setJSON("oauth_state", { state, at: Date.now() });
        const q = new URLSearchParams({
          client_id: env("MS_CLIENT_ID"),
          response_type: "code",
          redirect_uri: redirectUri,
          response_mode: "query",
          scope: SCOPE,
          state,
          prompt: "select_account",
        });
        return Response.redirect(`${AUTH}/authorize?${q}`, 302);
      }

      // Microsoft 로그인 후 돌아오는 곳
      case "GET callback": {
        const error = url.searchParams.get("error_description") || url.searchParams.get("error");
        if (error) throw new HttpError(400, `Microsoft 로그인이 취소되었거나 실패했어요: ${error}`);
        const saved = await store().get("oauth_state", { type: "json" });
        if (!saved || saved.state !== url.searchParams.get("state") || Date.now() - saved.at > 15 * 60 * 1000) {
          throw new HttpError(400, "로그인 요청이 만료되었어요. 관리자 페이지에서 다시 시도하세요");
        }
        await store().delete("oauth_state");
        const data = await tokenRequest({
          grant_type: "authorization_code",
          code: url.searchParams.get("code"),
          redirect_uri: redirectUri,
        });
        const me = await graph("GET", "/me?$select=displayName,userPrincipalName,mail", null, data.access_token);
        const account = me.data?.mail || me.data?.userPrincipalName || me.data?.displayName || "Microsoft 계정";
        await saveToken(data, { account, connected_at: new Date().toISOString() });
        await ensureFolder([ROOT]);
        return Response.redirect(`${url.origin}/?connected=1`, 302);
      }

      // 관리자: 상태 + 현장 목록
      case "GET admin/status": {
        requireAdmin(req, url);
        const auth = await store().get("auth", { type: "json" });
        const sites = Object.entries(await getSites())
          .map(([code, s]) => ({ code, ...s }))
          .sort((a, b) => a.name.localeCompare(b.name, "ko", { numeric: true }));
        return json({ connected: Boolean(auth?.refresh_token), account: auth?.account || null, root: ROOT, sites });
      }

      // 관리자: 현장 만들기 (+ 기본 폴더)
      case "POST admin/sites": {
        requireAdmin(req, url);
        const name = cleanName(body.name, "현장 이름");
        const folders = [...new Set((body.folders || []).map((f) => String(f).trim()).filter(Boolean))].map((f) =>
          cleanName(f, `폴더 이름 "${f}"`)
        );
        const sites = await getSites();
        if (Object.values(sites).some((s) => s.name === name)) throw new HttpError(409, "같은 이름의 현장이 이미 있어요");
        await ensureFolder([ROOT, name]);
        for (const folder of folders) await ensureFolder([ROOT, name, folder], 2);
        let code = newCode();
        while (sites[code]) code = newCode();
        sites[code] = { name, created: new Date().toISOString() };
        await store().setJSON("sites", sites);
        return json({ code, name });
      }

      // 관리자: 현장 삭제 (초대 코드만 막음, OneDrive 사진은 그대로)
      case "DELETE admin/sites": {
        requireAdmin(req, url);
        const sites = await getSites();
        delete sites[String(url.searchParams.get("code") || "").toUpperCase()];
        await store().setJSON("sites", sites);
        return json({ ok: true });
      }

      // 앱: 초대 코드 확인
      case "POST join": {
        const site = await siteByCode(body.code);
        return json({ code: site.code, name: site.name });
      }

      // 앱: 현장 폴더 목록
      case "GET folders": {
        const site = await siteByCode(url.searchParams.get("code"));
        return json({ name: site.name, folders: await listFolders(site) });
      }

      // 앱: 현장에 새 폴더
      case "POST folders": {
        const site = await siteByCode(body.code);
        const folder = cleanName(body.name, "폴더 이름");
        await ensureFolder([ROOT, site.name, folder]);
        return json({ ok: true, name: folder });
      }

      // 웹앱: 폴더 안 사진 목록 (모든 참여자가 올린 사진, 미리보기 주소 포함)
      case "GET photos": {
        const site = await siteByCode(url.searchParams.get("code"));
        const folder = cleanName(url.searchParams.get("folder"), "폴더 이름");
        const path =
          `/me/drive/root:/${enc([ROOT, site.name, folder])}:/children` +
          `?$top=200&$expand=thumbnails`;
        const { res, data } = await graph("GET", path);
        if (res.status === 404) return json({ photos: [] });
        if (!res.ok) throw new HttpError(502, `OneDrive 목록 오류: ${data?.error?.message || res.status}`);
        const photos = (data.value || [])
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
        return json({ photos });
      }

      // 웹앱: 브라우저가 OneDrive로 직접 못 올릴 때 서버를 거쳐 올리기 (최대 약 5MB)
      case "POST upload-file": {
        const site = await siteByCode(url.searchParams.get("code"));
        const folder = cleanName(url.searchParams.get("folder"), "폴더 이름");
        const fileName = cleanName(url.searchParams.get("name"), "파일 이름");
        if (!/\.(jpe?g|png|heic|heif|webp)$/i.test(fileName)) throw new HttpError(400, "사진 파일만 올릴 수 있어요");
        const bytes = await req.arrayBuffer();
        if (!bytes.byteLength) throw new HttpError(400, "빈 파일이에요");
        if (bytes.byteLength > 5.5 * 1024 * 1024) throw new HttpError(413, "사진이 너무 커요");
        const target =
          `${GRAPH}/me/drive/root:/${enc([ROOT, site.name, folder, fileName])}:/content` +
          `?@microsoft.graph.conflictBehavior=rename`;
        const put = async () =>
          fetch(target, {
            method: "PUT",
            headers: {
              authorization: `Bearer ${await accessToken()}`,
              "content-type": req.headers.get("content-type") || "image/jpeg",
            },
            body: bytes,
          });
        let res = await put();
        if (res.status === 404) {
          await ensureFolder([ROOT, site.name, folder]);
          res = await put();
        }
        if (!res.ok) {
          const data = await res.json().catch(() => null);
          throw new HttpError(502, `업로드 실패: ${data?.error?.message || res.status}`);
        }
        return json({ ok: true });
      }

      // 앱: 현장 폴더 삭제 (비어 있는 폴더만 — 다른 사람 사진 보호)
      case "DELETE folders": {
        const site = await siteByCode(url.searchParams.get("code"));
        const folder = cleanName(url.searchParams.get("name"), "폴더 이름");
        const base = `/me/drive/root:/${enc([ROOT, site.name, folder])}:`;
        const { res, data } = await graph("GET", `${base}/children?$top=1&$select=name`);
        if (res.status === 404) return json({ ok: true });
        if (!res.ok) throw new HttpError(502, `OneDrive 오류: ${data?.error?.message || res.status}`);
        if ((data.value || []).length > 0) {
          throw new HttpError(409, "사진이 들어 있는 폴더는 지울 수 없어요. 관리자가 OneDrive에서 정리해 주세요");
        }
        const del = await graph("DELETE", base);
        if (!del.res.ok && del.res.status !== 404) {
          throw new HttpError(502, `폴더 삭제 실패: ${del.data?.error?.message || del.res.status}`);
        }
        return json({ ok: true });
      }

      // 앱: 사진 업로드 주소 발급 (사진은 폰 → OneDrive로 직접 전송됨)
      case "POST upload": {
        const site = await siteByCode(body.code);
        const folder = cleanName(body.folder, "폴더 이름");
        const fileName = cleanName(body.fileName, "파일 이름");
        if (!/\.(jpe?g|png|heic|heif|webp)$/i.test(fileName)) throw new HttpError(400, "사진 파일만 올릴 수 있어요");
        return json({ uploadUrl: await createUploadSession(site, folder, fileName) });
      }

      default:
        throw new HttpError(404, "없는 주소예요");
    }
  } catch (e) {
    const status = e.status || 500;
    if (status >= 500) console.error(e);
    if (route === "callback") {
      return new Response(
        `<meta charset="utf-8"><meta name="viewport" content="width=device-width"><p style="font:16px sans-serif;padding:24px">${String(
          e.message
        ).replace(/</g, "&lt;")}</p><p style="padding:0 24px"><a href="/">관리자 페이지로</a></p>`,
        { status, headers: { "content-type": "text/html; charset=utf-8" } }
      );
    }
    return json({ error: e.message || "서버 오류" }, status);
  }
};

export const config = { path: "/api/*" };
