#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# test-toon-e2e.sh - E2E test for TOON serialization format via Recaf MCP
#
# Tests multiple MCP tool calls and validates response format (JSON vs TOON).
# Outputs a summary table with character counts per tool, suitable for
# comparing serialization overhead between formats.
# ============================================================================

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
FORMAT="json"
HOST="127.0.0.1"
PORT="8085"
CSV_OUTPUT=""
VERBOSE=0
HEADER_FILE=$(mktemp /tmp/mcp-headers-XXXXXX.txt)

# ---------------------------------------------------------------------------
# Colors
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
cleanup() {
    rm -f "$HEADER_FILE"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

E2E test for TOON serialization format via the Recaf MCP server.

Calls several MCP tools, captures responses, validates the response format,
and prints a summary table with character counts.

Options:
  --format FORMAT   Expected response format: "json" or "toon" (default: json)
                    The server must already be running with the matching config.
                    When "toon", responses are validated to NOT be JSON.
  --host HOST       MCP server host (default: 127.0.0.1)
  --port PORT       MCP server port (default: 8085)
  --csv FILE        Write CSV results to FILE (tool_name,format,char_count)
  --verbose         Print full raw responses (not truncated)
  --help            Show this help message

Examples:
  # Test against a JSON-format server (default)
  $(basename "$0")

  # Test against a TOON-format server on port 8086
  $(basename "$0") --format toon --port 8086

  # Generate CSV for later comparison
  $(basename "$0") --format json --csv /tmp/json-results.csv
  $(basename "$0") --format toon --port 8086 --csv /tmp/toon-results.csv

  # Compare two runs (external diff)
  diff /tmp/json-results.csv /tmp/toon-results.csv
EOF
    exit 0
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --format)
            FORMAT="$2"
            shift 2
            ;;
        --host)
            HOST="$2"
            shift 2
            ;;
        --port)
            PORT="$2"
            shift 2
            ;;
        --csv)
            CSV_OUTPUT="$2"
            shift 2
            ;;
        --verbose)
            VERBOSE=1
            shift
            ;;
        --help|-h)
            usage
            ;;
        *)
            echo -e "${RED}ERROR:${RESET} Unknown option: $1" >&2
            echo "Run with --help for usage information." >&2
            exit 1
            ;;
    esac
done

if [[ "$FORMAT" != "json" && "$FORMAT" != "toon" ]]; then
    echo -e "${RED}ERROR:${RESET} --format must be 'json' or 'toon', got '$FORMAT'" >&2
    exit 1
fi

BASE_URL="http://${HOST}:${PORT}/mcp"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log_info()  { echo -e "${BLUE}[INFO]${RESET}  $*"; }
log_pass()  { echo -e "${GREEN}[PASS]${RESET}  $*"; }
log_fail()  { echo -e "${RED}[FAIL]${RESET}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
log_step()  { echo -e "${CYAN}[STEP]${RESET}  $*"; }

# Check dependencies
for cmd in curl jq; do
    if ! command -v "$cmd" &>/dev/null; then
        echo -e "${RED}ERROR:${RESET} Required command '$cmd' not found." >&2
        exit 1
    fi
done

# Parse SSE response: extracts the JSON-RPC payload from SSE event stream.
# If the response is already plain JSON (starts with {), returns it as-is.
parse_sse_response() {
    local raw="$1"

    # If it looks like plain JSON already, return as-is
    if echo "$raw" | head -c1 | grep -q '{'; then
        echo "$raw"
        return
    fi

    # SSE format: lines like "event: message\ndata: {...}\n\n"
    # Extract the last data: line (the JSON-RPC result)
    local data_line
    data_line=$(echo "$raw" | grep '^data: ' | tail -1 | sed 's/^data: //')
    if [[ -n "$data_line" ]]; then
        echo "$data_line"
    else
        # Fallback: return raw
        echo "$raw"
    fi
}

# Extract text content from a tools/call result.
# The result structure is: {"result":{"content":[{"type":"text","text":"..."}]}}
extract_text_content() {
    local json="$1"
    # Try to extract the text field from the first content item
    local text
    text=$(echo "$json" | jq -r '.result.content[0].text // empty' 2>/dev/null)
    if [[ -n "$text" ]]; then
        echo "$text"
        return
    fi
    # Fallback: try without .result wrapper (in case response shape differs)
    text=$(echo "$json" | jq -r '.content[0].text // empty' 2>/dev/null)
    if [[ -n "$text" ]]; then
        echo "$text"
        return
    fi
    # Last resort: return the raw json
    echo "$json"
}

# Validate format: checks if the text content matches the expected format
validate_format() {
    local text="$1"
    local expected="$2"
    local trimmed
    trimmed=$(echo "$text" | sed 's/^[[:space:]]*//')

    if [[ "$expected" == "toon" ]]; then
        # TOON should NOT start with { or [
        local first_char
        first_char=$(echo "$trimmed" | head -c1)
        if [[ "$first_char" == "{" || "$first_char" == "[" ]]; then
            echo "STILL_JSON"
        else
            echo "OK"
        fi
    else
        # JSON should start with { or [
        local first_char
        first_char=$(echo "$trimmed" | head -c1)
        if [[ "$first_char" == "{" || "$first_char" == "[" ]]; then
            echo "OK"
        else
            # Could be plain text (e.g., error messages) - that's acceptable
            echo "PLAIN_TEXT"
        fi
    fi
}

# Make an MCP request and return the parsed JSON-RPC response
mcp_request() {
    local payload="$1"
    local raw_response

    raw_response=$(curl -s -w '\n' -X POST "$BASE_URL" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        ${SESSION_ID:+-H "Mcp-Session-Id: $SESSION_ID"} \
        -d "$payload" \
        --max-time 30 2>/dev/null) || {
        echo '{"error":"curl_failed"}'
        return
    }

    parse_sse_response "$raw_response"
}

# ---------------------------------------------------------------------------
# Results storage
# ---------------------------------------------------------------------------
declare -a RESULT_NAMES=()
declare -a RESULT_COUNTS=()
declare -a RESULT_FORMATS=()
declare -a RESULT_STATUSES=()

record_result() {
    local name="$1" count="$2" status="$3"
    RESULT_NAMES+=("$name")
    RESULT_COUNTS+=("$count")
    RESULT_FORMATS+=("$FORMAT")
    RESULT_STATUSES+=("$status")
}

# ---------------------------------------------------------------------------
# Preflight: check server reachability
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}========================================${RESET}"
echo -e "${BOLD} Recaf MCP - TOON E2E Test${RESET}"
echo -e "${BOLD}========================================${RESET}"
echo ""
log_info "Server:  ${BASE_URL}"
log_info "Format:  ${FORMAT}"
echo ""

log_step "Checking server reachability..."
if ! curl -s -o /dev/null --max-time 5 -X POST "$BASE_URL" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","id":0,"method":"ping"}' 2>/dev/null; then
    log_fail "Cannot reach MCP server at ${BASE_URL}"
    log_info "Make sure Recaf is running with the MCP plugin loaded."
    if [[ "$FORMAT" == "toon" ]]; then
        log_info "For TOON format, start with: -Drecaf.mcp.format=toon"
    fi
    exit 1
fi
log_pass "Server is reachable"
echo ""

# ---------------------------------------------------------------------------
# Step 1: Initialize MCP session
# ---------------------------------------------------------------------------
log_step "Initializing MCP session..."

SESSION_ID=""
INIT_PAYLOAD='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"toon-e2e-test","version":"1.0"}}}'

INIT_RAW=$(curl -s -D "$HEADER_FILE" -w '\n' -X POST "$BASE_URL" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d "$INIT_PAYLOAD" \
    --max-time 15 2>/dev/null) || {
    log_fail "Failed to send initialize request"
    exit 1
}

# Extract session ID from response headers
SESSION_ID=$(grep -i 'Mcp-Session-Id' "$HEADER_FILE" | tr -d '\r\n' | awk '{print $2}')

INIT_RESPONSE=$(parse_sse_response "$INIT_RAW")

if [[ -z "$SESSION_ID" ]]; then
    log_warn "No Mcp-Session-Id header in response (server may not require sessions)"
    log_info "Raw init response (first 300 chars):"
    echo "  ${INIT_RESPONSE:0:300}"
else
    log_pass "Session established: ${DIM}${SESSION_ID}${RESET}"
fi

# Verify init response has serverInfo
SERVER_NAME=$(echo "$INIT_RESPONSE" | jq -r '.result.serverInfo.name // empty' 2>/dev/null)
if [[ -n "$SERVER_NAME" ]]; then
    log_pass "Server identified as: ${SERVER_NAME}"
else
    log_warn "Could not extract serverInfo from init response"
    if [[ "$VERBOSE" -eq 1 ]]; then
        echo "  Raw: $INIT_RESPONSE"
    fi
fi

# Send initialized notification (required by MCP protocol)
log_step "Sending initialized notification..."
mcp_request '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null 2>&1 || true
log_pass "Initialized notification sent"

echo ""

# ---------------------------------------------------------------------------
# Step 2: Call tools
# ---------------------------------------------------------------------------
REQUEST_ID=10

call_tool() {
    local tool_name="$1"
    local arguments="$2"
    local description="$3"

    REQUEST_ID=$((REQUEST_ID + 1))
    log_step "Calling tool: ${BOLD}${tool_name}${RESET} - ${description}"

    local payload
    payload=$(jq -n \
        --arg id "$REQUEST_ID" \
        --arg name "$tool_name" \
        --argjson args "$arguments" \
        '{jsonrpc:"2.0", id:($id|tonumber), method:"tools/call", params:{name:$name, arguments:$args}}')

    local response
    response=$(mcp_request "$payload")

    # Check for curl failure
    if echo "$response" | jq -e '.error == "curl_failed"' &>/dev/null; then
        log_fail "  Request failed (connection error)"
        record_result "$tool_name" 0 "ERROR"
        return
    fi

    # Check for JSON-RPC error
    local rpc_error
    rpc_error=$(echo "$response" | jq -r '.error.message // empty' 2>/dev/null)
    if [[ -n "$rpc_error" ]]; then
        log_warn "  JSON-RPC error: ${rpc_error}"
    fi

    # Extract text content
    local text_content
    text_content=$(extract_text_content "$response")
    local char_count=${#text_content}

    # Print response preview
    if [[ "$VERBOSE" -eq 1 ]]; then
        echo -e "  ${DIM}--- Response (${char_count} chars) ---${RESET}"
        echo "  $text_content"
        echo -e "  ${DIM}--- End ---${RESET}"
    else
        local preview="${text_content:0:500}"
        if [[ ${#text_content} -gt 500 ]]; then
            preview="${preview}..."
        fi
        echo -e "  ${DIM}Response (${char_count} chars):${RESET}"
        echo "  ${preview}" | head -10
        local line_count
        line_count=$(echo "$preview" | wc -l)
        if [[ $line_count -gt 10 ]]; then
            echo -e "  ${DIM}  ... (truncated)${RESET}"
        fi
    fi

    # Validate format
    local format_check
    format_check=$(validate_format "$text_content" "$FORMAT")

    case "$format_check" in
        OK)
            log_pass "  Format check: ${FORMAT} confirmed (${char_count} chars)"
            record_result "$tool_name" "$char_count" "PASS"
            ;;
        STILL_JSON)
            log_fail "  Format check: STILL JSON - TOON not active"
            record_result "$tool_name" "$char_count" "FAIL_FORMAT"
            ;;
        PLAIN_TEXT)
            log_warn "  Format check: plain text response (may be error/empty workspace)"
            record_result "$tool_name" "$char_count" "WARN"
            ;;
    esac
    echo ""
}

echo -e "${BOLD}--- Tool Calls ---${RESET}"
echo ""

# Tool 1: workspace-get-info (no params)
call_tool "workspace-get-info" '{}' "Get current workspace info"

# Tool 2: class-list with pagination
call_tool "class-list" '{"page":1,"pageSize":5}' "List first 5 classes"

# Tool 3: method-list (use a common class name; will error if not found)
call_tool "method-list" '{"className":"java/lang/Object"}' "List methods of java/lang/Object"

# Tool 4: search-strings
call_tool "search-strings" '{"query":"main"}' "Search for string constants matching 'main'"

# ---------------------------------------------------------------------------
# Step 3: Summary table
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}========================================${RESET}"
echo -e "${BOLD} Summary${RESET}"
echo -e "${BOLD}========================================${RESET}"
echo ""

# Table header
printf "  ${BOLD}%-30s %-8s %10s  %-12s${RESET}\n" "Tool" "Format" "Chars" "Status"
printf "  %-30s %-8s %10s  %-12s\n" "------------------------------" "--------" "----------" "------------"

TOTAL_CHARS=0
PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

for i in "${!RESULT_NAMES[@]}"; do
    name="${RESULT_NAMES[$i]}"
    count="${RESULT_COUNTS[$i]}"
    fmt="${RESULT_FORMATS[$i]}"
    status="${RESULT_STATUSES[$i]}"

    TOTAL_CHARS=$((TOTAL_CHARS + count))

    local_color="$RESET"
    case "$status" in
        PASS)
            local_color="$GREEN"
            PASS_COUNT=$((PASS_COUNT + 1))
            ;;
        FAIL_FORMAT)
            local_color="$RED"
            FAIL_COUNT=$((FAIL_COUNT + 1))
            ;;
        WARN)
            local_color="$YELLOW"
            WARN_COUNT=$((WARN_COUNT + 1))
            ;;
        ERROR)
            local_color="$RED"
            FAIL_COUNT=$((FAIL_COUNT + 1))
            ;;
    esac

    printf "  %-30s %-8s %10s  ${local_color}%-12s${RESET}\n" "$name" "$fmt" "$count" "$status"
done

printf "  %-30s %-8s %10s  %-12s\n" "------------------------------" "--------" "----------" "------------"
printf "  ${BOLD}%-30s %-8s %10s${RESET}\n" "TOTAL" "$FORMAT" "$TOTAL_CHARS"
echo ""

# Overall result
TOTAL_TESTS=${#RESULT_NAMES[@]}
if [[ $FAIL_COUNT -eq 0 && $TOTAL_TESTS -gt 0 ]]; then
    log_pass "All ${TOTAL_TESTS} tool calls completed (${PASS_COUNT} pass, ${WARN_COUNT} warn)"
elif [[ $FAIL_COUNT -gt 0 ]]; then
    log_fail "${FAIL_COUNT}/${TOTAL_TESTS} tool calls failed format validation"
else
    log_warn "No tool calls were made"
fi
echo ""

# ---------------------------------------------------------------------------
# Step 4: CSV output (if requested)
# ---------------------------------------------------------------------------
if [[ -n "$CSV_OUTPUT" ]]; then
    {
        echo "tool_name,format,char_count,status"
        for i in "${!RESULT_NAMES[@]}"; do
            echo "${RESULT_NAMES[$i]},${RESULT_FORMATS[$i]},${RESULT_COUNTS[$i]},${RESULT_STATUSES[$i]}"
        done
    } > "$CSV_OUTPUT"
    log_info "CSV results written to: ${CSV_OUTPUT}"
    echo ""
fi

# ---------------------------------------------------------------------------
# Exit code
# ---------------------------------------------------------------------------
if [[ $FAIL_COUNT -gt 0 ]]; then
    exit 1
fi
exit 0
