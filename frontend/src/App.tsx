import { type JSX } from 'react';
import { Route, Routes } from 'react-router-dom';
import LandingPage from './landing/LandingPage';
import SignupPage from './auth/SignupPage';
import VerifyPage from './auth/VerifyPage';

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
