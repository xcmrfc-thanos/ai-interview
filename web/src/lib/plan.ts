// 面试计划领域类型与准备包字段元数据（契约 §4）
export type PreparationStatus = "none" | "generating" | "draft" | "needs_review" | "outdated" | "confirmed";

export type Plan = {
  plan_id: number;
  user_id?: number;
  resume_id?: number | null;
  company_name: string;
  position_name: string;
  job_description: string;
  extra_requirements?: string | null;
  level?: string | null;
  tech_tags?: string | string[] | null;
  status: string;
  preparation_status?: PreparationStatus;
  created_at?: string;
  updated_at?: string;
  packs?: PreparationPack[];
};

export type PreparationPack = {
  pack_id: number;
  plan_id: number;
  version: number;
  status: "draft" | "needs_review" | "outdated" | "confirmed";
  intro_30?: string | null;
  intro_60?: string | null;
  intro_90?: string | null;
  highlights?: string | string[] | null;
  project_followups?: string | string[] | null;
  risk_points?: string | string[] | null;
  frequent_questions?: string | string[] | null;
  star_stories?: string | string[] | null;
  review_topics?: string | string[] | null;
  source_evidence?: string | string[] | null;
};

/** 后端个别实现以 JSON 字符串存储数组列，这里统一归一化为 string[]。 */
export function asStringArray(value: string | string[] | null | undefined): string[] {
  if (!value) return [];
  if (Array.isArray(value)) return value.map(String).filter(Boolean);
  try {
    const parsed = JSON.parse(value);
    if (Array.isArray(parsed)) return parsed.map(String).filter(Boolean);
  } catch {
    // 非 JSON 文本：按行拆分
  }
  return String(value)
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean);
}

export function techTagsOf(plan: Plan): string[] {
  return asStringArray(plan.tech_tags);
}

/** 准备包区块字段元数据：键 → 展示名/是否数组/是否可重生成。 */
export const PACK_SECTIONS = [
  { key: "intro_30", label: "30 秒自我介绍", isArray: false },
  { key: "intro_60", label: "60 秒自我介绍", isArray: false },
  { key: "intro_90", label: "90 秒自我介绍", isArray: false },
  { key: "highlights", label: "岗位亮点", isArray: true },
  { key: "project_followups", label: "项目深挖追问", isArray: true },
  { key: "risk_points", label: "风险点", isArray: true },
  { key: "frequent_questions", label: "高频问题", isArray: true },
  { key: "star_stories", label: "STAR 故事", isArray: true },
  { key: "review_topics", label: "复习方向", isArray: true },
  { key: "source_evidence", label: "来源证据", isArray: true },
] as const;

export type PackSectionKey = (typeof PACK_SECTIONS)[number]["key"];
