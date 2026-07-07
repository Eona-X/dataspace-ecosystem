# Dataspace Ecosystem - Minimum Viable Dataspace

## Prerequisite

- A dataspace running locally, with at least:
  - an authority
  - a provider connector
  - a consumer connector

## Run the tests

To allow the system tests to connect directly to the PostgreSQL pod instance, open a terminal and execute the following command (keep it open during the execution of the test):
```bash
kubectl port-forward postgresql-0 57521:5432 &
```

Afterwards, you can execute the following command to run the tests:
```bash
./gradlew :system-tests:runner:test -DincludeTags="EndToEndTest"
```

Note: The tests can only be run once. If you want to rerun them, destroy first the dataspace and then re-deploy it.
