-- Remove o agente investigador e o assistente de chat (substituídos por um RAG): investigações, memória e
-- histórico de conversa deixam de existir. A base de LLM (roteamento entre modelos) continua no módulo ai.
DROP TABLE IF EXISTS ai_chat_messages;
DROP TABLE IF EXISTS ai_chat_memory;
DROP TABLE IF EXISTS ai_investigations;
