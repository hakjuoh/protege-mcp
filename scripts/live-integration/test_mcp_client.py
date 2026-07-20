#!/usr/bin/env python3

import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))

from mcp_client import HarnessFailure, decode_response, structured_tool_result


class McpClientContractTest(unittest.TestCase):
    def test_decodes_json_response(self):
        self.assertEqual({"jsonrpc": "2.0", "id": 1}, decode_response(
            b'{"jsonrpc":"2.0","id":1}', "application/json"))

    def test_decodes_last_sse_data_event_with_crlf(self):
        body = (b"event: message\r\ndata: {\"id\":1}\r\n\r\n"
                b"event: message\r\ndata: {\"id\":2}\r\n\r\n")
        self.assertEqual({"id": 2}, decode_response(body, "text/event-stream; charset=utf-8"))

    def test_selects_matching_json_rpc_id_from_sse(self):
        body = (b'data: {"jsonrpc":"2.0","id":7,"result":{}}\n\n'
                b'data: {"jsonrpc":"2.0","method":"notifications/progress"}\n\n')
        self.assertEqual(7, decode_response(body, "text/event-stream", 7)["id"])

    def test_rejects_sse_without_data(self):
        with self.assertRaises(HarnessFailure):
            decode_response(b"event: ping\n\n", "text/event-stream")

    def test_prefers_structured_tool_content(self):
        response = {"result": {"structuredContent": {"count": 2}, "content": []}}
        self.assertEqual({"count": 2}, structured_tool_result(response))

    def test_falls_back_to_text_json(self):
        response = {"result": {"content": [{"type": "text", "text": json.dumps({"ok": True})}]}}
        self.assertEqual({"ok": True}, structured_tool_result(response))

    def test_rejects_json_rpc_errors(self):
        with self.assertRaises(HarnessFailure):
            structured_tool_result({"error": {"code": -32603, "message": "boom"}})


if __name__ == "__main__":
    unittest.main()
