# Back channeling

![Back channeling](./resources/public/img/logo.png)

Back channeling is a real-time BBS tool.

It has the features as follows:

- Setup easily
- Supports markdown format
- Supports voice chat
- Curating of comments

## Get started

### On-premise

Start a datomic transactor.

```
% bin/transactor
```

Start a back channeling.

```
% DATOMIC_URL=datomic:free://localhost:4334/bc bin/back_channeling
```

The default port is 3009.

![screenshot](http://i.imgur.com/6n1Yj8D.png)

### Heroku

[![Deploy](https://www.herokucdn.com/deploy/button.svg)](https://heroku.com/deploy?template=https://github.com/kawasima/back-channeling)

or

1. Git clone.
```
% git clone https://github.com/kawasima/back-channeling.git
```
1. Create a heroku application.
```
% cd back-channeling
% heroku create
```
1. Deploy the back-channeling.
```
% git push heroku master
```

It takes only 3 minutes!!

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
curl  -H 'Accept: application/json' -H 'Authorization: Token xxxxxxxxxxxxxxxx' [API url]
```

And if you send a POST request, Add `Content-Type` to the request header.

```
curl -X POST -H 'Accept: application/json' -H 'Content-Type: application/json' -H 'Authorization:  Token xxxxxxxxxxxxxxxx' [API url]
```

### Board

Get a board data.

```
GET /api/board/:board-name
```

An example of response as follows:

```
{
  "id":17592186045424,
  "name":"default",
  "description":"Default board",
  "threads":[
    {"id":17592186045428,
     "title":"aaa",
     "since":"20150722T101724.515Z",
     "last-updated":"20150722T110108.015Z",
     "resnum":1000,"watchers":[]},
    {"id":17592186045651,"title":"hohoho",
     "since":"20150722T104559.129Z",
     "last-updated":"20150929T123754.988Z",
     "watchers":["bot2"],"resnum":1000}
  ]
}
```

### Thread

```
GET /api/thread/:thread-id
```

### New thread

```
POST /api/board/:board-name/threads

{"thread/name": "New thread", "comment/content": "Hello"}
```

### Post comment

```
POST /api/thread/:thread-id/comments

{"comment/content": "Hello"}
```
