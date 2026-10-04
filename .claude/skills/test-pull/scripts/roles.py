"""Roles as configuration: the abstract model caller of the evaluation.

A role is one configuration artifact, `fixtures/roles/<set>/<name>.json`. The roles of a set are exactly the
artifacts present; no role name is known to the code, and a scenario names roles freely. Every property of
a role comes from its artifact: the harness, model, and effort it runs on, whether it is a warm worker or a
fresh job per task, how many run at once, the skills in its launch prompt, whether it may consult another
role, and when its workers are recycled.

`slots()` turns the roles a scenario needs into the slots of the job runtime (`supervisor.py`); `prompt()`
composes a role's launch prompt; `limits()` gives the runtime a role's recycling rules. The caller acts on
the configuration and nothing else.
"""
import json
import pathlib

ROLES = pathlib.Path(__file__).resolve().parents[1] / "fixtures" / "roles"
HARNESSES = ("claude", "opencode", "agy")
FORMS = ("warm", "fresh")
KEYS = {"description": str, "harness": str, "model": str, "effort": (str, type(None)), "form": str, "count": int,
        "skills": list, "consult": bool, "idle_wakeups": (int, type(None)), "token_budget": (int, type(None)),
        "recycle_every": (int, type(None))}


class RoleError(ValueError):
    """A role artifact that does not match the schema, or a role a scenario names that the set lacks."""


def load(role_set="default"):
    """Reads every role artifact of a set; returns {name: config}."""
    directory = ROLES / role_set
    if not directory.is_dir():
        raise RoleError(f"no role set {role_set!r} under {ROLES}")
    roles = {}
    for path in sorted(directory.glob("*.json")):
        config = json.loads(path.read_text(encoding="utf-8"))
        unknown = set(config) - set(KEYS)
        missing = set(KEYS) - set(config)
        if unknown or missing:
            raise RoleError(f"{path.name}: unknown keys {sorted(unknown)}, missing keys {sorted(missing)}")
        for key, kind in KEYS.items():
            if not isinstance(config[key], kind):
                raise RoleError(f"{path.name}: {key} has the wrong type")
        if config["harness"] not in HARNESSES or config["form"] not in FORMS or config["count"] < 1:
            raise RoleError(f"{path.name}: harness, form, or count out of range")
        roles[path.stem] = config
    return roles


def require(roles, names):
    missing = [name for name in names if name not in roles]
    if missing:
        raise RoleError(f"the role set has no role {missing}; it has {sorted(roles)}")
    return {name: roles[name] for name in names}


def slots(roles, names, eager=None):
    """Slots of the job runtime for the named roles.

    A warm role gets `count` long-lived workers, started at once unless `eager` says otherwise; a fresh role
    gets `count` places whose workers take one task each and are started when the stub shows work.
    """
    entries = []
    for name, config in require(roles, names).items():
        warm = config["form"] == "warm"
        entries.append({"role": name, "harness": config["harness"], "model": config["model"],
                        "effort": config["effort"], "count": config["count"],
                        "eager": warm if eager is None else eager.get(name, warm),
                        "prompt": f"role:{name}"})
    return entries


def prompt(config, protocol_prompt):
    """The launch prompt of a role: its skills, and for a fresh job the instruction to take one task."""
    return protocol_prompt(*config["skills"], one_task=config["form"] == "fresh")


def limits(config):
    """The recycling rules of a role's workers; a fresh job ends after its task anyway."""
    if config["form"] == "fresh":
        return {"idle_wakeups": None, "token_budget": None, "recycle_every": None}
    return {key: config[key] for key in ("idle_wakeups", "token_budget", "recycle_every")}


def tools(config, scenario):
    """The plan-marshall tools of a role: what the offered links can name, and nothing else."""
    names = ["pull_wait", "pull_submit"]
    if scenario.get("ack") == "explicit":
        names.append("pull_ack")
    if scenario.get("ack") == "implicit":
        names.append("pull_task")
    if scenario.get("consult") and config["consult"]:
        names.append("pull_consult")
    if scenario.get("skills_dir"):
        names += ["pm_skill", "pm_skill_file"]
    return names
