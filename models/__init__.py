from datetime import datetime, timezone

from flask_sqlalchemy import SQLAlchemy

db = SQLAlchemy()


def utc_now():
    return datetime.now(timezone.utc)


def import_workspace_models():
    """Register personal workspace model metadata before table creation."""
    from .Applicant import Applicant
    from .CopilotEvent import CopilotEvent
    from .CopilotSession import CopilotSession
    from .CopilotTurn import CopilotTurn
    from .InterviewPlan import InterviewPlan
    from .KnowledgeItem import KnowledgeItem
    from .LlmConfig import LlmConfig
    from .MockInterview import MockInterview
    from .MockInterviewTurn import MockInterviewTurn
    from .PreparationPack import PreparationPack
    from .Review import Review
    from .ResumeOptimization import ResumeOptimization
    from .Resume import Resume
    from .User import User
    from .VoiceProfile import VoiceProfile

    return (
        InterviewPlan,
        PreparationPack,
        KnowledgeItem,
        CopilotSession,
        CopilotTurn,
        CopilotEvent,
        MockInterview,
        MockInterviewTurn,
        Review,
        ResumeOptimization,
        User,
        Applicant,
        Resume,
        VoiceProfile,
    )
