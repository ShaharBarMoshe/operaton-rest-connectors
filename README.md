# Operaton REST connectors (Spring Boot)

Operaton 2.2.2 · Spring Boot 4.1.3 · Java 17+ (build with a JDK: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn test`)

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
| `bodySchema` | inline JSON Schema that **builds + validates** the body from the merged sources |
| `paramsSchema` | inline JSON Schema that builds path / query / header params (GET, DELETE, or any method) |
| `responseSchema` | optional inline JSON Schema, shapes + validates the response |
| `headers` | optional static headers: JSON object string (`{"X-A": "${expr}"}`) or `camunda:map` |
| `jwt` | optional bearer token → `Authorization: Bearer <token>`. Give the **name of the variable** holding it (`accessToken`); a token literal or `${expr}` result also works. See [Authentication](#authentication-jwt) |
| `resultVariable` / `statusVariable` | where to store the response (as JSON) / HTTP status |

Schemas are given **as the input value itself** (an inline JSON object string), not as a file path,
so a model is self-contained. Two things to know:

- The engine evaluates every `${...}` inside an input string *before* the connector runs, so a schema
  must not contain `${...}`. For expressions use `"x-expression": "userId"` (no wrapper).
- Input values are stored as string variables (limit ~4000 characters); keep schemas compact.

`ModelPortabilityTest` checks both. The JSON files in `src/test/resources/schemas` are test fixtures
(the same schemas as inlined in the sample models).

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

Operaton Modeler converts the file to the `operaton:` namespace when it saves. The engine runs that
too (`UserSyncProcessTest.runsOperatonNamespaceCopy`), but run `scripts/make-portable.py` afterwards:
it converts the file back to `camunda:`. `ModelPortabilityTest` fails if a model uses the Operaton
namespace.

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

Set the `jwt` input to the name of the process variable that holds the token:

```xml
<camunda:inputParameter name="jwt">accessToken</camunda:inputParameter>
```

The connector reads the variable when the task runs and sends `Authorization: Bearer <token>`
(a value that already starts with `Bearer ` is accepted). It replaces any `Authorization` from
`headers` or the params schema.

- Pass the token as a normal start variable (`examples/start-customer-snapshot.json`):
  `"accessToken": {"value": "eyJ..."}`.
- Missing or empty variable → exception (incident). Server says 401 / 403 → `BpmnError HTTP_401` /
  `HTTP_403`, catch it with an error boundary event. The token is never logged.

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

`user-sync` and `customer-snapshot` use `jwt = accessToken` on every REST task; the nginx mock
answers 401 on the customer endpoints without a `Bearer header.payload.signature` token
(`CustomerSnapshotNginxTest`).

## Errors

| situation | result |
|---|---|
| request does not match schema | `BpmnError REQUEST_INVALID` |
| response does not match schema | `BpmnError RESPONSE_INVALID` |
| HTTP 4xx | `BpmnError HTTP_<status>` (catch with an error boundary event, e.g. `HTTP_404`) |
| HTTP 5xx, timeout, I/O | exception → incident (use async-before + retries for automatic retry) |

See `src/main/resources/bpmn/user-sync.bpmn` and `UserSyncProcessTest` for GET → PUT → UPDATE → DELETE end to end.

## nginx mock service

`mock/nginx` is a stateful REST mock (nginx + njs, state kept in an nginx shared dict):

| endpoint | behaviour |
|---|---|
| `GET /api/users/{id}` | stored user (1042 is seeded), 404 otherwise |
| `PUT /api/users/{id}` | replaces the user, echoes the body |
| `PATCH /api/users/{id}/preferences` | applies `theme / smsEnabled / pushEnabled / updatedBy`, 204 |
| `DELETE /api/users/{id}/sessions` | 204 (404 for unknown user) |
| `POST /api/contacts` | 201 `{id, ...body}` |
| `GET /api/customers/501/{resource}` | needs `Authorization: Bearer <jwt>` (else 401). 10 different objects: profile, contact, address, preferences, account, orders, loyalty, billing, shipping, consents |
| `POST /api/customer-snapshots` | needs `Authorization: Bearer <jwt>` (else 401). 201 `{id, ...body}` |
| `GET /__mock/calls` | every request received (method, uri, args, headers, body) |
| `DELETE /__mock/calls` | resets all state |

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

## Fan-out example: 10 GETs → 1 POST

`src/main/resources/bpmn/customer-snapshot.bpmn`: a parallel gateway runs 10 REST GET tasks
(`/api/customers/{customerId}/profile`, `contact`, `address`, `preferences`, `account`, `orders`,
`loyalty`, `billing`, `shipping`, `consents`). Each stores its response in its own variable. After the
join, REST POST uses

    sources = profile, contact, address, preferences, account, orders, loyalty, billing, shipping, consents

so the 10 objects are deep-merged in that order (later wins, independent of which GET finished first).
The inline body schema then builds the snapshot: `x-source` pointers rename / re-nest fields
(`/customer/firstName` → `name.first`), `additionalProperties: false` drops noise (`_links`, card
tokens, …) and types are coerced (`"1534.50"` → `1534.5`). Fixture copy of the schema:
`src/test/resources/schemas/customer-snapshot.schema.json`. Manual run: `examples/start-customer-snapshot.json`.
