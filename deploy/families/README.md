# Family jar sets

One directory per server family. Its feature jars are baked into that family's image at build time
(`--build-arg FAMILY=<name>`), so a pod is immutable and versioned.

```
deploy/families/
  <family>/
    api/       # shared contract jars (the api/ layer) for this family
    modules/   # the feature jars this family runs
```

Drop the built jars in, then build the image:

```
docker build -f deploy/images/paper-family.Dockerfile --build-arg FAMILY=<name> -t <registry>/cryon-<name> .
```

`hub` is provided as the template. Create a sibling directory (with both `api/` and `modules/`) for
each family you run: `skyblock`, `bedwars-mini`, `bedwars-mega`, and so on. A family with no feature
jars still needs the two empty directories so the image build's `COPY` succeeds.
