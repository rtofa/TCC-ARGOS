package br.com.argos.argos_api.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

/**
 * LangChain4j agent that investigates one incident. The system prompt and the tool definitions are fixed and come
 * first in every request (prompt caching); the volatile briefing comes last. Memory is per investigation and is
 * evicted when it ends; it exists so the output guardrail can send the agent back with its full tool history.
 */
public interface InvestigatorAgent extends ChatMemoryAccess {

    Result<String> investigate(@MemoryId String investigationId, @UserMessage String briefing);
}
