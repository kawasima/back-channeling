# Back channeling

![Back channeling](./resources/public/img/logo.png)

Back channeling is a real-time BBS tool.

It has the features as follows:

- Setup easily
- Supports markdown format
- Supports voice chat
- Curating of comments

## Get started

Currently, we support a development mode only.

Start a datomic transactor.

```
% bin/transactor config/transactor.properties
```

Start a back channeling.

```
% DATOMIC_URL=datomic:free://localhost:4334/bc bin/back_channeling
```

The default port is 3009.

