from utils.auth_security import role_matches


def test_role_matches_requires_the_expected_role():
    assert role_matches({"user_id": 3, "role": "applicant"}, "applicant") is True
    assert role_matches({"user_id": 3, "role": "company"}, "applicant") is False
    assert role_matches({}, "applicant") is False
