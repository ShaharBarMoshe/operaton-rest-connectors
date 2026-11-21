# Presentation: Operaton REST Connectors

29 slides: what the connectors do, their inputs and outputs, schemas pasted from OpenAPI /
Swagger (checked against the Swagger Petstore), the customer-snapshot run call by call, error
handling, test mode with mock data, the layers inside, and how to use them.

- **View it here:** open `index.html` in a browser (local images; fonts from Google Fonts).
- **Edit it:** the live deck is https://claude.ai/artifact/YFdL12amgLmZpaWWpMQUkw (private to its
  owner; the PPTX / PDF downloads are there).

| path | |
|---|---|
| `deck/deck.json` | the deck's index: title, slide order, sections, fonts |
| `deck/deck.css` | the shared styles |
| `deck/slides/<id>.html` | one slide each; speaker notes in its `<aside>`. Pictures point at the live deck's uploads (`/_blob/...`) |
| `images/` | those pictures, named after their slide; `index.html` uses these |
| `index.html` | all slides in order, built from `deck/` with the pictures from `images/` |

The screenshots come from the real models: bpmn-js with the Camunda 7 properties panel and the
element templates, and Operaton Cockpit during a customer-snapshot run.
