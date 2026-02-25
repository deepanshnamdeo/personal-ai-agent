# Personal AI Agent

A personal productivity agent built with **Spring Boot**, implementing the **ReAct (Reason → Act → Observe)** loop with OpenAI GPT-4o.

## Architecture

```
User Request
    ↓
AgentLoop (ReAct)
    ↓
Planner (OpenAI GPT-4o)
    ↓              ↓
Final Answer    ToolCall
                   ↓
            ToolRegistry.execute()
                   ↓
            Observation → back to Planner
```

## Memory Model

| Layer | Store | TTL | Purpose |
|-------|-------|-----|---------|
| Short-term | Redis | 60 min | Conversation window (last 20 messages) |
| Long-term | PostgreSQL | Permanent | Persisted facts, preferences, context |

## Stack

- Java 21 + Spring Boot 3.2
- OpenAI GPT-4o
- PostgreSQL (long-term memory)
- Redis (short-term session memory)

## Getting Started

### Prerequisites
- Docker (for PostgreSQL + Redis)
- OpenAI API key

### Run infrastructure
```bash
docker run -d --name postgres -e POSTGRES_DB=agent_db -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:15
docker run -d --name redis -p 6379:6379 redis:7
```

### Configure
```bash
export OPENAI_API_KEY=your_key_here
```

### Run
```bash
./mvnw spring-boot:run
```

## API

### Run the agent
```bash
curl -X POST http://localhost:8080/api/v1/agent/run \
  -H "Content-Type: application/json" \
  -d '{"input": "Summarize what I need to do today", "userId": "deepansh"}'
```

### Continue a session
```bash
curl -X POST http://localhost:8080/api/v1/agent/run \
  -H "Content-Type: application/json" \
  -d '{"input": "Add more detail to that", "sessionId": "your-session-id", "userId": "deepansh"}'
```

### View long-term memories
```bash
curl http://localhost:8080/api/v1/agent/memory/deepansh
```

### Manually store a memory
```bash
curl -X POST http://localhost:8080/api/v1/agent/memory/deepansh \
  -H "Content-Type: application/json" \
  -d '{"content": "Prefers bullet-point summaries", "tag": "preference"}'
```

## Roadmap

- [ ] Step 3: WebSearch tool (Brave/Tavily API)
- [ ] Step 4: REST API caller tool
- [ ] Step 5: PostgreSQL read/write tool
- [ ] Step 6: File ops tool
- [ ] Step 7: Streaming responses (SSE)
- [ ] Step 8: pgvector semantic memory search
- [ ] Step 9: Observability (Micrometer + tracing)
