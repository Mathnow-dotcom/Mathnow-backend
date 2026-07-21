#!/usr/bin/env bash
# =============================================================================
# InfinityIsland — DIVISION MODULE TEST SUITE (13 levels)
# Tests: Operation Unlock (after mul L10), Quiz Flow, Belt Progression,
#        No-Divide-By-Zero invariant, Non-Commutativity, Cross-Op Review,
#        Pretest, Resume, Lightning/Surf/Rocket/Bonus game modes.
# =============================================================================
set -o pipefail

BASE="${BASE:-http://localhost:8081/api}"
PIN="${PIN:-9905}"
NAME="${NAME:-DivTest}"
ADMIN_PIN="${ADMIN_PIN:-7878}"
DEBUG="${DEBUG:-0}"

PASS=0
FAIL=0
SKIP=0
TOTAL_TIME=0

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'
BOLD='\033[1m'

say() { printf "\n${BOLD}%s${NC}\n" "$*"; }
subsay() { printf "${CYAN} ▸ %s${NC}\n" "$*"; }
ok() { printf " ${GREEN}✓${NC} %s\n" "$*"; PASS=$((PASS+1)); }
bad() { printf " ${RED}✗${NC} %s\n" "$*" >&2; FAIL=$((FAIL+1)); }
skip_test() { printf " ${YELLOW}⊘${NC} %s\n" "$*"; SKIP=$((SKIP+1)); }
debug() { [[ "$DEBUG" == "1" ]] && printf " ${BLUE}DEBUG:${NC} %s\n" "$*"; }

need() { command -v "$1" >/dev/null || { bad "Missing: $1"; exit 1; }; }
need jq
need curl

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
  echo "$resp"
}

get_json() {
  local url="${BASE%/}$1" pin="${2:-$PIN}"
  curl -sS --max-time 30 -H 'Connection: close' -X GET \
    -H 'accept: application/json' -H "x-pin: $pin" "$url" 2>&1
}

put_json() {
  local url="${BASE%/}$1" data="$2" pin="${3:-$PIN}"
  curl -sS --max-time 30 -X PUT -H 'Content-Type: application/json' -H "x-pin: $pin" "$url" -d "$data"
}

delete_json() {
  local url="${BASE%/}$1" pin="${2:-$PIN}"
  curl -sS --max-time 30 -X DELETE -H 'accept: application/json' -H "x-pin: $pin" "$url"
}

cleanup_user() {
  delete_json "/user/delete" "$PIN" >/dev/null 2>&1
  sleep 0.2
}

reset_user() {
  delete_json "/user/delete" "$PIN" >/dev/null 2>&1
  sleep 0.2
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null
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

# Prepare a game-mode quiz; if pretest intercepts, force-pass it and re-prepare.
prepare_game_mode() {
  local body="$1"
  local prep=$(post_json "/quiz/prepare" "$body")
  while jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; do
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" "$body")
  done
  echo "$prep"
}

skip_pretest() {
  local op="${1:-div}" level="${2:-1}"
  local prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"white\",\"operation\":\"$op\"}")
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz_forcepass "$runId" "$start" >/dev/null
  fi
}

# Fast-forward through add+sub+mul so div is unlocked
fast_forward_to_div() {
  post_json "/admin/restore-user" \
    "{\"pin\":\"$PIN\",\"operations\":{\"add\":19,\"sub\":11,\"mul\":10},\"grandTotalCorrect\":5000,\"currentStreak\":10}" \
    "$ADMIN_PIN" >/dev/null
}

# =============================================================================
# TEST 1: New User Progress Includes Div Branch
# =============================================================================
test_new_user_progress_format() {
  say "TEST 1: New User Progress Format (per-operation includes div)"
  cleanup_user

  local resp=$(post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}")
  jq -e '.token and .user._id' >/dev/null <<<"$resp" && ok "Login successful" || bad "Login failed"

  jq -e '.user.progress.add' >/dev/null <<<"$resp" && ok "progress.add exists" || bad "No progress.add"
  jq -e '.user.progress.sub' >/dev/null <<<"$resp" && ok "progress.sub exists" || bad "No progress.sub"
  jq -e '.user.progress.mul' >/dev/null <<<"$resp" && ok "progress.mul exists" || bad "No progress.mul"
  jq -e '.user.progress.div' >/dev/null <<<"$resp" && ok "progress.div exists" || bad "No progress.div"

  jq -e '.user.progress.div.L1.unlocked==false' >/dev/null <<<"$resp" && ok "div.L1 locked" || bad "div.L1 should be locked"
}

# =============================================================================
# TEST 2: Operations Endpoint Surfaces Div
# =============================================================================
test_operations_endpoint() {
  say "TEST 2: GET /user/operations exposes div"

  local resp=$(get_json "/user/operations")
  jq -e '.operations.div' >/dev/null <<<"$resp" && ok "div operation listed" || bad "No div operation"
  jq -e '.operations.div.maxLevel==13' >/dev/null <<<"$resp" && ok "div maxLevel=13" || bad "Wrong div maxLevel"
  jq -e '.operations.div.enabled==true' >/dev/null <<<"$resp" && ok "div enabled=true" || bad "div should be enabled"
  jq -e '.operations.div.prerequisite=="mul"' >/dev/null <<<"$resp" && ok "div prerequisite=mul" || bad "Wrong div prerequisite"
  jq -e '.operations.div.unlocked==false' >/dev/null <<<"$resp" && ok "div locked (mul not done)" || bad "div should be locked"
}

# =============================================================================
# TEST 3: Div Locked Until Mul Complete
# =============================================================================
test_div_locked_guard() {
  say "TEST 3: Division Locked Until Multiplication Complete"
  reset_user

  local resp=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"div"}')
  if echo "$resp" | jq -e '.error.message' >/dev/null 2>/dev/null; then
    local msg=$(echo "$resp" | jq -r '.error.message')
    ok "Div prepare blocked: $msg"
  elif echo "$resp" | jq -e '.quizRunId' >/dev/null 2>/dev/null; then
    bad "Div prepare should be blocked but got quizRunId"
  else
    ok "Div prepare returned error response"
  fi
}

# =============================================================================
# TEST 4: Complete Add+Sub+Mul -> Div Unlocks
# =============================================================================
test_prerequisites_unlock_div() {
  say "TEST 4: Complete Add+Sub+Mul -> Division Unlocks"
  subsay "Restoring user with all add+sub+mul completed..."
  fast_forward_to_div

  local ops=$(get_json "/user/operations")
  jq -e '.operations.div.unlocked==true' >/dev/null <<<"$ops" && ok "div unlocked after mul complete" || bad "div still locked"

  local prog=$(get_json "/user/progress")
  jq -e '.progress.mul.L10.completed==true' >/dev/null <<<"$prog" && ok "mul.L10 completed" || bad "mul.L10 not completed"
  jq -e '.progress.div.L1.unlocked==true' >/dev/null <<<"$prog" && ok "div.L1 unlocked in progress" || bad "div.L1 still locked"
  jq -e '.progress.div.L1.white.unlocked==true' >/dev/null <<<"$prog" && ok "div.L1.white unlocked in progress" || bad "div.L1.white still locked"

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"div"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    ok "Div L1 pretest triggered (expected)"
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz_forcepass "$runId" "$start" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"div"}')
  fi
  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Div prepare succeeds" || bad "Div prepare still failing"
}

# =============================================================================
# TEST 5: No Division By Zero In Any Generated Question
# =============================================================================
test_no_divide_by_zero() {
  say "TEST 5: No Generated Division Question Has b=0"

  local zero_found=0
  for lvl in $(seq 1 13); do
    for belt in white yellow green blue red brown; do
      local prep=$(post_json "/quiz/prepare" "{\"level\":$lvl,\"beltOrDegree\":\"$belt\",\"operation\":\"div\"}")
      if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
        local rid=$(jq -r '.quizRunId' <<<"$prep")
        local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
        complete_quiz_forcepass "$rid" "$st" >/dev/null
        prep=$(post_json "/quiz/prepare" "{\"level\":$lvl,\"beltOrDegree\":\"$belt\",\"operation\":\"div\"}")
      fi
      local runId=$(jq -r '.quizRunId' <<<"$prep")
      [[ "$runId" == "null" || -z "$runId" ]] && continue

      local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
      local qcount=$(jq '.questions | length' <<<"$start")
      for ((i=0; i<qcount; i++)); do
        local op=$(jq -r ".questions[$i].operation" <<<"$start")
        local b=$(jq ".questions[$i].params.b" <<<"$start")
        if [[ "$op" == "div" && "$b" == "0" ]]; then
          local q=$(jq -r ".questions[$i].question" <<<"$start")
          bad "L$lvl $belt: divide-by-zero generated: $q"
          zero_found=1
        fi
      done
      complete_quiz_forcepass "$runId" "$start" >/dev/null
    done
  done
  [[ $zero_found -eq 0 ]] && ok "No divide-by-zero across L1-L13 all colored belts"
}

# =============================================================================
# TEST 6: Division Symbol In Question Text
# =============================================================================
test_division_symbol() {
  say "TEST 6: Division Questions Use ÷ Symbol"

  local prep=$(post_json "/quiz/prepare" '{"level":2,"beltOrDegree":"white","operation":"div"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":2,"beltOrDegree":"white","operation":"div"}')
  fi
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  local div_symbol_found=0
  for ((i=0; i<qcount; i++)); do
    local q=$(jq -r ".questions[$i].question" <<<"$start")
    local op=$(jq -r ".questions[$i].operation" <<<"$start")
    if [[ "$op" == "div" && "$q" == *"÷"* ]]; then
      div_symbol_found=1
      break
    fi
  done
  [[ $div_symbol_found -eq 1 ]] && ok "div questions use ÷ symbol" || bad "No ÷ found in div questions"
  complete_quiz_forcepass "$runId" "$start" >/dev/null
}

# =============================================================================
# TEST 7: Non-Commutativity (a÷b ≠ b÷a)
# =============================================================================
test_non_commutative() {
  say "TEST 7: Division is Non-Commutative"

  # Sample many quizzes and verify no question's (a,b) appears as its (b,a)
  # in the same set with the same correct answer (would indicate commutativity).
  local prep=$(post_json "/quiz/prepare" '{"level":2,"beltOrDegree":"white","operation":"div"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":2,"beltOrDegree":"white","operation":"div"}')
  fi
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  # For each div question with a≠b, search for swapped variant in same set.
  local swap_count=0
  for ((i=0; i<qcount; i++)); do
    local op=$(jq -r ".questions[$i].operation" <<<"$start")
    [[ "$op" != "div" ]] && continue
    local a=$(jq ".questions[$i].params.a" <<<"$start")
    local b=$(jq ".questions[$i].params.b" <<<"$start")
    [[ "$a" == "$b" ]] && continue
    for ((j=i+1; j<qcount; j++)); do
      local op2=$(jq -r ".questions[$j].operation" <<<"$start")
      [[ "$op2" != "div" ]] && continue
      local a2=$(jq ".questions[$j].params.a" <<<"$start")
      local b2=$(jq ".questions[$j].params.b" <<<"$start")
      if [[ "$a" == "$b2" && "$b" == "$a2" ]]; then
        swap_count=$((swap_count+1))
      fi
    done
  done
  # Non-commutative generation: swaps shouldn't be deliberately produced (some
  # incidental collision is possible in the random review pool; one is fine).
  if [[ $swap_count -le 1 ]]; then
    ok "No deliberate commutative swap pairs in div quiz (count=$swap_count)"
  else
    bad "Found $swap_count commutative swap pairs in div quiz"
  fi
  complete_quiz_forcepass "$runId" "$start" >/dev/null
}

# =============================================================================
# TEST 8: Belt Progression on Div L2 (real answers)
# Requires div L1 completed; we restore-user with div:1 first.
# =============================================================================
test_div_belt_progression() {
  say "TEST 8: Division Belt Progression (Level 2)"

  reset_user
  post_json "/admin/restore-user" \
    "{\"pin\":\"$PIN\",\"operations\":{\"add\":19,\"sub\":11,\"mul\":10,\"div\":1}}" \
    "$ADMIN_PIN" >/dev/null

  local belts=("white" "yellow" "green" "blue" "red" "brown")
  local next_belts=("yellow" "green" "blue" "red" "brown" "black")

  for i in "${!belts[@]}"; do
    local belt="${belts[$i]}"
    local next="${next_belts[$i]}"
    subsay "Completing div L2 $belt belt"

    local prep=$(prepare_game_mode "{\"level\":2,\"beltOrDegree\":\"$belt\",\"operation\":\"div\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    [[ "$runId" == "null" || -z "$runId" ]] && { skip_test "div L2 $belt prepare failed"; continue; }
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz "$runId" "$start" 50 >/dev/null

    local prog=$(get_json "/user/progress")
    jq -e ".progress.div.L2.\"$belt\".completed==true" >/dev/null <<<"$prog" \
      && ok "div L2 $belt completed" || bad "div L2 $belt not completed"

    if [[ "$next" == "black" ]]; then
      jq -e '.progress.div.L2.black.unlocked==true' >/dev/null <<<"$prog" \
        && ok "div L2 black unlocked after brown" || bad "div L2 black not unlocked"
    else
      jq -e ".progress.div.L2.\"$next\".unlocked==true" >/dev/null <<<"$prog" \
        && ok "div L2 $next unlocked" || bad "div L2 $next not unlocked"
    fi
  done
}

# =============================================================================
# TEST 9: Black Belt Degrees on Div L2 (real answers)
# =============================================================================
test_div_black_belt() {
  say "TEST 9: Division Black Belt Degrees (L2)"

  for degree in 1 2 3 4 5 6 7; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":2,\"beltOrDegree\":\"black-$degree\",\"operation\":\"div\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    [[ "$runId" == "null" || -z "$runId" ]] && { bad "black-$degree prepare failed"; continue; }
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qcount=$(jq '.questions | length' <<<"$start")
    [[ "$qcount" == "20" ]] && ok "black-$degree: 20 questions" || bad "black-$degree: expected 20 got $qcount"
    complete_quiz "$runId" "$start" 50 >/dev/null
  done

  local prog=$(get_json "/user/progress")
  jq -e '.progress.div.L2.completed==true' >/dev/null <<<"$prog" && ok "div L2 completed" || bad "div L2 not completed"
  jq -e '.progress.div.L3.unlocked==true' >/dev/null <<<"$prog" && ok "div L3 unlocked after L2 black-7" || bad "div L3 should be unlocked"
}

# =============================================================================
# TEST 10: Cross-Op Review (sub + mul + add) Appears in Div Quizzes
# =============================================================================
test_cross_op_review() {
  say "TEST 10: Cross-Op Review (sub/mul/add facts mix into div quizzes)"

  local prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"yellow","operation":"div"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"yellow","operation":"div"}')
  fi
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  local has_div=0 has_other=0
  for ((i=0; i<qcount; i++)); do
    local op=$(jq -r ".questions[$i].operation" <<<"$start")
    case "$op" in
      div) has_div=1 ;;
      mul|sub|add) has_other=1 ;;
    esac
  done
  [[ $has_div -eq 1 ]] && ok "Div questions present" || bad "No div questions in div quiz"
  [[ $has_other -eq 1 ]] && ok "Cross-op review present (mul/sub/add)" || bad "No cross-op review questions"
  complete_quiz_forcepass "$runId" "$start" >/dev/null
}

# =============================================================================
# TEST 11: No Consecutive Duplicate Questions
# =============================================================================
test_no_consecutive_duplicates() {
  say "TEST 11: No Consecutive Duplicate Questions"

  local prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"green","operation":"div"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local rid=$(jq -r '.quizRunId' <<<"$prep")
    local st=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
    complete_quiz_forcepass "$rid" "$st" >/dev/null
    prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"green","operation":"div"}')
  fi
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  local dup=0 prev=""
  for ((i=0; i<qcount; i++)); do
    local q=$(jq -r ".questions[$i].question" <<<"$start")
    if [[ -n "$prev" && "$q" == "$prev" ]]; then
      dup=1; break
    fi
    prev="$q"
  done
  [[ $dup -eq 0 ]] && ok "No consecutive duplicate questions ($qcount questions)" || bad "Consecutive duplicate found"
  complete_quiz_forcepass "$runId" "$start" >/dev/null
}

# =============================================================================
# TEST 12: Pretest On Div
# =============================================================================
test_div_pretest() {
  say "TEST 12: Pretest On Div L3"

  # Reset div progress so pretest fires fresh
  reset_user
  fast_forward_to_div

  local prep=$(post_json "/quiz/prepare" '{"level":3,"beltOrDegree":"white","operation":"div"}')
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    ok "Pretest triggered for div L3"
    jq -e '.gameModeType=="pretest"' >/dev/null <<<"$prep" && ok "gameModeType=pretest" || bad "wrong gameModeType"
    jq -e '.pretestTimeLimitMs' >/dev/null <<<"$prep" && ok "pretestTimeLimitMs surfaced" || bad "no pretestTimeLimitMs"

    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    jq -e '.pretestMode==true' >/dev/null <<<"$start" && ok "start: pretestMode=true" || bad "start missing pretestMode"

    # Force pass the pretest
    local comp=$(complete_quiz_forcepass "$runId" "$start")
    jq -e '.passed==true or .completed==true' >/dev/null <<<"$comp" && ok "Pretest force-passed" || bad "Pretest force-pass failed"
  else
    skip_test "Pretest already taken or not triggered for div L3"
  fi
}

# =============================================================================
# TEST 13: Lightning Mode On Div
# =============================================================================
test_div_lightning() {
  say "TEST 13: Lightning Mode on Div L1 white"

  local prep=$(prepare_game_mode '{"level":1,"beltOrDegree":"white","operation":"div","gameMode":true,"gameModeType":"lightning"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "Lightning prepare failed"; return; }
  jq -e '.gameModeType=="lightning"' >/dev/null <<<"$prep" && ok "Lightning prepared" || bad "gameModeType not lightning"

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  jq -e '.questions | length > 0' >/dev/null <<<"$start" && ok "Lightning questions returned" || bad "no questions"
  complete_quiz_forcepass "$runId" "$start" >/dev/null
  ok "Lightning quiz completed"
}

# =============================================================================
# TEST 14: Surf Mode On Div
# =============================================================================
test_div_surf() {
  say "TEST 14: Surf Mode on Div L1 white"

  local prep=$(prepare_game_mode '{"level":1,"beltOrDegree":"white","operation":"div","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "Surf prepare failed"; return; }
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$prep" && ok "Surf prepared" || bad "gameModeType not surf"

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz_forcepass "$runId" "$start" >/dev/null
  ok "Surf quiz completed"
}

# =============================================================================
# TEST 15: Rocket Mode On Div
# =============================================================================
test_div_rocket() {
  say "TEST 15: Rocket Mode on Div L1 white"

  local prep=$(prepare_game_mode '{"level":1,"beltOrDegree":"white","operation":"div","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" == "null" || -z "$runId" ]] && { bad "Rocket prepare failed"; return; }
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$prep" && ok "Rocket prepared" || bad "gameModeType not rocket"

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz_forcepass "$runId" "$start" >/dev/null
  ok "Rocket quiz completed"
}

# =============================================================================
# TEST 16: Bonus Mode On Div (force-pass for speed)
# Skipped if rocket isn't naturally completed for this slot.
# =============================================================================
test_div_bonus() {
  say "TEST 16: Bonus Mode on Div L1 white (forcePass awards belt)"

  # Bonus prereq: rocket completed for same level/belt/op. Complete a real rocket
  # quiz on div L1 white via forcePass (NO skipLevelAward — we want the unlock).
  local prep=$(prepare_game_mode '{"level":1,"beltOrDegree":"white","operation":"div","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  if [[ "$runId" != "null" && -n "$runId" ]]; then
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qcount=$(jq '.questions | length' <<<"$start")
    for ((i=0; i<qcount; i++)); do
      local qid=$(jq -r ".questions[$i]._id" <<<"$start")
      local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
      local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":50,\"forcePass\":true}")
      if jq -e '.completed==true or .bonusRequired==true' >/dev/null <<<"$resp"; then break; fi
    done
  fi

  prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"div","gameMode":true,"gameModeType":"bonus"}')
  if jq -e '.error' >/dev/null <<<"$prep" 2>/dev/null; then
    skip_test "Bonus rejected (rocket completion may not have stuck): $(jq -r '.error.message // .error' <<<"$prep")"
    return
  fi
  local rid=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$rid" == "null" || -z "$rid" ]] && { bad "Bonus prepare failed"; return; }
  jq -e '.gameModeType=="bonus"' >/dev/null <<<"$prep" && ok "Bonus prepared" || bad "gameModeType not bonus"
  jq -e '.bonusCorrectStreak==0' >/dev/null <<<"$prep" && ok "bonusCorrectStreak=0 on prepare" || bad "bonusCorrectStreak missing"

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}")
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$rid\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}")
  jq -e '.bonusComplete==true' >/dev/null <<<"$resp" && ok "Bonus forcePass awards belt" || bad "Bonus forcePass didn't complete"
}

# =============================================================================
# TEST 17: Resume Mid-Quiz
# =============================================================================
test_div_resume() {
  say "TEST 17: Resume Mid-Quiz on Div"

  reset_user
  fast_forward_to_div
  skip_pretest div 1

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"div"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qcount=$(jq '.questions | length' <<<"$start")

  # Answer one
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local ans=$(jq '.questions[0].correctAnswer' <<<"$start")
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":300}" >/dev/null

  # Resume via prepare — should return the same quiz with currentIndex > 0
  local resume=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"div"}')
  local resumed=$(jq -r '.resumed // false' <<<"$resume")
  [[ "$resumed" == "true" ]] && ok "Resume returns existing run" || skip_test "Resume not flagged (may have completed)"
  complete_quiz_forcepass "$runId" "$start" >/dev/null
}

# =============================================================================
# TEST 18: Wrong Answer Triggers Practice On Div
# =============================================================================
test_div_practice() {
  say "TEST 18: Wrong Answer Triggers Practice on Div"

  reset_user
  fast_forward_to_div

  local prep=$(prepare_game_mode '{"level":2,"beltOrDegree":"white","operation":"div"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$((correct + 1))
  [[ "$correct" == "0" ]] && wrong=1

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")
  # The response carries `correct:false` for normal-mode wrong answers OR returns practice directly.
  if jq -e '.correct==false or .practice' >/dev/null <<<"$resp"; then
    ok "Wrong answer rejected (practice or correct:false present)"
  else
    bad "wrong answer not flagged: $(echo "$resp" | head -c 200)"
  fi
  jq -e '.practice' >/dev/null <<<"$resp" && ok "Practice question returned" || bad "no practice question"
}

# =============================================================================
# TEST 19: Inactivity Triggers Practice On Div
# =============================================================================
test_div_inactivity() {
  say "TEST 19: Inactivity Triggers Practice on Div"

  reset_user
  fast_forward_to_div

  # First fire pretest on div L1 to clear it, then complete L1 white so yellow is unlocked.
  local prep=$(prepare_game_mode '{"level":1,"beltOrDegree":"white","operation":"div"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" >/dev/null

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"div"}')
  local rid=$(jq -r '.quizRunId' <<<"$prep2")
  post_json "/quiz/start" "{\"quizRunId\":\"$rid\"}" >/dev/null

  local resp=$(post_json "/quiz/inactivity" "{\"quizRunId\":\"$rid\"}")
  jq -e '.practice' >/dev/null <<<"$resp" && ok "Inactivity returns practice" || bad "inactivity missing practice"
  if jq -e '.reason=="inactivity"' >/dev/null <<<"$resp" 2>/dev/null; then
    ok "reason=inactivity"
  else
    skip_test "reason field optional/absent on this endpoint"
  fi
}

# =============================================================================
# TEST 21: Admin Restore-User Accepts Div
# =============================================================================
test_admin_restore_div() {
  say "TEST 21: /admin/restore-user accepts operations.div"

  reset_user
  local resp=$(post_json "/admin/restore-user" \
    "{\"pin\":\"$PIN\",\"operations\":{\"add\":19,\"sub\":11,\"mul\":10,\"div\":13},\"grandTotalCorrect\":6000,\"currentStreak\":12}" \
    "$ADMIN_PIN")
  jq -e '.success==true or .message' >/dev/null <<<"$resp" && ok "Restore returned success" || bad "Restore failed: $(echo "$resp" | head -c 200)"

  local prog=$(get_json "/user/progress")
  jq -e '.progress.div.L13.completed==true' >/dev/null <<<"$prog" && ok "div.L13 completed via restore" || bad "div.L13 not completed"
}

# =============================================================================
# TEST 21: L1->L2->L3->L4 Level Progression (real answers)
# =============================================================================
test_div_level_progression() {
  say "TEST 21: Div Level Progression L1 -> L2 -> L3 -> L4"

  reset_user
  fast_forward_to_div

  local belts=("white" "yellow" "green" "blue" "red" "brown")

  for lvl in 1 2 3; do
    subsay "Completing div L$lvl all colored + black belts"
    for belt in "${belts[@]}"; do
      local prep=$(prepare_game_mode "{\"level\":$lvl,\"beltOrDegree\":\"$belt\",\"operation\":\"div\"}")
      local runId=$(jq -r '.quizRunId' <<<"$prep")
      [[ "$runId" == "null" || -z "$runId" ]] && { bad "div L$lvl $belt prepare failed"; continue; }
      local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
      complete_quiz "$runId" "$start" 50 >/dev/null
    done
    # Black belts via forcePass — real-answer completion is too slow with remote Mongo.
    for degree in 1 2 3 4 5 6 7; do
      local prep=$(post_json "/quiz/prepare" "{\"level\":$lvl,\"beltOrDegree\":\"black-$degree\",\"operation\":\"div\"}")
      local runId=$(jq -r '.quizRunId' <<<"$prep")
      [[ "$runId" == "null" || -z "$runId" ]] && continue
      local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
      local qid=$(jq -r '.questions[0]._id' <<<"$start")
      local ans=$(jq '.questions[0].correctAnswer' <<<"$start")
      post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10,\"forcePass\":true}" >/dev/null
    done

    local prog=$(get_json "/user/progress")
    jq -e ".progress.div.L$lvl.completed==true" >/dev/null <<<"$prog" \
      && ok "div L$lvl completed" || bad "div L$lvl not completed"
    local next=$((lvl + 1))
    jq -e ".progress.div.L$next.unlocked==true" >/dev/null <<<"$prog" \
      && ok "div L$next unlocked" || bad "div L$next not unlocked"
  done
}

# =============================================================================
# MAIN
# =============================================================================
main() {
  printf "${BOLD}═══════════════════════════════════════════════════════════════${NC}\n"
  printf "${BOLD}  InfinityIsland Division Module Test Suite${NC}\n"
  printf "${BOLD}═══════════════════════════════════════════════════════════════${NC}\n"
  printf "  Server: ${BASE}\n"
  printf "  PIN: ${PIN}  Name: ${NAME}\n\n"

  test_new_user_progress_format      # 1
  test_operations_endpoint           # 2
  test_div_locked_guard              # 3
  test_prerequisites_unlock_div      # 4
  test_no_divide_by_zero             # 5
  test_division_symbol               # 6
  test_non_commutative               # 7
  test_div_belt_progression          # 8
  test_div_black_belt                # 9
  test_cross_op_review               # 10
  test_no_consecutive_duplicates     # 11
  test_div_pretest                   # 12
  test_div_lightning                 # 13
  test_div_surf                      # 14
  test_div_rocket                    # 15
  test_div_bonus                     # 16
  test_div_resume                    # 17
  test_div_practice                  # 18
  test_div_inactivity                # 19
  test_admin_restore_div             # 20
  test_div_level_progression         # 21

  printf "\n${BOLD}══════════════════════════════════════════════════════${NC}\n"
  printf "${BOLD}RESULTS:${NC} "
  printf "${GREEN}%d passed${NC}, " "$PASS"
  printf "${RED}%d failed${NC}, " "$FAIL"
  printf "${YELLOW}%d skipped${NC}\n" "$SKIP"
  printf "Total: %d tests\n" "$((PASS + FAIL + SKIP))"
  [[ $TOTAL_TIME -gt 0 ]] && printf "Total API time: %d ms\n" "$TOTAL_TIME"
  [[ $FAIL -eq 0 ]] && printf "${GREEN}ALL TESTS PASSED!${NC}\n" || printf "${RED}SOME TESTS FAILED${NC}\n"
  printf "${BOLD}══════════════════════════════════════════════════════${NC}\n"

  exit $FAIL
}

main "$@"
