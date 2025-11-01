const {scanClojure} = require('@multiplyco/tailwind-clj');

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: {
       files: [
          './src/**/*.{clj,cljs,cljc}'
          ],
      extract: {
        clj: (content) => scanClojure(content),
        cljs: (content) => scanClojure(content),
        cljc: (content) => scanClojure(content)
      }
  },
  theme: {
    extend: {},
  },
  plugins: [],
}
