/** @type {import('tailwindcss').Config} */
export default {
    corePlugins: {
        preflight: false,
    },
    content: [
        "./src/**/*.{js,jsx,ts,tsx}",
    ],
    theme: {
        extend: {
            fontFamily: {
                montserrat: ['Montserrat', 'sans-serif'],
                playfair: ['Playfair Display', 'serif'],
            },
        },
    },
}

