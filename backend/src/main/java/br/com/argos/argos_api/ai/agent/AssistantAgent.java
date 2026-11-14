package br.com.argos.argos_api.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j agent for follow-up questions about an incident. Its memory is persistent and shared per incident
 * (a window of the conversation stored in PostgreSQL), so the team can ask "and what about payment?" later on.
 */
public interface AssistantAgent {

    Result<String> answer(@MemoryId String conversationId, @UserMessage String question);
}
