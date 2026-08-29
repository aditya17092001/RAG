# How to Run Local-RAG

This project is a Retrieval-Augmented Generation (RAG) app with a Spring Boot
backend and a React (Vite) frontend. It runs in two modes selected by the
active Spring profile.

## Architecture at a glance

| Concern         | Local (default profile)            | Prod (`prod` profile)              |
| --------------- | ---------------------------------- | ---------------------------------- |
| Chat model      | Ollama `llama3.2` (on your machine)| OpenRouter `gemini-flash-1.5-8b:free` |
| Embeddings      | Google Gemini `text-embedding-004` | Google Gemini `text-embedding-004` |
| Relational DB   | H2 file (`./data/users`)           | Aiven PostgreSQL                   |
| Vector DB       | Aiven PostgreSQL + pgvector        | Aiven PostgreSQL + pgvector        |
| Frontend API URL| `http://localhost:8080`            | value in `frontend/.env.production`|

Notes:
- Embeddings always use Google Gemini (hosted, free tier) in both modes.
- The vector store is a separate Aiven Postgres database with the `pgvector`
  extension; it is wired manually in `VectorStoreConfig` and is used in both
  modes.

---

## 1. Prerequisites

- **Java 21**
- **Maven** (or use the bundled `./mvnw` / `mvnw.cmd` wrapper)
- **Node.js 18+** and **npm** (for the frontend)
- **Ollama** — only needed for LOCAL chat. Install from https://ollama.com then:
  ```bash
  ollama pull llama3.2
  ```
  (Prod does not need Ollama — chat runs on OpenRouter.)
- **Docker** — only if you want to run the containerized prod build.
- Accounts / keys:
  - **Google Gemini API key** (free) — https://aistudio.google.com/apikey
  - **OpenRouter API key** (for prod chat) — https://openrouter.ai/keys
  - **Aiven** PostgreSQL databases (one for login data, one for vectors)

---

## 2. Backend environment variables (`.env`)

Copy `.env.example` to `.env` and fill in real values. `.env` is gitignored.

```
# JWT
JWT_SECRET=<long-random-string-256-bits+>
JWT_EXPIRY=86400000

# Gmail SMTP (App Password, not your login password)
MAIL_USERNAME=<your-email@gmail.com>
MAIL_PASSWORD=<gmail-app-password>

# OpenRouter (prod chat)
OPENROUTER_API_KEY=<sk-or-...>

# Google Gemini (embeddings, both modes)
GEMINI_API_KEY=<AIza...>

# Login / relational DB (Aiven Postgres) - prod profile
DB_URL=jdbc:postgresql://<host>:<port>/defaultdb?sslmode=require
DB_USERNAME=<user>
DB_PASSWORD=<password>

# Vector DB (separate Aiven Postgres + pgvector) - both modes
VECTOR_DB_URL=jdbc:postgresql://<vector-host>:<port>/defaultdb?sslmode=require
VECTOR_DB_USERNAME=<user>
VECTOR_DB_PASSWORD=<password>
```

> Provider connection strings are usually in libpq form
> (`postgres://user:pass@host:port/db`). Convert to JDBC form:
> `jdbc:postgresql://host:port/db?sslmode=require`, with user/password separate.

### One-time vector DB setup

The vector database needs the `pgvector` extension enabled once:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Run it from the Aiven console query editor or with `psql`. The app creates the
`vector_store` table automatically on first start.

---

## 3. Frontend environment variables

The frontend reads its backend URL from `VITE_API_BASE_URL`. Vite auto-selects
the file based on the command:

- `frontend/.env.development` — used by `npm run dev` (default: `http://localhost:8080`)
- `frontend/.env.production`  — used by `npm run build` (set this to your deployed backend URL)

Change the backend URL by editing the relevant file. **Restart the dev server**
after editing, Vite reads env files only at startup.

---

## 4. Run LOCAL (development)

Local uses Ollama for chat, Gemini for embeddings, H2 for login data.

**Terminal 1 — make sure Ollama is running** (it usually runs as a background
service after install). Verify:
```bash
ollama list
```

**Terminal 2 — backend** (from the project root):
```bash
# Linux/macOS
./mvnw spring-boot:run
# Windows PowerShell
.\mvnw.cmd spring-boot:run
```
Backend starts on http://localhost:8080 (default profile).

**Terminal 3 — frontend** (from `frontend/`):
```bash
npm install    # first time only
npm run dev
```
Frontend starts on http://localhost:5173 and calls the backend at
`http://localhost:8080`.

Open http://localhost:5173 in your browser.

---

## 5. Run PROD profile (OpenRouter + Aiven Postgres)

Prod needs no Ollama. Two ways to run it:

### Option A — Maven with the prod profile
```bash
# Linux/macOS
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
# Windows PowerShell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=prod"
```

### Option B — Docker (recommended for prod)
```bash
# Build
docker build -f Dockerfile.prod -t local-rag:prod .
# Run (reads all secrets from .env)
docker run --rm -p 8080:8080 --env-file .env local-rag:prod
```

The prod profile is activated automatically inside the container.

Then build/serve the frontend against the prod backend:
```bash
cd frontend
npm run build      # uses .env.production
npm run preview    # serves the built app locally, or deploy dist/ anywhere
```

---

## 6. Docker (local image)

There is also a `Dockerfile` for the local profile. Because the app runs inside
a container, it cannot reach host Ollama via `localhost`, so point it at the
host machine:

```bash
docker build -t local-rag:local .
docker run --rm -p 8080:8080 --env-file .env \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  --add-host host.docker.internal:host-gateway \
  local-rag:local
```

---

## 7. First-use flow

1. Sign up in the UI (an OTP is emailed via the configured Gmail account).
2. Verify the OTP.
3. Sign in.
4. Upload a document (PDF, TXT, DOCX, etc.). It is chunked (recursive character
   splitter), embedded via Gemini, and stored in the vector DB.
5. Ask questions, answers are grounded in your uploaded documents (RAG).

---

## 8. Troubleshooting

- **`APPLICATION FAILED TO START` / placeholder not resolved**: a required env
  var in `.env` is missing or empty.
- **`type "vector" does not exist`**: run `CREATE EXTENSION IF NOT EXISTS vector;`
  on the vector database.
- **Embeddings fail / auth error from Gemini**: check `GEMINI_API_KEY`.
- **CORS errors in the browser console**: the backend must allow the frontend
  origin (`http://localhost:5173` in dev). Check the backend CORS config.
- **Frontend still calls the old URL**: restart `npm run dev` after editing
  `.env.development`.
- **Switched embedding models?**: existing vectors from a different model are
  incompatible. Clear and re-ingest: `TRUNCATE TABLE vector_store;`
