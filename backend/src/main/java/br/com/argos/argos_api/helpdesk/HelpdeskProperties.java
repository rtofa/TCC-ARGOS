package br.com.argos.argos_api.helpdesk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings of the support agent ({@code argos.helpdesk.*}); every one can be overridden by an environment variable
 * (e.g. {@code ARGOS_HELPDESK_RETRIEVAL_FINAL_TOP_K=8}). Validated at startup.
 */
@ConfigurationProperties(prefix = "argos.helpdesk")
@Validated
@Getter
@Setter
public class HelpdeskProperties {

    @Valid
    private KnowledgeBase knowledgeBase = new KnowledgeBase();
    @Valid
    private Store store = new Store();
    @Valid
    private Chunking chunking = new Chunking();
    @Valid
    private Retrieval retrieval = new Retrieval();
    @Valid
    private Generation generation = new Generation();
    @Valid
    private Guardrails guardrails = new Guardrails();
    @Valid
    private Execution execution = new Execution();

    @Getter
    @Setter
    public static class KnowledgeBase {
        /** Where the official documents (Markdown) live: {@code classpath:...} or {@code file:/...}. */
        @NotBlank
        private String location = "classpath:knowledge-base/";
        /** Index the documents when the application starts (unchanged documents are skipped). */
        private boolean ingestOnStartup = true;
        /** First wait before retrying a failed startup indexing (the search cluster may still be starting). */
        @NotNull
        private Duration retryInitialDelay = Duration.ofSeconds(5);
        @NotNull
        private Duration retryMaxDelay = Duration.ofMinutes(5);
    }

    @Getter
    @Setter
    public static class Store {
        /** pgvector table of the child chunks, created by LangChain4j when embeddings are configured. */
        @Pattern(regexp = "[a-z_][a-z0-9_]{0,62}", message = "nome de tabela inválido")
        private String chunksTable = "helpdesk_chunks";
        /** PostgreSQL text search configuration (dictionary) used by the full-text side of the hybrid search. */
        @Pattern(regexp = "[a-z_]+", message = "configuração de busca textual inválida")
        private String textSearchConfig = "portuguese";
        /** Attempts per database call (first included), with exponential backoff between them. */
        @Min(1)
        @Max(10)
        private int maxAttempts = 3;
        @NotNull
        private Duration retryInitialDelay = Duration.ofMillis(200);
        @NotNull
        private Duration retryMaxDelay = Duration.ofSeconds(2);
    }

    @Getter
    @Setter
    public static class Chunking {
        /** Headings up to this level start a new parent section (2 = each "##" section is a parent). */
        @Min(1)
        @Max(4)
        private int parentHeadingLevel = 2;
        @Min(500)
        private int maxParentChars = 3000;
        @Min(100)
        private int childTargetChars = 600;
        @Min(200)
        private int childMaxChars = 900;
    }

    @Getter
    @Setter
    public static class Retrieval {
        /** At most this many queries (the question and its reformulations) are searched. */
        @Min(1)
        @Max(5)
        private int maxQueries = 3;
        /** Results of each hybrid (or full-text) search. */
        @Min(1)
        @Max(100)
        private int topK = 20;
        /** Reciprocal Rank Fusion constant, score = Σ 1 / (k + rank), inside each hybrid search and across queries. */
        @Min(1)
        private int rrfK = 60;
        /** Fused candidates sent to the reranker. */
        @Min(1)
        @Max(100)
        private int rerankCandidates = 20;
        /** Child chunks kept after reranking (their parents become the context). */
        @Min(1)
        @Max(20)
        private int finalTopK = 6;
        /** Cohere relevance score (0..1) below which a chunk is dropped. */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double minRerankScore = 0.10;
        /** Without a reranker, a chunk counts as relevant with this many words of the question... */
        @Min(1)
        @Max(10)
        private int minLexicalOverlap = 2;
        /** ...or with at least this cosine similarity to the question. */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double minSemanticSimilarity = 0.30;
        /** Parent sections given to the model. */
        @Min(1)
        @Max(12)
        private int maxPassages = 5;
        /** Longest parent used whole; longer ones contribute only their matching children. */
        @Min(500)
        private int maxPassageChars = 3500;
        /** Budget for all passages together (keeps prompts within free-tier token limits). */
        @Min(1000)
        private int maxContextChars = 12000;
        @NotNull
        private Duration timeout = Duration.ofSeconds(20);
    }

    @Getter
    @Setter
    public static class Generation {
        /** Extra searches the model may run with the documentation tool before answering. */
        @Min(0)
        @Max(3)
        private int maxToolCalls = 1;
        /** Earlier turns used to understand follow-up questions. */
        @Min(0)
        @Max(12)
        private int maxHistoryTurns = 6;
        /** Longest wait for the query-understanding step before falling back to the heuristic. */
        @NotNull
        private Duration analysisTimeout = Duration.ofSeconds(12);
    }

    @Getter
    @Setter
    public static class Guardrails {
        @Min(10)
        @Max(4000)
        private int maxQuestionChars = 1000;
        /** Share of the answer's content words that must appear in the retrieved passages. */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double minGroundedness = 0.55;
        /** Numbers, codes and paths in the answer must appear in the passages. */
        private boolean checkFacts = true;
    }

    @Getter
    @Setter
    public static class Execution {
        /** Questions answered at the same time (bulkhead); the rest get HTTP 429. */
        @Min(1)
        @Max(500)
        private int maxConcurrent = 16;
        @NotNull
        private Duration acquireTimeout = Duration.ofSeconds(2);
        /** Longest a whole answer may take, streaming included. */
        @NotNull
        private Duration timeout = Duration.ofSeconds(120);
        /**
         * How often an idle stream gets an SSE comment, so proxies (CloudFront closes after 60 s without data, the
         * ALB after its idle timeout) keep the connection while the model is still thinking or waiting out a rate limit.
         */
        @NotNull
        private Duration heartbeatInterval = Duration.ofSeconds(15);
    }
}
