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

## Tools

| Tool | Name | Purpose | Config |
|------|------|---------|--------|
| WebSearchTool | `web_search` | Brave Search API — live web search | `BRAVE_API_KEY` |
| RestApiCallerTool | `call_api` | Generic HTTP GET/POST/PUT/PATCH | `TOOL_ALLOWED_DOMAINS` |
| DatabaseQueryTool | `query_database` | PostgreSQL SELECT/INSERT/UPDATE | `DB_READABLE_TABLES`, `DB_WRITABLE_TABLES` |
| SaveMemoryTool | `save_memory` | Agent persists facts to long-term memory | — |
| SearchMemoryTool | `search_memory` | Agent queries its own memory | — |
| EchoTool | `echo` | Smoke test | — |

### Tool Setup

#### Brave Search (free tier)
1. Register at https://api.search.brave.com/register
2. Get API key → set `BRAVE_API_KEY=your_key`

#### Domain allowlist (optional, personal agent default = allow all)
```bash
export TOOL_ALLOWED_DOMAINS=api.github.com,api.notion.com,api.weather.com
```

#### Database write access (default = read-only)
```bash
export DB_READABLE_TABLES=agent_memories,agent_sessions,your_table
export DB_WRITABLE_TABLES=notes,tasks
```

## Database Migration: PostgreSQL → MongoDB

The project now uses **MongoDB** instead of PostgreSQL.

### Collections

| Collection | Purpose |
|------------|---------|
| `agent_memories` | Long-term memory facts (with embedding vector) |
| `agent_sessions` | Session metadata and turn counts |
| `agent_run_traces` | Full observability traces per agent run |

### Start MongoDB locally

```bash
# Community MongoDB
docker run -d --name mongodb -p 27017:27017 mongo:7

# Or with authentication
docker run -d --name mongodb \
  -e MONGO_INITDB_ROOT_USERNAME=admin \
  -e MONGO_INITDB_ROOT_PASSWORD=password \
  -p 27017:27017 mongo:7

export MONGODB_URI=mongodb://admin:password@localhost:27017/agent_db?authSource=admin
```

### Atlas Vector Search (for production semantic memory)

1. Deploy to MongoDB Atlas (free tier available)
2. Set `MONGODB_URI` to your Atlas connection string
3. Create a Vector Search index on `agent_memories`:
   ```json
   {
     "fields": [{
       "type": "vector",
       "path": "embedding",
       "numDimensions": 1536,
       "similarity": "cosine"
     }]
   }
   ```
4. Uncomment the `$vectorSearch` aggregation in `SemanticMemoryService`

For local/community MongoDB, in-process cosine similarity is used automatically.
