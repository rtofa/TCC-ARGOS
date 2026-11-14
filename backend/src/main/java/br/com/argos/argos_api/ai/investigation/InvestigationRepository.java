package br.com.argos.argos_api.ai.investigation;

import br.com.argos.argos_api.ai.agent.AgentContext.GuardrailEvent;
import br.com.argos.argos_api.ai.agent.AgentContext.Step;
import br.com.argos.argos_api.ai.agent.InvestigationOutcome;
import br.com.argos.argos_api.ai.agent.InvestigationReport;
import br.com.argos.argos_api.ai.llm.LlmCallLog.LlmCall;
import br.com.argos.argos_api.ai.investigation.Investigation.Claim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of investigations, also used as a durable work queue. Claims use {@code FOR UPDATE SKIP LOCKED}, so
 * several API instances can poll the same table without double work, and pending work survives restarts.
 */
@Repository
public class InvestigationRepository {

    private static final String COLUMNS = """
            id, organization_id, incident_id, status, trigger, mode, requested_by, attempts, run_after,
            report::text AS report, steps::text AS steps, guardrail_events::text AS guardrail_events,
            llm_calls::text AS llm_calls, model, fallback_used, input_tokens, output_tokens, cache_read_tokens,
            cache_write_tokens, cost_usd, tool_calls, error, created_at, started_at, finished_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final RowMapper<Investigation> mapper = this::map;

    public InvestigationRepository(JdbcTemplate jdbc, TransactionTemplate transactions, JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    /** Creates a pending investigation unless one is already pending or running for the incident (idempotent). */
    public Investigation enqueue(UUID organizationId, UUID incidentId, String trigger, UUID requestedBy, Instant runAfter) {
        List<Investigation> inserted = jdbc.query("""
                INSERT INTO ai_investigations (id, organization_id, incident_id, status, trigger, requested_by, run_after)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                ON CONFLICT (incident_id) WHERE status IN ('PENDING', 'RUNNING') DO NOTHING
                """ + " RETURNING " + COLUMNS,
                mapper, UUID.randomUUID(), organizationId, incidentId, trigger, requestedBy, Timestamp.from(runAfter));
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        return findActive(organizationId, incidentId).orElseThrow(() ->
                new IllegalStateException("Investigação ativa sumiu durante a criação"));
    }

    public Optional<Investigation> findActive(UUID organizationId, UUID incidentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_investigations WHERE organization_id = ? AND incident_id = ? "
                + "AND status IN ('PENDING', 'RUNNING')", mapper, organizationId, incidentId).stream().findFirst();
    }

    public Optional<Investigation> latest(UUID organizationId, UUID incidentId) {
        return history(organizationId, incidentId, 1).stream().findFirst();
    }

    public List<Investigation> history(UUID organizationId, UUID incidentId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_investigations WHERE organization_id = ? AND incident_id = ? "
                + "ORDER BY created_at DESC LIMIT ?", mapper, organizationId, incidentId, limit);
    }

    /** Status and conclusion of the latest investigation of each incident, for the console home. */
    public record LatestSummary(UUID incidentId, String status, String mode, String rootCause, String confidence) {
    }

    public java.util.Map<UUID, LatestSummary> latestSummaries(UUID organizationId, java.util.Collection<UUID> incidentIds) {
        if (incidentIds.isEmpty()) {
            return java.util.Map.of();
        }
        String[] ids = incidentIds.stream().map(UUID::toString).toArray(String[]::new);
        return jdbc.query("""
                SELECT DISTINCT ON (incident_id) incident_id, status, mode,
                       report -> 'rootCause' ->> 'summary' AS root_cause, report -> 'rootCause' ->> 'confidence' AS confidence
                FROM ai_investigations
                WHERE organization_id = ? AND incident_id = ANY (?::uuid[])
                ORDER BY incident_id, created_at DESC
                """, (rs, i) -> new LatestSummary(rs.getObject("incident_id", UUID.class), rs.getString("status"),
                rs.getString("mode"), rs.getString("root_cause"), rs.getString("confidence")), organizationId, ids)
                .stream().collect(java.util.stream.Collectors.toMap(LatestSummary::incidentId, s -> s));
    }

    /** Latest completed report of an incident, for the assistant's context. */
    public Optional<InvestigationReport> latestReport(UUID organizationId, UUID incidentId) {
        return jdbc.queryForList("SELECT report::text FROM ai_investigations WHERE organization_id = ? AND incident_id = ? "
                        + "AND status = 'COMPLETED' AND report IS NOT NULL ORDER BY finished_at DESC LIMIT 1",
                String.class, organizationId, incidentId).stream().findFirst()
                .map(text -> json.readValue(text, InvestigationReport.class));
    }

    /**
     * Claims up to {@code max} due items, respecting a per-organization limit of running investigations so one noisy
     * tenant cannot starve the others.
     */
    public List<Claim> claimDue(int max, int maxRunningPerOrganization) {
        if (max <= 0) {
            return List.of();
        }
        return transactions.execute(status -> {
            enterSystemScope();
            return jdbc.query("""
                    UPDATE ai_investigations a
                    SET status = 'RUNNING', attempts = a.attempts + 1, started_at = NOW()
                    WHERE a.id IN (
                        SELECT c.id FROM ai_investigations c
                        WHERE c.status = 'PENDING' AND c.run_after <= NOW()
                          AND (SELECT count(*) FROM ai_investigations r
                               WHERE r.organization_id = c.organization_id AND r.status = 'RUNNING') < ?
                        ORDER BY c.run_after
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED)
                    RETURNING a.id, a.organization_id, a.incident_id, a.attempts
                    """, (rs, i) -> new Claim(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                    rs.getObject("incident_id", UUID.class), rs.getInt("attempts")), maxRunningPerOrganization, max);
        });
    }

    /** Gives a claimed item back to the queue (the worker pool rejected it). */
    public void release(Claim claim) {
        transactions.executeWithoutResult(status -> {
            enterSystemScope();
            jdbc.update("UPDATE ai_investigations SET status = 'PENDING', attempts = attempts - 1, started_at = NULL "
                    + "WHERE id = ? AND status = 'RUNNING' AND attempts = ?", claim.id(), claim.attempt());
        });
    }

    /** Stores the result; ignored when the watchdog already took this attempt away. */
    public boolean complete(Claim claim, InvestigationOutcome outcome) {
        return jdbc.update("""
                UPDATE ai_investigations SET status = 'COMPLETED', mode = ?, report = ?::jsonb, steps = ?::jsonb,
                       guardrail_events = ?::jsonb, llm_calls = ?::jsonb, model = ?, fallback_used = ?,
                       input_tokens = ?, output_tokens = ?, cache_read_tokens = ?, cache_write_tokens = ?, cost_usd = ?,
                       tool_calls = ?, error = ?, finished_at = NOW()
                WHERE id = ? AND organization_id = ? AND status = 'RUNNING' AND attempts = ?
                """, outcome.mode(), json.writeValueAsString(outcome.report()), json.writeValueAsString(outcome.steps()),
                json.writeValueAsString(outcome.guardrailEvents()), json.writeValueAsString(outcome.llmCalls()),
                outcome.model(), outcome.fallbackUsed(), outcome.inputTokens(), outcome.outputTokens(),
                outcome.cacheReadTokens(), outcome.cacheWriteTokens(), outcome.costUsd(), outcome.toolCalls(),
                outcome.note(), claim.id(), claim.organizationId(), claim.attempt()) == 1;
    }

    public void fail(Claim claim, String error) {
        jdbc.update("UPDATE ai_investigations SET status = 'FAILED', error = ?, finished_at = NOW() "
                        + "WHERE id = ? AND organization_id = ? AND status = 'RUNNING' AND attempts = ?",
                abbreviate(error), claim.id(), claim.organizationId(), claim.attempt());
    }

    /**
     * Watchdog: runs that exceeded the timeout (a crashed instance, a hung call) go back to the queue while attempts
     * remain, otherwise they fail. Returns how many rows were touched.
     */
    public int recoverStale(Duration timeout, int maxAttempts) {
        Integer touched = transactions.execute(status -> {
            enterSystemScope();
            Timestamp limit = Timestamp.from(Instant.now().minus(timeout));
            int requeued = jdbc.update("UPDATE ai_investigations SET status = 'PENDING', run_after = NOW(), started_at = NULL "
                    + "WHERE status = 'RUNNING' AND started_at < ? AND attempts < ?", limit, maxAttempts);
            int failed = jdbc.update("UPDATE ai_investigations SET status = 'FAILED', finished_at = NOW(), "
                    + "error = 'Tempo esgotado: a investigação não terminou dentro do limite' "
                    + "WHERE status = 'RUNNING' AND started_at < ? AND attempts >= ?", limit, maxAttempts);
            return requeued + failed;
        });
        return touched == null ? 0 : touched;
    }

    /** Lets the queue jobs see every organization under RLS (see migration V8); scoped to the transaction. */
    private void enterSystemScope() {
        jdbc.queryForObject("SELECT set_config('app.system_jobs', 'on', true)", String.class);
    }

    private Investigation map(ResultSet rs, int row) throws SQLException {
        return new Investigation(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getString("status"), rs.getString("trigger"),
                rs.getString("mode"), rs.getObject("requested_by", UUID.class), rs.getInt("attempts"),
                instant(rs.getTimestamp("run_after")),
                read(rs.getString("report"), new TypeReference<InvestigationReport>() { }),
                list(rs.getString("steps"), new TypeReference<List<Step>>() { }),
                list(rs.getString("guardrail_events"), new TypeReference<List<GuardrailEvent>>() { }),
                list(rs.getString("llm_calls"), new TypeReference<List<LlmCall>>() { }),
                rs.getString("model"), rs.getBoolean("fallback_used"), rs.getInt("input_tokens"),
                rs.getInt("output_tokens"), rs.getInt("cache_read_tokens"), rs.getInt("cache_write_tokens"),
                rs.getBigDecimal("cost_usd"), rs.getInt("tool_calls"), rs.getString("error"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")));
    }

    private <T> T read(String text, TypeReference<T> type) {
        return text == null ? null : json.readValue(text, type);
    }

    private <T> List<T> list(String text, TypeReference<List<T>> type) {
        List<T> value = read(text, type);
        return value == null ? List.of() : value;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1000 ? text : text.substring(0, 999) + "…";
    }
}
