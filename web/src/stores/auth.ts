import { create } from "zustand";
import { api } from "@/lib/api";

type AuthState = {
  role: string | null;
  checked: boolean;
  checkRole: () => Promise<void>;
  logout: () => Promise<void>;
};

export const useAuth = create<AuthState>((set) => ({
  role: null,
  checked: false,
  checkRole: async () => {
    try {
      const data = await api.get<{ success: boolean; role: string | null }>("/api/check_role");
      set({ role: data.role ?? null, checked: true });
    } catch {
      set({ role: null, checked: true });
    }
  },
  logout: async () => {
    try {
      await api.post("/api/logout");
    } finally {
      set({ role: null, checked: true });
    }
  },
}));
