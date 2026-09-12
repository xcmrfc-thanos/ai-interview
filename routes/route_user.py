from pathlib import Path
from uuid import uuid4

from flask import Blueprint, current_app, request, jsonify, send_from_directory, session, url_for
from werkzeug.utils import secure_filename
from models.User import User
from models.Applicant import Applicant
from models.VoiceProfile import VoiceProfile
from models import db
from utils.auth_security import hash_password

user_bp = Blueprint('user', __name__)
AUDIO_EXTENSIONS = {".m4a", ".mp3", ".mp4", ".ogg", ".wav", ".webm"}
MAX_VOICE_BYTES = 10 * 1024 * 1024


def _voice_directory():
    directory = Path(current_app.config["UPLOAD_DIR"]) / "voice_profiles"
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def _voice_profile_payload(profile):
    if not profile:
        return {"available": False, "audio_url": None, "original_filename": None, "updated_at": None}
    return {
        "available": True,
        "audio_url": url_for("user.voice_profile_audio"),
        "original_filename": profile.original_filename,
        "updated_at": profile.updated_at.isoformat() if profile.updated_at else None,
    }

@user_bp.route('/users')
def get_user_info():
    user_id = session.get('user_id')
    if not user_id:
        return jsonify({'success': False, 'message': '未登录'}), 401

    user = db.session.get(User, user_id)
    applicant = Applicant.query.filter_by(user_id=user_id).first()
    
    if not user or not applicant:
        return jsonify({'success': False, 'message': '用户不存在'}), 404

    return jsonify({
        'success': True,
        'user': {
            'full_name': applicant.full_name,
            'email': user.email,
            'phone': applicant.phone,
            'expected_position': applicant.expected_position,
            'expected_salary':applicant.expected_salary,
            'work_years': applicant.work_years,
        }
    })

@user_bp.route('/users/update', methods=['POST'])
def update_user_info():
    user_id = session.get('user_id')
    if not user_id:
        return jsonify({'success': False, 'message': '未登录'}), 401

    data = request.get_json()
    user = db.session.get(User, user_id)
    applicant = Applicant.query.filter_by(user_id=user_id).first()

    if not user or not applicant:
        return jsonify({'success': False, 'message': '用户不存在'}), 404

    applicant.full_name = data.get('full_name', applicant.full_name)
    user.email = data.get('email', user.email)
    applicant.phone = data.get('phone', applicant.phone)
    applicant.expected_position = data.get('expected_position', applicant.expected_position)
    applicant.expected_salary = data.get('expected_salary', applicant.expected_salary)
    if data.get('password'):
        user.password = hash_password(data['password'])

    db.session.commit()
    session['full_name'] = applicant.full_name

    return jsonify({
        'success': True,
        'user': {
            'full_name': applicant.full_name,
            'email': user.email,
            'phone': applicant.phone,
            'expected_position': applicant.expected_position,
            'expected_salary': applicant.expected_salary,
        }
    })


@user_bp.get('/users/voice-profile')
def get_voice_profile():
    user_id = session.get('user_id')
    if not user_id:
        return jsonify({'success': False, 'message': '未登录'}), 401
    profile = VoiceProfile.query.filter_by(user_id=user_id).first()
    return jsonify({'success': True, 'voice_profile': _voice_profile_payload(profile)})


@user_bp.get('/users/voice-profile/audio')
def voice_profile_audio():
    user_id = session.get('user_id')
    if not user_id:
        return jsonify({'success': False, 'message': '未登录'}), 401
    profile = VoiceProfile.query.filter_by(user_id=user_id).first()
    if not profile:
        return jsonify({'success': False, 'message': '尚未录入语音'}), 404
    return send_from_directory(_voice_directory(), profile.storage_name, mimetype=profile.mime_type)


@user_bp.post('/users/voice-profile')
def save_voice_profile():
    user_id = session.get('user_id')
    if not user_id:
        return jsonify({'success': False, 'message': '未登录'}), 401
    audio = request.files.get('audio')
    if not audio or not audio.filename:
        return jsonify({'success': False, 'message': '请选择或录制一段语音'}), 400
    raw_name = Path(str(audio.filename)).name.replace("\x00", "")
    suffix = Path(raw_name).suffix.lower()
    original_name = secure_filename(raw_name) or f"voice-profile{suffix}"
    if suffix not in AUDIO_EXTENSIONS:
        return jsonify({'success': False, 'message': '仅支持 wav、webm、ogg、mp3 或 m4a 音频'}), 400
    audio.stream.seek(0, 2)
    size = audio.stream.tell()
    audio.stream.seek(0)
    if size <= 0 or size > MAX_VOICE_BYTES:
        return jsonify({'success': False, 'message': '语音文件需大于 0 且不超过 10MB'}), 400

    profile = VoiceProfile.query.filter_by(user_id=user_id).first()
    old_storage_name = profile.storage_name if profile else None
    storage_name = f"user_{user_id}_{uuid4().hex}{suffix}"
    audio.save(_voice_directory() / storage_name)
    if not profile:
        profile = VoiceProfile(user_id=user_id)
        db.session.add(profile)
    profile.storage_name = storage_name
    profile.original_filename = original_name
    profile.mime_type = audio.mimetype or "application/octet-stream"
    db.session.commit()
    if old_storage_name and old_storage_name != storage_name:
        (_voice_directory() / old_storage_name).unlink(missing_ok=True)
    return jsonify({'success': True, 'voice_profile': _voice_profile_payload(profile)}), 201


@user_bp.delete('/users/voice-profile')
def delete_voice_profile():
    user_id = session.get('user_id')
    if not user_id:
        return jsonify({'success': False, 'message': '未登录'}), 401
    profile = VoiceProfile.query.filter_by(user_id=user_id).first()
    if profile:
        storage_path = _voice_directory() / profile.storage_name
        db.session.delete(profile)
        db.session.commit()
        try:
            storage_path.unlink(missing_ok=True)
        except PermissionError:
            # Windows 下试听响应仍占用文件时，记录已删除，物理文件稍后由清理任务处理。
            pass
    return jsonify({'success': True, 'voice_profile': _voice_profile_payload(None)})
