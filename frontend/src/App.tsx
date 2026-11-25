import React from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useForgeStore } from './lib/store';
import { AppShell } from './components/AppShell';
import { LoginPage } from './pages/LoginPage';
import { OverviewPage } from './pages/OverviewPage';
import { RepositoriesPage } from './pages/RepositoriesPage';
import { CodeIntelPage } from './pages/CodeIntelPage';
import { ReviewsPage } from './pages/ReviewsPage';
import { ReviewDetailPage } from './pages/ReviewDetailPage';
import { SecurityCenterPage } from './pages/SecurityCenterPage';
import { CiIntelligencePage } from './pages/CiIntelligencePage';
import { AnalyticsEvalPage } from './pages/AnalyticsEvalPage';
import { AuditLogsPage } from './pages/AuditLogsPage';
import { SettingsPage } from './pages/SettingsPage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      retry: 1,
    },
  },
});

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const token = useForgeStore((s) => s.token);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <AppShell />
              </ProtectedRoute>
            }
          >
            <Route index element={<OverviewPage />} />
            <Route path="repositories" element={<RepositoriesPage />} />
            <Route path="code-intel" element={<CodeIntelPage />} />
            <Route path="reviews" element={<ReviewsPage />} />
            <Route path="reviews/:reviewId" element={<ReviewDetailPage />} />
            <Route path="security" element={<SecurityCenterPage />} />
            <Route path="ci" element={<CiIntelligencePage />} />
            <Route path="analytics" element={<AnalyticsEvalPage />} />
            <Route path="audit" element={<AuditLogsPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}

export default App;
