from . import db, utc_now


class LlmConfig(db.Model):
    """LLM 运行时配置（全局单例，id=1）。

    api_key 以 Fernet 对称加密后存 api_key_enc；加密密钥来自
    CONFIG_ENCRYPTION_KEY（缺失时由 APP_SECRET_KEY 派生），明文不落库。
    未配置时运行时回退 .env 的 LLM_* 环境变量。
    """

    __tablename__ = "llm_config"

    id = db.Column(db.Integer, primary_key=True, default=1)
    provider = db.Column(db.String(32), nullable=False, default="")
    base_url = db.Column(db.String(255), nullable=False, default="")
    model = db.Column(db.String(128), nullable=False, default="")
    copilot_model = db.Column(db.String(128), nullable=False, default="")
    think_model = db.Column(db.String(128), nullable=False, default="")
    api_key_enc = db.Column(db.Text, nullable=True)
    updated_at = db.Column(db.DateTime, nullable=False, default=utc_now, onupdate=utc_now)

    def to_dict(self):
        return {
            "provider": self.provider,
            "base_url": self.base_url,
            "model": self.model,
            "copilot_model": self.copilot_model,
            "think_model": self.think_model,
        }
