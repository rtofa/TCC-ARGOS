package br.com.argos.argos_api.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Short-term memory of the assistant, persisted in PostgreSQL so a conversation survives restarts and is shared by
 * every instance. LangChain4j decides what goes in (a window of recent messages); this class only stores it.
 *
 * <p>Memory ids carry the organization ({@code assistant:<org>:<incident>}) and every query filters by it, so one
 * tenant can never load another tenant's conversation even with a forged id.
 */
@Component
public class JdbcChatMemoryStore implements ChatMemoryStore {

    private final JdbcTemplate jdbc;

    public JdbcChatMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String conversationId(UUID organizationId, UUID incidentId) {
        return "assistant:" + organizationId + ":" + incidentId;
    }

    static UUID organizationOf(Object memoryId) {
        String[] parts = String.valueOf(memoryId).split(":");
        if (parts.length != 3 || !parts[0].equals("assistant")) {
            throw new IllegalArgumentException("Id de memória inválido: " + memoryId);
        }
        return UUID.fromString(parts[1]);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<String> rows = jdbc.queryForList(
                "SELECT messages::text FROM ai_chat_memory WHERE memory_id = ? AND organization_id = ?",
                String.class, String.valueOf(memoryId), organizationOf(memoryId));
        return rows.isEmpty() ? List.of() : ChatMessageDeserializer.messagesFromJson(rows.get(0));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        jdbc.update("""
                INSERT INTO ai_chat_memory (memory_id, organization_id, messages, updated_at)
                VALUES (?, ?, ?::jsonb, NOW())
                ON CONFLICT (memory_id) DO UPDATE SET messages = EXCLUDED.messages, updated_at = NOW()
                WHERE ai_chat_memory.organization_id = EXCLUDED.organization_id
                """, String.valueOf(memoryId), organizationOf(memoryId), ChatMessageSerializer.messagesToJson(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        jdbc.update("DELETE FROM ai_chat_memory WHERE memory_id = ? AND organization_id = ?",
                String.valueOf(memoryId), organizationOf(memoryId));
    }
}
