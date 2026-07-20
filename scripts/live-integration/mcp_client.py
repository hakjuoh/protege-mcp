#!/usr/bin/env python3
"""Minimal dependency-free MCP client for the live Protégé release harness."""

from __future__ import annotations

import argparse
import json
import pathlib
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


PROTOCOL_VERSION = "2025-06-18"


class HarnessFailure(RuntimeError):
    pass


def decode_response(
    body: bytes, content_type: str, expected_id: int | None = None
) -> dict[str, Any] | None:
    if not body.strip():
        return None
    text = body.decode("utf-8")
    if "text/event-stream" in content_type:
        payloads = []
        for event in text.replace("\r\n", "\n").split("\n\n"):
            data = "\n".join(
                line[5:].lstrip() for line in event.splitlines() if line.startswith("data:")
            )
            if data:
                payloads.append(json.loads(data))
        if not payloads:
            raise HarnessFailure("MCP response contained no SSE data event")
        if expected_id is not None:
            for payload in payloads:
                if payload.get("id") == expected_id:
                    return payload
        return payloads[-1]
    return json.loads(text)


def structured_tool_result(response: dict[str, Any]) -> dict[str, Any]:
    if "error" in response:
        raise HarnessFailure(f"JSON-RPC error: {response['error']}")
    result = response.get("result") or {}
    structured = result.get("structuredContent")
    if isinstance(structured, dict):
        return structured
    for item in result.get("content") or []:
        if item.get("type") == "text":
            try:
                value = json.loads(item.get("text", ""))
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                return value
    raise HarnessFailure(f"Tool result had no structured JSON object: {result!r}")


@dataclass
class HttpFailure(Exception):
    status: int
    body: str


def http_json(
    method: str,
    url: str,
    token: str | None,
    payload: dict[str, Any] | None = None,
    session_id: str | None = None,
) -> tuple[int, dict[str, Any] | None, dict[str, str]]:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json, text/event-stream"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if session_id:
        headers["Mcp-Session-Id"] = session_id
        headers["Mcp-Protocol-Version"] = PROTOCOL_VERSION
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            raw_headers = {key.lower(): value for key, value in response.headers.items()}
            decoded = decode_response(
                response.read(), raw_headers.get("content-type", "application/json"),
                None if payload is None else payload.get("id"),
            )
            return response.status, decoded, raw_headers
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise HttpFailure(error.code, body) from error


class McpSession:
    def __init__(self, url: str, token: str):
        self.url = url
        self.token = token
        self.session_id: str | None = None
        self.next_id = 1

    def initialize(self) -> dict[str, Any]:
        payload = {
            "jsonrpc": "2.0",
            "id": self.next_id,
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "protege-mcp-live-harness", "version": "1"},
            },
        }
        self.next_id += 1
        _, response, headers = http_json("POST", self.url, self.token, payload)
        if response is None or "error" in response:
            raise HarnessFailure(f"MCP initialize failed: {response!r}")
        self.session_id = headers.get("mcp-session-id")
        if not self.session_id:
            raise HarnessFailure("MCP initialize response omitted Mcp-Session-Id")
        notification = {
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
            "params": {},
        }
        http_json("POST", self.url, self.token, notification, self.session_id)
        return response["result"]

    def call(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        if not self.session_id:
            raise HarnessFailure("MCP session is not initialized")
        payload = {
            "jsonrpc": "2.0",
            "id": self.next_id,
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments or {}},
        }
        self.next_id += 1
        _, response, _ = http_json(
            "POST", self.url, self.token, payload, self.session_id
        )
        if response is None:
            raise HarnessFailure(f"{name} returned an empty response")
        return structured_tool_result(response)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise HarnessFailure(message)


def wait_for_instances(base_url: str, token: str, deadline_seconds: int) -> list[dict[str, Any]]:
    deadline = time.monotonic() + deadline_seconds
    last = "broker endpoint was not reachable"
    while time.monotonic() < deadline:
        try:
            _, body, _ = http_json("GET", f"{base_url}/instances", token)
            instances = [] if body is None else body.get("instances", [])
            if len(instances) >= 2:
                return instances
            last = f"only {len(instances)} window(s) registered"
        except (OSError, HttpFailure, json.JSONDecodeError) as error:
            last = str(error)
        time.sleep(0.5)
    raise HarnessFailure(f"two Protégé windows did not register before the deadline: {last}")


def run(base_url: str, token: str, report_path: pathlib.Path) -> None:
    try:
        http_json("GET", f"{base_url}/instances", None)
        raise HarnessFailure("unauthenticated /instances request unexpectedly succeeded")
    except HttpFailure as error:
        require(error.status == 401, f"unauthenticated request returned HTTP {error.status}")
    try:
        http_json("GET", f"{base_url}/instances", token + "-wrong")
        raise HarnessFailure("an incorrect bearer token unexpectedly succeeded")
    except HttpFailure as error:
        require(error.status == 401, f"incorrect token returned HTTP {error.status}")
    unauthenticated_initialize = {
        "jsonrpc": "2.0",
        "id": 0,
        "method": "initialize",
        "params": {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "unauthenticated-live-harness", "version": "1"},
        },
    }
    try:
        http_json("POST", f"{base_url}/mcp", None, unauthenticated_initialize)
        raise HarnessFailure("an unauthenticated MCP initialize unexpectedly succeeded")
    except HttpFailure as error:
        require(error.status == 401, f"unauthenticated MCP initialize returned HTTP {error.status}")

    instances = wait_for_instances(base_url, token, 90)
    sessions: dict[str, McpSession] = {}
    ontology_by_window: dict[str, str] = {}
    server_versions: set[str] = set()
    for instance in instances[:2]:
        window_id = instance["id"]
        session = McpSession(base_url + instance["mcp_path"], token)
        initialized = session.initialize()
        server_versions.add(initialized["serverInfo"]["version"])
        active = session.call("get_active_ontology")
        ontology_by_window[window_id] = active.get("ontology_iri")
        sessions[window_id] = session

    expected_iris = {"http://example.org/live-a", "http://example.org/live-b"}
    require(
        set(ontology_by_window.values()) == expected_iris,
        f"window routes did not expose both fixture ontologies: {ontology_by_window}",
    )

    generic = McpSession(f"{base_url}/mcp", token)
    generic.initialize()
    pinned_before = generic.call("get_active_ontology")["ontology_iri"]
    require(pinned_before in expected_iris, f"generic session targeted {pinned_before!r}")
    other_window = next(
        window_id
        for window_id, iri in ontology_by_window.items()
        if iri != pinned_before
    )
    mismatch_payload = {
        "jsonrpc": "2.0",
        "id": 9001,
        "method": "tools/call",
        "params": {"name": "get_active_ontology", "arguments": {}},
    }
    try:
        http_json(
            "POST",
            base_url + next(i["mcp_path"] for i in instances if i["id"] == other_window),
            token,
            mismatch_payload,
            generic.session_id,
        )
        raise HarnessFailure("a pinned session was accepted by a different explicit window route")
    except HttpFailure as error:
        require(error.status == 409, f"window mismatch returned HTTP {error.status}: {error.body}")
        require("session_window_mismatch" in error.body, "window mismatch lacked its stable error code")
    pinned_after = generic.call("get_active_ontology")["ontology_iri"]
    require(pinned_after == pinned_before, "generic MCP session changed windows between calls")

    target_window = next(
        window_id
        for window_id, iri in ontology_by_window.items()
        if iri == "http://example.org/live-a"
    )
    target = sessions[target_window]
    term_iri = "http://example.org/live-a#HarnessTerm"
    created = target.call(
        "create_term",
        {
            "iri": term_iri,
            "label": "live harness term",
            "definition": "Created by the bounded live integration harness.",
            "parents": ["http://example.org/live-a#Animal"],
            "strict": True,
        },
    )
    require(created.get("applied", 0) >= 3, f"create_term applied too few changes: {created}")
    visible = target.call("get_entity", {"entity": term_iri})
    require(visible.get("count") == 1, f"live model did not expose the created term: {visible}")
    peek = target.call("undo_change", {"peek": True})
    require(
        peek.get("next_undo", {}).get("changes") == created["applied"],
        f"create_term was not one matching Undo transaction: create={created}, peek={peek}",
    )
    undone = target.call("undo_change")
    require(undone.get("undone") is True, f"undo_change failed: {undone}")
    absent = target.call("get_entity", {"entity": term_iri})
    require("error" in absent, f"one Undo did not remove the complete term: {absent}")

    selected = target.call("set_reasoner", {"reasoner": "HermiT"})
    require("HermiT" in selected.get("selected", {}).get("name", ""), f"HermiT not selected: {selected}")
    classified = target.call("run_reasoner", {"timeout_ms": 120_000})
    require(classified.get("started") is True, f"classification did not start: {classified}")
    require(classified.get("completed") is True, f"classification did not complete: {classified}")
    require(classified.get("inconsistent") is False, f"fixture classified inconsistent: {classified}")
    explanation = target.call(
        "get_explanations",
        {
            "axiom_type": "subclass_of",
            "sub": "http://example.org/live-a#Dog",
            "super": "http://example.org/live-a#Animal",
            "max": 1,
            "timeout_ms": 120_000,
        },
    )
    require(explanation.get("entailed") is True, f"expected entailment was not explained: {explanation}")
    require(
        explanation.get("justification_count", 0) >= 1,
        f"real reasoner returned no justification: {explanation}",
    )
    require(
        explanation.get("justifications", [{}])[0].get("size", 0) >= 2,
        f"explanation did not contain the asserted hierarchy chain: {explanation}",
    )

    report = {
        "status": "pass",
        "authentication": {
            "unauthenticated_instances_status": 401,
            "wrong_token_status": 401,
            "unauthenticated_mcp_status": 401,
            "static_token": "accepted",
        },
        "windows": ontology_by_window,
        "server_versions": sorted(server_versions),
        "session_pinning": {"ontology_before": pinned_before, "ontology_after": pinned_after},
        "write_and_undo": {
            "created_iri": term_iri,
            "transaction_changes": created["applied"],
            "removed_by_one_undo": True,
        },
        "reasoner": {
            "name": selected["selected"]["name"],
            "classification_completed": True,
            "justification_count": explanation["justification_count"],
        },
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--report", type=pathlib.Path, required=True)
    args = parser.parse_args()
    run(args.base_url.rstrip("/"), args.token, args.report)


if __name__ == "__main__":
    main()
