// 统一 API client：契约见 docs/api-contract.md
// 错误统一抛 ApiError(message, status)，业务代码 catch 后可直接展示 message

// 前后端分离打包：构建时设 VITE_API_BASE=http://127.0.0.1:5000（后端地址），
// dist 即可由任意静态服务器托管；未设置 = 同源部署（dist 由后端托管或反向代理），行为不变。
// 跨源时需后端配置 CORS_ORIGINS 允许前端来源，且 Cookie 走 include。
export const API_BASE = ((import.meta.env.VITE_API_BASE as string | undefined) ?? "").replace(/\/+$/, "");

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export class UnauthorizedError extends ApiError {
  constructor(message = "用户未登录") {
    super(401, message);
  }
}

type Options = {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  body?: unknown;
  formData?: FormData;
  signal?: AbortSignal;
};

async function request<T>(path: string, opts: Options = {}): Promise<T> {
  const crossOrigin = API_BASE !== "" && API_BASE !== globalThis.location.origin;
  const init: RequestInit = {
    method: opts.method ?? "GET",
    credentials: crossOrigin ? "include" : "same-origin",
    signal: opts.signal,
  };
  if (opts.formData) {
    init.body = opts.formData;
  } else if (opts.body !== undefined) {
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(opts.body);
  }
  const res = await fetch(API_BASE + path, init);
  if (res.status === 401) {
    throw new UnauthorizedError();
  }
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) {
    let msg = `请求失败（${res.status}）`;
    if (data && typeof data === "object" && "message" in data) {
      const m = (data as { message: unknown }).message;
      if (typeof m === "string" && m) msg = m;
    }
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: "POST", body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: "PATCH", body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: "PUT", body }),
  delete: <T>(path: string) => request<T>(path, { method: "DELETE" }),
  upload: <T>(path: string, formData: FormData) => request<T>(path, { method: "POST", formData }),
};
