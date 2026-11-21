# Third-party API descriptions used by the tests

Copied unchanged (downloaded 2026-11-21) to test the connector against real OpenAPI / Swagger
documents with nested objects (`Pet` → `Category`, `Pet.tags[]` → `Tag`):

| file | source | version |
|---|---|---|
| `petstore-openapi3.json` | https://petstore3.swagger.io/api/v3/openapi.json | OpenAPI 3.0.4, "Swagger Petstore - OpenAPI 3.0" 1.0.27 |
| `petstore-swagger2.json` | https://petstore.swagger.io/v2/swagger.json | Swagger 2.0, "Swagger Petstore" 1.0.9 |

Both are sample API descriptions by the Swagger project (SmartBear), licensed under the
Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0), as stated in their `info.license`.
Used by `PetstoreMergeTest` and `PetstoreProcessTest`.
