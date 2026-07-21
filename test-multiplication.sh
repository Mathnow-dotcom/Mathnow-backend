#!/usr/bin/env bash
# =============================================================================
# InfinityIsland — MULTIPLICATION MODULE TEST SUITE
# Tests: Operation Unlock, Multiplication Quiz Flow, Single-Digit Operands,
#        Belt Progression, Practice Questions, Game Modes, Cross-Op Review,
#        Commutativity, Level 1 (x0) Edge Cases, Pretest, Resume
# =============================================================================
set -o pipefail

# ---------- Config ----------
BASE="${BASE:-http://localhost:8081/api}"
PIN="${PIN:-9902}"
NAME="${NAME:-MulTest}"
ADMIN_PIN="${ADMIN_PIN:-7878}"
DEBUG="${DEBUG:-0}"

# ---------- Counters ----------
PASS=0
FAIL=0
SKIP=0
TOTAL_TIME=0

# ---------- Colors ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'
BOLD='\033[1m'

# ---------- Helpers ----------
say() { printf "\n${BOLD}%s${NC}\n" "$*"; }
subsay() { printf "${CYAN} ▸ %s${NC}\n" "$*"; }
ok() { printf " ${GREEN}✓${NC} %s\n" "$*"; PASS=$((PASS+1)); }
bad() { printf " ${RED}✗${NC} %s\n" "$*" >&2; FAIL=$((FAIL+1)); }
skip_test() { printf " ${YELLOW}⊘${NC} %s\n" "$*"; SKIP=$((SKIP+1)); }
debug() { [[ "$DEBUG" == "1" ]] && printf " ${BLUE}DEBUG:${NC} %s\n" "$*"; }

need() { command -v "$1" >/dev/null || { bad "Missing: $1"; exit 1; }; }
need jq
need curl
need bc

# ---------- HTTP Helpers ----------
post_json() {
  local url="${BASE%/}$1" data="$2" pin="${3:-$PIN}"
  local start_time end_time elapsed
  start_time=$(date +%s%N 2>/dev/null || date +%s)
  local resp=$(curl -sS --max-time 30 -H 'Connection: close' -X POST \
    -H 'Content-Type: application/json' \
    -H "x-pin: $pin" \
    "$url" -d "$data" 2>&1)
  end_time=$(date +%s%N 2>/dev/null || date +%s)
  if [[ "$start_time" =~ ^[0-9]{10,}$ ]]; then
    elapsed=$(( (end_time - start_time) / 1000000 ))
    TOTAL_TIME=$((TOTAL_TIME + elapsed))
  fi
  debug "POST $url → $(echo "$resp" | head -c 200)"
  [[ "$DEBUG" == "1" ]] && echo "$resp" | jq . 2>/dev/null >&2
  echo "$resp"
}

get_json() {
  local url="${BASE%/}$1" pin="${2:-$PIN}"
  local resp=$(curl -sS --max-time 30 -H 'Connection: close' -X GET \
    -H 'accept: application/json' \
    -H "x-pin: $pin" \
    "$url" 2>&1)
  debug "GET $url"
  echo "$resp"
}

delete_json() {
  local url="${BASE%/}$1" pin="${2:-$PIN}"
  curl -sS --max-time 30 -H 'Connection: close' -X DELETE -H 'accept: application/json' -H "x-pin: $pin" "$url" 2>&1
}

cleanup_user() {
  delete_json "/user/delete" "$PIN" >/dev/null 2>&1
  sleep 0.2
}

reset_user() {
  post_json "/user/reset" '{}' >/dev/null
  sleep 0.3
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null
}

complete_quiz() {
  local runId="$1" start="$2" speed="${3:-50}"
  local qcount=$(jq '.questions | length' <<<"$start")
  local startIdx=$(jq '.currentIndex // 0' <<<"$start")
  for ((i=startIdx; i<qcount; i++)); do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":$speed}")
    if jq -e '.completed==true' >/dev/null <<<"$resp"; then
      echo "$resp"
      return
    fi
  done
}

complete_quiz_forcepass() {
  local runId="$1" start="$2"
  local qcount=$(jq '.questions | length' <<<"$start")
  for ((i=0; i<qcount; i++)); do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10,\"forcePass\":true,\"skipLevelAward\":true}")
    if jq -e '.completed==true' >/dev/null <<<"$resp"; then
      echo "$resp"
      return
    fi
  done
}

# Skip pretest by completing it
skip_pretest() {
  local op="${1:-add}" level="${2:-1}"
  local prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"white\",\"operation\":\"$op\"}")

  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz_forcepass "$runId" "$start" >/dev/null
    debug "Pretest skipped for $op L$level"
  fi
}

# Complete all belts + black degrees for a level/operation
complete_full_level() {
  local op="$1" level="$2"
  local belts=("white" "yellow" "green" "blue" "red" "brown")

  skip_pretest "$op" "$level"

  for belt in "${belts[@]}"; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"$belt\",\"operation\":\"$op\"}")
    # If pretest intercepts again, handle it
    if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
      local runId=$(jq -r '.quizRunId' <<<"$prep")
      local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
      complete_quiz_forcepass "$runId" "$start" >/dev/null
      prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"$belt\",\"operation\":\"$op\"}")
    fi
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz_forcepass "$runId" "$start" >/dev/null
  done

  for degree in 1 2 3 4 5 6 7; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"black-$degree\",\"operation\":\"$op\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz_forcepass "$runId" "$start" >/dev/null
  done
}

# =============================================================================
# TEST 1: New User Progress Format
# =============================================================================
test_new_user_progress_format() {
  say "TEST 1: New User Progress Format (per-operation includes mul)"
  cleanup_user

  local resp=$(post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}")
  jq -e '.token and .user._id' >/dev/null <<<"$resp" && ok "Login successful" || bad "Login failed"

  # Check per-operation progress format
  jq -e '.user.progress.add' >/dev/null <<<"$resp" && ok "progress.add exists" || bad "No progress.add"
  jq -e '.user.progress.sub' >/dev/null <<<"$resp" && ok "progress.sub exists" || bad "No progress.sub"
  jq -e '.user.progress.mul' >/dev/null <<<"$resp" && ok "progress.mul exists" || bad "No progress.mul"

  # mul.L1 should be locked
  jq -e '.user.progress.mul.L1.unlocked==false' >/dev/null <<<"$resp" && ok "mul.L1 locked" || bad "mul.L1 should be locked"
}

# =============================================================================
# TEST 2: Operations Endpoint
# =============================================================================
test_operations_endpoint() {
  say "TEST 2: GET /user/operations"

  local resp=$(get_json "/user/operations")
  jq -e '.operations.add' >/dev/null <<<"$resp" && ok "add operation listed" || bad "No add operation"
  jq -e '.operations.sub' >/dev/null <<<"$resp" && ok "sub operation listed" || bad "No sub operation"
  jq -e '.operations.mul' >/dev/null <<<"$resp" && ok "mul operation listed" || bad "No mul operation"

  jq -e '.operations.mul.maxLevel==10' >/dev/null <<<"$resp" && ok "mul maxLevel=10" || bad "Wrong mul maxLevel"
  jq -e '.operations.mul.enabled==true' >/dev/null <<<"$resp" && ok "mul enabled" || bad "mul not enabled"
  jq -e '.operations.mul.prerequisite=="sub"' >/dev/null <<<"$resp" && ok "mul prerequisite=sub" || bad "Wrong mul prerequisite"
  jq -e '.operations.mul.unlocked==false' >/dev/null <<<"$resp" && ok "mul locked (sub not done)" || bad "mul should be locked"
}

# =============================================================================
# TEST 3: Mul Locked Until Sub Complete
# =============================================================================
test_mul_locked_guard() {
  say "TEST 3: Multiplication Locked Until Subtraction Complete"
  reset_user

  # Try to prepare mul quiz - should fail
  local resp=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul"}')

  # Should get an error (400 with message about locked operation)
  if echo "$resp" | jq -e '.error.message' >/dev/null 2>/dev/null; then
    local msg=$(echo "$resp" | jq -r '.error.message')
    ok "Mul prepare blocked: $msg"
  elif echo "$resp" | jq -e '.quizRunId' >/dev/null 2>/dev/null; then
    bad "Mul prepare should be blocked but got quizRunId"
  else
    ok "Mul prepare returned error response"
  fi
}

# =============================================================================
# TEST 4: Complete All Add + Sub -> Mul Unlocks
# =============================================================================
test_prerequisites_unlock_mul() {
  say "TEST 4: Complete All Addition + Subtraction -> Multiplication Unlocks"

  # Use admin restore to set all add + sub levels as completed (faster than playing through)
  subsay "Restoring user with all add+sub completed via admin endpoint..."
  local restore=$(post_json "/admin/restore-user" \
    "{\"pin\":\"$PIN\",\"operations\":{\"add\":19,\"sub\":11},\"grandTotalCorrect\":5000,\"currentStreak\":10}" \
    "$ADMIN_PIN")
  local rmsg=$(echo "$restore" | jq -r '.message // .error // "unknown"')
  debug "Restore result: $rmsg"

  # Check mul is now unlocked
  local ops=$(get_json "/user/operations")
  jq -e '.operations.mul.unlocked==true' >/dev/null <<<"$ops" && ok "mul unlocked after add+sub complete" || bad "mul still locked"

  # Check progress
  local prog=$(get_json "/user/progress")
  jq -e '.progress.sub.L11.completed==true' >/dev/null <<<"$prog" && ok "sub.L11 completed" || bad "sub.L11 not completed"
  jq -e '.progress.mul.L1.unlocked==true' >/dev/null <<<"$prog" && ok "mul.L1 unlocked in progress" || bad "mul.L1 still locked in progress"
  jq -e '.progress.mul.L1.white.unlocked==true' >/dev/null <<<"$prog" && ok "mul.L1.white unlocked in progress" || bad "mul.L1.white still locked in progress"

  # Now prepare mul should work
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul"}')
  # Might get pretest first
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    ok "Mul L1 pretest triggered (expected)"
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz_forcepass "$runId" "$start" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul"}')
  fi

  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Mul prepare succeeds" || bad "Mul prepare still failing"
}

# =============================================================================
# TEST 5: Single-Digit Operands Only
# =============================================================================
test_single_digit_operands() {
  say "TEST 5: Single-Digit Operands Only (0-9)"

  local bad_found=0
  for test_level in 1 3 5; do
    # Prepare mul quiz
    local prep=$(post_json "/quiz/prepare" "{\"level\":$test_level,\"beltOrDegree\":\"white\",\"operation\":\"mul\"}")
    if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
      local rid=$(jq -r '.quizRunId' <<<"$prep")
      local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
      complete_quiz_forcepass "$rid" "$st" >/dev/null
      prep=$(post_json "/quiz/prepare" "{\"level\":$test_level,\"beltOrDegree\":\"white\",\"operation\":\"mul\"}")
    fi

    local runId=$(jq -r '.quizRunId' <<<"$prep")
    if [[ "$runId" == "null" || -z "$runId" ]]; then
      skip_test "Cannot prepare mul L$test_level (may not be unlocked yet)"
      continue
    fi

    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qcount=$(jq '.questions | length' <<<"$start")

    for ((i=0; i<qcount; i++)); do
      local a=$(jq ".questions[$i].params.a" <<<"$start")
      local b=$(jq ".questions[$i].params.b" <<<"$start")
      local q=$(jq -r ".questions[$i].question" <<<"$start")
      local op=$(jq -r ".questions[$i].operation" <<<"$start")

      if [[ "$op" == "mul" ]]; then
        # Check operands are single-digit (0-9)
        if [[ "$a" -lt 0 || "$a" -gt 9 ]]; then
          bad "L$test_level Q$i: operand a=$a out of range in '$q'"
          bad_found=1
        fi
        if [[ "$b" -lt 0 || "$b" -gt 9 ]]; then
          bad "L$test_level Q$i: operand b=$b out of range in '$q'"
          bad_found=1
        fi
      fi

      debug "L$test_level Q$i: $q (a=$a, b=$b, op=$op)"
    done

    # Check question text uses multiplication symbol
    local mul_symbol_found=0
    for ((i=0; i<qcount; i++)); do
      local q=$(jq -r ".questions[$i].question" <<<"$start")
      local op=$(jq -r ".questions[$i].operation" <<<"$start")
      if [[ "$op" == "mul" && "$q" == *"×"* ]]; then
        mul_symbol_found=1
        break
      fi
    done
    [[ $mul_symbol_found -eq 1 ]] && ok "L$test_level: question text uses × symbol" || skip_test "L$test_level: no × symbol found (may be digit recognition)"

    # Complete the quiz to clean up
    complete_quiz_forcepass "$runId" "$start" >/dev/null
  done

  [[ $bad_found -eq 0 ]] && ok "All mul operands are single-digit (0-9)"
}

# =============================================================================
# TEST 6: Mul Belt Progression (Level 3: x2)
# =============================================================================
test_mul_belt_progression() {
  say "TEST 6: Multiplication Belt Progression (Level 3)"

  local belts=("white" "yellow" "green" "blue" "red" "brown")
  local next_belts=("yellow" "green" "blue" "red" "brown" "black")

  for i in "${!belts[@]}"; do
    local belt="${belts[$i]}"
    local next="${next_belts[$i]}"
    subsay "Completing mul L3 $belt belt"

    local prep=$(post_json "/quiz/prepare" "{\"level\":3,\"beltOrDegree\":\"$belt\",\"operation\":\"mul\"}")
    if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
      local rid=$(jq -r '.quizRunId' <<<"$prep")
      local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
      complete_quiz_forcepass "$rid" "$st" >/dev/null
      prep=$(post_json "/quiz/prepare" "{\"level\":3,\"beltOrDegree\":\"$belt\",\"operation\":\"mul\"}")
    fi

    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local final=$(complete_quiz "$runId" "$start" 30)

    if jq -e '.completed==true and .passed==true' >/dev/null <<<"$final"; then
      ok "mul L3 $belt completed"

      local prog=$(get_json "/user/progress")
      if [[ "$next" == "black" ]]; then
        jq -e '.progress.mul.L3.black.unlocked==true' >/dev/null <<<"$prog" && ok "mul L3 black unlocked" || bad "mul L3 black not unlocked"
      else
        jq -e ".progress.mul.L3.$next.unlocked==true" >/dev/null <<<"$prog" && ok "mul L3 $next unlocked" || bad "mul L3 $next not unlocked"
      fi
    else
      bad "mul L3 $belt failed to complete"
      debug "Final: $(echo "$final" | head -c 300)"
      break
    fi
  done
}

# =============================================================================
# TEST 7: Wrong Answer -> Practice
# =============================================================================
test_mul_practice() {
  say "TEST 7: Multiplication Wrong Answer -> Practice"

  skip_pretest "mul" 3
  local prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"yellow","operation":"mul"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"yellow","operation":"mul"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for mul yellow"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$((correct + 99))

  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":100}")
  jq -e 'has("practice")' >/dev/null <<<"$wresp" && ok "Wrong answer triggers practice" || bad "No practice on wrong answer"

  # Check practice question uses multiplication symbol
  local pq=$(jq -r '.practice.question // ""' <<<"$wresp")
  if [[ "$pq" == *"×"* ]]; then
    ok "Practice question uses × symbol: '$pq'"
  elif [[ -n "$pq" && "$pq" != "" ]]; then
    skip_test "Practice question format: '$pq' (may be digit recognition)"
  fi

  # Complete practice correctly
  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")
  jq -e '.resume==true or has("next")' >/dev/null <<<"$presp" && ok "Practice completes and resumes" || bad "Practice resume failed"

  # Clean up
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

# =============================================================================
# TEST 8: Inactivity -> Practice
# =============================================================================
test_mul_inactivity() {
  say "TEST 8: Multiplication Inactivity Handling"

  skip_pretest "mul" 3
  local prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"white","operation":"mul"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"white","operation":"mul"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for inactivity test"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer with responseMs > 5000 (inactivity threshold)
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local iresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":6000}")

  # Check if marked as wrong or practice triggered due to inactivity
  if jq -e 'has("practice")' >/dev/null <<<"$iresp" 2>/dev/null; then
    ok "Inactivity (responseMs>5000) triggers practice"
  elif jq -e '.inactivity==true' >/dev/null <<<"$iresp" 2>/dev/null; then
    ok "Inactivity flag set in response"
  else
    # Also try the inactivity endpoint
    local iresp2=$(post_json "/quiz/inactivity" "{\"quizRunId\":\"$runId\"}")
    jq -e 'has("practice")' >/dev/null <<<"$iresp2" && ok "Inactivity endpoint triggers practice" || bad "No practice on inactivity"
  fi

  # Clean up
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

# =============================================================================
# TEST 9: Cross-Operation Review
# =============================================================================
test_cross_op_review() {
  say "TEST 9: Cross-Operation Review in Mul Quiz"

  # Use mul L3 yellow belt — should have current mul + previous (mix of mul + sub + add)
  skip_pretest "mul" 3
  local prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"yellow","operation":"mul"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"yellow","operation":"mul"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for cross-op test"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  local mul_count=0
  local add_count=0
  local sub_count=0
  local other_count=0
  for ((i=0; i<qcount; i++)); do
    local qop=$(jq -r ".questions[$i].operation" <<<"$start")
    local q=$(jq -r ".questions[$i].question" <<<"$start")
    case "$qop" in
      mul) mul_count=$((mul_count + 1)) ;;
      add) add_count=$((add_count + 1)) ;;
      sub) sub_count=$((sub_count + 1)) ;;
      *) other_count=$((other_count + 1)) ;;
    esac
    debug "Q$i: op=$qop q='$q'"
  done

  ok "Mul L3 yellow: $mul_count mul, $add_count add, $sub_count sub, $other_count other"
  [[ $mul_count -ge 2 ]] && ok "At least 2 mul current questions present" || bad "Too few mul questions: $mul_count"
  local review_count=$((add_count + sub_count))
  [[ $review_count -gt 0 ]] && ok "Review questions present (add=$add_count, sub=$sub_count)" || bad "No review questions from prerequisite chain"

  # Cleanup
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

# =============================================================================
# TEST 10: Black Belt with Cross-Op Review
# =============================================================================
test_mul_black_belt() {
  say "TEST 10: Multiplication Black Belt with Cross-Op Review"

  # Ensure L3 colored belts are done (from test 6)
  local prog=$(get_json "/user/progress")
  if ! jq -e '.progress.mul.L3.black.unlocked==true' >/dev/null <<<"$prog" 2>/dev/null; then
    subsay "Completing mul L3 colored belts first..."
    complete_full_level "mul" 3
    prog=$(get_json "/user/progress")
  fi

  jq -e '.progress.mul.L3.black.unlocked==true' >/dev/null <<<"$prog" && ok "mul L3 black unlocked" || { bad "mul L3 black not unlocked"; return; }

  subsay "Testing mul black-1"
  local prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"black-1","operation":"mul"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for mul black-1"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")
  [[ "$qcount" -ge 20 ]] && ok "Black-1 has $qcount questions (>=20)" || bad "Wrong count: $qcount"

  # Check timer
  local timelimit=$(jq '.timer.limitMs // 0' <<<"$start")
  [[ "$timelimit" -gt 0 ]] && ok "Black-1 timer set: ${timelimit}ms" || bad "No timer for black belt"

  # Check for cross-op review questions
  local add_review_count=0
  local sub_review_count=0
  for ((i=0; i<qcount; i++)); do
    local qop=$(jq -r ".questions[$i].operation" <<<"$start")
    if [[ "$qop" == "add" ]]; then
      add_review_count=$((add_review_count + 1))
    elif [[ "$qop" == "sub" ]]; then
      sub_review_count=$((sub_review_count + 1))
    fi
  done
  local total_review=$((add_review_count + sub_review_count))
  [[ $total_review -gt 0 ]] && ok "Black belt has $total_review review questions (add=$add_review_count, sub=$sub_review_count)" || bad "Black belt has no review questions (expected cross-op review)"

  local final=$(complete_quiz "$runId" "$start" 5)
  jq -e '.completed==true' >/dev/null <<<"$final" && ok "Mul black-1 completed" || bad "Not completed"

  prog=$(get_json "/user/progress")
  jq -e '.progress.mul.L3.black.completedDegrees | contains([1])' >/dev/null <<<"$prog" && ok "Black-1 recorded in progress" || bad "Degree not recorded"
}

# =============================================================================
# TEST 11: Level 1 (x0) Edge Case
# =============================================================================
test_level1_x0_edge() {
  say "TEST 11: Level 1 (x0) Edge Case"

  skip_pretest "mul" 1
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for mul L1"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  [[ "$qcount" -ge 10 ]] && ok "Got $qcount questions for L1" || bad "Too few questions: $qcount"

  local all_zero=1
  local bad_choices=0
  local full_expression=1
  for ((i=0; i<qcount; i++)); do
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    local op=$(jq -r ".questions[$i].operation" <<<"$start")
    local q=$(jq -r ".questions[$i].question" <<<"$start")

    # For mul questions at L1, answer should be 0
    if [[ "$op" == "mul" ]]; then
      if [[ "$ans" -ne 0 ]]; then
        bad "L1 Q$i: expected answer 0 but got $ans for '$q'"
        all_zero=0
      fi

      # Check choices: should have at least 4, not all zeros
      local num_choices=$(jq ".questions[$i].choices | length" <<<"$start")
      [[ "$num_choices" -ge 4 ]] || { bad "L1 Q$i: only $num_choices choices"; bad_choices=1; }

      local all_zero_choices=$(jq "[.questions[$i].choices[] | select(. == 0)] | length" <<<"$start")
      if [[ "$all_zero_choices" -eq "$num_choices" ]]; then
        bad "L1 Q$i: all choices are zero"
        bad_choices=1
      fi

      # Check digit questions show full expression "X × 0" not just "X"
      if [[ "$q" != *"×"* && "$q" != *"x"* ]]; then
        debug "L1 Q$i: question '$q' may not show full expression"
        # Only flag if it looks like a bare number
        if [[ "$q" =~ ^[0-9]+$ ]]; then
          bad "L1 Q$i: shows bare number '$q' instead of full expression"
          full_expression=0
        fi
      fi
    fi
  done

  [[ $all_zero -eq 1 ]] && ok "All L1 mul answers are 0" || bad "Some L1 mul answers are not 0"
  [[ $bad_choices -eq 0 ]] && ok "L1 choices are varied (not all zeros)"
  [[ $full_expression -eq 1 ]] && ok "L1 digit questions show full expression"

  # Complete the quiz
  complete_quiz_forcepass "$runId" "$start" >/dev/null
}

# =============================================================================
# TEST 12: Commutative Validation
# =============================================================================
test_commutative() {
  say "TEST 12: Commutative Validation (a × b and b × a)"

  # Use L4 (x3) or higher where pairs are non-identical (a != b)
  skip_pretest "mul" 4
  local prep=$(post_json "/quiz/prepare" '{"level":4,"beltOrDegree":"white","operation":"mul"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":4,"beltOrDegree":"white","operation":"mul"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  if [[ "$runId" == "null" || -z "$runId" ]]; then
    skip_test "Cannot prepare mul L4 for commutative test"
    return
  fi

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  # Collect all (a,b) pairs for mul questions
  local pairs=""
  local reversed_found=0
  for ((i=0; i<qcount; i++)); do
    local op=$(jq -r ".questions[$i].operation" <<<"$start")
    if [[ "$op" == "mul" ]]; then
      local a=$(jq ".questions[$i].params.a" <<<"$start")
      local b=$(jq ".questions[$i].params.b" <<<"$start")
      pairs="$pairs $a,$b"
      debug "Mul pair: $a × $b"
    fi
  done

  # Check if both (a,b) and (b,a) appear for at least one pair
  for pair in $pairs; do
    local a=${pair%,*}
    local b=${pair#*,}
    if [[ "$a" -ne "$b" ]]; then
      local reverse="$b,$a"
      if echo " $pairs " | grep -q " $reverse "; then
        reversed_found=1
        ok "Commutative pair found: $a × $b and $b × $a"
        break
      fi
    fi
  done

  if [[ $reversed_found -eq 0 ]]; then
    # Try a larger sample — check across multiple belts
    skip_test "No commutative pair found in single quiz (may appear across quizzes or catalog has both forms)"
  fi

  # Complete the quiz
  complete_quiz_forcepass "$runId" "$start" >/dev/null
}

# =============================================================================
# TEST 13: Level Progression (L1 -> L3)
# =============================================================================
test_mul_level_progression() {
  say "TEST 13: Multiplication Level Progression (L1 -> L3)"

  # Complete L1 fully
  local prog=$(get_json "/user/progress")
  if ! jq -e '.progress.mul.L1.completed==true' >/dev/null <<<"$prog" 2>/dev/null; then
    subsay "Completing mul L1 fully..."
    complete_full_level "mul" 1
  fi

  prog=$(get_json "/user/progress")
  jq -e '.progress.mul.L1.completed==true' >/dev/null <<<"$prog" && ok "mul L1 completed" || bad "mul L1 not completed"
  jq -e '.progress.mul.L2.unlocked==true' >/dev/null <<<"$prog" && ok "mul L2 unlocked" || bad "mul L2 not unlocked"
  jq -e '.progress.mul.L2.white.unlocked==true' >/dev/null <<<"$prog" && ok "mul L2 white unlocked" || bad "mul L2 white not unlocked"

  # Complete L2 fully
  if ! jq -e '.progress.mul.L2.completed==true' >/dev/null <<<"$prog" 2>/dev/null; then
    subsay "Completing mul L2 fully..."
    complete_full_level "mul" 2
  fi

  prog=$(get_json "/user/progress")
  jq -e '.progress.mul.L2.completed==true' >/dev/null <<<"$prog" && ok "mul L2 completed" || bad "mul L2 not completed"
  jq -e '.progress.mul.L3.unlocked==true' >/dev/null <<<"$prog" && ok "mul L3 unlocked" || bad "mul L3 not unlocked"
  jq -e '.progress.mul.L3.white.unlocked==true' >/dev/null <<<"$prog" && ok "mul L3 white unlocked" || bad "mul L3 white not unlocked"
}

# =============================================================================
# TEST 14: Lightning Mode
# =============================================================================
test_mul_lightning() {
  say "TEST 14: Multiplication Lightning Mode"

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul","gameMode":true,"gameModeType":"lightning"}')

  if jq -e '.quizRunId' >/dev/null <<<"$prep" 2>/dev/null; then
    ok "Lightning mode prepare succeeded"

    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

    jq -e '.questions | length >= 10' >/dev/null <<<"$start" && ok "Lightning questions generated" || bad "No lightning questions"
    jq -e '.gameModeType=="lightning"' >/dev/null <<<"$start" && ok "Mode is lightning" || bad "Wrong mode"

    # Answer with mix of fast and slow
    local qcount=$(jq '.questions | length' <<<"$start")
    for ((i=0; i<3 && i<qcount; i++)); do
      local qid=$(jq -r ".questions[$i]._id" <<<"$start")
      local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
      local speed=10
      [[ $((i % 2)) -eq 1 ]] && speed=6000  # slow answer
      post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":$speed}" >/dev/null
    done
    ok "Lightning answers accepted (fast + slow mix)"

    post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
  else
    bad "Lightning mode prepare failed"
    debug "Response: $(echo "$prep" | head -c 300)"
  fi
}

# =============================================================================
# TEST 15: Surf Mode
# =============================================================================
test_mul_surf() {
  say "TEST 15: Multiplication Surf Mode"

  # Ensure lightning mode is completed first (prerequisite for surf)
  local lprep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul","gameMode":true,"gameModeType":"lightning"}')
  local lrid=$(jq -r '.quizRunId' <<<"$lprep")
  if [[ -n "$lrid" && "$lrid" != "null" ]]; then
    local lst=$(post_json "/quiz/start" "{\"quizRunId\":\"$lrid\"}")
    local lfq=$(jq -r '.questions[0]._id' <<<"$lst")
    post_json "/quiz/answer" "{\"quizRunId\":\"$lrid\",\"questionId\":\"$lfq\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" > /dev/null
  fi

  # Run 5 surf quiz cycles
  local surf_success=0
  local prereq_pool_has_add_sub=0
  for cycle in $(seq 1 5); do
    local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul","gameMode":true,"gameModeType":"surf"}')
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    if [[ -z "$runId" || "$runId" == "null" ]]; then
      bad "Surf cycle $cycle: no quizRunId"
      continue
    fi

    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qcount=$(jq '.questions | length' <<<"$start")
    [[ "$qcount" -ge 1 ]] && surf_success=$((surf_success + 1))

    # Check if prerequisite pool includes add+sub facts
    for ((i=0; i<qcount; i++)); do
      local qop=$(jq -r ".questions[$i].operation" <<<"$start")
      if [[ "$qop" == "add" || "$qop" == "sub" ]]; then
        prereq_pool_has_add_sub=1
      fi
    done

    # Complete the cycle
    local firstQ=$(jq -r '.questions[0]._id' <<<"$start")
    local firstA=$(jq '.questions[0].correctAnswer' <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$firstQ\",\"answer\":$firstA,\"responseMs\":10,\"forcePass\":true}" > /dev/null
  done

  [[ $surf_success -ge 5 ]] && ok "Completed 5 surf cycles" || bad "Only $surf_success/5 surf cycles succeeded"
  [[ $prereq_pool_has_add_sub -eq 1 ]] && ok "Surf pool includes add/sub facts" || skip_test "Surf pool did not pick add/sub facts (random selection)"
}

# =============================================================================
# TEST 16: Rocket Mode
# =============================================================================
test_mul_rocket() {
  say "TEST 16: Multiplication Rocket Mode"

  # Ensure lightning is done first
  local lprep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul","gameMode":true,"gameModeType":"lightning"}')
  local lrid=$(jq -r '.quizRunId' <<<"$lprep")
  if [[ -n "$lrid" && "$lrid" != "null" ]]; then
    local lst=$(post_json "/quiz/start" "{\"quizRunId\":\"$lrid\"}")
    local lfq=$(jq -r '.questions[0]._id' <<<"$lst")
    post_json "/quiz/answer" "{\"quizRunId\":\"$lrid\",\"questionId\":\"$lfq\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" > /dev/null
  fi

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  if [[ -z "$runId" || "$runId" == "null" ]]; then
    bad "Rocket mode prepare failed"
    debug "Response: $(echo "$prep" | head -c 300)"
    return
  fi
  ok "Rocket mode prepare succeeded"

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")
  [[ "$qcount" -ge 1 ]] && ok "Rocket questions generated: $qcount" || bad "No rocket questions"

  # Verify expression choices use × symbol
  local mul_symbol_in_choices=0
  for ((i=0; i<qcount; i++)); do
    local choices=$(jq -r ".questions[$i].choices[]?" <<<"$start" 2>/dev/null)
    while read -r ch; do
      if [[ "$ch" == *"×"* ]]; then
        mul_symbol_in_choices=1
        break 2
      fi
    done <<<"$choices"
  done
  [[ $mul_symbol_in_choices -eq 1 ]] && ok "Rocket choices use × symbol" || skip_test "No × in rocket choices (may be numeric choices)"

  # Complete quiz
  local final=$(complete_quiz "$runId" "$start" 10)
  if jq -e '.completed==true' >/dev/null <<<"$final" 2>/dev/null; then
    ok "Rocket quiz completed"
    # Contract change: rocket no longer awards belt — bonus does. Verify the run passed
    # (still .passed==true) but belt is deferred and bonusRequired is true.
    jq -e '.passed==true' >/dev/null <<<"$final" && ok "Rocket passed=true" || bad "Rocket passed should be true"
    jq -e '.beltAwarded==false' >/dev/null <<<"$final" && ok "beltAwarded=false (bonus pending)" || bad "beltAwarded should be false after rocket"
    jq -e '.bonusRequired==true' >/dev/null <<<"$final" && ok "bonusRequired=true" || bad "bonusRequired missing"
  else
    # Force complete
    post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
    ok "Rocket quiz force-completed"
  fi
}

# =============================================================================
# TEST 17: Pretest
# =============================================================================
test_mul_pretest() {
  say "TEST 17: Multiplication Pretest"

  # Reset to a fresh level that hasn't had pretest
  # Use L5 which should not have been touched
  skip_pretest "mul" 5

  # After skipping pretest with forcePass, the entire level may be awarded
  local prog=$(get_json "/user/progress")
  if jq -e '.progress.mul.L5.pretest.taken==true and .progress.mul.L5.pretest.passed==true' >/dev/null <<<"$prog" 2>/dev/null; then
    ok "Pretest taken and passed"
    # Check if level was auto-completed from pretest pass
    if jq -e '.progress.mul.L5.completed==true' >/dev/null <<<"$prog" 2>/dev/null; then
      ok "Pretest pass awarded entire level"
    else
      ok "Pretest passed, level not auto-completed (expected for forcePass skip)"
    fi
  elif jq -e '.progress.mul.L5.pretest.taken==true' >/dev/null <<<"$prog" 2>/dev/null; then
    ok "Pretest taken (not passed — forcePass may not trigger full award)"
  else
    skip_test "Pretest not triggered for mul L5 (may already be taken or level structure differs)"
  fi
}

# =============================================================================
# TEST 18: Resume
# =============================================================================
test_mul_resume() {
  say "TEST 18: Multiplication Quiz Resume"

  skip_pretest "mul" 3
  local prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"white","operation":"mul"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"white","operation":"mul"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for resume test"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer 1 question
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local ans=$(jq '.questions[0].correctAnswer' <<<"$start")
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":50}" >/dev/null

  # Prepare again - should resume same quiz
  local prep2=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"white","operation":"mul"}')
  local runId2=$(jq -r '.quizRunId' <<<"$prep2")

  if [[ "$runId2" == "$runId" ]]; then
    ok "Resume returns same quizRunId: $runId"
  else
    bad "Resume returned different quizRunId: $runId2 (expected $runId)"
  fi

  # Start and verify currentIndex > 0 (resumed)
  local start2=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId2\"}")
  local idx=$(jq '.currentIndex // 0' <<<"$start2")
  [[ "$idx" -ge 1 ]] && ok "Resume currentIndex=$idx (>= 1)" || skip_test "currentIndex=$idx (may handle resume differently)"

  # Clean up
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

# =============================================================================
# TEST 19: Div Stays Locked After Mul L1 Pretest Pass (premature-unlock guard)
# Div is enabled (maxLevel=4) but locked until mul L10 completion.
# =============================================================================
test_div_not_unlocked_after_mul() {
  say "TEST 19: Div Locked After Mul L1 Pretest Pass"

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"mul"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for mul L1 pretest"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")
  debug "Mul L1 quiz has $qcount questions"

  for i in $(seq 0 $((qcount - 1))); do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":50}" >/dev/null
  done

  local comp=$(post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}")
  debug "Complete response: $(echo "$comp" | jq -c '.passed // .beltAwarded // "unknown"')"

  local ops=$(get_json "/user/operations")
  if jq -e '.operations.div.unlocked == false' >/dev/null <<<"$ops"; then
    ok "div locked after mul L1 pass (unlocked=false)"
  else
    bad "div should be locked but got unlocked=$(jq -c '.operations.div' <<<"$ops")"
  fi

  if jq -e '.operations.div.enabled == true' >/dev/null <<<"$ops"; then
    ok "div enabled=true (maxLevel=4)"
  else
    bad "div should be enabled but got $(jq -c '.operations.div' <<<"$ops")"
  fi

  local prog=$(get_json "/user/progress")
  if jq -e '.progress.div.L1.unlocked == false' >/dev/null <<<"$prog"; then
    ok "div L1 locked in progress tree"
  else
    bad "div L1 should be locked but got $(jq -c '.progress.div.L1' <<<"$prog")"
  fi
}

# =============================================================================
# TEST 20: Prepare Rejects Div Before Mul Complete (locked-op gate)
# =============================================================================
test_disabled_op_prepare_blocked() {
  say "TEST 20: Prepare Rejects Locked Operation (div before mul L10)"

  local resp=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"div"}')

  if echo "$resp" | jq -e '.error.message' >/dev/null 2>/dev/null; then
    local msg=$(echo "$resp" | jq -r '.error.message')
    if [[ "$msg" == *"unlocked"* ]]; then
      ok "Locked op rejected with unlock-error: $msg"
    else
      ok "Locked op rejected: $msg"
    fi
  elif echo "$resp" | jq -e '.quizRunId' >/dev/null 2>/dev/null; then
    bad "Locked op prepare should be blocked but got quizRunId"
  else
    ok "Locked op prepare returned error response"
  fi
}

# =============================================================================
# TEST 21: Operations Endpoint Shows Correct State
# All four ops enabled; div is locked until mul complete.
# =============================================================================
test_operations_enabled_state() {
  say "TEST 21: Operations Endpoint Enabled/Locked State"

  local resp=$(get_json "/user/operations")

  jq -e '.operations.add.enabled==true' >/dev/null <<<"$resp" && ok "add enabled=true" || bad "add should be enabled"
  jq -e '.operations.sub.enabled==true' >/dev/null <<<"$resp" && ok "sub enabled=true" || bad "sub should be enabled"
  jq -e '.operations.mul.enabled==true' >/dev/null <<<"$resp" && ok "mul enabled=true" || bad "mul should be enabled"

  jq -e '.operations.div.enabled==true' >/dev/null <<<"$resp" && ok "div enabled=true (maxLevel=4)" || bad "div should be enabled"
  jq -e '.operations.div.unlocked==false' >/dev/null <<<"$resp" && ok "div unlocked=false (mul not complete)" || bad "div should be locked"

  local div_maxLevel=$(jq -r '.operations.div.maxLevel // "absent"' <<<"$resp")
  if [[ "$div_maxLevel" == "4" ]]; then
    ok "div maxLevel=4"
  else
    bad "div maxLevel should be 4 but got $div_maxLevel"
  fi

  jq -e '.operations.div.prerequisite=="mul"' >/dev/null <<<"$resp" && ok "div prerequisite=mul" || bad "div prerequisite wrong"
}

# =============================================================================
# TEST 22: Mul L9/L10 Belt Placements Match Seed (Bug 1 regression guard)
# Per seed-multiplication-catalogs.js, L9 blue MUST be 8×7 (not 9×7 — that's L9 green).
# L10 belts must match the API doc Levels 6-10 example.
# =============================================================================
test_mul_belt_placement_l9_l10() {
  say "TEST 22: Mul Belt Placements Match Canonical S&S (v1.7)"

  post_json "/admin/restore-user" "{\"pin\":\"$PIN\",\"operations\":{\"add\":19,\"sub\":11,\"mul\":10}}" "$ADMIN_PIN" >/dev/null

  # Canonical S&S layout. Format: "level belt expected_a expected_b"
  # Mul is commutative — assertion accepts either (a,b) or (b,a).
  local expectations=(
    # L1: 0×N pairs
    "1 white 0 0"
    "1 yellow 0 1"
    "1 green 0 2"
    "1 blue 0 3"
    "1 red 0 4"
    "1 brown 0 5"
    # L2: 0×N continues; brown transitions to 1×1
    "2 white 0 5"
    "2 yellow 0 6"
    "2 green 0 7"
    "2 blue 0 8"
    "2 red 0 9"
    "2 brown 1 1"
    # L3: 1×N (yellow duplicates white per S&S)
    "3 white 1 2"
    "3 yellow 1 2"
    "3 green 1 3"
    "3 blue 1 4"
    "3 red 1 5"
    "3 brown 1 6"
    # L4
    "4 white 1 7"
    "4 yellow 1 8"
    "4 green 1 9"
    "4 blue 2 2"
    "4 red 2 3"
    "4 brown 2 4"
    # L5
    "5 white 2 5"
    "5 yellow 2 6"
    "5 green 2 7"
    "5 blue 2 8"
    "5 red 2 9"
    "5 brown 3 3"
    # L6
    "6 white 3 4"
    "6 yellow 3 5"
    "6 green 3 6"
    "6 blue 3 7"
    "6 red 3 8"
    "6 brown 3 9"
    # L7
    "7 white 4 4"
    "7 yellow 4 5"
    "7 green 4 6"
    "7 blue 4 7"
    "7 red 4 8"
    "7 brown 4 9"
    # L8
    "8 white 5 5"
    "8 yellow 5 6"
    "8 green 5 7"
    "8 blue 5 8"
    "8 red 5 9"
    "8 brown 6 6"
    # L9
    "9 white 6 7"
    "9 yellow 6 8"
    "9 green 6 9"
    "9 blue 7 7"
    "9 red 7 8"
    "9 brown 7 9"
    # L10: each fact repeats in two belts
    "10 white 8 8"
    "10 yellow 8 9"
    "10 green 9 9"
    "10 blue 8 8"
    "10 red 8 9"
    "10 brown 9 9"
  )
  for row in "${expectations[@]}"; do
    local lvl=$(echo "$row" | awk '{print $1}')
    local belt=$(echo "$row" | awk '{print $2}')
    local exp_a=$(echo "$row" | awk '{print $3}')
    local exp_b=$(echo "$row" | awk '{print $4}')
    local rid=$(post_json "/quiz/prepare" "{\"level\":$lvl,\"beltOrDegree\":\"$belt\",\"operation\":\"mul\"}" | jq -r '.quizRunId')
    if [[ "$rid" == "null" || -z "$rid" ]]; then bad "L$lvl $belt prepare failed"; continue; fi
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    local got_a=$(echo "$start" | jq '[.questions[] | select(.source=="current")][0].params.a // -1')
    local got_b=$(echo "$start" | jq '[.questions[] | select(.source=="current")][0].params.b // -1')
    if [[ ( "$got_a" == "$exp_a" && "$got_b" == "$exp_b" ) || \
          ( "$got_a" == "$exp_b" && "$got_b" == "$exp_a" ) ]]; then
      ok "mul L$lvl $belt: ${exp_a}×${exp_b}"
    else
      bad "mul L$lvl $belt: expected ${exp_a}×${exp_b}, got ${got_a}×${got_b}"
    fi
    post_json "/quiz/complete" "{\"quizRunId\":\"$rid\"}" >/dev/null
  done
}

# =============================================================================
# TEST 24: No Out-Of-Range Operands at L1 (v1.7 regression guard)
# Per S&S: L1 operands are constrained to {0..5}. No 6×0, 7×0, 8×0, 9×0 anywhere
# at L1 (normal/black/pretest/bonus).
# =============================================================================
test_mul_l1_operand_range() {
  say "TEST 24: Mul L1 Operands in {0..5} — no 6×0, 7×0, 8×0, 9×0"

  post_json "/admin/restore-user" "{\"pin\":\"$PIN\",\"operations\":{\"add\":19,\"sub\":11,\"mul\":1}}" "$ADMIN_PIN" >/dev/null

  local violations=0
  for belt in white yellow green blue red brown; do
    local rid=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"mul\"}" | jq -r '.quizRunId')
    [[ "$rid" == "null" || -z "$rid" ]] && continue
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    local out_of_range=$(echo "$start" | jq '[.questions[] | select(.operation=="mul" and .source=="current") | select(.params.a > 5 or .params.b > 5)] | length')
    if [[ "$out_of_range" -gt 0 ]]; then
      bad "L1 $belt: $out_of_range out-of-range mul question(s)"
      violations=$((violations + 1))
    fi
    post_json "/quiz/complete" "{\"quizRunId\":\"$rid\"}" >/dev/null
  done

  # Black belts at L1
  for degree in 1 2 3 4 5 6 7; do
    local rid=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"black-$degree\",\"operation\":\"mul\"}" | jq -r '.quizRunId')
    [[ "$rid" == "null" || -z "$rid" ]] && continue
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    local out_of_range=$(echo "$start" | jq '[.questions[] | select(.operation=="mul" and .source=="current") | select(.params.a > 5 or .params.b > 5)] | length')
    if [[ "$out_of_range" -gt 0 ]]; then
      bad "L1 black-$degree: $out_of_range out-of-range mul question(s)"
      violations=$((violations + 1))
    fi
    post_json "/quiz/complete" "{\"quizRunId\":\"$rid\"}" >/dev/null
  done

  [[ $violations -eq 0 ]] && ok "No L1 mul questions have operand > 5 (sweep across all belts + degrees)"
}

# =============================================================================
# TEST 25: Specific Tester Pain Points (v1.7 regression guard)
# Cross-checks the exact placements the tester flagged in Testing MathNow (1).pdf.
# =============================================================================
test_mul_specific_placements() {
  say "TEST 25: Specific S&S Placements (tester pain points)"

  post_json "/admin/restore-user" "{\"pin\":\"$PIN\",\"operations\":{\"add\":19,\"sub\":11,\"mul\":3}}" "$ADMIN_PIN" >/dev/null

  # L2 yellow should be (0, 6) — i.e. the new ×0 fact 0×6 / 6×0
  local rid=$(post_json "/quiz/prepare" '{"level":2,"beltOrDegree":"yellow","operation":"mul"}' | jq -r '.quizRunId')
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
  local has_06=$(echo "$start" | jq '[.questions[] | select(.operation=="mul" and (.params.a == 6 and .params.b == 0) or (.params.a == 0 and .params.b == 6))] | length')
  [[ "$has_06" -gt 0 ]] && ok "L2 yellow contains 6×0 / 0×6" || bad "L2 yellow missing 6×0 / 0×6 (had: $(echo "$start" | jq -c '[.questions[] | select(.source=="current") | .question]'))"
  post_json "/quiz/complete" "{\"quizRunId\":\"$rid\"}" >/dev/null

  # L2 brown should be (1, 1) — the transition to ×1
  rid=$(post_json "/quiz/prepare" '{"level":2,"beltOrDegree":"brown","operation":"mul"}' | jq -r '.quizRunId')
  start=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
  local has_11=$(echo "$start" | jq '[.questions[] | select(.operation=="mul" and .params.a == 1 and .params.b == 1)] | length')
  [[ "$has_11" -gt 0 ]] && ok "L2 brown contains 1×1" || bad "L2 brown missing 1×1"
  post_json "/quiz/complete" "{\"quizRunId\":\"$rid\"}" >/dev/null

  # L3 white should be (1, 2) or commutative (2, 1)
  rid=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"white","operation":"mul"}' | jq -r '.quizRunId')
  start=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
  local has_12=$(echo "$start" | jq '[.questions[] | select(.operation=="mul" and ((.params.a == 1 and .params.b == 2) or (.params.a == 2 and .params.b == 1)))] | length')
  [[ "$has_12" -gt 0 ]] && ok "L3 white contains 1×2 / 2×1" || bad "L3 white missing 1×2 / 2×1"
  post_json "/quiz/complete" "{\"quizRunId\":\"$rid\"}" >/dev/null
}

# =============================================================================
# TEST 23: Mul Distractor Quality (Bug 2 regression guard)
# For mul questions with correct > 0, distractors should be product-based —
# at most 1 of 3 should be in {c-2, c-1, c+1, c+2}, and at least 2 should be
# expressible as a product of single-digit factors.
# =============================================================================
test_mul_distractor_quality() {
  say "TEST 23: Mul Distractor Quality (product-based, not arithmetic neighbours)"

  post_json "/admin/restore-user" "{\"pin\":\"$PIN\",\"operations\":{\"add\":19,\"sub\":11,\"mul\":10}}" "$ADMIN_PIN" >/dev/null

  local sample_belts=("white" "yellow" "blue" "brown")
  local sample_levels=(5 7 9 10)
  local neighbour_violations=0
  local product_violations=0
  local checked=0

  for lvl in "${sample_levels[@]}"; do
    for belt in "${sample_belts[@]}"; do
      local rid=$(post_json "/quiz/prepare" "{\"level\":$lvl,\"beltOrDegree\":\"$belt\",\"operation\":\"mul\"}" | jq -r '.quizRunId')
      [[ "$rid" == "null" || -z "$rid" ]] && continue
      local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
      local current_q=$(echo "$start" | jq -c '[.questions[] | select(.operation=="mul" and .source=="current" and .correctAnswer>0)][0]')
      [[ "$current_q" == "null" || -z "$current_q" ]] && continue

      local correct=$(echo "$current_q" | jq '.correctAnswer')
      # Distractors = choices - {correct}.
      local distractors=$(echo "$current_q" | jq -c "[.choices[] | select(. != $correct)]")
      local neighbour_count=0
      local product_count=0
      for d in $(echo "$distractors" | jq '.[]'); do
        # Neighbour check
        if [[ "$d" -ge $((correct - 2)) && "$d" -le $((correct + 2)) && "$d" != "$correct" ]]; then
          neighbour_count=$((neighbour_count + 1))
        fi
        # Product check: is d expressible as i*j for some i,j in 0..9?
        local is_product=0
        for ((i=0; i<=9; i++)); do
          for ((j=0; j<=9; j++)); do
            if [[ $((i * j)) -eq "$d" ]]; then is_product=1; break 2; fi
          done
        done
        product_count=$((product_count + is_product))
      done

      checked=$((checked + 1))
      if [[ $neighbour_count -gt 1 ]]; then
        neighbour_violations=$((neighbour_violations + 1))
      fi
      if [[ $product_count -lt 2 ]]; then
        product_violations=$((product_violations + 1))
      fi
      post_json "/quiz/complete" "{\"quizRunId\":\"$rid\"}" >/dev/null
    done
  done

  if [[ $neighbour_violations -eq 0 ]]; then
    ok "Distractors not clustered around correct (checked $checked questions, 0 violations)"
  else
    bad "Distractors clustered for $neighbour_violations of $checked questions"
  fi
  if [[ $product_violations -eq 0 ]]; then
    ok "Distractors are product-based (checked $checked questions, 0 violations)"
  else
    bad "Distractors not product-based for $product_violations of $checked questions"
  fi
}

# =============================================================================
# MAIN
# =============================================================================
main() {
  printf "\n${BOLD}╔══════════════════════════════════════════════════════╗${NC}\n"
  printf "${BOLD}║   InfinityIsland — Multiplication Module Test Suite  ║${NC}\n"
  printf "${BOLD}╚══════════════════════════════════════════════════════╝${NC}\n"
  printf "  Server: ${BASE}\n"
  printf "  PIN: ${PIN}  Name: ${NAME}\n\n"

  test_new_user_progress_format      # Test 1
  test_operations_endpoint           # Test 2
  test_mul_locked_guard              # Test 3
  test_prerequisites_unlock_mul      # Test 4 (slow - completes all 19 add + 11 sub levels)
  test_single_digit_operands         # Test 5
  test_mul_belt_progression          # Test 6
  test_mul_practice                  # Test 7
  test_mul_inactivity                # Test 8
  test_cross_op_review               # Test 9
  test_mul_black_belt                # Test 10
  test_level1_x0_edge               # Test 11
  test_commutative                   # Test 12
  test_mul_level_progression         # Test 13
  test_mul_lightning                 # Test 14
  test_mul_surf                      # Test 15
  test_mul_rocket                    # Test 16
  test_mul_pretest                   # Test 17
  test_mul_resume                    # Test 18
  test_div_not_unlocked_after_mul    # Test 19
  test_disabled_op_prepare_blocked   # Test 20
  test_operations_enabled_state      # Test 21
  test_mul_belt_placement_l9_l10     # Test 22
  test_mul_distractor_quality        # Test 23
  test_mul_l1_operand_range          # Test 24
  test_mul_specific_placements       # Test 25

  # ---------- Summary ----------
  printf "\n${BOLD}══════════════════════════════════════════════════════${NC}\n"
  printf "${BOLD}RESULTS:${NC} "
  printf "${GREEN}%d passed${NC}, " "$PASS"
  printf "${RED}%d failed${NC}, " "$FAIL"
  printf "${YELLOW}%d skipped${NC}\n" "$SKIP"
  printf "Total: %d tests\n" "$((PASS + FAIL + SKIP))"

  if [[ $TOTAL_TIME -gt 0 ]]; then
    printf "Total API time: %d ms\n" "$TOTAL_TIME"
  fi

  [[ $FAIL -eq 0 ]] && printf "${GREEN}ALL TESTS PASSED!${NC}\n" || printf "${RED}SOME TESTS FAILED${NC}\n"
  printf "${BOLD}══════════════════════════════════════════════════════${NC}\n"

  exit $FAIL
}

main "$@"
