import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import terser from '@rollup/plugin-terser';

export default {
  input: 'resources/js/app.js',
  output: {
    file: 'prod-resources/public/js/bundle.js',
    format: 'iife',
    sourcemap: false  // No sourcemaps for production
  },
  plugins: [
    resolve(),
    commonjs(),
    terser({
      compress: {
        drop_console: true,  // Remove console.log statements
        passes: 2
      },
      mangle: true,
      format: {
        comments: false  // Remove all comments
      }
    })
  ]
};
