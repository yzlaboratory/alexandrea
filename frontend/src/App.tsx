import { Navigate, Route, Routes } from 'react-router-dom';

import { LoginPage } from './pages/LoginPage';
import { HomePage } from './pages/HomePage';
import { RequireAuth } from './auth/RequireAuth';
import { RedirectIfAuthed } from './auth/RedirectIfAuthed';

export function App() {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <RedirectIfAuthed>
            <LoginPage />
          </RedirectIfAuthed>
        }
      />
      <Route
        path="/"
        element={
          <RequireAuth>
            <HomePage />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
