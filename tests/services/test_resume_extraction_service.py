from zipfile import ZIP_DEFLATED, ZipFile


def write_docx(path, text):
    paragraphs = "".join(
        f"<w:p><w:r><w:t>{line}</w:t></w:r></w:p>"
        for line in text.splitlines()
    )
    document_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        f"<w:body>{paragraphs}</w:body></w:document>"
    )
    with ZipFile(path, "w", ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", document_xml)


def test_extract_resume_document_returns_local_text_and_keywords(tmp_path):
    from services.resume_extraction_service import extract_resume_document

    resume_path = tmp_path / "resume.docx"
    write_docx(resume_path, "Python Flask 工程师\n负责 Redis 和 Docker 项目")

    result = extract_resume_document(resume_path)

    assert result["status"] == "uploaded"
    assert "Python Flask 工程师" in result["extracted_text"]
    assert "Python" in result["keywords"]
    assert "Redis" in result["keywords"]
    assert result["facts"]["skills"]


def test_extract_resume_document_keeps_upload_success_when_text_is_unavailable(tmp_path):
    from services.resume_extraction_service import extract_resume_document

    result = extract_resume_document(tmp_path / "broken.docx")

    assert result == {
        "status": "uploaded",
        "extracted_text": "",
        "keywords": [],
        "facts": {"skills": []},
    }


def test_extract_resume_document_handles_malformed_docx_without_raising(tmp_path):
    from services.resume_extraction_service import extract_resume_document

    resume_path = tmp_path / "malformed.docx"
    with ZipFile(resume_path, "w", ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", "<not-valid-xml")

    result = extract_resume_document(resume_path)

    assert result["status"] == "uploaded"
    assert result["extracted_text"] == ""
