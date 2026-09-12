"""Deterministic review summaries for Copilot and mock-interview records."""

from collections import Counter, defaultdict


class ReviewService:
    @staticmethod
    def build(source_type, source, plan):
        if source_type == "copilot":
            return _copilot_review(source, plan)
        if source_type == "mock":
            return _mock_review(source, plan)
        raise ValueError("不支持的复盘来源")


def _copilot_review(source, plan):
    turns = list(source.turns or [])
    partial_count = sum(turn.status != "completed" for turn in turns)
    errors = sum(event.event_type == "answer_error" for event in source.events or [])
    reconnects = sum("reconnect" in event.event_type for event in source.events or [])
    latencies = [event.latency_ms for event in source.events or [] if event.latency_ms is not None]
    expression_issues = []
    if partial_count:
        expression_issues.append(f"{partial_count} 个问题的参考回答未完整生成")
    next_actions = ["回看本次问题并用自己的语言重答"] if turns else ["完成一次包含有效问题的实时辅助"]
    if errors:
        next_actions.append("检查模型配置或网络后重试未完成回答")
    return {
        "summary": f"{plan.company_name} · {plan.position_name} 实时辅助共识别 {len(turns)} 个问题。",
        "question_categories": [{"name": "实时问答", "count": len(turns)}],
        "scores": {"answer_completion": round((len(turns) - partial_count) / len(turns) * 100, 1)} if turns else {},
        "fact_risks": [],
        "expression_issues": expression_issues,
        "weak_topics": [],
        "next_actions": next_actions,
        "diagnostics": {
            "answer_errors": errors,
            "reconnects": reconnects,
            "average_latency_ms": round(sum(latencies) / len(latencies), 1) if latencies else None,
        },
    }


def _mock_review(source, plan):
    turns = [turn for turn in source.turns or [] if turn.status == "answered"]
    score_values = defaultdict(list)
    issues = []
    fact_risks = []
    for turn in turns:
        for name, value in (turn.scores or {}).items():
            score_values[name].append(float(value))
        for item in turn.improvements or []:
            if item.startswith("核实回答中的数字："):
                fact_risks.append(item)
            else:
                issues.append(item)
    scores = {name: round(sum(values) / len(values), 1) for name, values in score_values.items()}
    category_counts = Counter(
        item.get("category", "general") for item in (source.question_outline or [])
    )
    weak = [name for name, score in sorted(scores.items(), key=lambda pair: pair[1]) if score < 75]
    next_actions = list(dict.fromkeys(issues))[:5]
    if weak:
        next_actions.append(f"重点练习低分维度：{'、'.join(weak)}")
    if not next_actions:
        next_actions.append("复述高质量回答并继续一轮更高难度练习")
    return {
        "summary": f"{plan.company_name} · {plan.position_name} 模拟面试完成 {len(turns)} 道回答。",
        "question_categories": [
            {"name": name, "count": count} for name, count in category_counts.items()
        ],
        "scores": scores,
        "fact_risks": list(dict.fromkeys(fact_risks)),
        "expression_issues": list(dict.fromkeys(issues)),
        "weak_topics": weak,
        "next_actions": next_actions,
        "diagnostics": {"answered_turns": len(turns)},
    }
