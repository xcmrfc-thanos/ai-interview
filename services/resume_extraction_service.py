import re
from pathlib import Path
from zipfile import ZipFile
from defusedxml import ElementTree


KEYWORD_TERMS = (
    "Python", "Flask", "Django", "FastAPI", "JavaScript", "TypeScript",
    "Java", "Spring", "Go", "Rust", "C++", "SQL", "MySQL", "PostgreSQL",
    "Redis", "Docker", "Kubernetes", "Vue", "React", "Node.js", "Git",
)


def _extract_text(file_path):
    path = Path(file_path)
    if path.suffix.lower() == ".docx":
        with ZipFile(path) as archive:
            # defusedxml：禁用实体解析，防 XML 实体扩展（billion laughs）
            document = ElementTree.fromstring(archive.read("word/document.xml"), forbid_dtd=True)
        parts = [
            node.text.strip()
            for node in document.iter()
            if node.tag.endswith("}t") and node.text and node.text.strip()
        ]
        return "\n".join(parts)

    if path.suffix.lower() == ".txt":
        return path.read_text(encoding="utf-8", errors="replace")

    if path.suffix.lower() == ".pdf":
        try:
            from pypdf import PdfReader
        except ImportError:
            return ""
        parts = [page.extract_text() or "" for page in PdfReader(path).pages]
        return "\n".join(part.strip() for part in parts if part.strip())

    return ""


def _clean_text(text):
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.splitlines()]
    return "\n".join(line for line in lines if line)


def _extract_keywords(text):
    lowered = text.casefold()
    found = []
    for term in KEYWORD_TERMS:
        if term.casefold() in lowered:
            found.append((lowered.index(term.casefold()), term))
    found.sort(key=lambda item: item[0])
    return [term for _, term in found[:20]]


def extract_keywords(text):
    lowered = text.casefold()
    found = []
    for term in KEYWORD_TERMS:
        if term.casefold() in lowered:
            found.append((lowered.index(term.casefold()), term))
    found.sort(key=lambda item: item[0])
    return [term for _, term in found[:20]]


def extract_resume_document(file_path):
    try:
        text = _clean_text(_extract_text(file_path))
    except (OSError, KeyError, ValueError, TypeError, ElementTree.ParseError):
        text = ""
    keywords = _extract_keywords(text)
    return {
        "status": "uploaded",
        "extracted_text": text,
        "keywords": keywords,
        "facts": {"skills": keywords},
    }
