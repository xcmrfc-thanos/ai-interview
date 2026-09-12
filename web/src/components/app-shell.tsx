import { NavLink, Outlet, useLocation, useNavigate } from "react-router";
import {
  LayoutDashboard,
  FolderKanban,
  FileText,
  BookOpen,
  ClipboardCheck,
  Settings,
  Mic,
  LogOut,
  MessagesSquare,
  Plus,
  UserRound,
  Cpu,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Avatar } from "@/components/ui/avatar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ThemeToggle } from "@/components/theme-toggle";
import { useAuth } from "@/stores/auth";
import { cn } from "@/lib/utils";

const NAV_GROUPS: { label: string; items: { to: string; label: string; icon: typeof Mic }[] }[] = [
  {
    label: "实战",
    items: [
      { to: "/applicant/workspace", label: "工作台", icon: LayoutDashboard },
      { to: "/applicant/interview-plans", label: "面试计划", icon: FolderKanban },
    ],
  },
  {
    label: "资产",
    items: [
      { to: "/applicant/resumes", label: "简历中心", icon: FileText },
      { to: "/applicant/knowledge", label: "知识库", icon: BookOpen },
      { to: "/applicant/reviews", label: "复盘", icon: ClipboardCheck },
    ],
  },
  {
    label: "设置",
    items: [
      { to: "/applicant/profile", label: "个人设置", icon: Settings },
      { to: "/applicant/llm-settings", label: "模型配置", icon: Cpu },
    ],
  },
];

const PAGE_TITLES: Record<string, string> = {
  "/applicant/workspace": "工作台",
  "/applicant/interview-plans": "面试计划",
  "/applicant/copilot": "实时辅助",
  "/applicant/mock-interview": "模拟面试",
  "/applicant/resumes": "简历中心",
  "/applicant/resume-optimize": "简历优化",
  "/applicant/knowledge": "知识库",
  "/applicant/reviews": "复盘",
  "/applicant/profile": "个人设置",
  "/applicant/llm-settings": "模型配置",
};

// 应用壳：品牌侧栏（分组导航 + 用户卡）+ 顶栏上下文（Quiet Command，边框分层、浮层用阴影）
export function AppShell() {
  const navigate = useNavigate();
  const location = useLocation();
  const logout = useAuth((s) => s.logout);
  const pageTitle =
    PAGE_TITLES[location.pathname] ??
    (location.pathname.startsWith("/applicant/reviews/")
      ? "复盘详情"
      : location.pathname.startsWith("/applicant/interview-plans/")
        ? "计划详情"
        : "面试 Copilot");

  async function handleLogout() {
    await logout();
    navigate("/login");
  }

  const userMenu = (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="flex w-full cursor-pointer items-center gap-2.5 rounded-lg p-2 text-left transition-colors hover:bg-hover"
          aria-label="账号菜单"
        >
          <Avatar name="求职者" className="size-8" />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-medium">个人账号</span>
            <span className="block text-xs text-faint">求职者</span>
          </span>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-48">
        <DropdownMenuItem onClick={() => navigate("/applicant/profile")}>
          <UserRound />
          个人设置
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem destructive onClick={handleLogout}>
          <LogOut />
          退出登录
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );

  return (
    <div className="flex min-h-screen">
      <aside className="sticky top-0 hidden h-screen w-56 shrink-0 flex-col border-r border-border bg-card md:flex">
        <NavLink to="/applicant/workspace" className="flex items-center gap-2.5 px-4 py-4">
          <span className="flex size-8 items-center justify-center rounded-lg bg-brand-gradient text-white shadow-sm">
            <MessagesSquare className="size-4" aria-hidden />
          </span>
          <span className="text-base font-semibold tracking-tight">面试 Copilot</span>
        </NavLink>
        <nav className="flex flex-1 flex-col gap-4 overflow-y-auto px-2 py-2" aria-label="主导航">
          {NAV_GROUPS.map((group) => (
            <div key={group.label}>
              <div className="px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-faint">
                {group.label}
              </div>
              <div className="flex flex-col gap-0.5">
                {group.items.map(({ to, label, icon: Icon }) => (
                  <NavLink
                    key={to}
                    to={to}
                    className={({ isActive }) =>
                      cn(
                        "flex h-9 items-center gap-2.5 rounded-md px-3 text-sm text-muted-foreground transition-colors hover:bg-hover hover:text-foreground",
                        isActive && "bg-primary-soft font-medium text-primary hover:text-primary",
                      )
                    }
                  >
                    <Icon className="size-4" />
                    {label}
                  </NavLink>
                ))}
              </div>
            </div>
          ))}
        </nav>
        <div className="border-t border-border p-2">{userMenu}</div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-border bg-card px-4">
          <NavLink to="/applicant/workspace" className="flex items-center gap-2 md:hidden">
            <Mic className="size-5 text-primary" />
            <span className="font-semibold">面试 Copilot</span>
          </NavLink>
          <h1 className="hidden text-sm font-medium text-muted-foreground md:block">{pageTitle}</h1>
          <div className="ml-auto flex items-center gap-1.5">
            <Button asChild variant="gradient" size="sm" className="hidden sm:inline-flex">
              <NavLink to="/applicant/interview-plans?create=1">
                <Plus />
                新建计划
              </NavLink>
            </Button>
            <ThemeToggle />
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="cursor-pointer rounded-full transition-opacity hover:opacity-80 md:hidden"
                  aria-label="账号菜单"
                >
                  <Avatar name="求职者" className="size-8 rounded-full" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-44">
                <DropdownMenuItem destructive onClick={handleLogout}>
                  <LogOut />
                  退出登录
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>
        {/* 移动端底部不可达的侧栏项以横向滚动导航兜底 */}
        <nav className="flex gap-1 overflow-x-auto border-b border-border bg-card px-3 py-2 md:hidden" aria-label="主导航">
          {NAV_GROUPS.flatMap((g) => g.items).map(({ to, label }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                cn(
                  "whitespace-nowrap rounded-md px-3 py-1.5 text-sm text-muted-foreground",
                  isActive && "bg-primary-soft text-primary",
                )
              }
            >
              {label}
            </NavLink>
          ))}
        </nav>
        <main className="min-w-0 flex-1">
          {/* key 按路径重挂载，让 page-in 淡入在每次页面切换时重放 */}
          <div key={location.pathname} className="animate-page-in">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}
