/** Tailwind build config for the existing original shop UI. Do not redesign markup. */
module.exports = {
  content: [
    "./android/app/src/main/assets/www/index.html",
    "./The_Green_Grocery_Shop_POS__2_.html"
  ],
  theme: {
    extend: {
      fontFamily: { sans: ["Inter", "Arial", "sans-serif"] },
      colors: {
        brandDark: "#0E5A1F",
        brandLime: "#58B924",
        brandLight: "#EEF9E8"
      }
    }
  },
  plugins: []
};
