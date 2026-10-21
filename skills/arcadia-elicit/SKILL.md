---
name: arcadia-elicit
description: Elicit the system before modelling it. Use when starting a new system, subsystem or perspective, or when a request leaves design decisions open that neither the prompt nor the model settles.
---

# Arcadia elicitation

Interview the user until you share one understanding of the system, then model it. Adapted from
Matt Pocock's [`grilling`](https://github.com/mattpocock/skills/blob/main/skills/productivity/grilling/SKILL.md) (MIT).

A request whose every decision is already fixed (named elements, owners, perspective), or a user who
says to just build it, goes straight to `arcadia-method` and `capella-model-edits`.

## Facts and decisions

**Facts** are yours: what the model already holds, read through the `capella` read tools and
`capella_docs`. **Decisions** are the user's: need, boundary, decomposition, trade-offs. Put each
decision to the user; look up each fact yourself before asking anything that depends on it.

## Steps

1. **Round 0: calibrate.** Ask, in one round: experience with the domain, experience with
   Arcadia/MBSE (novice, practitioner or expert), and what the model is for (concept study, design
   review, interface control, safety analysis, teaching). Done when all three are answered.
   - Novice: explain each Arcadia concept in a plain sentence where it first appears.
   - Expert: terse questions, concept names only.
2. **Read the model.** `model_summary`, then search and inspect what the request touches. Done when
   you know which perspectives exist and which decisions the model already settles.
3. **Grill the design tree.** Each decision branches into the decisions that hang off it, in Arcadia
   order: need → boundary → behaviour → logical → physical. Questions per perspective are in
   [`QUESTIONS.md`](QUESTIONS.md). The **frontier** is every open decision whose prerequisites are
   settled. Ask the whole frontier as one round, then wait for answers and recompute it. A question
   that depends on another question in the same round belongs to the next round. Done when the
   frontier is empty for the perspectives the request reaches.
4. **Write the brief.** A short system brief: decisions, assumptions (every decision the user
   deferred, with your recommended value), open items. Done when the user confirms it.
5. **Build from the brief** with `arcadia-method` and `capella-model-edits`. Record each assumption
   as a TBD on the element it affects. Done when every brief decision maps to a native element or a
   recorded TBD.

## Round format

```
❓ **Q1** - **<title>**: <question; options when there are any; for a novice, one sentence on the concept>

➡️ <your recommended answer>
```

Number the questions and word each so "yes" accepts your recommendation.
