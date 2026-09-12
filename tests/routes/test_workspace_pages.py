import app as application


def login(client):
    with client.session_transaction() as session:
        session["user_id"] = 1
        session["role"] = "applicant"
        session["full_name"] = "测试用户"


def test_workspace_and_interview_plan_pages_require_applicant_login():
    client = application.app.test_client()

    assert client.get("/applicant/workspace").status_code == 302
    assert client.get("/applicant/interview-plans").status_code == 302

    login(client)
    assert client.get("/applicant/workspace").status_code == 200
    assert client.get("/applicant/interview-plans").status_code == 200
