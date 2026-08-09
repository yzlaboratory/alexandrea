import { type ReactNode } from 'react';
import { Route, Routes } from 'react-router-dom';
import LandingPage from './landing/LandingPage';
import SignupPage from './auth/SignupPage';
import VerifyPage from './auth/VerifyPage';
import LoginPage from './auth/LoginPage';
import ForgotPasswordPage from './auth/ForgotPasswordPage';
import ResetPasswordPage from './auth/ResetPasswordPage';
import ConfirmEmailChangePage from './auth/ConfirmEmailChangePage';
import RequireAuth from './auth/RequireAuth';
import { SessionProvider } from './auth/SessionContext';
import AppShell from './shell/AppShell';
import AccountPage from './shell/AccountPage';
import CatalogPlaceholder from './shell/CatalogPlaceholder';
import CatalogPage from './catalog/CatalogPage';

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
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route
          path="/confirm-email-change"
          element={<ConfirmEmailChangePage />}
        />
        <Route element={<RequireAuth />}>
          <Route element={<AppShell />}>
            {/* Only Movies has a real catalog provider wired up yet (#39
                adds the rest); every other media type still falls through
                to the generic placeholder route below. */}
            <Route
              path="/movies/catalog"
              element={<CatalogPage mediaType="movies" />}
            />
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
