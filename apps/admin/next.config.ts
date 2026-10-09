import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  output: 'standalone',
  // Explicitly trust local loopback aliases during development only; production is unchanged.
  allowedDevOrigins: ['localhost', '127.0.0.1'],
  async rewrites() {
    return [{
      source: '/api/:path*',
      destination: `${process.env.BACKEND_INTERNAL_URL ?? 'http://api:8080'}/api/:path*`,
    }];
  },
};

export default nextConfig;
