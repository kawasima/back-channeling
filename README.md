# Back channeling

![Back channeling](./resources/back_channeling/public/img/logo.png)

Back channeling is a real-time BBS tool.

It has the features as follows:

- Setup easily
- Supports markdown format
- Supports voice chat
- Curating of comments

## Prerequisites

- JDK 11+
- [Leiningen](https://leiningen.org/) 2.0+

## Get started

### Development (in-memory)

The simplest way to start is using the in-memory database, which requires no external services:

```
% lein run
```

The app starts at <http://localhost:3009> with `datomic:mem://bc` by default.

### With Datomic Pro transactor

If you want data persistence, start a Datomic Pro transactor first.

#### Using Docker Compose

```
% docker compose up -d
```

#### Manual setup

Download [Datomic Pro](https://www.datomic.com/) and start the transactor:

```
% /path/to/datomic-pro/bin/transactor config/dev-transactor.properties
```

Then update `resources/back_channeling/config.edn` to point to the transactor:

```clojure
:back-channeling.database/datomic {:uri "datomic:dev://localhost:4334/bc?password=admin" :recreate? false}
```

Start the app:

```
% lein run
```

The default port is 3009.

![screenshot](http://i.imgur.com/6n1Yj8D.png)

## Technology stack

| Layer | Library |
| --- | --- |
| Language | Clojure 1.12, ClojureScript 1.11 |
| Database | Datomic Pro (peer) 1.0.7187 |
| HTTP Server | Undertow 2.3 |
| Framework | Duct / Integrant |
| Frontend | Reagent 1.2 + re-frame 1.4 |
| Routing (frontend) | reitit-frontend 0.7 |
| Validation | Malli 0.16 |
| Authentication | Buddy (auth 3.0, core 1.12, sign 3.6) |
| REST | Liberator 0.15 |
| CSS | Fomantic UI 2.9 |

## API

When you signup, select a type of bot account.
You must remember the authorization code.

![Imgur](http://i.imgur.com/diJJjhT.png)

First, you get token by authorization code.

```
POST /api/token

code=[authorization code]
```

You will get a response as follows:

```
{"access_token": , "name": "bot", "email": "bot@example.com"}
```

You must add the token to HTTP headers when you request to BackChanneling web APIs.

```
curl -H 'Accept: application/json' -H 'Authorization: Token xxxxxxxxxxxxxxxx' [API url]
```

And if you send a POST request, Add `Content-Type` to the request header.

```
curl -X POST -H 'Accept: application/json' -H 'Content-Type: application/json' -H 'Authorization: Token xxxxxxxxxxxxxxxx' [API url]
```

### Board

Get a board data.

```
GET /api/board/:board-name
```

An example of response as follows:

```json
{
  "id": 17592186045424,
  "name": "default",
  "description": "Default board",
  "threads": [
    {
      "id": 17592186045428,
      "title": "aaa",
      "since": "20150722T101724.515Z",
      "last-updated": "20150722T110108.015Z",
      "resnum": 1000,
      "watchers": []
    }
  ]
}
```

### Thread

```
GET /api/board/:board-name/thread/:thread-id
```

### New thread

```
POST /api/board/:board-name/threads

{"thread/title": "New thread", "comment/content": "Hello"}
```

### Post comment

```
POST /api/board/:board-name/thread/:thread-id/comments

{"comment/content": "Hello"}
```
