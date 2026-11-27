import { Suspense, lazy, useEffect, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { api } from './lib/api';
import { queryClient } from './lib/queryClient';
import { useForgeStore } from './lib/store';
import { AppShell } from './components/AppShell';
import { SkeletonBlock } from './components/ui';
import { LoginPage } from './pages/LoginPage';

const ShowcasePage = lazy(() =>
  import('./pages/ShowcasePage').then((m) => ({ default: m.ShowcasePage }))
);
const EngineeringReliabilityPage = lazy(() =>
  import('./pages/EngineeringReliabilityPage').then((m) => ({
    default: m.EngineeringReliabilityPage,
  }))
);
const OverviewPage = lazy(() => import('./pages/OverviewPage').then((m) => ({ default: m.OverviewPage })));
const RepositoriesPage = lazy(() =>
  import('./pages/RepositoriesPage').then((m) => ({ default: m.RepositoriesPage }))
);
const CodeIntelPage = lazy(() => import('./pages/CodeIntelPage').then((m) => ({ default: m.CodeIntelPage })));
const ReviewsPage = lazy(() => import('./pages/ReviewsPage').then((m) => ({ default: m.ReviewsPage })));
const ReviewDetailPage = lazy(() =>
  import('./pages/ReviewDetailPage').then((m) => ({ default: m.ReviewDetailPage }))
);
const PredictiveIntelligencePage = lazy(() =>
  import('./pages/PredictiveIntelligencePage').then((m) => ({
    default: m.PredictiveIntelligencePage,
  }))
);
const SecurityCenterPage = lazy(() =>
  import('./pages/SecurityCenterPage').then((m) => ({ default: m.SecurityCenterPage }))
);
const CiIntelligencePage = lazy(() =>
  import('./pages/CiIntelligencePage').then((m) => ({ default: m.CiIntelligencePage }))
);
const AnalyticsEvalPage = lazy(() =>
  import('./pages/AnalyticsEvalPage').then((m) => ({ default: m.AnalyticsEvalPage }))
);
const AuditLogsPage = lazy(() => import('./pages/AuditLogsPage').then((m) => ({ default: m.AuditLogsPage })));
const SettingsPage = lazy(() => import('./pages/SettingsPage').then((m) => ({ default: m.SettingsPage })));

function ProtectedRoute({ children }: { children: ReactNode }) {
  const token = useForgeStore((s) => s.token);
  const updateSessionInfo = useForgeStore((s) => s.updateSessionInfo);

  useEffect(() => {
    if (!token) return;
    let active = true;
    api
      .me()
      .then((session) => {
        if (active && session?.user && session?.tenant) {
          updateSessionInfo(session);
        }
      })
      .catch(() => {
        // 401 is handled by api client (auto-refresh or clearSession)
      });
    return () => {
      active = false;
    };
  }, [token, updateSessionInfo]);

  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function PageFallback() {
  return (
    <div className="space-y-4">
      <SkeletonBlock className="h-16" />
      <SkeletonBlock className="h-72" />
    </div>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Suspense fallback={<PageFallback />}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/showcase" element={<ShowcasePage />} />
            <Route path="/engineering" element={<EngineeringReliabilityPage />} />
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
              <Route path="predictions" element={<PredictiveIntelligencePage />} />
              <Route path="security" element={<SecurityCenterPage />} />
              <Route path="ci" element={<CiIntelligencePage />} />
              <Route path="analytics" element={<AnalyticsEvalPage />} />
              <Route path="audit" element={<AuditLogsPage />} />
              <Route path="settings" element={<SettingsPage />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </BrowserRouter>
    </QueryClientProvider>
  );
}

export default App;
