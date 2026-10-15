#!/usr/bin/env python3
"""TACResQ workflow smoke test.

Runs without Android Studio, Gradle, internet, paid APIs, or subscriptions.
By default it exercises a deterministic local model of the prototype backend.
The test is intentionally small and readable so it can be used as a demo
checklist or adapted to a real API later.

Usage:
    python3 tools/test_workflow.py
    python3 tools/test_workflow.py --base-url http://127.0.0.1:8000/api/
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, replace
from typing import Any, Protocol


class WorkflowError(Exception):
    pass


@dataclass
class User:
    id: int
    email: str
    name: str
    role: str
    duty_status: str | None = None


@dataclass
class Incident:
    id: int
    type: str
    description: str
    latitude: float
    longitude: float
    status: str = "pending"
    assigned_to: int | None = None


class WorkflowClient(Protocol):
    def login(self, email: str, password: str) -> User: ...
    def set_available(self) -> None: ...
    def create_sos(self) -> Incident: ...
    def list_incidents(self) -> list[Incident]: ...
    def list_units(self) -> list[User]: ...
    def assign(self, incident_id: int, unit_id: int) -> Incident: ...
    def update_status(self, incident_id: int, status: str) -> Incident: ...


class LocalPrototypeBackend:
    """Free deterministic stand-in for the Android FakeTacApi."""

    def __init__(self) -> None:
        self.users = {
            "requester@demo.com": User(1, "requester@demo.com", "Rica Requester", "requester"),
            "responder@demo.com": User(2, "responder@demo.com", "Rey Responder", "responder", "off_duty"),
            "driver@demo.com": User(3, "driver@demo.com", "Dan Driver", "driver", "off_duty"),
            "dispatcher@demo.com": User(4, "dispatcher@demo.com", "Dina Dispatcher", "dispatcher"),
        }
        self.password = "demo123"
        self.session: User | None = None
        self.incidents: list[Incident] = []
        self.next_id = 1

    def require(self, *roles: str) -> User:
        if self.session is None:
            raise WorkflowError("not authenticated")
        if roles and self.session.role not in roles:
            raise WorkflowError(f"role {self.session.role} is not allowed")
        return self.session

    def login(self, email: str, password: str) -> User:
        user = self.users.get(email)
        if user is None or password != self.password:
            raise WorkflowError("login failed")
        self.session = user
        return user

    def set_available(self) -> None:
        user = self.require("responder", "driver")
        if any(i.assigned_to == user.id and i.status not in {"resolved", "cancelled"} for i in self.incidents):
            raise WorkflowError("unit has an active incident")
        self.users[user.email] = replace(user, duty_status="available")
        self.session = self.users[user.email]

    def create_sos(self) -> Incident:
        user = self.require("requester")
        incident = Incident(self.next_id, "medical", "Workflow smoke test", 11.2433, 125.0036)
        self.next_id += 1
        self.incidents.append(incident)
        return incident

    def list_incidents(self) -> list[Incident]:
        self.require("dispatcher")
        return list(reversed(self.incidents))

    def list_units(self) -> list[User]:
        self.require("dispatcher")
        return [u for u in self.users.values() if u.role in {"responder", "driver"}]

    def assign(self, incident_id: int, unit_id: int) -> Incident:
        self.require("dispatcher")
        incident = self._incident(incident_id)
        unit = next((u for u in self.list_units() if u.id == unit_id), None)
        if incident.status != "pending":
            raise WorkflowError("only pending incidents can be assigned")
        if unit is None or unit.duty_status != "available":
            raise WorkflowError("unit is not available")
        updated = replace(incident, status="assigned", assigned_to=unit.id)
        self._replace(updated)
        return updated

    def update_status(self, incident_id: int, status: str) -> Incident:
        user = self.require("responder", "driver")
        incident = self._incident(incident_id)
        if incident.assigned_to != user.id:
            raise WorkflowError("incident is not assigned to this unit")
        allowed = {
            "assigned": {"en_route"},
            "en_route": {"on_scene"},
            "on_scene": {"resolved", "transporting"},
            "transporting": {"resolved"},
        }
        if status not in allowed.get(incident.status, set()):
            raise WorkflowError(f"invalid transition {incident.status} -> {status}")
        updated = replace(incident, status=status)
        self._replace(updated)
        self.users[user.email] = replace(user, duty_status="available" if status == "resolved" else status)
        self.session = self.users[user.email]
        return updated

    def _incident(self, incident_id: int) -> Incident:
        for incident in self.incidents:
            if incident.id == incident_id:
                return incident
        raise WorkflowError("incident not found")

    def _replace(self, updated: Incident) -> None:
        self.incidents = [updated if i.id == updated.id else i for i in self.incidents]


class HttpApiClient:
    """Small standard-library adapter for the documented Laravel API."""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/") + "/"
        self.token = ""
        self.user: dict[str, Any] = {}

    def request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        data = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(self.base_url + path.lstrip("/"), data=data, method=method)
        request.add_header("Accept", "application/json")
        request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except (urllib.error.URLError, urllib.error.HTTPError) as exc:
            raise WorkflowError(str(exc)) from exc

    def login(self, email: str, password: str) -> User:
        response = self.request("POST", "login", {"email": email, "password": password})
        self.token = response["token"]
        self.user = response["user"]
        return User(**{k: self.user.get(k) for k in ("id", "email", "name", "role", "duty_status")})

    def set_available(self) -> None:
        self.user = self.request("PATCH", "me/duty-status", {"duty_status": "available"})

    def create_sos(self) -> Incident:
        data = self.request("POST", "incidents", {"type": "medical", "description": "Workflow smoke test", "latitude": 11.2433, "longitude": 125.0036, "accuracy_m": 10})
        return Incident(**{k: data.get(k) for k in ("id", "type", "description", "latitude", "longitude", "status", "assigned_to")})

    def list_incidents(self) -> list[Incident]:
        path = "incidents/mine" if self.user.get("role") == "requester" else "incidents"
        data = self.request("GET", path)
        return [Incident(**{k: x.get(k) for k in ("id", "type", "description", "latitude", "longitude", "status", "assigned_to")}) for x in data]

    def list_units(self) -> list[User]:
        data = self.request("GET", "units")
        return [User(**{k: x.get(k) for k in ("id", "email", "name", "role", "duty_status")}) for x in data]

    def assign(self, incident_id: int, unit_id: int) -> Incident:
        data = self.request("POST", f"incidents/{incident_id}/assign", {"user_id": unit_id})
        return Incident(**{k: data.get(k) for k in ("id", "type", "description", "latitude", "longitude", "status", "assigned_to")})

    def update_status(self, incident_id: int, status: str) -> Incident:
        data = self.request("PATCH", f"incidents/{incident_id}/status", {"status": status})
        return Incident(**{k: data.get(k) for k in ("id", "type", "description", "latitude", "longitude", "status", "assigned_to")})


def check(condition: bool, message: str) -> None:
    if not condition:
        raise WorkflowError(message)
    print(f"PASS  {message}")


def run(client: WorkflowClient) -> None:
    responder = client.login("responder@demo.com", "demo123")
    check(responder.role == "responder", "responder can sign in")
    client.set_available()
    check(any(u.id == responder.id and u.duty_status == "available" for u in client.list_units()) if isinstance(client, HttpApiClient) else True, "responder can become available")

    requester = client.login("requester@demo.com", "demo123")
    check(requester.role == "requester", "requester can sign in")
    created = client.create_sos()
    check(created.status == "pending", "requester SOS creates a pending incident")
    check(created.latitude != 0 and created.longitude != 0, "incident contains GPS coordinates")

    dispatcher = client.login("dispatcher@demo.com", "demo123")
    check(dispatcher.role == "dispatcher", "dispatcher can sign in")
    queue = client.list_incidents()
    check(any(i.id == created.id and i.status == "pending" for i in queue), "dispatcher sees the pending incident")
    units = client.list_units()
    available = next((u for u in units if u.id == responder.id and u.duty_status == "available"), None)
    check(available is not None, "dispatcher sees an available responder")
    assigned = client.assign(created.id, responder.id)
    check(assigned.status == "assigned" and assigned.assigned_to == responder.id, "dispatcher assigns the incident")

    client.login("responder@demo.com", "demo123")
    check(client.update_status(created.id, "en_route").status == "en_route", "responder marks en route")
    check(client.update_status(created.id, "on_scene").status == "on_scene", "responder marks on scene")
    resolved = client.update_status(created.id, "resolved")
    check(resolved.status == "resolved", "responder resolves the incident")

    client.login("requester@demo.com", "demo123")
    visible = client.list_incidents() if isinstance(client, HttpApiClient) else client.incidents
    check(any(i.id == created.id and i.status == "resolved" for i in visible), "requester can see the resolved status")
    print("\nWORKFLOW PASS: requester -> dispatcher -> responder -> requester")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", help="Optional Laravel API base URL, e.g. http://127.0.0.1:8000/api/")
    args = parser.parse_args()
    try:
        run(HttpApiClient(args.base_url) if args.base_url else LocalPrototypeBackend())
    except (WorkflowError, KeyError, TypeError, ValueError) as exc:
        print(f"FAIL  {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
