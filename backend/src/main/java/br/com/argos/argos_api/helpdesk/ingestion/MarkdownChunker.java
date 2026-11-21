package br.com.argos.argos_api.helpdesk.ingestion;

import br.com.argos.argos_api.helpdesk.HelpdeskProperties;
import br.com.argos.argos_api.helpdesk.model.ChunkedDocument;
import br.com.argos.argos_api.helpdesk.model.KnowledgeChunk;
import br.com.argos.argos_api.helpdesk.model.ParentSection;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure-aware, parent-child chunking of Markdown documents.
 *
 * <ol>
 *   <li>The document is read heading by heading (code fences are respected), keeping the path of headings above
 *       each section ("Incidentes > Severidades > SEV1").</li>
 *   <li><b>Parents</b>: each section at {@code parentHeadingLevel} or above, together with its subsections, is one
 *       parent, the context the model reads. Oversized parents are split at subsection, then paragraph boundaries.</li>
 *   <li><b>Children</b>: each section body is cut at block boundaries (paragraphs, lists, tables and code blocks are
 *       never cut in the middle unless a single block is too long), into pieces of about {@code childTargetChars}.
 *       Children are what the retrievers search: small enough to be precise.</li>
 *   <li>Every child is <b>contextualized</b>: the document title and its heading path are prepended to the text that
 *       is indexed and embedded, so "Clique em Testar" still knows it belongs to "Integrações > Slack".</li>
 * </ol>
 */
public class MarkdownChunker {

    /** Bumped whenever the chunking changes, so every document is re-indexed. */
    public static final int VERSION = 1;

    public record SourceFile(String source, String content) {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?;:])\\s+");
    private static final int MIN_CHILD_CHARS = 120;

    private final int parentHeadingLevel;
    private final int maxParentChars;
    private final int childTargetChars;
    private final int childMaxChars;

    public MarkdownChunker(HelpdeskProperties.Chunking settings) {
        this(settings.getParentHeadingLevel(), settings.getMaxParentChars(), settings.getChildTargetChars(),
                settings.getChildMaxChars());
    }

    public MarkdownChunker(int parentHeadingLevel, int maxParentChars, int childTargetChars, int childMaxChars) {
        this.parentHeadingLevel = parentHeadingLevel;
        this.maxParentChars = maxParentChars;
        this.childTargetChars = childTargetChars;
        this.childMaxChars = Math.max(childMaxChars, childTargetChars);
    }

    /** Settings that change the output; part of each document's fingerprint. */
    public String signature() {
        return "md-v" + VERSION + "/" + parentHeadingLevel + "/" + maxParentChars + "/" + childTargetChars + "/" + childMaxChars;
    }

    public ChunkedDocument chunk(SourceFile file) {
        FrontMatter frontMatter = FrontMatter.parse(file.content());
        List<Section> sections = parse(frontMatter.body());
        String title = frontMatter.title() != null ? frontMatter.title()
                : sections.stream().filter(s -> s.level == 1).map(s -> s.heading).findFirst()
                .orElse(fallbackTitle(file.source()));
        String docId = docId(file.source());

        List<ParentSection> parents = new ArrayList<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (List<Section> group : groups(sections)) {
            for (List<Section> part : splitToFit(group)) {
                if (part.stream().allMatch(section -> section.bodyText().isEmpty())) {
                    continue;
                }
                String parentId = docId + "::p" + (parents.size() + 1);
                parents.add(new ParentSection(parentId, docId, title, path(part.get(0)), file.source(), render(part),
                        parents.size() + 1));
                for (Section section : part) {
                    String sectionPath = path(section);
                    for (String piece : children(section.bodyText())) {
                        String chunkId = parentId + "::c" + (chunks.size() + 1);
                        chunks.add(new KnowledgeChunk(chunkId, parentId, docId, title, sectionPath, file.source(), piece,
                                contextual(title, sectionPath, piece), chunks.size() + 1));
                    }
                }
            }
        }
        return new ChunkedDocument(docId, title, file.source(), List.copyOf(parents), List.copyOf(chunks));
    }

    /** Text that is indexed (full-text) and embedded for a child: where it comes from, then what it says. */
    public static String contextual(String title, String sectionPath, String body) {
        StringBuilder text = new StringBuilder("Documento: ").append(title);
        if (!sectionPath.isEmpty()) {
            text.append("\nSeção: ").append(sectionPath);
        }
        return text.append("\n\n").append(body).toString();
    }

    // ---------------------------------------------------------------- parsing

    private static final class Section {
        final int level;
        final String heading;
        final List<String> path;
        final StringBuilder body = new StringBuilder();

        Section(int level, String heading, List<String> path) {
            this.level = level;
            this.heading = heading;
            this.path = path;
        }

        String bodyText() {
            return body.toString().strip();
        }

        Section withBody(String text) {
            Section copy = new Section(level, heading, path);
            copy.body.append(text);
            return copy;
        }

        String markdown() {
            String text = bodyText();
            if (heading == null) {
                return text;
            }
            String headingLine = "#".repeat(level) + " " + heading;
            return text.isEmpty() ? headingLine : headingLine + "\n\n" + text;
        }
    }

    private List<Section> parse(String markdown) {
        List<Section> sections = new ArrayList<>();
        Section current = new Section(0, null, List.of());
        sections.add(current);
        String[] stack = new String[7];
        boolean inFence = false;
        for (String line : markdown.split("\\R", -1)) {
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
                current.body.append(line).append('\n');
                continue;
            }
            Matcher heading = inFence ? null : HEADING.matcher(line);
            if (heading != null && heading.matches()) {
                int level = heading.group(1).length();
                String text = heading.group(2).strip();
                stack[level] = text;
                Arrays.fill(stack, level + 1, stack.length, null);
                List<String> path = new ArrayList<>();
                for (int l = 2; l <= level; l++) { // the H1 is the document title, shown apart
                    if (stack[l] != null) {
                        path.add(stack[l]);
                    }
                }
                current = new Section(level, text, List.copyOf(path));
                sections.add(current);
            } else {
                current.body.append(line).append('\n');
            }
        }
        return sections;
    }

    /** Sections at the parent level or above open a group; deeper sections join the open group. */
    private List<List<Section>> groups(List<Section> sections) {
        List<List<Section>> groups = new ArrayList<>();
        List<Section> open = null;
        for (Section section : sections) {
            if (open == null || section.level <= parentHeadingLevel) {
                open = new ArrayList<>();
                groups.add(open);
            }
            open.add(section);
        }
        return groups;
    }

    /** Oversized groups are split at subsection boundaries, oversized sections at block boundaries. */
    private List<List<Section>> splitToFit(List<Section> group) {
        if (render(group).length() <= maxParentChars) {
            return List.of(group);
        }
        List<Section> pieces = new ArrayList<>();
        for (Section section : group) {
            if (section.markdown().length() <= maxParentChars) {
                pieces.add(section);
            } else {
                for (String part : pack(blocks(section.bodyText()), maxParentChars - section.markdown().length()
                        + section.bodyText().length())) {
                    pieces.add(section.withBody(part));
                }
            }
        }
        List<List<Section>> parts = new ArrayList<>();
        List<Section> current = new ArrayList<>();
        for (Section piece : pieces) {
            List<Section> candidate = new ArrayList<>(current);
            candidate.add(piece);
            if (!current.isEmpty() && render(candidate).length() > maxParentChars) {
                parts.add(current);
                current = new ArrayList<>();
            }
            current.add(piece);
        }
        if (!current.isEmpty()) {
            parts.add(current);
        }
        return parts;
    }

    private static String render(List<Section> sections) {
        return String.join("\n\n", sections.stream().map(Section::markdown).filter(text -> !text.isEmpty()).toList());
    }

    private static String path(Section section) {
        return String.join(" > ", section.path);
    }

    // ---------------------------------------------------------------- children

    private List<String> children(String body) {
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> pieces = new ArrayList<>(pack(blocks(body), childTargetChars));
        // A tiny trailing piece reads better glued to the previous one.
        if (pieces.size() > 1) {
            String last = pieces.get(pieces.size() - 1);
            String previous = pieces.get(pieces.size() - 2);
            if (last.length() < MIN_CHILD_CHARS && previous.length() + last.length() + 2 <= childMaxChars) {
                pieces.set(pieces.size() - 2, previous + "\n\n" + last);
                pieces.remove(pieces.size() - 1);
            }
        }
        return pieces;
    }

    /** Paragraphs, lists, tables and fenced code blocks, in order. */
    static List<String> blocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\\R", -1)) {
            boolean fence = FENCE.matcher(line).find();
            if (!inFence && !fence && line.isBlank()) {
                flush(blocks, current);
                continue;
            }
            current.append(line).append('\n');
            if (fence) {
                inFence = !inFence;
                if (!inFence) {
                    flush(blocks, current);
                }
            }
        }
        flush(blocks, current);
        return blocks;
    }

    private static void flush(List<String> blocks, StringBuilder current) {
        String block = current.toString().strip();
        if (!block.isEmpty()) {
            blocks.add(block);
        }
        current.setLength(0);
    }

    /** Packs blocks into pieces of about {@code target} chars; a block longer than the hard limit is split. */
    private List<String> pack(List<String> blocks, int target) {
        int limit = Math.max(target, childMaxChars);
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (block.length() > limit) {
                if (!current.isEmpty()) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                pieces.addAll(splitLongBlock(block, limit));
                continue;
            }
            if (!current.isEmpty() && current.length() + 2 + block.length() > target) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(block);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /** Code and lists split by line, prose by sentence; as a last resort by words. */
    private static List<String> splitLongBlock(String block, int limit) {
        boolean lineOriented = block.startsWith("```") || block.startsWith("~~~") || block.startsWith("|")
                || block.startsWith("- ") || block.startsWith("* ") || block.matches("(?s)^\\d+\\.\\s.*");
        String[] units = lineOriented ? block.split("\\R") : SENTENCE_BREAK.split(block);
        String separator = lineOriented ? "\n" : " ";
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            for (String part : unit.length() > limit ? splitWords(unit, limit) : List.of(unit)) {
                if (!current.isEmpty() && current.length() + separator.length() + part.length() > limit) {
                    pieces.add(current.toString().strip());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append(separator);
                }
                current.append(part);
            }
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString().strip());
        }
        return pieces;
    }

    private static List<String> splitWords(String text, int limit) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > limit) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    // ---------------------------------------------------------------- identity

    /** Stable id from the file path: "guias/Incidentes.md" -> "guias-incidentes". */
    public static String docId(String source) {
        String withoutExtension = source.replaceFirst("\\.(md|markdown)$", "");
        String ascii = Normalizer.normalize(withoutExtension, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "documento" : slug;
    }

    private static String fallbackTitle(String source) {
        String name = source.replaceAll("^.*/", "").replaceFirst("\\.(md|markdown)$", "").replace('-', ' ').replace('_', ' ');
        return name.isEmpty() ? "Documento" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Optional YAML-like header: {@code ---\ntitle: ...\n---}. */
    record FrontMatter(String title, String body) {
        static FrontMatter parse(String content) {
            String text = content.replace("\r\n", "\n");
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            if (!text.startsWith("---\n")) {
                return new FrontMatter(null, text);
            }
            int end = text.indexOf("\n---", 4);
            if (end < 0) {
                return new FrontMatter(null, text);
            }
            String title = null;
            for (String line : text.substring(4, end).split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).strip().equalsIgnoreCase("title")) {
                    title = line.substring(colon + 1).strip().replaceAll("^[\"']|[\"']$", "");
                }
            }
            int bodyStart = text.indexOf('\n', end + 4);
            return new FrontMatter(title == null || title.isBlank() ? null : title,
                    bodyStart < 0 ? "" : text.substring(bodyStart + 1));
        }
    }
}
