import { BACKEND, hasTokens } from "@/lib/api";

export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
const ALLOWED_EXTENSIONS = new Set(["txt", "md", "csv", "json", "png", "jpg", "jpeg", "webp", "pdf"]);
const ALLOWED_TYPES = new Set([
  "text/plain",
  "text/markdown",
  "application/json",
  "text/csv",
  "image/png",
  "image/jpeg",
  "image/webp",
  "application/pdf",
]);

export function sanitizeFileName(original: string) {
  const base = original.split(/[\\/]/).pop() ?? "upload.bin";
  const safe = base.replace(/[^a-zA-Z0-9._-]/g, "_").replace(/^\.+/, "") || "upload.bin";
  return safe.length > 120 ? `${safe.slice(0, 117)}.bin` : safe;
}

export function validateFile(file: File) {
  const extension = file.name.split(".").pop()?.toLowerCase() ?? "";
  if (file.size > MAX_UPLOAD_BYTES) {
    return "File exceeds the 5 MB limit.";
  }
  if (!ALLOWED_EXTENSIONS.has(extension)) {
    return "File type is not allowed.";
  }
  if (!ALLOWED_TYPES.has(file.type) && file.type !== "") {
    return "File content-type is not allowed.";
  }
  return null;
}

export function uploadFile(
  file: File,
  onProgress: (progress: number) => void,
): Promise<{ name: string; size: number; url: string; status: "success" | "error"; message?: string }> {
  return new Promise((resolve) => {
    const validationError = validateFile(file);
    if (validationError) {
      resolve({
        name: sanitizeFileName(file.name),
        size: file.size,
        url: "",
        status: "error",
        message: validationError,
      });
      return;
    }

    const safeName = sanitizeFileName(file.name);
    const body = new FormData();
    body.append("file", file, safeName);

    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${BACKEND}/uploads`);
    if (hasTokens()) {
      const token = window.sessionStorage.getItem("jarvis_access_token");
      if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    }

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        const percent = (event.loaded / event.total) * 100;
        onProgress(percent);
      }
    };

    xhr.onload = () => {
      const status = xhr.status;
      if (status >= 200 && status < 300) {
        const response = xhr.responseText ? JSON.parse(xhr.responseText) : {};
        const name = typeof response.name === "string" ? response.name : safeName;
        const url = `${BACKEND}/uploads/${encodeURIComponent(name)}`;
        resolve({ name, size: file.size, url, status: "success" });
      } else if (status === 404 || status === 501 || status === 405) {
        resolve({
          name: safeName,
          size: file.size,
          url: "",
          status: "error",
          message: "Upload endpoint not enabled.",
        });
      } else {
        resolve({
          name: safeName,
          size: file.size,
          url: "",
          status: "error",
          message: "Upload failed." ,
        });
      }
    };

    xhr.onerror = () => {
      resolve({
        name: safeName,
        size: file.size,
        url: "",
        status: "error",
        message: "Upload endpoint not enabled.",
      });
    };

    xhr.send(body);
  });
}
