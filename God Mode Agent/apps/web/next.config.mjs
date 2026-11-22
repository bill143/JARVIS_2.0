/** @type {import('next').NextConfig} */

// The browser always talks to the web origin ("/api", see lib/api.ts); this
// rewrite is what forwards those calls to the FastAPI backend. Keeping the
// backend origin server-side means no absolute host is ever baked into the
// client bundle, so the same build works on localhost and over the tailnet.
const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN ?? "http://127.0.0.1:8000";

const nextConfig = {
  reactStrictMode: true,
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${BACKEND_ORIGIN}/:path*` }];
  },
};

export default nextConfig;
