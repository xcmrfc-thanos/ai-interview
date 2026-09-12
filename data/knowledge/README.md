# 个人面试知识库数据

支持通过 Flask CLI 导入 JSON、Markdown 和 TXT：

```powershell
$env:FLASK_APP = "app.py"
py -3.12 -m flask knowledge import data/knowledge/sample-interview-basics.json --user-id 1
py -3.12 -m flask knowledge stats --user-id 1
```

`sample-interview-basics.json` 是可直接幂等导入的最小种子库，包含通用表达、项目深挖和项目当前支持的核心技术主题。内容为原创摘要或官方文档摘要，不是来源不明的整包题库。

JSON 文件使用对象数组。至少提供 `title` 和 `question`，建议同时提供 `core_conclusion`、`answer_points`、`source`、`source_url`、`role_tags` 与 `tech_tags`。每次导入会按“标题 + 问题”去重；相同条目更新内容，不创建副本。请只导入本人有权使用的资料，并保留来源。
