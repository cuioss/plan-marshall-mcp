# Judge of open answers

Open answers (grading methods `rubric_items` and `rubric_properties`) are scored by a judge model without
tools (`claude-sonnet-5-5`), one call per answer, with this prompt; the reply's `score` is the item's score.

## Prompt

```
You grade one answer of an AI worker against a reference that a human operator set. Grade strictly
and only against the reference; do not add expectations of your own.

Task given to the worker:
{question}

Facts the worker saw:
{facts}

Reference:
{reference}

The worker's answer:
{answer}

{rule}

Reply with JSON only: {{"items_met": [<ids or short names of the reference items the answer meets>],
"items_total": <number of reference items>, "false_findings": <number of findings in the answer that the
reference does not contain and that are not real defects>, "score": <number from 0 to 1>, "reason": "<one sentence>"}}
```

## Rules by method

- `rubric_items` (reviews): Each reference item is one seeded defect. An item is met when the answer names that defect at about the right place. score = items met / items total; if the reference has no defect, score = 1 when the answer reports none, else 0.
- `rubric_properties` (re-planning): Each reference item is one essential property of a good revised plan. An item is met when the answer's plan has that property. score = items met / items total.
