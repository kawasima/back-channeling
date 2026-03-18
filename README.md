# Back-Channeling

![Back-Channeling](./resources/back_channeling/public/img/logo.png)

**Back-Channeling** is a self-hosted, real-time team discussion platform built on a thread-based BBS model. Unlike flat chat tools, it keeps conversations organized by topic and lets you turn discussions into lasting knowledge assets.

## Why Back-Channeling?

Most chat SaaS tools optimize for speed — messages pile up, context is lost, and valuable discussions disappear into history. Back-Channeling is designed for teams that want their conversations to *mean something* over time.

## Key Features

### Thread-based discussions

Conversations are organized into boards and threads — topics stay separated and easy to navigate. Browse multiple threads simultaneously in a tabbed interface.

### Curation — turn conversations into knowledge

Select comments from any thread and compile them into a structured article. Add editorial notes between curated blocks, reorder content, and export as Markdown. Your team's best insights stop being ephemeral.

### AI bots as team members

Bots authenticate with API tokens and participate in discussions through the same API as humans. Trigger them with `@mentions`, and they respond with full thread context. Supports Claude, OpenAI, and any OpenAI-compatible model (e.g. Ollama).

### Voice comments

Record and post voice messages directly from the browser — no external service needed. Useful when typing isn't practical.

### Unread tracking and watch notifications

Track your read position per thread. Watch specific threads and receive desktop notifications when new comments arrive.

### Fine-grained permissions

Control access at the operation level: `read-board`, `write-thread`, `delete-any-comment`, and more. Suitable for multi-team or enterprise deployments.

## Prerequisites

- JDK 11+
- [Leiningen](https://leiningen.org/) 2.0+

## Getting Started

### Development (in-memory)

The simplest way to start — no external services required:

```
lein run
```

The app starts at <http://localhost:3009> using an in-memory Datomic database.

### With Datomic Pro transactor (persistent data)

#### Using Docker Compose

```
docker compose up -d
lein run
```

#### Manual setup

Download [Datomic Pro](https://www.datomic.com/) and start the transactor:

```
/path/to/datomic-pro/bin/transactor config/dev-transactor.properties
```

Then update `resources/back_channeling/config.edn`:

```clojure
:back-channeling.database/datomic {:uri "datomic:dev://localhost:4334/bc?password=admin" :recreate? false}
```

Start the app:

```
lein run
```

## Technology Stack

| Layer | Library |
| --- | --- |
| Language | Clojure 1.12, ClojureScript 1.11 |
| Database | Datomic Pro (peer) 1.0.7187 |
| HTTP Server | Undertow 2.3 (Jakarta EE 10) |
| Framework | Duct / Integrant |
| Frontend | Reagent 1.2 + re-frame 1.4 |
| Routing (frontend) | reitit-frontend 0.7 |
| Validation | Malli 0.16 |
| Authentication | Buddy (auth 3.0, core 1.12, sign 3.6) |
| REST | Liberator 0.15 |
| CSS | Fomantic UI 2.9 |

## Bot / API Integration

### Create a bot account

On the sign-up screen, select the robot icon to register a bot account. Save the generated **authorization code** — you'll need it to obtain an access token.

### Obtain an access token

```
POST /api/token

code=[authorization code]
```

Response:

```json
{"access_token": "xxxxxxxxxxxxxxxx", "name": "bot", "email": "bot@example.com"}
```

### Authenticate requests

Include the token in the `Authorization` header:

```
curl -H 'Accept: application/json' \
     -H 'Authorization: Token xxxxxxxxxxxxxxxx' \
     [API URL]
```

For POST requests, also set `Content-Type`:

```
curl -X POST \
     -H 'Accept: application/json' \
     -H 'Content-Type: application/json' \
     -H 'Authorization: Token xxxxxxxxxxxxxxxx' \
     [API URL]
```

### API Reference

#### Get board

```
GET /api/board/:board-name
```

```json
{
  "id": 17592186045424,
  "name": "default",
  "description": "Default board",
  "threads": [
    {
      "id": 17592186045428,
      "title": "Example thread",
      "since": "20150722T101724.515Z",
      "last-updated": "20150722T110108.015Z",
      "resnum": 42,
      "watchers": []
    }
  ]
}
```

#### Get thread

```
GET /api/board/:board-name/thread/:thread-id
```

#### Create thread

```
POST /api/board/:board-name/threads

{"thread/title": "New thread", "comment/content": "Hello"}
```

#### Post comment

```
POST /api/board/:board-name/thread/:thread-id/comments

{"comment/content": "Hello"}
```
