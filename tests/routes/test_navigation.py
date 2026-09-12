import app as application


def login(client, role="applicant"):
    with client.session_transaction() as session:
        session["user_id"] = 1
        session["role"] = role
        session["full_name"] = "测试用户"
        session["company_name"] = "测试企业"


def test_personal_navigation_targets_render_and_legacy_routes_redirect():
    client = application.app.test_client()
    login(client)

    for path in (
        "/applicant/workspace",
        "/applicant/interview-plans",
        "/applicant/copilot",
        "/applicant/mock-interview",
        "/applicant/resume_manage",
        "/applicant/reviews",
        "/applicant/personal_center",
        "/applicant/knowledge",
    ):
        assert client.get(path).status_code == 200, path

    redirects = {
        "/applicant/dashboard": "/applicant/workspace",
        "/applicant/interview_manage": "/applicant/interview-plans",
        "/applicant/reports": "/applicant/reviews",
        "/applicant/resume": "/applicant/resume_manage",
        "/applicant/sim_interview": "/applicant/mock-interview",
        "/applicant/interview": "/applicant/interview-plans",
        "/applicant/analyze_resume": "/applicant/resume_manage",
        "/applicant/upload_resume": "/applicant/resume_manage",
        "/applicant/resume_report": "/applicant/resume_manage",
        "/applicant/interviewindex": "/applicant/mock-interview",
        "/applicant/TestConfig": "/applicant/mock-interview",
        "/applicant/avatar": "/applicant/mock-interview",
    }
    for path, destination in redirects.items():
        response = client.get(path)
        assert response.status_code == 302, path
        assert response.headers["Location"].startswith(destination)

    # interview-workspace 旧入口拆为两个独立路由，并按 mode/query 转发
    response = client.get("/applicant/interview-workspace?mode=mock&plan_id=1")
    assert response.status_code == 302
    assert response.headers["Location"].startswith("/applicant/mock-interview")

    response = client.get("/applicant/interview-workspace?mode=copilot")
    assert response.status_code == 302
    assert response.headers["Location"].startswith("/applicant/copilot")


def test_interview_mode_pages_use_standalone_layout_and_hide_app_sidebar():
    client = application.app.test_client()
    login(client)

    # 页面已迁移至 web/ SPA：服务端回落 dist/index.html，专注布局由前端路由接管
    for path in ("/applicant/copilot", "/applicant/mock-interview"):
        response = client.get(path)
        assert response.status_code == 200, path
        assert b'id="root"' in response.data
        assert b"layouts/standalone" not in response.data


def test_company_direct_routes_do_not_raise_template_errors():
    """企业端已归档（docs/archive/legacy-company/）：直接访问返回 404，不再抛模板错误。"""
    client = application.app.test_client()
    login(client, role="company")

    for path in ("/company/manage_applicant", "/company/manage_interview"):
        response = client.get(path)
        assert response.status_code == 404, path
