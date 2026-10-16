/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        reclip: {
          terracotta: '#E85D2A',
          terracottaLight: '#F06A38',
          terracottaDark: '#C7481A',
        },
        paper: {
          bg: '#F7F5F0',
          surface: '#FFFFFF',
          card: '#FFFFFF',
          border: '#E2DED6',
          text: '#2A2825',
          muted: '#878378',
        },
        obsidian: {
          bg: '#141412',
          surface: '#1C1B18',
          card: '#1F1E1A',
          border: '#2E2D28',
          text: '#EFECE6',
          muted: '#969287',
        }
      },
      fontFamily: {
        serif: ['"Instrument Serif"', 'Georgia', 'serif'],
        mono: ['"DM Mono"', 'monospace'],
        sans: ['Inter', '-apple-system', 'BlinkMacSystemFont', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
