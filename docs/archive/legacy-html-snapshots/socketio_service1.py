# utils/socketio_service.py
from flask_socketio import SocketIO, emit
from openai import OpenAI
import os
from models import db
from models.Job import Job
from models.Resume import Resume

job_info_json=None

# 初始化OpenAI客户端
client = OpenAI(
    api_key=os.getenv("DASHSCOPE_API_KEY"),
    base_url="https://dashscope.aliyuncs.com/compatible-mode/v1"
)

system_prompt = """
你是一个专业严谨的AI面试官，需要完成以下任务：

【角色设定】
1. 面试流程分三个阶段：
   - 技术面试（1个问题）
   - 行为面试（1个问题）
   - 总结反馈（1个问题）
2. 基于以下信息生成问题：
   - 职位要求：{position_info}
   - 候选人简历：{resume_info}
3. 评分维度（百分制）：
   - 技术能力（权重40%）
   - 学习能力（权重20%）
   - 团队协作（权重15%）
   - 问题解决（权重15%）
   - 沟通表达（权重10%）

【对话规则】
1. 每次只问1个问题。
2. 问题间保持逻辑连贯。
3. 第10轮自动结束面试。
4. 使用中文提问，保持专业但友好的语气。

【输出格式要求】
{{"current_round": 当前轮次, "phase": 阶段名称}}
"""

EVALUATION_PROMPT = """
请根据面试对话历史生成结构化评估：

【输入数据】
面试记录：
{history}

岗位要求：
{position}

【输出要求】
1. 按百分制给出五项评分
2. 每项提供50字评估依据
3. 输出JSON格式：
{{
  "evaluation_details": [
    {{"dimension": "技术能力", "score": {{score}}, "reason": "..."}},
    {{"dimension": "学习能力", "score": {{score}}, "reason": "..."}},
    {{"dimension": "团队协作", "score": {{score}}, "reason": "..."}},
    {{"dimension": "问题解决", "score": {{score}}, "reason": "..."}},
    {{"dimension": "沟通表达", "score": {{score}}, "reason": "..."}}
  ]
}}
"""

def stream_chat(messages):
    answer_content = ""  # 定义完整回复

    # 创建聊天完成请求
    stream = client.chat.completions.create(
        model="deepseek-v3",  # 此处以 deepseek-v3 为例，可按需更换模型名称
        messages=messages,
        stream=True
    )

    for chunk in stream:
        if not getattr(chunk, 'choices', None):
            continue

        delta = chunk.choices[0].delta

        if not hasattr(delta, 'content'):
            continue

        if not getattr(delta, 'content', None):
            continue

        answer_content += delta.content

    return answer_content

def analyze_resume(resume_id, job_id):
    resume = Resume.query.filter_by(resume_id=resume_id).first()
    job = Job.query.filter_by(job_id=job_id).first()
    job_details = {
        'title': job.title,
        'job_type': job.job_type,
        'description': job.description,
        'requirements': job.requirements,
        'min_salary': job.min_salary,
        'max_salary': job.max_salary
    }
    job_info_json = job_details
    resume_json = resume.parsed_data
    return job_info_json, resume_json

def generate_evaluation_report(history, position_info):
    evaluation_message = EVALUATION_PROMPT.format(
        history=history,
        position=position_info
    )
    messages = [
        {"role": "system", "content": evaluation_message}
    ]
    evaluation_report = stream_chat(messages)
    return evaluation_report

def initialize_socketio(app):
    socketio = SocketIO(app)

    @socketio.on('start_interview')
    def handle_start_interview(data):
        resume_id = data.get('resume_id')
        job_id = data.get('job_id')

        job_info_json, resume_json = analyze_resume(resume_id, job_id)

        initial_message = system_prompt.format(
            position_info=job_info_json,
            resume_info=resume_json
        )

        messages = [
            {"role": "system", "content": initial_message},
            {"role": "user", "content": "我准备好了，面试开始"}
        ]

        answer = stream_chat(messages)
        messages.append({"role": "assistant", "content": answer})
        emit('new_message', {'role': 'assistant', 'content': answer})

        global conversation_log
        conversation_log = messages

    @socketio.on('user_input')
    def handle_user_input(data):
        user_input = data.get('content')
        conversation_log.append({"role": "user", "content": user_input})
        print(conversation_log)
        talklength=6

        if len(conversation_log) >= talklength+2:  # 假设进行3轮对话，每轮对话有两个消息
            history = "\n".join([f"{msg['role']}: {msg['content']}" for msg in conversation_log[2:]])
            evaluation_report = generate_evaluation_report(history, job_info_json)
            emit('evaluation_report', {'report': evaluation_report})
            print(history)
            print(evaluation_report)
            return

        answer = stream_chat(conversation_log)
        conversation_log.append({"role": "assistant", "content": answer})
        emit('new_message', {'role': 'assistant', 'content': answer})

    return socketio
