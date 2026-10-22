package cn.redture.campus.rag.retrieval;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Recognition is independent of availability: a missing curriculum must not broaden retrieval. */
public record CurriculumRoute(List<Long> docIds) {
    private static final List<String> KNOWN_MAJORS = loadMajorNames();
    private static final java.util.Map<String, String> MAJOR_ALIASES = loadAliases();
    private static final Pattern EXPLICIT_MAJOR = Pattern.compile(
            "^(?:请问\\s*)?(?:(?:20\\d{2}|\\d{2})(?:级|届|年|版)?\\s*(?:的\\s*)?)?([\\p{IsHan}]{2,30}?)专业(?=的|课程|培养|执行|最低|毕业|学分|$)");

    public static String effectiveMajor(String question, String requestedMajor, List<CurriculumDocument> documents) {
        var vocabulary = new LinkedHashSet<>(KNOWN_MAJORS);
        documents.stream().map(CurriculumRoute::subject).filter(s -> !s.isBlank()).forEach(vocabulary::add);
        if (requestedMajor != null && !requestedMajor.isBlank()) vocabulary.add(requestedMajor.trim());
        var explicit = EXPLICIT_MAJOR.matcher(question.trim());
        if (explicit.find() && !explicit.group(1).matches("(?:我|你|他|她|本|该|这|那|各|所|什么|哪|所有|不同).*")) {
            vocabulary.add(explicit.group(1));
        }
        // An abbreviated major must be tied to a curriculum intent, not a course like 计算机网络.
        String resolvedQuestion = question;
        for (var alias : MAJOR_ALIASES.entrySet()) {
            resolvedQuestion = resolvedQuestion.replaceAll(java.util.regex.Pattern.quote(alias.getKey())
                    + "(?=(?:专业|的?培养方案|的?执行计划))", java.util.regex.Matcher.quoteReplacement(alias.getValue()));
        }
        String namedQuestion = resolvedQuestion;
        var subjects = vocabulary.stream().filter(s -> s.length() >= 2 && namedQuestion.contains(s)).toList();
        var independent = subjects.stream().filter(s -> subjects.stream().noneMatch(other -> !other.equals(s) && other.contains(s))).toList();
        if (independent.size() > 1) throw new IllegalArgumentException("请一次查询一个专业，避免混用培养方案");
        return independent.isEmpty() ? (requestedMajor == null || requestedMajor.isBlank() ? null : requestedMajor.trim()) : independent.getFirst();
    }

    public static List<Long> excludedMajors(String major, List<CurriculumDocument> documents) {
        if (major == null || major.isBlank()) return List.of();
        return documents.stream().filter(d -> !subject(d).isBlank() && !subject(d).equals(major.trim()))
                .map(CurriculumDocument::getDocId).distinct().toList();
    }

    public static String inferMajor(String text) {
        var header = java.util.regex.Pattern.compile("(?m)^\\s*(20\\d{2}(?:级|年|版)?[^\\r\\n]{2,80}?(?:培养方案|执行计划))\\s*$").matcher(text);
        if (!header.find()) return null;
        var doc = new CurriculumDocument();
        doc.setTitle(header.group(1));
        return subject(doc).replaceFirst("专业$", "");
    }

    public static CurriculumRoute resolve(String question, AcademicScope scope, List<CurriculumDocument> documents) {
        String major = effectiveMajor(question, null, documents);
        return forMajor(major, scope, documents);
    }

    /** A resolved context major need not occur in the routing vocabulary or question text. */
    public static CurriculumRoute forMajor(String major, AcademicScope scope, List<CurriculumDocument> documents) {
        if (major == null) return new CurriculumRoute(null);
        return new CurriculumRoute(documents.stream().filter(d -> major.equals(subject(d)))
                .filter(d -> scope.grade() == null || scope.grade().equals(d.getTargetGrade()))
                .map(CurriculumDocument::getDocId).distinct().toList());
    }

    private static List<String> loadMajorNames() {
        // Keep recognized campus majors even when their documents are removed or have not been ingested.
        var stream = Objects.requireNonNull(CurriculumRoute.class.getResourceAsStream("/rag/major-names.txt"),
                "Missing RAG major routing vocabulary");
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).distinct().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load RAG major routing vocabulary", e);
        }
    }

    private static java.util.Map<String, String> loadAliases() {
        var aliases = new java.util.Properties();
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                CurriculumRoute.class.getResourceAsStream("/rag/major-aliases.properties")), StandardCharsets.UTF_8)) {
            aliases.load(reader);
            var result = new java.util.LinkedHashMap<String, String>();
            for (String key : aliases.stringPropertyNames()) {
                String major = aliases.getProperty(key).trim();
                if (!KNOWN_MAJORS.contains(major)) throw new IllegalStateException("Alias references an unregistered major");
                result.put(key, major);
            }
            return java.util.Collections.unmodifiableMap(result);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static String subject(CurriculumDocument d) {
        if (d.getTargetMajor() != null && !d.getTargetMajor().isBlank()) return d.getTargetMajor().trim();
        if (!d.getTitle().matches("^20\\d{2}.*")) return "";
        return d.getTitle().replaceFirst("^20\\d{2}(?:级|年|版)?", "")
                .replaceAll("(?:专业)?(?:培养方案|执行计划)$", "").trim();
    }

    public boolean unavailable() { return docIds != null && docIds.isEmpty(); }
}
