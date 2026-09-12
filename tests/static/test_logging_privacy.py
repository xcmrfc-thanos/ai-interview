from pathlib import Path


def test_login_route_does_not_enable_global_debug_logging():
    source = Path("routes/route_login.py").read_text(encoding="utf-8")

    assert "basicConfig(level=logging.DEBUG)" not in source
    assert "logging.debug(" not in source
    assert "logger = logging.getLogger(__name__)" in source
