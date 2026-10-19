import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  output: 'standalone',
  // Explicitly trust local loopback aliases during development only; production is unchanged.
  allowedDevOrigins: ['localhost', '127.0.0.1'],
  async rewrites() {
    // Docker Compose supplies BACKEND_INTERNAL_URL=http://api:8080. For next dev
    // launched directly on the host, that Docker service name is not resolvable.
    // Keep the Compose-network target as the non-development fallback because Next
    // records rewrites in the build output; a later container env cannot replace it.
    const defaultBackendUrl = process.env.NODE_ENV === 'development'
      ? 'http://localhost:8080'
      : 'http://api:8080';
    const backendUrl = process.env.BACKEND_INTERNAL_URL ?? defaultBackendUrl;
    return [{
      source: '/api/:path*',
      destination: `${backendUrl}/api/:path*`,
    }];
  },
};

export default nextConfig;
