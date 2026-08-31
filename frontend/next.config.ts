import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Emit a self-contained server bundle at .next/standalone (server.js + minimal
  // node_modules) that the Docker runner stage copies and runs via `node server.js`.
  output: "standalone",
  reactCompiler: true,
};

export default nextConfig;
