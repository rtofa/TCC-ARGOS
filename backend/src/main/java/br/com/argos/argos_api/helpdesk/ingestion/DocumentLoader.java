package br.com.argos.argos_api.helpdesk.ingestion;

import br.com.argos.argos_api.helpdesk.ingestion.MarkdownChunker.SourceFile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the official documents (Markdown) from the configured location: the classpath for the documents shipped
 * with the application, or a directory ({@code file:/...}) for documents maintained outside the build.
 */
public class DocumentLoader {

    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private final ResourcePatternResolver resolver;
    private final String location;

    public DocumentLoader(ResourcePatternResolver resolver, String location) {
        this.resolver = resolver;
        this.location = location.endsWith("/") ? location : location + "/";
    }

    public String location() {
        return location;
    }

    public List<SourceFile> load() {
        try {
            String pattern = location.startsWith("classpath:") && !location.startsWith("classpath*:")
                    ? "classpath*:" + location.substring("classpath:".length()) : location;
            String base = resolver.getResource(location).getURL().toString();
            List<SourceFile> files = new ArrayList<>();
            for (Resource resource : resolver.getResources(pattern + "**/*.md")) {
                if (!resource.isReadable() || resource.contentLength() > MAX_BYTES) {
                    continue;
                }
                String url = resource.getURL().toString();
                String source = url.startsWith(base) ? url.substring(base.length()) : resource.getFilename();
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    files.add(new SourceFile(source, content));
                }
            }
            files.sort(Comparator.comparing(SourceFile::source));
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler os documentos em " + location, e);
        }
    }
}
