# Operaton REST connectors (Spring Boot)

Operaton 2.2.2 · Spring Boot 4.1.3 · Java 17+ (build with a JDK: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn test`)

Presentation: [`docs/presentation`](docs/presentation/README.md) (open `docs/presentation/index.html`).

## Project layout

```
.camunda/element-templates/   Modeler templates (the Modelers look for this folder name)
docker-compose.yml            the nginx mock on :8081
docs/presentation/            the slide deck (index.html to view)
examples/                     start payloads for the engine REST API
mock/nginx/                   mock service (nginx + njs)
mock/openapi/                 its OpenAPI document, served as GET /openapi.yaml
scripts/                      make-portable.py, git pre-commit hook
src/main/java/com/example/operaton/
  OperatonRestApplication
  connector/
    config/    Spring wiring: RestConnectorConfig, RestConnectorProperties (rest-connector.*)
    engine/    Operaton adapter
      delegate/  RestConnectorDelegate: the JavaDelegate bean per HTTP method (restGet ... restDelete)
      input/     TaskInputs (task inputs → RestCall), ExecutionCallContext, EngineValues (Spin → plain)
      output/    ResultVariables: status / result → process variables (JSON → Spin Json)
      auth/      TokenProvider, VariableTokenProvider (rest-connector.jwt-variable)
    core/      the use case
      api/       the contract the engine layer uses: RestConnector, RestCall, RestResult, CallContext, TechnicalErrors
      error/     ConnectorError (business, → BPMN error), RestConnectorException (technical, → incident)
      request/   RequestFactory: sources + schemas → HttpRequest (params, body, URL, headers, token)
      response/  ResponseMapper: status codes → result or error, response schema
      service/   DefaultRestConnector: RequestFactory → HttpTransport → ResponseMapper
    schema/    schemas + JSON: SchemaCompiler, OpenApiSchemaCompiler, PastedSchema, JsonComposer
    http/      the wire
      transport/   HttpTransport, HttpRequest, HttpResponse, TransportException (the contract)
      restclient/  RestClientHttpTransport (Spring RestClient on the JDK HttpClient)
      params/      HttpRequestParams: x-in → path / query / header, URI building
src/main/resources/bpmn/      the sample models
src/test/java/com/example/operaton/
  connector/{core/service,engine/auth,schema,config}/   unit tests per layer (no engine, fake transport)
  connector/LayerDependencyTest              which layer / sub-package may import which
  process/                                 the models on the engine against the mock (Testcontainers)
  model/                                   the model files: namespace, inputs, schemas compile
```

## Architecture

```
 engine  ─────────▶  core  ─────────▶  schema
 (Operaton)          (use case)   └──▶  http
```

Each layer only depends on the ones to its right; only `engine` knows Operaton, and `core` does
not know the engine or the HTTP client. Inside `core`, `engine` and `http` the sub-packages have
rules too (e.g. `engine/auth` and `http/transport` depend on nothing); `LayerDependencyTest` fails
the build on a wrong import.

| layer | role | interfaces (Spring beans) | default implementation |
|---|---|---|---|
| `engine` | one `JavaDelegate` bean per HTTP method: reads the task inputs into a `RestCall`, stores status / result variables, turns `ConnectorError` into `BpmnError` | `TokenProvider` | `VariableTokenProvider` (variable `rest-connector.jwt-variable`) |
| `core` | merge sources, build + validate params and body (`request`), send, map status codes, shape + validate the response (`response`); `service` orchestrates, `api` is the contract | `RestConnector`, `CallContext` (expressions and variables, backed by the execution) | `DefaultRestConnector` with `RequestFactory`, `ResponseMapper` |
| `schema` | finds the schema in what was pasted, validates it in its OpenAPI dialect, shapes JSON with it | `SchemaCompiler` | `OpenApiSchemaCompiler` (swagger-parser + networknt) |
| `http` | the wire | `HttpTransport` | `RestClientHttpTransport` (Spring `RestClient`, JDK HttpClient) |

`RestConnectorConfig` creates all beans; the five delegates are the same class with different
settings (`restGet` = GET without body ... `restPost` = POST, merged sources as body by default).
To replace a part, declare a bean of its interface with `@Primary`, e.g. a `TokenProvider` that
fetches OAuth2 client-credentials tokens, or an `HttpTransport` with mTLS.

## Connectors

| Bean (`camunda:delegateExpression`) | HTTP | Body by default |
|---|---|---|
| `${restGet}`    | GET    | no |
| `${restPut}`    | PUT    | yes |
| `${restUpdate}` | PATCH  | yes |
| `${restDelete}` | DELETE | no |
| `${restPost}`   | POST   | yes |

## Service-task input parameters

| name | |
|---|---|
| `url` | required, e.g. `${apiBaseUrl}/users/{userId}`. `{x}` is filled from params with `x-in: path` |
| `sources` | variable names (`currentUser, userChanges` or a `camunda:list` of names); list items may also be JSON literals or `${expr}` values. **Deep-merged in order** (later wins; arrays replaced). Prefer names: `${var}` items holding Spin `Json` values cannot be stored by the engine |
| `bodySchema` | the request schema **pasted from the target service's OpenAPI / Swagger document**; it **builds + validates** the body from the merged sources. See [Schemas from OpenAPI](#schemas-from-openapi--swagger) |
| `paramsSchema` | the operation's OpenAPI `parameters` list (path / query / header), pasted. A parameter not found in the sources is taken from the process variable with the same name |
| `responseSchema` | optional, the response schema pasted (a schema, a response, or the operation's `responses`): shapes + validates the response |
| `headers` | optional static headers: JSON object string (`{"X-A": "${expr}"}`) or `camunda:map` |
| `resultVariable` / `statusVariable` | where to store the response / HTTP status. A JSON object or array is stored as a Spin `Json` variable (Cockpit shows its value; read a field with `${snapshot.prop("id").stringValue()}`; usable as a source again), anything else as a plain value |
| `technicalErrors` | `exception` (default) or `bpmnError`: how 5xx / timeouts / connection problems are raised, see [Errors](#errors) |
| `mockEnabled` / `mockStatus` / `mockResponse` | test mode: answer with mock data instead of calling the service, see [Test mode](#test-mode-mock-data) |

There is no authentication input: the connector reads the token from the execution itself, see
[Authentication](#authentication-jwt).

## Schemas from OpenAPI / Swagger

Copy the schema from the other service's OpenAPI (Swagger) document and paste it into the task,
**JSON or YAML, OpenAPI 3.1 / 3.0 or Swagger 2.0**. What you can paste:

| field | paste |
|---|---|
| `paramsSchema` | the operation's `parameters` list (or the whole operation) |
| `bodySchema` | a schema (`components.schemas.X`), the operation's `requestBody`, or the whole operation |
| `responseSchema` | a schema, a response, or the operation's `responses` (the first 2xx with a body is used) |

`$ref`s are resolved inside the pasted text only (remote URLs are never fetched), so paste what
they point to as well: the `components` section, or simply **the whole document**, plus one line on
top that names what to use:

```yaml
$ref: '#/components/schemas/CustomerSnapshot'      # or '#/paths/~1customer-snapshots/post/responses'
openapi: 3.0.3
...the rest of the document, unchanged...
```

What the connector does with it: `PastedSchema` finds the schema to use (whole documents are read
with **swagger-parser**, which also converts Swagger 2.0 to OpenAPI 3); nothing is translated. The
**networknt** validator checks it in the document's own **OpenAPI dialect** (3.0 for OpenAPI 3.0 and
Swagger 2.0, 3.1 / JSON Schema 2020-12 for 3.1 documents and for fragments), resolving `$ref`s inside
the pasted text only. The shaper (`JsonComposer`) reads the same pasted text.

- `readOnly` properties are left out of requests (and are not required there), `writeOnly` out of
  responses. So the same `CustomerSnapshot` schema works for the POST body (no `id`) and for the
  response (`id` required).
- **Only declared properties are sent / kept**; add `additionalProperties: true` to pass the rest.
- `nullable` / `x-nullable`, boolean `exclusiveMinimum`, formats, `allOf` / `oneOf` / `anyOf` are
  understood; `example`, `xml`, `externalDocs` and other `x-` extensions are ignored. `discriminator`
  is not enforced (networknt does not, in our tests); the `oneOf` / `anyOf` itself still is.
- Values are coerced to the declared type (`"12"` → `12`).
- Path / query / header parameters are taken from the merged sources, else from the **process
  variable with the same name** (`customerId`), so a pasted parameter list needs no changes.

Where your process data is named or nested differently from the service's schema, add one of our
extensions to the pasted property (OpenAPI allows `x-` keys):

```yaml
first: {type: string, x-source: /customer/firstName}     # JSON pointer into the merged sources
snapshotBy:
  type: string
  x-expression: execution.getVariable('initiator')         # Operaton expression, no ${ } wrapper
```

Plain JSON Schema (draft 2020-12, the OpenAPI 3.1 schema dialect) works too; `user-sync` and
`user-onboarding` use that form, with `x-in: path | query | header` on params.

Two things to know:

- The engine evaluates every `${...}` inside an input string *before* the connector runs, so a schema
  must not contain `${...}`. For expressions use `x-expression` (no wrapper).
- Size: with this app's history level `activity` the inputs never reach the history tables, so a
  whole document (`PastedOpenApiDocumentTest` pastes 5.5 KB) works. With history level `audit` /
  `full` the engine stores every input in history and fails on more than 4000 characters; paste
  only the needed part then.

`ModelPortabilityTest` checks that every schema in the models parses and compiles. The mock's API is
described in `mock/openapi/customer-api.yaml` (served as `GET /openapi.yaml`); the customer-snapshot
schemas are pasted from it. The JSON files in `src/test/resources/schemas` are test fixtures.

Checked against a real third-party description too: the **Swagger Petstore** (OpenAPI 3.0 and
Swagger 2.0, unmodified, `src/test/resources/openapi`). `PetstoreMergeTest` merges two inputs into
its nested `Pet` (`category` → `Category`, `tags[]` → `Tag`) for both documents, referenced by
component and by `POST /pet`; `PetstoreProcessTest` does the same in a BPMN task on the engine with
the whole 17 KB document pasted. For a JSON document, name the target by adding the key in front:
`{"$ref": "#/paths/~1pet/post", "openapi": "3.0.4", ...}`.

## Modeler templates

`.camunda/element-templates/rest-connectors.json` holds one element template per connector
(**REST GET / PUT / UPDATE (PATCH) / POST / DELETE**). Operaton Modeler picks them up automatically
for any diagram inside this project (it searches `.camunda/element-templates` in the diagram's
parent folders). Select a task → *Template* → choose e.g. "REST GET"; the properties panel then shows
labelled, documented fields (URL, Sources, Params / Body / Response schema, Static headers,
Result / Status variable, Async before) instead of raw input parameters. The template sets the
`delegateExpression` for you. To use them in other projects copy the file to
`~/.config/operaton-modeler/resources/element-templates/`.

### Models must also work without the templates

A task linked to a template the Modeler does not have shows only *"Template not found"*, which
hides its configuration. So the committed models are **not** linked to templates. Instead each REST
task carries every connector input (empty when unused) and a documentation text that explains each
input, so any Modeler (or plain bpmn.io) can show and edit it.

Workflow: build tasks with the templates, then before committing run

```bash
scripts/make-portable.py          # camunda: namespace, unlink templates, add all inputs + documentation
scripts/make-portable.py --check  # report only (exit 1 if something would change)
```

`ModelPortabilityTest` fails the build if a model in `src/main/resources/bpmn` uses the Operaton
namespace, is still linked or misses connector inputs.

To edit existing models **with** the template forms, re-link them first:

```bash
scripts/make-portable.py --link   # adds the template link back, values untouched
# ... edit in Operaton Modeler ...
scripts/make-portable.py          # unlink again before committing
```

Do not re-apply a template by hand to an existing task (*Template → select*): the Modeler then resets
the task's inputs to the template defaults.

### Namespace: `camunda:` (works in every Modeler)

The models use the Camunda 7 namespace (`xmlns:camunda="http://camunda.org/schema/1.0/bpmn"`,
`camunda:inputOutput`, `camunda:delegateExpression`) and declare
`modeler:executionPlatform="Camunda Platform"`. That way the inputs show in **Camunda Modeler**, the
**Miragon VS Code BPMN Modeler** (both only recognise Camunda namespaces) and Operaton Modeler. The
Operaton engine runs the `camunda:` namespace as is.

**One file for both Operaton Modeler and Camunda Modeler.** Operaton Modeler opens `camunda:` files
and shows everything (it maps `camunda:` to `operaton:` on import), but on **save** it always writes
`operaton:` and `executionPlatform="Operaton"`, which Camunda Modeler / the VS Code extension then
cannot show. The engine runs either form (`UserSyncProcessTest.runsOperatonNamespaceCopy`), so the
fix is only about the Modelers: `scripts/make-portable.py` converts an Operaton Modeler save back to
the committed `camunda:` form (same content, only `exporter` differs). Let git do it on every commit:

```bash
git config core.hooksPath scripts/git-hooks   # once per clone: pre-commit runs make-portable.py
```

`ModelPortabilityTest` fails the build if a model still has the Operaton namespace or metadata.

Both Camunda Modeler and the VS Code extension find the templates in `.camunda/element-templates`
(in VS Code, open the project folder as the workspace).

## How a schema builds the request

1. Merge `sources` → one document.
2. Walk the schema: each property takes the value with the same name from the document, **or**
   - `"x-source": "/json/pointer"` – take it from somewhere else in the merged doc
   - `"x-expression": "userId"` – any Operaton expression, written **without** `${ }`
     (process variable, bean, `execution.getVariable('optionalVar')`)
   - `const` / `default` when nothing was found
   - `"x-flatten": true` on an object schema: nested input objects are flattened first
     (`address.city` → `city`). Each source is flattened on its own and then merged, so a later
     source wins over an earlier one; within one source the deeper key wins. Properties that are
     objects in the schema stay nested. Example: `src/test/resources/schemas/contact.flat.schema.json`.
3. `additionalProperties: false` drops unknown fields; scalars are coerced to the schema type.
4. Validate (draft 2020-12, formats asserted).

Params schemas: each top-level property is one param, `"x-in": "path" | "query" | "header"` (default query).
Arrays → repeated query params; nested objects → `name.child=value`.

## Authentication (JWT)

Nothing to configure on the tasks: the connector reads the token from the **execution variable
`accessToken`** (set it when starting the process) and sends `Authorization: Bearer <token>` on every
call (a value that already starts with `Bearer ` is accepted). It replaces any `Authorization` from
`headers` or params. The variable name is an app setting:

```yaml
rest-connector:
  jwt-variable: accessToken     # empty: never send a token
```

- Pass the token as a normal start variable (`examples/start-customer-snapshot.json`):
  `"accessToken": {"value": "eyJ..."}`.
- No `accessToken` variable → no Authorization header (for APIs without auth); a protected API then
  answers 401 → `BpmnError HTTP_401`. An empty or malformed value → exception (incident).
  401 / 403 → `BpmnError HTTP_401` / `HTTP_403`, catch it with an error boundary event or the error
  subprocess. The token is never logged.

### No variables in history, none left after completion

`application.yaml` sets `operaton.bpm.history-level: activity`:

| | kept |
|---|---|
| process instances, activity instances, incidents (Cockpit: what ran, when, where it ended) | yes |
| variables (accessToken and every other start / result variable), variable updates | **never written to history** |
| runtime variables | only while the instance runs, deleted by the engine when it completes |

So after an instance completes there is no copy of the token or any other variable in the
database. While an instance is running and waiting (async continuation, timer, user task) its
variables are in the runtime table `ACT_RU_VARIABLE` (encrypt the DB / restrict access if that
matters, or keep REST tasks free of wait states before they use the token).

Callers that need the final variables get them in the start response instead
(`"withVariablesInReturn": true` over REST, `executeWithVariablesInReturn()` in Java); that is
what the tests do. Every process test checks that nothing is left
(`StoredVariables.assertNone`: no runtime variables, no historic variables, no historic details).

The history level is engine-wide and is stored in the database when the schema is created. With an
existing database created with another level, the engine refuses to start (history level mismatch):
update `ACT_GE_PROPERTY` `historyLevel` (`1` = activity) or use a fresh schema. Instances that ran with a higher level earlier still have their variables in history;
remove those with history cleanup / `historyService.deleteHistoricProcessInstances`.

`user-sync` and `customer-snapshot` are started with `accessToken`; the nginx mock
answers 401 on the customer endpoints without a `Bearer header.payload.signature` token
(`CustomerSnapshotNginxTest`).

## Test mode (mock data)

Every REST task has three more inputs (group *Test mode (mock data)* in the templates):

| input | |
|---|---|
| `mockEnabled` | `true` = do not send the request; empty / `false` = real call. Usually an expression, so a start variable switches the whole process: `${execution.getVariable('useMockData') == true}` |
| `mockStatus` | the status the mock answers with, default `200`; `404`, `503`, ... test the error handling |
| `mockResponse` | the response body (JSON or text); `${...}` inside is evaluated, so it can echo variables |

In test mode the request is still **built and validated** (sources, params / body schemas), it is
logged as `MOCK GET ... - not sent`, and the mock answer goes through the normal response handling:
status → BPMN errors, response schema, result / status variables. A mock body that breaks the
pasted response schema ends in `RESPONSE_INVALID`, like a real one would.

All three sample models carry mock data on every REST task behind the `useMockData` switch:

```bash
curl -X POST -H 'Content-Type: application/json' -d @examples/start-customer-snapshot-mock.json \
  localhost:8080/engine-rest/process-definition/key/customer-snapshot/start   # no request leaves the app
```

`MockModeProcessTest` runs each model with `useMockData = true` and checks that the service received
nothing, and that without the switch the same model calls the service.

## Errors

| situation | result |
|---|---|
| request does not match schema | `BpmnError REQUEST_INVALID` |
| response does not match schema | `BpmnError RESPONSE_INVALID` |
| HTTP 4xx | `BpmnError HTTP_<status>` (catch with an error boundary event, e.g. `HTTP_404`) |
| HTTP 5xx, timeout, I/O | `technicalErrors` = `exception` (default): exception → incident (use async-before + retries for automatic retry). `technicalErrors` = `bpmnError`: `BpmnError HTTP_<5xx>` / `CONNECTION_FAILED` |
| missing source variable, malformed token, bad input value or schema | exception (a modelling mistake, never a BPMN error) |

### Error handling in the sample models

All three models set `technicalErrors = bpmnError` on every REST task and have one interrupting
**error event subprocess** "Handle REST errors": an error start event without an error code (catches
every BPMN error of the process, also from the 5 parallel GETs) stores `errorCode` / `errorMessage`,
and a gateway ends the instance at

| end event | errorCode |
|---|---|
| `errEndAuth` Not authorized | `HTTP_401`, `HTTP_403` |
| `errEndNotFound` Not found | `HTTP_404` |
| `errEndInvalid` Invalid data | `REQUEST_INVALID`, `RESPONSE_INVALID` |
| `errEndUnavailable` Service unavailable | `HTTP_5xx`, `CONNECTION_FAILED` |
| `errEndRejected` Request rejected (default) | any other `HTTP_4xx` |

A boundary event on a task still takes precedence (`HTTP_404` on the first GET of user-sync /
user-onboarding). Callers get `errorCode` / `errorMessage` with `withVariablesInReturn`; history shows
which end event was reached. `CustomerSnapshotErrorsTest` runs every path against the nginx mock.

See `src/main/resources/bpmn/user-sync.bpmn` and `UserSyncProcessTest` for GET → PUT → UPDATE → DELETE end to end.

## Cockpit

The app includes the Operaton web apps (`operaton-bpm-spring-boot-starter-webapp`): Cockpit, Tasklist
and Admin at `http://localhost:8080/operaton/app/`, login `demo` / `demo`. Cockpit shows running
instances, where their tokens wait and their runtime variables (the GET results are JSON object
variables). Completed instances keep no variables (history level `activity`).

## nginx mock service

`mock/nginx` is a stateful REST mock (nginx + njs, state kept in an nginx shared dict):

| endpoint | behaviour |
|---|---|
| `GET /api/users/{id}` | stored user (1042 is seeded), 404 otherwise |
| `PUT /api/users/{id}` | replaces the user, echoes the body |
| `PATCH /api/users/{id}/preferences` | applies `theme / smsEnabled / pushEnabled / updatedBy`, 204 |
| `DELETE /api/users/{id}/sessions` | 204 (404 for unknown user) |
| `POST /api/contacts` | 201 `{id, ...body}` |
| `GET /api/customers/501/{resource}` | needs `Authorization: Bearer <jwt>` (else 401). 10 different objects (the model uses 5): profile, contact, address, preferences, account, orders, loyalty, billing, shipping, consents |
| `POST /api/customer-snapshots` | needs `Authorization: Bearer <jwt>` (else 401). 201 `{id, ...body}` |
| `GET /openapi.yaml` | the API description (`mock/openapi/customer-api.yaml`), to copy schemas from |
| `GET /__mock/calls` | every request received (method, uri, args, headers, body) |
| `DELETE /__mock/calls` | resets all state (and faults) |
| `PUT /__mock/faults` | `{"method":"POST","uri":"/api/customer-snapshots","status":503,"body":{...}}`: later matching requests get that answer (fault injection) |

Run it by hand and drive the `user-onboarding` model through the engine REST API:

```bash
docker compose up -d --wait                       # mock on http://localhost:8081
java -jar target/operaton-rest-connectors-0.0.1-SNAPSHOT.jar
curl -X POST -H 'Content-Type: application/json' -d @examples/start-user-onboarding.json \
  localhost:8080/engine-rest/process-definition/key/user-onboarding/start
curl localhost:8081/__mock/calls                  # see what the connectors sent
```

`UserOnboardingNginxTest` does the same automatically: Testcontainers starts the nginx mock
(Docker required), the test runs `user-onboarding.bpmn`
(GET → PUT → UPDATE → POST (x-flatten) → DELETE → GET) and asserts on the recorded calls.

## Fan-out example: 5 GETs → 1 POST

`src/main/resources/bpmn/customer-snapshot.bpmn`: a parallel gateway runs 5 REST GET tasks
(`/api/customers/{customerId}/profile`, `contact`, `address`, `account`, `loyalty`). Each stores its
response in its own variable. After the join, REST POST uses

    sources = profile, contact, address, account, loyalty

so the 5 objects are deep-merged in that order (later wins, independent of which GET finished first:
`loyalty` carries `account.tier: platinum`, which overrides `gold` from `account`).
The body schema is the `CustomerSnapshot` schema pasted (YAML) from the mock's
`customer-api.yaml` with its components; added are only the `$ref` line and `x-source` pointers that
rename / re-nest fields (`/customer/firstName` → `name.first`). Undeclared fields are dropped
(`_links`, `internalNotes`, `geo`, `openedAt`, …) and the readOnly `id` is not sent. The response
schema is the same paste without the additions, so the response must carry an `id` like `s-1`.
The GET tasks paste the operation's `parameters` unchanged; `customerId` comes from the process variable. Fixture copy of the schema:
`src/test/resources/schemas/customer-snapshot.schema.json`. Manual run: `examples/start-customer-snapshot.json`.
