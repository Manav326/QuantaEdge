import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  output: 'standalone',
  // Explicitly trust local loopback aliases during development only; production is unchanged.
  allowedDevOrigins: ['localhost', '127.0.0.1'],
  async rewrites() {
    // Docker Compose supplies BACKEND_INTERNAL_URL=http://api:8080. For a web dev
    // server launched directly on the host, use the published loopback API port instead:
    // the Compose service name "api" is not resolvable from the host OS.
    const backendUrl = process.env.BACKEND_INTERNAL_URL ?? 'http://localhost:8080';
    return [{
      source: '/api/:path*',
      destination: `${backendUrl}/api/:path*`,
    }];
  },
};

export default nextConfig;
