# Dynamic Form Rendering

*Describe a form once. Render it anywhere — no new code each time.*

A plain-language guide to how our forms are built from data, illustrated with the five examples we prototyped. No code, just the ideas.

---

## The core idea

Most forms are built by hand: a developer lays out each field, writes its rules, and ships it inside the app. Every change — a new question, a new rule — means another code change and another release.

Dynamic rendering flips that around. **The form becomes data.** The backend sends a *description* of the form — its fields, their types, their rules, where their data comes from, and how they relate to each other. A single rendering engine reads that description and draws the working screen.

| The old way | The dynamic way |
|---|---|
| Every form is built by hand in the app | The backend sends a description of the form |
| A new field or rule means a code change | One rendering engine draws any form from it |
| Each change waits for an app release | New fields and rules go live without a release |
| Validation and data lists get duplicated | Validation and data sources are defined once |

---

## How it works

From a description to a working screen, in three steps:

1. **The backend sends a form description.** Fields, types, rules, data sources and relationships — all expressed as data.
2. **The engine reads it.** One renderer interprets the description and lays out the screen.
3. **A live form appears.** Controls, validation and data behave exactly as described.

The same engine renders a sign-up form, a compliance questionnaire, or a beneficial-owner list. Only the description changes.

---

## What a description contains

Everything in our examples comes down to six things a form can describe:

- **Field types** — text, dropdown, checkbox, radio, file upload, and more.
- **Validation rules** — how long an answer can be, which characters are allowed, how many options can be picked.
- **Data sources** — lists fetched live from another system rather than baked into the form.
- **Conditional logic** — follow-up questions that appear based on earlier answers.
- **Nesting and repetition** — forms within forms, each with sensible limits.
- **Saved values** — past submissions that flow back in, pre-filled.

### Anatomy of a field

Every single field carries four things, and the engine reads all four:

- **A label** — what the person sees and understands.
- **A control** — how they answer: type, pick, toggle, or upload.
- **Validation** — what counts as a valid answer.
- **A data source** — where its options come from, if any.

Because these are described rather than coded, the same checks and the same lists can be reused across every form that needs them.

---

## The examples

We built five working prototypes. Each one highlights a different capability of dynamic rendering.

### Example 1 — Smart forms that react

*Questions that adapt to the answer.*

A compliance questionnaire where picking an option reveals the right follow-up — and hides everything irrelevant.

- **Pick and reveal** — choosing "investments" opens a field asking which kind.
- **Built-in rules** — each answer is checked against its own length and format rules.
- **One source of truth** — the questions and the rules live in the description, not the app.

### Example 2 — Fields powered by live data

*Lists that come from another system.*

A branch picker and a card-benefit chooser whose contents are fetched the moment the form opens.

- **Not baked in** — the branch list (001, 002, …) isn't part of the form; it's pulled live.
- **Always current** — add a branch in the source system and it simply appears, no form change needed.
- **Named source** — the form only says *where* to get the list; the engine fetches it.

### Example 3 — Forms inside forms

*Repeatable entries, with limits.*

A beneficial-owner section: add several people, each with its own address pop-up — all governed by the description.

- **Add many** — capture up to ten owners, each its own mini-form.
- **Pop-up details** — an address sub-form, limited to one per owner.
- **Two views** — one layout for entering information, another for the finished record.

### Example 4 — Options from the database

*Saved records become choices.*

Previously submitted owners come back as selectable options. Pick one and its form re-opens, already filled in.

- **Pick a record** — saved submissions appear as a single-select list.
- **Pre-filled** — selecting one restores the values that were saved.
- **Same description** — the same form definition both captures and replays the data.

---

## Why it matters

- **Change without releases** — adjust a form by changing its description; no app deployment.
- **One consistent experience** — every form is drawn by the same engine, so they look and behave alike.
- **Rules stay in sync** — validation and data lists are defined once and reused everywhere.
- **Faster to respond** — new compliance or product needs can ship in days, not release cycles.

---

## What to keep an eye on

Dynamic rendering is powerful, but doing it well takes discipline:

1. **A clear, consistent contract.** The backend and the engine must agree on how a description is written — consistent naming and structure. (In our samples, some descriptions used different naming styles for the same idea; that kind of drift is exactly what a shared contract prevents.)
2. **Governing the data sources.** Lists pulled from other systems need clear ownership and reliability.
3. **Performance of live lookups.** Fetching lists adds load — cache where possible and design gracefully for slow or failed responses.
4. **Testing the engine, not each form.** Confidence comes from testing the renderer against the full range of descriptions it might receive, rather than testing forms one by one.

---

## In one line

**Describe the form once. Render it anywhere.** Forms become data the business can change — drawn by one engine, the same way every time.
