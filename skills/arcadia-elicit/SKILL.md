---
name: arcadia-elicit
description: Elicit the system before modelling it. Use when starting a new system, subsystem or perspective, or when a request leaves design decisions open that neither the prompt nor the model settles.
---

# Arcadia elicitation

Interview the user until you share one understanding of the system, then model it. Adapted from
Matt Pocock's [`grilling`](https://github.com/mattpocock/skills/blob/main/skills/productivity/grilling/SKILL.md) (MIT).

A request whose every decision is already fixed (named elements, owners, perspective), or a user who
says to just build it, goes straight to building.

## Model facts, team knowledge, decisions

- **Model facts** are yours: read them through the `capella` read tools and `capella_docs` before
  asking anything that depends on them.
- **Team knowledge** is what the user already holds and the model lacks: chosen parts, limits,
  protocols, rules, who operates what. Draw it out with **open questions** ("What is fixed about the
  drivetrain?") so the user tells you what they know in their own words.
- **Decisions** are what nobody has settled yet: put each with your recommended answer.

## Steps

1. **Read the model.** `model_summary`, then search and inspect what the request touches. Done when
   you know which perspectives exist and what the model already settles.
2. **Round 1: calibrate and harvest.** In one round ask: experience with the domain and with
   Arcadia/MBSE (novice, practitioner or expert); what the model is for; and one open question per
   area the request touches, asking what is already fixed (parts, values, interfaces, rules,
   operators). Done when every area has an answer, even "nothing fixed".
   - Novice: explain each Arcadia concept in a plain sentence where it first appears.
   - Expert: terse questions, concept names only.
3. **Grill the decisions.** What round 1 left open is the design tree, in Arcadia order:
   need → boundary → behaviour → logical → physical ([`QUESTIONS.md`](QUESTIONS.md)). The
   **frontier** is every open decision whose prerequisites are settled. Ask the whole frontier as one
   round, then recompute: every answer that names a new part, value or actor opens its own branch,
   asked as an open question first. Done when the frontier is empty for the perspectives the request
   reaches.
4. **Brief and stop.** Write the system brief: team knowledge with its values, decisions, assumptions
   (each deferred decision with your recommended value) and open items. End the turn by asking the
   user to confirm or correct it. Done only when the user confirms; then read [`BUILD.md`](BUILD.md).

## Round format

```
❓ **Q1** - **<title>**: <question; options when there are any; for a novice, one sentence on the concept>

➡️ <your recommended answer, for decisions only>
```

Number the questions. Word each decision so "yes" accepts your recommendation.
