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
import CatalogSurfaceRoute from './shell/CatalogSurfaceRoute';

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
            {/* Wins the match for every media type's Catalog surface over
                the generic /:mediaType/:surface route below regardless of
                declaration order — React Router ranks routes by automatic
                specificity scoring, and this route's static /catalog
                segment outscores that route's dynamic :surface segment.
                CatalogSurfaceRoute itself decides which :mediaType values
                render the real CatalogPage vs. the generic placeholder,
                keeping :mediaType a genuine route param either way. */}
            <Route
              path="/:mediaType/catalog"
              element={<CatalogSurfaceRoute />}
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
