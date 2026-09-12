package com.aiinterview.server.svc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** 简历本地提取：docx（zip+XML）/ pdf（PDFBox）/ txt，与 Python 侧逻辑对齐。 */
public final class ResumeExtractor {

    private static final String[] KEYWORD_TERMS = {
        "Python", "Flask", "Django", "FastAPI", "JavaScript", "TypeScript",
        "Java", "Spring", "Go", "Rust", "C++", "SQL", "MySQL", "PostgreSQL",
        "Redis", "Docker", "Kubernetes", "Vue", "React", "Node.js", "Git",
    };

    private ResumeExtractor() {
    }

    /** 返回 {status, extracted_text, keywords, facts} JSON 字符串。 */
    public static JSONObject extract(File file) {
        String suffix = extension(file.getName()).toLowerCase();
        String text = "";
        String status = "uploaded";
        try {
            if (".docx".equals(suffix)) {
                text = extractDocx(file);
            } else if (".txt".equals(suffix)) {
                text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } else if (".pdf".equals(suffix)) {
                text = extractPdf(file);
            } else {
                status = "unsupported";
            }
        } catch (Exception e) {
            status = "error";
        }
        text = clean(text);
        List<String> keywords = extractKeywords(text);
        JSONObject payload = new JSONObject();
        payload.put("status", status);
        payload.put("extracted_text", text);
        payload.put("keywords", keywords);
        JSONObject facts = new JSONObject();
        facts.put("skills", keywords);
        payload.put("facts", facts);
        return payload;
    }

    private static String extractDocx(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zip = new ZipFile(file)) {
            java.util.zip.ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                return "";
            }
            byte[] bytes;
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                bytes = readAll(in);
            }
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(bytes));
            NodeList nodes = doc.getElementsByTagName("w:t");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                String text = node.getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(text.trim()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String extractPdf(File file) throws IOException {
        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            StringBuilder sb = new StringBuilder();
            for (String line : text.split("\n")) {
                String trimmed = line.replaceAll("[ \\t]+", " ").trim();
                if (!trimmed.isEmpty()) {
                    sb.append(trimmed).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String cleaned = line.replaceAll("[ \\t]+", " ").trim();
            if (!cleaned.isEmpty()) {
                sb.append(cleaned).append('\n');
            }
        }
        return sb.toString();
    }

    static List<String> extractKeywords(String text) {
        String lowered = text == null ? "" : text.toLowerCase();
        List<int[]> found = new ArrayList<>();
        for (int i = 0; i < KEYWORD_TERMS.length; i++) {
            int index = lowered.indexOf(KEYWORD_TERMS[i].toLowerCase());
            if (index >= 0) {
                found.add(new int[] {index, i});
            }
        }
        found.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < found.size() && i < 20; i++) {
            result.add(KEYWORD_TERMS[found.get(i)[1]]);
        }
        return result;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
