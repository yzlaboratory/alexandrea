import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import App from './App';

// MUI theme is intentionally minimal here — real theming (palette, contrast
// tokens for ADR 0010, typography) lands when the first surface needs it.
const theme = createTheme();

const rootEl = document.getElementById('root');
if (!rootEl) throw new Error('Root element #root was not found in index.html');

// BrowserRouter wraps the app from day one because ADR 0008 makes the URL the
// source of truth for the detail overlay; routes will be added per surface.
createRoot(rootEl).render(
  <StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter>
        <Routes>
          <Route path="*" element={<App />} />
        </Routes>
      </BrowserRouter>
    </ThemeProvider>
  </StrictMode>,
);
