package br.com.argos.argos_api.ai.assistant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Conversation shown in the console (the model's memory is only a window of it). */
@Repository
public class ChatMessageRepository {

    public record ChatMessageRow(UUID id, UUID incidentId, String role, UUID authorId, String content, String status,
                                 String model, int inputTokens, int outputTokens, int cacheReadTokens, String guardrail,
                                 Instant createdAt, Instant answeredAt) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ChatMessageRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * Stores the question and a pending answer atomically. Empty when the incident already has a pending answer:
     * one question at a time keeps the conversation memory in order.
     */
    public Optional<UUID> ask(UUID organizationId, UUID incidentId, UUID authorId, String question) {
        try {
            return transactions.execute(status -> {
                jdbc.update("INSERT INTO ai_chat_messages (id, organization_id, incident_id, role, author_id, content, status) "
                        + "VALUES (?, ?, ?, 'USER', ?, ?, 'COMPLETED')", UUID.randomUUID(), organizationId, incidentId,
                        authorId, question);
                UUID answerId = UUID.randomUUID();
                jdbc.update("INSERT INTO ai_chat_messages (id, organization_id, incident_id, role, status, created_at) "
                        + "VALUES (?, ?, ?, 'ASSISTANT', 'PENDING', NOW() + interval '1 millisecond')", answerId,
                        organizationId, incidentId);
                return Optional.of(answerId);
            });
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public void answer(UUID organizationId, UUID messageId, String content, String model, int inputTokens,
                       int outputTokens, int cacheReadTokens, String guardrail) {
        jdbc.update("""
                UPDATE ai_chat_messages SET content = ?, status = 'COMPLETED', model = ?, input_tokens = ?,
                       output_tokens = ?, cache_read_tokens = ?, guardrail = ?, answered_at = NOW()
                WHERE id = ? AND organization_id = ? AND status = 'PENDING'
                """, content, model, inputTokens, outputTokens, cacheReadTokens, guardrail, messageId, organizationId);
    }

    public void fail(UUID organizationId, UUID messageId, String content) {
        jdbc.update("UPDATE ai_chat_messages SET content = ?, status = 'FAILED', answered_at = NOW() "
                + "WHERE id = ? AND organization_id = ? AND status = 'PENDING'", content, messageId, organizationId);
    }

    public List<ChatMessageRow> list(UUID organizationId, UUID incidentId, int limit) {
        return jdbc.query("""
                SELECT * FROM (
                    SELECT id, incident_id, role, author_id, content, status, model, input_tokens, output_tokens,
                           cache_read_tokens, guardrail, created_at, answered_at
                    FROM ai_chat_messages WHERE organization_id = ? AND incident_id = ?
                    ORDER BY created_at DESC LIMIT ?
                ) recent ORDER BY created_at
                """, (rs, i) -> new ChatMessageRow(rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                rs.getString("role"), rs.getObject("author_id", UUID.class), rs.getString("content"),
                rs.getString("status"), rs.getString("model"), rs.getInt("input_tokens"), rs.getInt("output_tokens"),
                rs.getInt("cache_read_tokens"), rs.getString("guardrail"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("answered_at") == null ? null : rs.getTimestamp("answered_at").toInstant()),
                organizationId, incidentId, limit);
    }

    /** Watchdog for answers lost to a crash or restart. */
    public int failStale(Duration timeout) {
        Integer touched = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.system_jobs', 'on', true)", String.class);
            return jdbc.update("UPDATE ai_chat_messages SET status = 'FAILED', answered_at = NOW(), "
                    + "content = 'A resposta não ficou pronta a tempo. Pergunte novamente.' "
                    + "WHERE status = 'PENDING' AND created_at < ?", Timestamp.from(Instant.now().minus(timeout)));
        });
        return touched == null ? 0 : touched;
    }
}
