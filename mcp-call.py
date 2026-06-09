#!/usr/bin/env python3
"""Send JSON-RPC requests to GetGoat MCP server via stdio."""
import subprocess
import json
import sys

def send_rpc(proc, request):
    """Send a JSON-RPC request and return the response."""
    line = json.dumps(request)
    print(f"  -> {line}")
    proc.stdin.write(line + "\n")
    proc.stdin.flush()
    response_line = proc.stdout.readline()
    response = json.loads(response_line)
    print(f"  <- {json.dumps(response, indent=2)[:500]}")
    return response

# Start MCP server
print("Starting MCP server...")
proc = subprocess.Popen(
    ["/home/cna/getgoat/mcp-server.sh"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True
)

try:
    # Step 1: Initialize
    print("\n=== Initialize ===")
    resp = send_rpc(proc, {
        "jsonrpc": "2.0",
        "id": "1",
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "test-client", "version": "0.1"}
        }
    })
    print(f"Server: {resp.get('result', {}).get('serverInfo', {}).get('name')}")

    # Send initialized notification
    proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
    proc.stdin.flush()

    # Step 2: Create unit in Beijing
    print("\n=== Create Unit: 北京站 ===")
    resp = send_rpc(proc, {
        "jsonrpc": "2.0",
        "id": "2",
        "method": "tools/call",
        "params": {
            "name": "create_unit",
            "arguments": {
                "code": "beijing-station",
                "name": "北京站",
                "lat": 39.9042,
                "lng": 116.4074,
                "source": "beijing",
                "status": "active"
            }
        }
    })
    beijing_result = json.loads(resp["result"]["content"][0]["text"])
    print(f"北京: {json.dumps(beijing_result, indent=2, ensure_ascii=False)}")

    # Step 3: Create unit in Shanghai
    print("\n=== Create Unit: 上海站 ===")
    resp = send_rpc(proc, {
        "jsonrpc": "2.0",
        "id": "3",
        "method": "tools/call",
        "params": {
            "name": "create_unit",
            "arguments": {
                "code": "shanghai-station",
                "name": "上海站",
                "lat": 31.2304,
                "lng": 121.4737,
                "source": "shanghai",
                "status": "active"
            }
        }
    })
    shanghai_result = json.loads(resp["result"]["content"][0]["text"])
    print(f"上海: {json.dumps(shanghai_result, indent=2, ensure_ascii=False)}")

    # Extract unit codes and coordinates
    beijing_code = beijing_result.get("code")
    shanghai_code = shanghai_result.get("code")
    beijing_lat = beijing_result.get("lat")
    beijing_lng = beijing_result.get("lng")
    shanghai_lat = shanghai_result.get("lat")
    shanghai_lng = shanghai_result.get("lng")
    print(f"\n北京: {beijing_code} ({beijing_lat}, {beijing_lng})")
    print(f"上海: {shanghai_code} ({shanghai_lat}, {shanghai_lng})")

    # Step 4: Get distance between the two units
    print("\n=== Get Distance: 北京 → 上海 ===")
    resp = send_rpc(proc, {
        "jsonrpc": "2.0",
        "id": "4",
        "method": "tools/call",
        "params": {
            "name": "get_distance",
            "arguments": {
                "lat1": beijing_lat,
                "lng1": beijing_lng,
                "lat2": shanghai_lat,
                "lng2": shanghai_lng
            }
        }
    })
    distance_result = json.loads(resp["result"]["content"][0]["text"])
    print(f"\n距离结果: {json.dumps(distance_result, indent=2, ensure_ascii=False)}")

finally:
    proc.terminate()
    proc.wait(timeout=5)
    stderr_output = proc.stderr.read()
    if stderr_output:
        print(f"\n[stderr]: {stderr_output[:1000]}")
