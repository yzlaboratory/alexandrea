import { type ReactNode } from 'react';
import { Route, Routes } from 'react-router-dom';
import LandingPage from './landing/LandingPage';
import SignupPage from './auth/SignupPage';
import VerifyPage from './auth/VerifyPage';
import LoginPage from './auth/LoginPage';
import RequireAuth from './auth/RequireAuth';
import { SessionProvider } from './auth/SessionContext';
import AppShell from './shell/AppShell';
import AccountPage from './shell/AccountPage';
import CatalogPlaceholder from './shell/CatalogPlaceholder';

// SessionProvider lives here, not in main.tsx: LoginPage reads it directly
// (to refresh after a successful login) even though it sits outside
// RequireAuth, so every route App renders needs it in scope.
function App(): ReactNode {
  return (
    <SessionProvider>
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/verify" element={<VerifyPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireAuth />}>
          <Route element={<AppShell />}>
            <Route
              path="/:mediaType/:surface"
              element={<CatalogPlaceholder />}
            />
            <Route path="/account" element={<AccountPage />} />
          </Route>
        </Route>
        <Route path="*" element={<LandingPage />} />
      </Routes>
    </SessionProvider>
  );
}

export default App;
