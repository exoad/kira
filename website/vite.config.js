import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  base: "./",
  build: {
    outDir: "dist",
    assetsInlineLimit: 0,
    emptyOutDir: true,
    sourcemap: false,
    chunkSizeWarningLimit: 600,
    minify: "terser",
    terserOptions: {
      compress: {
        drop_console: true,
        drop_debugger: true,
        passes: 5,
        inline: 3,
        keep_fargs: false,
        pure_getters: "strict",
        unsafe: true,
        unsafe_arrows: true,
        unsafe_comps: true,
        unsafe_math: true,
        unsafe_methods: true,
        unsafe_proto: true,
        unsafe_regexp: true,
        unsafe_symbols: true,
        unsafe_undefined: true,
        toplevel: true,
        booleans_as_integers: true,
        conditionals: true,
        dead_code: true,
        evaluate: true,
        unused: true,
        join_vars: true,
        collapse_vars: true,
        reduce_funcs: true,
        reduce_vars: true,
        sequences: true,
        side_effects: true,
      },
      mangle: {
        eval: true,
        toplevel: true,
        safari10: true
      },
      format: {
        indent_level: 0,
        comments: false,
        semicolons: false
      },
    },
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            return 'vendor';
          }
          if (id.includes('src/components/')) {
            return 'components';
          }
          if (id.includes('src/pages/')) {
            return 'pages';
          }
        },
        assetFileNames: "assets/[name]-[hash].[ext]",
        chunkFileNames: "assets/[name]-[hash].js",
        entryFileNames: "assets/[name]-[hash].js",
      },
    },
    cssCodeSplit: true,
    brotliSize: false
  }
});
