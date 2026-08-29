// Central API client for the RAG backend.
// Uses the dev tunnel backend URL. For local dev, change to "http://localhost:8080".
const BASE_URL = "https://8r5fjqqj-8080.inc1.devtunnels.ms";

// --- token storage ---
export const getToken = () => localStorage.getItem("token");
export const setToken = (t) => localStorage.setItem("token", t);
export const clearToken = () => localStorage.removeItem("token");

// --- core request helper ---
async function request(path, { method = "GET", body, isForm = false } = {}) {
  const headers = {};
  const token = getToken();
  if (token) headers["Authorization"] = `Bearer ${token}`;

  let payload = body;
  if (body && !isForm) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }

  const res = await fetch(`${BASE_URL}${path}`, { method, headers, body: payload });

  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const err = await res.json();
      message = err.message || err.error || message;
    } catch {
      // response wasn't JSON
    }
    throw new Error(message);
  }

  // some endpoints return plain text (e.g. /ask), others JSON
  const contentType = res.headers.get("content-type") || "";
  return contentType.includes("application/json") ? res.json() : res.text();
}

// --- Auth ---
export const signup = (email, password, name) =>
  request("/auth/signup", { method: "POST", body: { email, password, name } });

export const verifyOtp = (email, otp) =>
  request("/auth/verify-otp", { method: "POST", body: { email, otp } });

export const signin = (email, password) =>
  request("/auth/signin", { method: "POST", body: { email, password } });

export const forgotPassword = (email) =>
  request("/auth/forgot-password", { method: "POST", body: { email } });

export const resetPassword = (email, otp, newPassword) =>
  request("/auth/reset-password", { method: "POST", body: { email, otp, newPassword } });

// --- Conversations ---
export const createConversation = (title) =>
  request("/api/v1/conversations", { method: "POST", body: { title } });

export const listConversations = () =>
  request("/api/v1/conversations");

export const getMessages = (conversationId) =>
  request(`/api/v1/conversations/${conversationId}/messages`);

export const deleteConversation = (conversationId) =>
  request(`/api/v1/conversations/${conversationId}`, { method: "DELETE" });

// --- RAG ---
export const ask = (question, conversationId) =>
  request(`/ask?question=${encodeURIComponent(question)}&conversationId=${conversationId}`);

// Streaming ask: calls onToken(chunk) for each piece as it arrives.
// Uses fetch + ReadableStream because EventSource can't send Authorization headers.
export async function askStream(question, conversationId, onToken) {
  const token = getToken();
  const url = `${BASE_URL}/ask/stream?question=${encodeURIComponent(question)}&conversationId=${conversationId}`;

  const res = await fetch(url, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "text/event-stream",
    },
  });

  if (!res.ok) {
    throw new Error(`Stream failed (${res.status})`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });

    // SSE frames are separated by a blank line; each data line starts with "data:"
    const frames = buffer.split("\n\n");
    buffer = frames.pop(); // keep the last (possibly incomplete) frame

    for (const frame of frames) {
      for (const line of frame.split("\n")) {
        if (line.startsWith("data:")) {
          // The backend Base64-encodes each token to preserve whitespace exactly.
          const b64 = line.slice(5).trim();
          if (b64) {
            // decode Base64 -> UTF-8 string
            const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
            const chunk = new TextDecoder().decode(bytes);
            onToken(chunk);
          }
        }
      }
    }
  }
}

// --- File upload ---
export const uploadFile = (file, visibility) => {
  const form = new FormData();
  form.append("file", file);
  form.append("visibility", visibility);
  return request("/upload", { method: "POST", body: form, isForm: true });
};
