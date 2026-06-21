import { type JSX } from 'react';
import { Route, Routes } from 'react-router-dom';
import LandingPage from './landing/LandingPage';
import SignupPage from './auth/SignupPage';
import VerifyPage from './auth/VerifyPage';

// The app's route table. Only the public auth surfaces exist so far: the
// landing page, signup (form -> check-email), and the verify-result page. Login
// and the protected catalog surfaces arrive in later slices; until then any
// unknown path falls back to the landing page.
function App(): JSX.Element {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/verify" element={<VerifyPage />} />
      <Route path="*" element={<LandingPage />} />
    </Routes>
  );
}

export default App;
