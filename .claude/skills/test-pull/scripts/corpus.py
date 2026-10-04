"""The model verification corpus (test/model/verification/): the loader of the evaluation driver.

The corpus outlives this driver: one JSON file per item, grouped by topic, in the format of
`schema/verification-item.schema.json`. The corpus was imported once, on 2026-10-04,
from the verified sets of the evaluation (git history of this file); it is the source now, `load()` reads it
for the driver, and fixtures.py writes only candidates.
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[4]
CORPUS = ROOT / "test" / "model" / "verification"
E7_TOPICS = {"build-triage": "triage/build", "pr-triage": "triage/pr-comment", "sonar-triage": "triage/sonar",
             "replan": "planning/replan", "security-review": "review/security",
             "simplify-review": "review/simplify", "self-review": "review/self-review"}


def load(topic):
    """All items of a topic (and its subtopics), sorted by id."""
    directory = CORPUS / topic
    items = [json.loads(path.read_text(encoding="utf-8")) for path in sorted(directory.rglob("*.json"))]
    return [item for item in items if item.get("schema_version") == 1]


