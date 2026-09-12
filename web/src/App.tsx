import { useEffect } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { AppShell } from "@/components/app-shell";
import { Toaster } from "@/components/ui/toast";
import { useAuth } from "@/stores/auth";
import { HomePage } from "@/pages/home";
import { LoginPage } from "@/pages/login";
import { RegisterPage } from "@/pages/register";
import { WorkspacePage } from "@/pages/workspace";
import { PlanListPage } from "@/pages/plan-list";
import { PlanDetailPage } from "@/pages/plan-detail";
import { CopilotPage } from "@/pages/copilot";
import { MockInterviewPage } from "@/pages/mock-interview";
import { ResumeManagePage } from "@/pages/resume-manage";
import { ResumeOptimizePage } from "@/pages/resume-optimize";
import { KnowledgePage } from "@/pages/knowledge";
import { ReviewListPage } from "@/pages/review-list";
import { ReviewDetailPage } from "@/pages/review-detail";
import { ProfilePage } from "@/pages/profile";
import { LlmSettingsPage } from "@/pages/llm-settings";

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { role, checked, checkRole } = useAuth();
  useEffect(() => {
    if (!checked) void checkRole();
  }, [checked, checkRole]);
  if (!checked) return null;
  if (role !== "applicant") return <Navigate to="/login" replace />;
  return children;
}

// 路由表 = docs/api-contract.md §0 SPA 页面路由
export function App() {
  return (
    <BrowserRouter>
      <Toaster />
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route
          element={
            <RequireAuth>
              <AppShell />
            </RequireAuth>
          }
        >
          <Route path="/applicant/workspace" element={<WorkspacePage />} />
          <Route path="/applicant/interview-plans" element={<PlanListPage />} />
          <Route path="/applicant/interview-plans/:planId" element={<PlanDetailPage />} />
          <Route path="/applicant/copilot" element={<CopilotPage />} />
          <Route path="/applicant/mock-interview" element={<MockInterviewPage />} />
          <Route path="/applicant/resumes" element={<ResumeManagePage />} />
          <Route path="/applicant/resume-optimize" element={<ResumeOptimizePage />} />
          <Route path="/applicant/knowledge" element={<KnowledgePage />} />
          <Route path="/applicant/reviews" element={<ReviewListPage />} />
          <Route path="/applicant/reviews/:reviewId" element={<ReviewDetailPage />} />
          <Route path="/applicant/profile" element={<ProfilePage />} />
          <Route path="/applicant/llm-settings" element={<LlmSettingsPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
