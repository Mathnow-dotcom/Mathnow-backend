#!/usr/bin/env bash
# =============================================================================
# InfinityIsland — SUBTRACTION MODULE TEST SUITE
# Tests: Migration, Operation Unlock, Subtraction Quiz Flow, No-Negative Guard,
#        Belt Progression, Practice Questions, Game Modes, Cross-Op Review
# =============================================================================
set -o pipefail

# ---------- Config ----------
BASE="${BASE:-http://localhost:8081/api}"
PIN="${PIN:-9901}"
NAME="${NAME:-SubTest}"
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
  say "TEST 1: New User Progress Format (per-operation)"
  cleanup_user

  local resp=$(post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}")
  jq -e '.token and .user._id' >/dev/null <<<"$resp" && ok "Login successful" || bad "Login failed"

  # Check per-operation progress format
  jq -e '.user.progress.add' >/dev/null <<<"$resp" && ok "progress.add exists" || bad "No progress.add"
  jq -e '.user.progress.sub' >/dev/null <<<"$resp" && ok "progress.sub exists" || bad "No progress.sub"
  jq -e '.user.progress.add.L1.unlocked==true' >/dev/null <<<"$resp" && ok "add.L1 unlocked" || bad "add.L1 not unlocked"
  jq -e '.user.progress.sub.L1.unlocked==false' >/dev/null <<<"$resp" && ok "sub.L1 locked" || bad "sub.L1 should be locked"

  # Check belt structure
  jq -e '.user.progress.add.L1.white.unlocked==true' >/dev/null <<<"$resp" && ok "add.L1.white unlocked" || bad "add.L1.white not unlocked"
  jq -e '.user.progress.sub.L1.white.unlocked==false' >/dev/null <<<"$resp" && ok "sub.L1.white locked" || bad "sub.L1.white should be locked"

  # Check pretest format (simple taken/passed, not per-operation)
  jq -e '.user.progress.add.L1.pretest.taken==false' >/dev/null <<<"$resp" && ok "add.L1.pretest.taken exists" || bad "pretest format wrong"
  jq -e '.user.progress.add.L1.pretest | has("add") | not' >/dev/null <<<"$resp" && ok "pretest not per-operation" || bad "pretest still has operation keys"
}

# =============================================================================
# TEST 2: Operations Endpoint
# =============================================================================
test_operations_endpoint() {
  say "TEST 2: GET /user/operations"

  local resp=$(get_json "/user/operations")
  jq -e '.operations.add' >/dev/null <<<"$resp" && ok "add operation listed" || bad "No add operation"
  jq -e '.operations.sub' >/dev/null <<<"$resp" && ok "sub operation listed" || bad "No sub operation"

  jq -e '.operations.add.maxLevel==19' >/dev/null <<<"$resp" && ok "add maxLevel=19" || bad "Wrong add maxLevel"
  jq -e '.operations.sub.maxLevel==11' >/dev/null <<<"$resp" && ok "sub maxLevel=11" || bad "Wrong sub maxLevel"

  jq -e '.operations.add.unlocked==true' >/dev/null <<<"$resp" && ok "add unlocked" || bad "add should be unlocked"
  jq -e '.operations.sub.unlocked==false' >/dev/null <<<"$resp" && ok "sub locked (add not done)" || bad "sub should be locked"

  jq -e '.operations.add.enabled==true' >/dev/null <<<"$resp" && ok "add enabled" || bad "add not enabled"
  jq -e '.operations.sub.enabled==true' >/dev/null <<<"$resp" && ok "sub enabled" || bad "sub not enabled"

  jq -e '.operations.sub.prerequisite=="add"' >/dev/null <<<"$resp" && ok "sub prerequisite=add" || bad "Wrong prerequisite"
}

# =============================================================================
# TEST 3: Operation Unlock Guard
# =============================================================================
test_operation_unlock_guard() {
  say "TEST 3: Subtraction Locked Until Addition Complete"
  reset_user

  # Try to prepare subtraction quiz - should fail
  local resp=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub"}')

  # Should get an error (400 with message about locked operation)
  if echo "$resp" | jq -e '.error.message' >/dev/null 2>/dev/null; then
    local msg=$(echo "$resp" | jq -r '.error.message')
    ok "Sub prepare blocked: $msg"
  elif echo "$resp" | jq -e '.quizRunId' >/dev/null 2>/dev/null; then
    bad "Sub prepare should be blocked but got quizRunId"
  else
    ok "Sub prepare returned error response"
  fi
}

# =============================================================================
# TEST 4: Addition Completion → Subtraction Unlock
# =============================================================================
test_addition_unlocks_subtraction() {
  say "TEST 4: Complete All Addition → Subtraction Unlocks"

  # Use admin restore to set all add levels as completed (faster than playing through)
  subsay "Restoring user with all addition completed via admin endpoint..."
  local restore=$(post_json "/admin/restore-user" \
    "{\"pin\":\"$PIN\",\"operations\":{\"add\":19},\"grandTotalCorrect\":3000,\"currentStreak\":10}" \
    "$ADMIN_PIN")
  local rmsg=$(echo "$restore" | jq -r '.message // .error // "unknown"')
  debug "Restore result: $rmsg"

  # Check subtraction is now unlocked
  local ops=$(get_json "/user/operations")
  jq -e '.operations.sub.unlocked==true' >/dev/null <<<"$ops" && ok "sub unlocked after add complete" || bad "sub still locked"

  # Check progress shows sub available and L1 is unlocked
  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L19.completed==true' >/dev/null <<<"$prog" && ok "add.L19 completed" || bad "add.L19 not completed"
  jq -e '.progress.sub.L1.unlocked==true' >/dev/null <<<"$prog" && ok "sub.L1 unlocked in progress" || bad "sub.L1 still locked in progress"
  jq -e '.progress.sub.L1.white.unlocked==true' >/dev/null <<<"$prog" && ok "sub.L1.white unlocked in progress" || bad "sub.L1.white still locked in progress"

  # Now prepare subtraction should work
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub"}')
  # Might get pretest first
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    ok "Sub L1 pretest triggered (expected)"
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz_forcepass "$runId" "$start" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub"}')
  fi

  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Sub prepare succeeds" || bad "Sub prepare still failing"
}

# =============================================================================
# TEST 5: Subtraction Quiz - No Negative Numbers
# =============================================================================
test_no_negative_numbers() {
  say "TEST 5: No Negative Numbers in Subtraction"

  # Prepare and start a sub quiz
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz_forcepass "$runId" "$start" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  [[ "$qcount" -ge 10 ]] && ok "Got $qcount questions" || bad "Too few questions: $qcount"

  local neg_found=0
  for ((i=0; i<qcount; i++)); do
    local a=$(jq ".questions[$i].params.a" <<<"$start")
    local b=$(jq ".questions[$i].params.b" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    local q=$(jq -r ".questions[$i].question" <<<"$start")
    local op=$(jq -r ".questions[$i].operation" <<<"$start")

    debug "Q$i: $q = $ans (a=$a, b=$b, op=$op)"

    # Check a >= b for subtraction questions
    if [[ "$op" == "sub" ]]; then
      if [[ "$a" -lt "$b" ]]; then
        bad "Negative: a=$a < b=$b in question '$q'"
        neg_found=1
      fi
    fi

    # Check answer is non-negative
    if [[ "$ans" -lt 0 ]]; then
      bad "Negative answer: $ans in question '$q'"
      neg_found=1
    fi

    # Check all choices are non-negative
    local choices=$(jq ".questions[$i].choices[]" <<<"$start")
    while read -r choice; do
      if [[ "$choice" -lt 0 ]]; then
        bad "Negative choice: $choice in question '$q'"
        neg_found=1
      fi
    done <<<"$choices"
  done

  [[ $neg_found -eq 0 ]] && ok "All questions, answers, and choices are non-negative"

  # Check question text uses minus sign
  local q0=$(jq -r '.questions[0].question' <<<"$start")
  if [[ "$q0" == *"-"* ]] || [[ "$q0" == *"−"* ]]; then
    ok "Question text contains minus: '$q0'"
  else
    # Might be a digit recognition question for L1 white
    skip_test "Question format check (may be digit recognition): '$q0'"
  fi

  # Complete the quiz
  complete_quiz_forcepass "$runId" "$start" >/dev/null
}

# =============================================================================
# TEST 6: Subtraction Belt Progression
# =============================================================================
test_subtraction_belt_progression() {
  say "TEST 6: Subtraction Belt Progression"

  local belts=("white" "yellow" "green" "blue" "red" "brown")
  local next_belts=("yellow" "green" "blue" "red" "brown" "black")

  for i in "${!belts[@]}"; do
    local belt="${belts[$i]}"
    local next="${next_belts[$i]}"
    subsay "Completing sub $belt belt"

    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"sub\"}")
    if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
      local rid=$(jq -r '.quizRunId' <<<"$prep")
      local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
      complete_quiz_forcepass "$rid" "$st" >/dev/null
      prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"sub\"}")
    fi

    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local final=$(complete_quiz "$runId" "$start" 30)

    if jq -e '.completed==true and .passed==true' >/dev/null <<<"$final"; then
      ok "sub $belt completed"

      local prog=$(get_json "/user/progress")
      if [[ "$next" == "black" ]]; then
        jq -e '.progress.sub.L1.black.unlocked==true' >/dev/null <<<"$prog" && ok "sub black unlocked" || bad "sub black not unlocked"
      else
        jq -e ".progress.sub.L1.$next.unlocked==true" >/dev/null <<<"$prog" && ok "sub $next unlocked" || bad "sub $next not unlocked"
      fi
    else
      bad "sub $belt failed to complete"
      debug "Final: $(echo "$final" | head -c 300)"
      break
    fi
  done
}

# =============================================================================
# TEST 7: Subtraction Wrong Answer → Practice
# =============================================================================
test_subtraction_practice() {
  say "TEST 7: Subtraction Wrong Answer → Practice"

  skip_pretest "sub" 1
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"sub"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"sub"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for sub yellow"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$((correct + 99))

  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":100}")
  jq -e 'has("practice")' >/dev/null <<<"$wresp" && ok "Wrong answer triggers practice" || bad "No practice on wrong answer"

  # Check practice question is non-negative
  local pa=$(jq '.practice.params.a // 0' <<<"$wresp")
  local pb=$(jq '.practice.params.b // 0' <<<"$wresp")
  local pans=$(jq '.practice.correctAnswer // 0' <<<"$wresp")

  [[ "$pans" -ge 0 ]] && ok "Practice answer non-negative: $pans" || bad "Practice has negative answer: $pans"

  # Complete practice correctly
  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")
  jq -e '.resume==true or has("next")' >/dev/null <<<"$presp" && ok "Practice completes and resumes" || bad "Practice resume failed"

  # Clean up
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

# =============================================================================
# TEST 8: Subtraction Black Belt
# =============================================================================
test_subtraction_black_belt() {
  say "TEST 8: Subtraction Black Belt"

  # Ensure L1 colored belts are done (from test 6)
  local prog=$(get_json "/user/progress")
  if ! jq -e '.progress.sub.L1.black.unlocked==true' >/dev/null <<<"$prog" 2>/dev/null; then
    subsay "Completing sub L1 colored belts first..."
    complete_full_level "sub" 1
    prog=$(get_json "/user/progress")
  fi

  jq -e '.progress.sub.L1.black.unlocked==true' >/dev/null <<<"$prog" && ok "sub black unlocked" || { bad "sub black not unlocked"; return; }

  subsay "Testing sub black-1"
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"black-1","operation":"sub"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for sub black-1"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")
  [[ "$qcount" -ge 20 ]] && ok "Black-1 has $qcount questions (>=20)" || bad "Wrong count: $qcount"

  # Check timer
  local timelimit=$(jq '.timer.limitMs // 0' <<<"$start")
  [[ "$timelimit" -gt 0 ]] && ok "Black-1 timer set: ${timelimit}ms" || bad "No timer for black belt"

  # Verify no negative numbers and check for addition review questions
  local neg_found=0
  local add_review_count=0
  for ((i=0; i<qcount; i++)); do
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    local qop=$(jq -r ".questions[$i].operation" <<<"$start")
    if [[ "$ans" -lt 0 ]]; then
      bad "Negative answer in black belt Q$i: $ans"
      neg_found=1
    fi
    if [[ "$qop" == "add" ]]; then
      add_review_count=$((add_review_count + 1))
    fi
  done
  [[ $neg_found -eq 0 ]] && ok "All black belt answers non-negative"
  [[ $add_review_count -gt 0 ]] && ok "Black belt has $add_review_count addition review questions" || bad "Black belt has no addition review questions (expected cross-op review)"

  local final=$(complete_quiz "$runId" "$start" 5)
  jq -e '.completed==true' >/dev/null <<<"$final" && ok "Sub black-1 completed" || bad "Not completed"

  prog=$(get_json "/user/progress")
  jq -e '.progress.sub.L1.black.completedDegrees | contains([1])' >/dev/null <<<"$prog" && ok "Black-1 recorded in progress" || bad "Degree not recorded"
}

# =============================================================================
# TEST 9: Subtraction Lightning Mode
# =============================================================================
test_subtraction_lightning() {
  say "TEST 9: Subtraction Lightning Mode"

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub","gameMode":true,"gameModeType":"lightning"}')

  if jq -e '.quizRunId' >/dev/null <<<"$prep" 2>/dev/null; then
    ok "Lightning mode prepare succeeded"

    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

    jq -e '.questions | length >= 10' >/dev/null <<<"$start" && ok "Lightning questions generated" || bad "No lightning questions"
    jq -e '.gameModeType=="lightning"' >/dev/null <<<"$start" && ok "Mode is lightning" || bad "Wrong mode"

    # Answer a few correctly
    for ((i=0; i<3; i++)); do
      local qid=$(jq -r ".questions[$((i % $(jq '.questions | length' <<<"$start")))]._id" <<<"$start")
      local ans=$(jq ".questions[$((i % $(jq '.questions | length' <<<"$start")))].correctAnswer" <<<"$start")
      post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10}" >/dev/null
    done
    ok "Lightning answers accepted"

    post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
  else
    bad "Lightning mode prepare failed"
    debug "Response: $(echo "$prep" | head -c 300)"
  fi
}

# =============================================================================
# TEST 10: Subtraction Inactivity
# =============================================================================
test_subtraction_inactivity() {
  say "TEST 10: Subtraction Inactivity Handling"

  skip_pretest "sub" 1
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for inactivity test"; return; }

  post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}" >/dev/null

  local resp=$(post_json "/quiz/inactivity" "{\"quizRunId\":\"$runId\"}")
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Inactivity triggers practice" || bad "No practice on inactivity"

  # Clean up
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

# =============================================================================
# TEST 11: Subtraction Level Progression (L1 → L2)
# =============================================================================
test_subtraction_level_progression() {
  say "TEST 11: Subtraction Level Progression (L1 → L2)"

  # Ensure all L1 colored + black complete
  local prog=$(get_json "/user/progress")
  if ! jq -e '.progress.sub.L1.completed==true' >/dev/null <<<"$prog" 2>/dev/null; then
    subsay "Completing sub L1 fully..."
    complete_full_level "sub" 1
  fi

  prog=$(get_json "/user/progress")
  jq -e '.progress.sub.L1.completed==true' >/dev/null <<<"$prog" && ok "sub L1 completed" || bad "sub L1 not completed"
  jq -e '.progress.sub.L2.unlocked==true' >/dev/null <<<"$prog" && ok "sub L2 unlocked" || bad "sub L2 not unlocked"
  jq -e '.progress.sub.L2.white.unlocked==true' >/dev/null <<<"$prog" && ok "sub L2 white unlocked" || bad "sub L2 white not unlocked"

  # Prepare sub L2
  local prep=$(post_json "/quiz/prepare" '{"level":2,"beltOrDegree":"white","operation":"sub"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    ok "Sub L2 pretest triggered"
  elif jq -e '.quizRunId' >/dev/null <<<"$prep" 2>/dev/null; then
    ok "Sub L2 prepare succeeded"
  else
    bad "Sub L2 prepare failed"
  fi
}

# =============================================================================
# TEST 12: Progress Endpoint Returns Per-Operation Format
# =============================================================================
test_progress_format() {
  say "TEST 12: GET /user/progress Format"

  local prog=$(get_json "/user/progress")

  jq -e '.progress.add' >/dev/null <<<"$prog" && ok "progress.add present" || bad "No progress.add"
  jq -e '.progress.sub' >/dev/null <<<"$prog" && ok "progress.sub present" || bad "No progress.sub"

  # Ensure no old-format L* keys at top level
  local topKeys=$(jq -r '.progress | keys[]' <<<"$prog" 2>/dev/null)
  local hasOldKeys=0
  while read -r key; do
    if [[ "$key" == L* ]]; then
      bad "Old format key at top level: $key"
      hasOldKeys=1
    fi
  done <<<"$topKeys"
  [[ $hasOldKeys -eq 0 ]] && ok "No old-format L* keys at top level"
}

# =============================================================================
# TEST 13: No Negative Answers in Surf/Rocket Game Modes
# =============================================================================
test_no_negatives_game_modes() {
  say "TEST 13: No Negative Answers in Surf/Rocket Modes"

  # Helper: prepare game mode, start, check all questions for negatives
  check_mode_negatives() {
    local mode=$1 belt=$2 level=$3
    local prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"$belt\",\"operation\":\"sub\",\"gameMode\":true,\"gameModeType\":\"$mode\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    if [[ -z "$runId" || "$runId" == "null" ]]; then
      bad "$mode $belt L$level: no quizRunId"
      return 1
    fi

    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qcount=$(jq '.questions | length' <<<"$start")
    local neg=0

    for ((i=0; i<qcount; i++)); do
      local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
      local q=$(jq -r ".questions[$i].question" <<<"$start")
      if [[ "$ans" -lt 0 ]]; then
        bad "$mode $belt L$level Q$i: negative answer $ans in '$q'"
        neg=1
      fi
      # For rocket mode, check expression choices too
      if [[ "$mode" == "rocket" ]]; then
        local choices=$(jq -r ".questions[$i].choices[]?" <<<"$start" 2>/dev/null)
        while read -r ch; do
          if [[ "$ch" == *"-"*"="* ]]; then
            local chAns=$(echo "$ch" | grep -oE '= *-?[0-9]+' | grep -oE '-?[0-9]+')
            if [[ -n "$chAns" && "$chAns" -lt 0 ]]; then
              bad "$mode $belt L$level Q$i: negative in choice '$ch'"
              neg=1
            fi
          fi
        done <<<"$choices"
      fi
    done

    # Cleanup: forcePass to not leave running quiz
    local firstQ=$(jq -r '.questions[0]._id' <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$firstQ\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" > /dev/null

    if [[ $neg -eq 0 ]]; then
      ok "$mode $belt L$level: all $qcount questions non-negative"
    fi
    return $neg
  }

  # Ensure lightning mode is completed first (prerequisite for surf/rocket)
  local lprep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub","gameMode":true,"gameModeType":"lightning"}')
  local lrid=$(jq -r '.quizRunId' <<<"$lprep")
  if [[ -n "$lrid" && "$lrid" != "null" ]]; then
    local lst=$(post_json "/quiz/start" "{\"quizRunId\":\"$lrid\"}")
    local lfq=$(jq -r '.questions[0]._id' <<<"$lst")
    post_json "/quiz/answer" "{\"quizRunId\":\"$lrid\",\"questionId\":\"$lfq\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" > /dev/null
  fi

  # Test surf mode at white belt (normal pool)
  check_mode_negatives "surf" "white" 1

  # Test rocket mode at white belt
  check_mode_negatives "rocket" "white" 1

  # Test surf mode at black belt (was the reported bug)
  # Need black belt unlocked — check if already done from test 8
  local prog=$(get_json "/user/progress")
  if jq -e '.progress.sub.L1.black.unlocked==true' >/dev/null <<<"$prog" 2>/dev/null; then
    # Complete lightning at black-2 before surf/rocket
    local blprep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"black-2","operation":"sub","gameMode":true,"gameModeType":"lightning"}')
    local blrid=$(jq -r '.quizRunId' <<<"$blprep")
    if [[ -n "$blrid" && "$blrid" != "null" ]]; then
      local blst=$(post_json "/quiz/start" "{\"quizRunId\":\"$blrid\"}")
      local blfq=$(jq -r '.questions[0]._id' <<<"$blst")
      post_json "/quiz/answer" "{\"quizRunId\":\"$blrid\",\"questionId\":\"$blfq\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" > /dev/null
    fi
    check_mode_negatives "surf" "black-2" 1
    check_mode_negatives "rocket" "black-2" 1
  else
    skip "Black belt not unlocked — skipping black belt game mode tests"
  fi
}

# =============================================================================
# TEST 14: Addition Facts Mixed Into Subtraction Quizzes
# =============================================================================
test_addition_facts_in_subtraction() {
  say "TEST 14: Addition Facts Mixed Into Subtraction Colored Belt Quizzes"

  # Use Sub L1 yellow belt — should have 4 current sub questions + 6 previous (mix of sub + add)
  skip_pretest "sub" 1
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"sub"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"sub"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for sub L1 yellow"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  local sub_count=0
  local add_count=0
  local other_count=0
  for ((i=0; i<qcount; i++)); do
    local qop=$(jq -r ".questions[$i].operation" <<<"$start")
    local q=$(jq -r ".questions[$i].question" <<<"$start")
    if [[ "$qop" == "sub" ]]; then
      sub_count=$((sub_count + 1))
    elif [[ "$qop" == "add" ]]; then
      add_count=$((add_count + 1))
    else
      other_count=$((other_count + 1))
    fi
    debug "Q$i: op=$qop q='$q'"
  done

  ok "Sub L1 yellow: $sub_count sub questions, $add_count add questions, $other_count other"
  [[ $add_count -gt 0 ]] && ok "Addition facts present in subtraction quiz" || bad "No addition facts in subtraction quiz (expected cross-op previous questions)"
  [[ $sub_count -ge 2 ]] && ok "At least 2 subtraction current questions present" || bad "Too few subtraction questions: $sub_count"

  # Also test surf mode has mixed pool
  subsay "Testing surf mode for mixed pool..."
  local lprep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"sub","gameMode":true,"gameModeType":"lightning"}')
  local lrid=$(jq -r '.quizRunId' <<<"$lprep")
  if [[ -n "$lrid" && "$lrid" != "null" ]]; then
    local lst=$(post_json "/quiz/start" "{\"quizRunId\":\"$lrid\"}")
    local lfq=$(jq -r '.questions[0]._id' <<<"$lst")
    post_json "/quiz/answer" "{\"quizRunId\":\"$lrid\",\"questionId\":\"$lfq\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" > /dev/null
  fi

  local sprep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"sub","gameMode":true,"gameModeType":"surf"}')
  local srid=$(jq -r '.quizRunId' <<<"$sprep")
  if [[ -n "$srid" && "$srid" != "null" ]]; then
    local sstart=$(post_json "/quiz/start" "{\"quizRunId\":\"$srid\"}")
    local sqcount=$(jq '.questions | length' <<<"$sstart")
    local surf_has_variety=0
    local ops_seen=""
    for ((i=0; i<sqcount; i++)); do
      local sqop=$(jq -r ".questions[$i].operation" <<<"$sstart")
      ops_seen="$ops_seen $sqop"
    done
    if echo "$ops_seen" | grep -q "add"; then
      ok "Surf mode pool includes addition facts"
    else
      skip_test "Surf mode may not have picked addition facts (random, pool is mixed)"
    fi
    # Cleanup
    local sfq=$(jq -r '.questions[0]._id' <<<"$sstart")
    post_json "/quiz/answer" "{\"quizRunId\":\"$srid\",\"questionId\":\"$sfq\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" > /dev/null
  fi

  # Cleanup original quiz
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

# =============================================================================
# TEST 15: No Consecutive Duplicate Questions
# =============================================================================
test_no_consecutive_duplicates() {
  say "TEST 15: No Consecutive Duplicate Questions"

  # Test normal mode quiz at Sub L1 yellow (has a mix of questions)
  skip_pretest "sub" 1
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"green","operation":"sub"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"green","operation":"sub"}')
  fi

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "No quizRunId for consecutive dup test"; return; }

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")
  local dup_found=0

  for ((i=1; i<qcount; i++)); do
    local prev_q=$(jq -r ".questions[$((i-1))].question" <<<"$start")
    local curr_q=$(jq -r ".questions[$i].question" <<<"$start")
    if [[ "$prev_q" == "$curr_q" ]]; then
      bad "Consecutive duplicate at Q$((i-1))/Q$i: '$curr_q'"
      dup_found=1
    fi
  done
  [[ $dup_found -eq 0 ]] && ok "Normal mode: no consecutive duplicate questions ($qcount questions)"

  # Cleanup
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

# =============================================================================
# MAIN
# =============================================================================
main() {
  printf "\n${BOLD}╔══════════════════════════════════════════════════════╗${NC}\n"
  printf "${BOLD}║   InfinityIsland — Subtraction Module Test Suite     ║${NC}\n"
  printf "${BOLD}╚══════════════════════════════════════════════════════╝${NC}\n"
  printf "  Server: ${BASE}\n"
  printf "  PIN: ${PIN}  Name: ${NAME}\n\n"

  test_new_user_progress_format    # Test 1
  test_operations_endpoint         # Test 2
  test_operation_unlock_guard      # Test 3
  test_addition_unlocks_subtraction # Test 4 (slow - completes all 19 add levels)
  test_no_negative_numbers         # Test 5
  test_subtraction_belt_progression # Test 6
  test_subtraction_practice        # Test 7
  test_subtraction_black_belt      # Test 8
  test_subtraction_lightning       # Test 9
  test_subtraction_inactivity      # Test 10
  test_subtraction_level_progression # Test 11
  test_progress_format             # Test 12
  test_no_negatives_game_modes     # Test 13
  test_addition_facts_in_subtraction # Test 14
  test_no_consecutive_duplicates   # Test 15

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
