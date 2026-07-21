#!/usr/bin/env bash
# =============================================================================
# InfinityIsland — COMPLETE TEST SUITE (MERGED)
# All tests: Auth, User, Quiz, Progression, Game Mode, Surf Mode, ForcePass,
#            Resume, Analytics, Edge Cases
# =============================================================================
set -o pipefail

# ---------- Config ----------
BASE="${BASE:-http://localhost:8081/api}"
PIN="${PIN:-4705}"
NAME="${NAME:-Uday}"
ADMIN_PIN="${ADMIN_PIN:-7878}"
DEBUG="${DEBUG:-0}"
TARGET="${TARGET:-5}"
LIGHTNING_TARGET="${LIGHTNING_TARGET:-5}"

# Surf mode constants (from GameModeConfig)
SURF_QUESTIONS_PER_QUIZ=4
SURF_QUIZZES_REQUIRED=5
INACTIVITY_THRESHOLD_MS=5000
FAST_THRESHOLD_MS=2000

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
skip() { printf " ${YELLOW}⊘${NC} %s\n" "$*"; SKIP=$((SKIP+1)); }
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
  local resp=$(curl -s -X POST \
    -H 'Content-Type: application/json' \
    -H "x-pin: $pin" \
    "$url" -d "$data")
  end_time=$(date +%s%N 2>/dev/null || date +%s)
  if [[ "$start_time" =~ ^[0-9]{10,}$ ]]; then
    elapsed=$(( (end_time - start_time) / 1000000 ))
    TOTAL_TIME=$((TOTAL_TIME + elapsed))
  fi
  debug "POST $url"
  [[ "$DEBUG" == "1" ]] && echo "$resp" | jq . 2>/dev/null >&2
  echo "$resp"
}

get_json() {
  local url="${BASE%/}$1" pin="${2:-$PIN}"
  local resp=$(curl -s -X GET \
    -H 'accept: application/json' \
    -H "x-pin: $pin" \
    "$url")
  debug "GET $url"
  echo "$resp"
}

delete_json() {
  local url="${BASE%/}$1" pin="${2:-$PIN}"
  curl -s -X DELETE -H 'accept: application/json' -H "x-pin: $pin" "$url"
}

cleanup_user() {
  delete_json "/user/delete" "$PIN" >/dev/null 2>&1
  sleep 0.2
}

# Skip pretest for a specific operation using forcePass
skip_pretest_for_op() {
  local op="${1:-add}"
  local level="${2:-1}"

  local prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"white\",\"operation\":\"$op\"}")

  # Check if this is a pretest
  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    if [[ "$runId" != "null" && -n "$runId" ]]; then
      local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
      local qid=$(jq -r '.questions[0]._id' <<<"$start")
      # Use forcePass with skipLevelAward to skip pretest WITHOUT awarding level
      # This marks pretest as "taken but failed" so user goes through normal belt progression
      post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true,\"skipLevelAward\":true}" >/dev/null
      debug "Skipped pretest for $op level $level (no level award)"
    fi
  else
    # Not a pretest, complete/abandon the quiz
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    if [[ "$runId" != "null" && -n "$runId" ]]; then
      post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
    fi
  fi
}

# Skip all pretests for level 1 (only unlocked operations)
skip_all_pretests() {
  local level="${1:-1}"
  skip_pretest_for_op "add" "$level"
}

# Complete a pretest naturally (20 questions in under 50s)
complete_pretest() {
  local runId="$1"
  local start="$2"
  local speed="${3:-10}"

  local qcount=$(jq '.questions | length' <<<"$start")

  for ((i=0; i<qcount; i++)); do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":$speed}")
    if jq -e '.completed==true' >/dev/null <<<"$resp"; then
      echo "$resp"
      return
    fi
  done
}

# Handle pretest if triggered, returns the final response or empty if no pretest
handle_pretest_if_triggered() {
  local prep="$1"

  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    if [[ "$runId" != "null" && -n "$runId" ]]; then
      local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
      local qid=$(jq -r '.questions[0]._id' <<<"$start")
      # Use forcePass to complete pretest
      local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}")
      echo "$resp"
      return 0
    fi
  fi
  return 1
}

reset_user() {
  post_json "/user/reset" '{}' >/dev/null
  sleep 0.3
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null
  # Skip all pretests for level 1 so tests can proceed normally
  skip_all_pretests 1
}

# Reset user without skipping pretests (for testing pretest functionality)
reset_user_with_pretests() {
  post_json "/user/reset" '{}' >/dev/null
  sleep 0.3
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null
}

rand_between() {
  local min=$1 max=$2
  echo $(( (RANDOM % (max - min + 1)) + min ))
}

# Complete a normal quiz (handles resumed quizzes)
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

# Complete a game mode quiz
complete_game_mode() {
  local runId="$1" start="$2" target="$3" wrong_rate="${4:-0}"
  local qcount=$(jq '.questions | length' <<<"$start")
  local correctCount=0 i=0 maxIter=500
  while [[ $correctCount -lt $target && $i -lt $maxIter ]]; do
    local idx=$((i % qcount))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local correctAns=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    local answer=$correctAns
    if [[ $wrong_rate -gt 0 ]]; then
      local rand=$(rand_between 1 100)
      [[ $rand -le $wrong_rate ]] && answer=$((correctAns + 50))
    fi
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$answer,\"responseMs\":10}")
    if jq -e '.completed==true' >/dev/null <<<"$resp"; then
      echo "$resp"
      return
    fi
    if jq -e 'has("practice")' >/dev/null <<<"$resp"; then
      local pid=$(jq -r '.practice._id' <<<"$resp")
      local pcorrect=$(jq '.practice.correctAnswer' <<<"$resp")
      post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}" >/dev/null
    else
      if jq -e 'has("nextIndex")' >/dev/null <<<"$resp"; then
        correctCount=$((correctCount + 1))
      fi
    fi
    i=$((i + 1))
  done
}

# Complete lightning mode using forcePass for speed
complete_lightning_mode() {
  local level="${1:-1}"
  local belt="${2:-white}"
  local op="${3:-add}"

  local prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"$belt\",\"operation\":\"$op\",\"gameMode\":true,\"gameModeType\":\"lightning\"}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  if [[ "$runId" == "null" || -z "$runId" ]]; then
    echo "ERROR: Failed to prepare lightning mode"
    return 1
  fi

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qid=$(jq -r '.questions[0]._id' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}")

  if jq -e '.completed==true and .passed==true' >/dev/null <<<"$resp"; then
    echo "$resp"
    return 0
  else
    echo "ERROR: Lightning mode did not complete"
    return 1
  fi
}

# Complete lightning mode naturally (without forcePass) - does NOT award progression
complete_lightning_mode_natural() {
  local level="${1:-1}"
  local belt="${2:-white}"
  local op="${3:-add}"
  local target="${4:-$TARGET}"

  local prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"$belt\",\"operation\":\"$op\",\"gameMode\":true,\"gameModeType\":\"lightning\",\"targetCorrect\":$target}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  if [[ "$runId" == "null" || -z "$runId" ]]; then
    echo "ERROR: Failed to prepare lightning mode"
    return 1
  fi

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local resp=$(complete_game_mode "$runId" "$start" "$target" 0)

  echo "$resp"
}

# Complete a single surf quiz (4 consecutive correct)
complete_surf_quiz() {
  local runId="$1"
  local questions="$2"
  local responseMs="${3:-500}"

  for i in 0 1 2 3; do
    local qid=$(jq -r ".[$i]._id" <<<"$questions")
    local correct=$(jq ".[$i].correctAnswer" <<<"$questions")

    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":$responseMs}")

    if jq -e '.surfQuizPassed==true or .completed==true' >/dev/null <<<"$resp"; then
      echo "$resp"
      return 0
    fi
  done

  echo "ERROR: Did not pass surf quiz"
  return 1
}

# =============================================================================
# AUTH TESTS
# =============================================================================
test_auth() {
  say "TEST: Authentication"

  cleanup_user

  local resp=$(post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}")
  jq -e '.token and .user._id' >/dev/null <<<"$resp" && ok "Valid login" || bad "Login failed"
  jq -e '.user.progress.add.L1' >/dev/null <<<"$resp" && ok "Progress in response" || bad "Progress missing"
  jq -e '.user.dailyStats' >/dev/null <<<"$resp" && ok "DailyStats in response" || bad "DailyStats missing"

  resp=$(post_json "/auth/login-pin" "{\"pin\":\"$PIN\"}")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects missing name" || bad "Should error"

  resp=$(post_json "/auth/login-pin" "{\"name\":\"$NAME\"}")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects missing PIN" || bad "Should error"

  resp=$(post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"WrongName\"}")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects wrong name" || bad "Should reject wrong name"
}

# =============================================================================
# USER TESTS
# =============================================================================
test_user() {
  say "TEST: User Endpoints"
  reset_user

  local resp=$(get_json "/user/progress")
  jq -e '.progress.add.L1' >/dev/null <<<"$resp" && ok "GET /user/progress" || bad "Progress failed"

  resp=$(get_json "/user/daily")
  jq -e 'has("correctCount")' >/dev/null <<<"$resp" && ok "GET /user/daily" || bad "Daily failed"
  jq -e 'has("grandTotalActiveMs")' >/dev/null <<<"$resp" && ok "grandTotalActiveMs present in daily" || bad "grandTotalActiveMs missing"
  local todayMs=$(jq -r '.totalActiveMs' <<<"$resp")
  local grandMs=$(jq -r '.grandTotalActiveMs' <<<"$resp")
  [[ "$grandMs" -ge "$todayMs" ]] && ok "grandTotalActiveMs >= totalActiveMs" || bad "grandTotalActiveMs ($grandMs) < totalActiveMs ($todayMs)"

  resp=$(post_json "/user/theme" '{"themeKey":"ocean"}')
  jq -e '.success==true or .error' >/dev/null <<<"$resp" && ok "POST /user/theme" || bad "Theme failed"

  resp=$(post_json "/user/rate-video" '{"rating":5,"level":1,"beltOrDegree":"white"}')
  jq -e '.success==true' >/dev/null <<<"$resp" && ok "POST /user/rate-video" || bad "Rate failed"

  resp=$(post_json "/user/reset" '{}')
  jq -e '.success==true' >/dev/null <<<"$resp" && ok "POST /user/reset" || bad "Reset failed"
}

# =============================================================================
# QUIZ BASIC TESTS
# =============================================================================
test_quiz_operations() {
  say "TEST: Quiz Operations (add - others locked until prerequisites met)"
  reset_user

  # Only test add since sub/mul/div require completing prerequisites
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  [[ "$runId" != "null" && -n "$runId" ]] || { bad "Prepare add failed"; return; }
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  jq -e '.questions | length >= 10' >/dev/null <<<"$start" && ok "Operation 'add'" || bad "Operation 'add' failed"
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null

  # Verify locked operations return proper error
  local sub_prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"sub"}')
  jq -e '.error.message' >/dev/null <<<"$sub_prep" && ok "Sub correctly locked (requires add completion)" || bad "Sub should be locked"
}

test_quiz_prepare_start() {
  say "TEST: Quiz Prepare/Start Flow"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Prepare returns quizRunId" || bad "No quizRunId"
  jq -e '.practice | length >= 0' >/dev/null <<<"$prep" && ok "Practice array present" || bad "No practice"

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  jq -e '.questions | length == 10' >/dev/null <<<"$start" && ok "10 questions for colored belt" || bad "Wrong count"
  jq -e '.run.status=="running"' >/dev/null <<<"$start" && ok "Status is running" || bad "Wrong status"
  jq -e '.questions[0]._id' >/dev/null <<<"$start" && ok "Questions have _id" || bad "No _id"
}

test_quiz_wrong_flow() {
  say "TEST: Wrong Answer → Practice Flow"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$((correct + 99))

  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":100}")
  jq -e 'has("practice")' >/dev/null <<<"$wresp" && ok "Wrong triggers practice" || bad "No practice"

  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")

  local pwrong=$((pcorrect + 5))
  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pwrong}")
  jq -e '.stillPracticing==true or has("practice")' >/dev/null <<<"$presp" && ok "Wrong practice continues" || bad "Should continue"

  presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")
  jq -e '.resume==true or has("next")' >/dev/null <<<"$presp" && ok "Correct practice resumes" || bad "Resume failed"
}

test_quiz_inactivity() {
  say "TEST: Inactivity Handling (Normal Mode)"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}" >/dev/null

  local resp=$(post_json "/quiz/inactivity" "{\"quizRunId\":\"$runId\"}")
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Inactivity triggers practice" || bad "No practice"
}

test_quiz_complete_endpoint() {
  say "TEST: Quiz Complete Endpoint"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}" >/dev/null

  local resp=$(post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}")
  jq -e '.completed==true' >/dev/null <<<"$resp" && ok "Complete returns completed=true" || bad "Not completed"
  jq -e '.result' >/dev/null <<<"$resp" && ok "Has result object" || bad "No result"
}

# =============================================================================
# COLORED BELT PROGRESSION TESTS
# =============================================================================
test_colored_progression() {
  say "TEST: Colored Belt Progression"
  reset_user

  local belts=("white" "yellow" "green" "blue" "red" "brown")
  local next_belts=("yellow" "green" "blue" "red" "brown" "black")

  for i in "${!belts[@]}"; do
    local belt="${belts[$i]}"
    local next="${next_belts[$i]}"
    subsay "Completing $belt belt"

    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"add\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local final=$(complete_quiz "$runId" "$start" 30)

    if jq -e '.completed==true and .passed==true' >/dev/null <<<"$final"; then
      ok "$belt completed"
      jq -e 'has("updatedProgress")' >/dev/null <<<"$final" && ok "Progression updated" || bad "No progression"

      local prog=$(get_json "/user/progress")
      if [[ "$next" == "black" ]]; then
        jq -e '.progress.add.L1.black.unlocked==true' >/dev/null <<<"$prog" && ok "Black unlocked" || bad "Black not unlocked"
      else
        jq -e ".progress.add.L1.$next.unlocked==true" >/dev/null <<<"$prog" && ok "$next unlocked" || bad "$next not unlocked"
      fi
    else
      bad "$belt failed"
      break
    fi
  done
}

# =============================================================================
# BLACK BELT TESTS
# =============================================================================
test_black_belt() {
  say "TEST: Black Belt Degrees"
  reset_user

  subsay "Unlocking black belt..."
  local belts=("white" "yellow" "green" "blue" "red" "brown")
  for belt in "${belts[@]}"; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"add\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz "$runId" "$start" 20 >/dev/null
  done

  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.black.unlocked==true' >/dev/null <<<"$prog" && ok "Black unlocked" || { bad "Black not unlocked"; return; }

  subsay "Testing black-1"
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"black-1","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qcount=$(jq '.questions | length' <<<"$start")
  [[ "$qcount" -ge 20 ]] && ok "Black-1 has 20 questions" || bad "Wrong count: $qcount"

  local timelimit=$(jq '.timer.limitMs // 0' <<<"$start")
  [[ "$timelimit" -eq 60000 ]] && ok "Black-1 time limit: 60s" || bad "Wrong time: $timelimit"

  local final=$(complete_quiz "$runId" "$start" 5)
  jq -e '.completed==true' >/dev/null <<<"$final" && ok "Black-1 completed" || bad "Not completed"

  prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.black.completedDegrees | contains([1])' >/dev/null <<<"$prog" && ok "Black-1 recorded" || bad "Degree not recorded"
}

# =============================================================================
# LEVEL PROGRESSION TESTS
# =============================================================================
test_level_progression() {
  say "TEST: Level Progression (L1 → L2)"
  reset_user

  subsay "Completing all L1 belts..."
  local belts=("white" "yellow" "green" "blue" "red" "brown")
  for belt in "${belts[@]}"; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"add\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz "$runId" "$start" 10 >/dev/null
  done

  for degree in 1 2 3 4 5 6 7; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"black-$degree\",\"operation\":\"add\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz "$runId" "$start" 5 >/dev/null
  done

  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.completed==true' >/dev/null <<<"$prog" && ok "L1 completed" || bad "L1 not complete"
  jq -e '.progress.add.L2.unlocked==true' >/dev/null <<<"$prog" && ok "L2 unlocked" || bad "L2 not unlocked"
  jq -e '.progress.add.L2.white.unlocked==true' >/dev/null <<<"$prog" && ok "L2 white unlocked" || bad "L2 white not unlocked"

  local prep=$(post_json "/quiz/prepare" '{"level":2,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId // empty' <<<"$prep")
  [[ -n "$runId" ]] && ok "Can start L2 quiz" || bad "Cannot start L2"
}

# =============================================================================
# GAME MODE TESTS (LIGHTNING MODE)
# =============================================================================
test_gamemode_default_target() {
  say "TEST: Game Mode - Default Target"
  reset_user
  sleep 0.5

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true}')
  local resumed=$(jq '.resumed // false' <<<"$prep")
  if [[ "$resumed" == "true" ]]; then
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
    reset_user
    sleep 0.5
    prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true}')
  fi

  local target=$(jq '.targetCorrect' <<<"$prep")
  [[ "$target" == "$TARGET" ]] && ok "Default targetCorrect=$TARGET" || bad "Default should be $TARGET, got $target"

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

test_gamemode_basic() {
  say "TEST: Game Mode - Basic Flow"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")

  jq -e '.gameMode==true' >/dev/null <<<"$prep" && ok "gameMode=true" || bad "gameMode not set"
  jq -e ".targetCorrect==$TARGET" >/dev/null <<<"$prep" && ok "targetCorrect=$TARGET" || bad "targetCorrect wrong"
  jq -e '.totalCorrect==0' >/dev/null <<<"$prep" && ok "totalCorrect=0" || bad "totalCorrect not 0"

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  jq -e '.gameMode==true' >/dev/null <<<"$start" && ok "gameMode in start" || bad "gameMode missing"
  jq -e '.questions | length == 10' >/dev/null <<<"$start" && ok "10 questions" || bad "Wrong count"
}

test_gamemode_cycling() {
  say "TEST: Game Mode - Question Cycling"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local answersToSend=$((TARGET - 1))
  subsay "Answering $answersToSend questions (less than target to test cycling)"
  local totalCorrect=0
  for i in $(seq 0 $((answersToSend - 1))); do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local ans=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10}")
    totalCorrect=$((totalCorrect + 1))
    local respTotal=$(jq '.totalCorrect' <<<"$resp")
    [[ "$respTotal" == "$totalCorrect" ]] || { bad "totalCorrect mismatch at $i"; return; }
  done
  ok "Questions cycle correctly"
  ok "totalCorrect increments properly"
}

# UPDATED: Lightning mode no longer awards belt (surf mode required)
test_gamemode_completion() {
  say "TEST: Game Mode - Lightning Completion (No Belt - Surf Required)"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  subsay "Completing lightning mode..."
  local final=$(complete_game_mode "$runId" "$start" "$TARGET" 20)

  jq -e '.completed==true' >/dev/null <<<"$final" && ok "completed=true" || bad "Not completed"
  jq -e '.passed==true' >/dev/null <<<"$final" && ok "passed=true" || bad "Not passed"
  jq -e '.gameModeType=="lightning"' >/dev/null <<<"$final" && ok "gameModeType=lightning" || bad "gameModeType wrong"
  jq -e '.lightningComplete==true' >/dev/null <<<"$final" && ok "lightningComplete=true" || bad "lightningComplete missing"
  jq -e '.surfRequired==true' >/dev/null <<<"$final" && ok "surfRequired=true" || bad "surfRequired missing"

  # KEY CHANGE: Lightning should NOT award belt anymore
  jq -e 'has("beltAwarded") | not' >/dev/null <<<"$final" && ok "No beltAwarded (surf required)" || bad "Should not award belt"
  jq -e 'has("updatedProgress") | not' >/dev/null <<<"$final" && ok "No updatedProgress (surf required)" || bad "Should not update progress"

  # Verify progression - yellow should NOT be unlocked yet
  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.yellow.unlocked==false' >/dev/null <<<"$prog" && ok "Yellow still locked" || bad "Yellow should be locked until surf"
}

# NEW: Complete Lightning + Surf for full belt progression
test_gamemode_full_flow() {
  say "TEST: Game Mode - Full Flow (Lightning + Surf)"
  reset_user

  # Step 1: Complete Lightning Mode
  subsay "Step 1: Completing Lightning Mode..."
  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"gameModeType\":\"lightning\",\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local lightning=$(complete_game_mode "$runId" "$start" "$TARGET" 10)

  jq -e '.lightningComplete==true' >/dev/null <<<"$lightning" && ok "Lightning completed" || { bad "Lightning failed"; return; }
  jq -e '.surfRequired==true' >/dev/null <<<"$lightning" && ok "Surf required" || bad "surfRequired missing"

  # Step 2: Start Surf Mode
  subsay "Step 2: Starting Surf Mode..."
  prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  runId=$(jq -r '.quizRunId' <<<"$prep")

  jq -e '.gameModeType=="surf"' >/dev/null <<<"$prep" && ok "Surf mode started" || { bad "Surf mode failed"; return; }

  # Step 3: Complete 5 Surf Quizzes
  subsay "Step 3: Completing 5 Surf Quizzes..."
  local lastResp=""
  for quiz in 1 2 3 4 5; do
    start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local questions=$(jq '.questions' <<<"$start")
    lastResp=$(complete_surf_quiz "$runId" "$questions")

    if jq -e '.completed==true' >/dev/null <<<"$lastResp"; then
      break
    fi
  done

  # Step 4: Verify Completion
  subsay "Step 4: Verifying Completion..."
  jq -e '.completed==true' >/dev/null <<<"$lastResp" && ok "Surf mode completed" || bad "Surf not completed"
  jq -e '.passed==true' >/dev/null <<<"$lastResp" && ok "passed=true" || bad "Not passed"
  jq -e '.beltAwarded==false' >/dev/null <<<"$lastResp" && ok "beltAwarded=false (belt after bonus)" || bad "beltAwarded should be false"
  jq -e 'has("updatedProgress") | not' >/dev/null <<<"$lastResp" && ok "No updatedProgress (belt after bonus)" || bad "Should not update progress"

  # Step 5: Verify Progression — no belt awarded yet, progression unchanged until rocket completes
  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.yellow.unlocked==false' >/dev/null <<<"$prog" && ok "Yellow still locked (rocket required)" || bad "Yellow should be locked until rocket"
}

test_gamemode_resume() {
  say "TEST: Game Mode - Resume"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"yellow\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer only 2 questions (less than TARGET) to keep quiz active for resume
  for i in 0 1; do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local ans=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10}" >/dev/null
  done

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":false}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "resumed=true" || bad "Should resume"
  jq -e '.gameMode==true' >/dev/null <<<"$prep2" && ok "Returns game mode" || bad "Should return game mode"
  jq -e '.totalCorrect==2' >/dev/null <<<"$prep2" && ok "totalCorrect=2" || bad "totalCorrect wrong"

  local retBelt=$(jq -r '.beltOrDegree' <<<"$prep2")
  [[ "$retBelt" == "yellow" ]] && ok "Original belt returned" || bad "Wrong belt: $retBelt"
}

test_gamemode_daily_stats() {
  say "TEST: Game Mode - Daily Stats"
  reset_user

  local initial=$(get_json "/user/daily")
  local initialCorrect=$(jq '.correctCount // 0' <<<"$initial")

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local expected=0
  local currentIdx=0
  for i in $(seq 0 9); do
    local qid=$(jq -r ".questions[$currentIdx]._id" <<<"$start")
    local correct=$(jq ".questions[$currentIdx].correctAnswer" <<<"$start")
    local answer=$correct
    if [[ $i -eq 1 || $i -eq 4 || $i -eq 8 ]]; then
      answer=$((correct + 99))
    else
      expected=$((expected + 1))
    fi
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$answer,\"responseMs\":10}")

    if jq -e '.completed==true' >/dev/null <<<"$resp"; then
      break
    fi

    if jq -e 'has("practice")' >/dev/null <<<"$resp"; then
      local pid=$(jq -r '.practice._id' <<<"$resp")
      local pcorrect=$(jq '.practice.correctAnswer' <<<"$resp")
      post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}" >/dev/null
    fi

    currentIdx=$(( (currentIdx + 1) % 10 ))
  done

  sleep 0.5
  local after=$(get_json "/user/daily")
  local afterCorrect=$(jq '.correctCount // 0' <<<"$after")
  local diff=$((afterCorrect - initialCorrect))

  [[ "$diff" -eq "$expected" ]] && ok "Daily correctCount +$expected" || bad "Expected +$expected, got +$diff"
}

test_gamemode_black_belt() {
  say "TEST: Game Mode - Black Belt"
  reset_user

  subsay "Unlocking black belt..."
  local belts=("white" "yellow" "green" "blue" "red" "brown")
  for belt in "${belts[@]}"; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"add\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qid=$(jq -r '.questions[0]._id' <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" >/dev/null
  done
  ok "Colored belts completed"

  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.black.unlocked==true' >/dev/null <<<"$prog" && ok "Black unlocked" || { bad "Black not unlocked"; return; }

  subsay "Starting black-1 game mode"
  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"black-1\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  jq -e '.gameMode==true' >/dev/null <<<"$prep" && ok "gameMode=true" || bad "gameMode not set"

  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qcount=$(jq '.questions | length' <<<"$start")
  [[ "$qcount" -eq 20 ]] && ok "20 questions for black" || bad "Wrong count: $qcount"

  subsay "Completing black-1 game mode"
  local final=$(complete_game_mode "$runId" "$start" "$TARGET" 15)

  jq -e '.completed==true and .passed==true' >/dev/null <<<"$final" && ok "Black-1 passed" || bad "Black-1 failed"
  jq -e 'has("updatedProgress")' >/dev/null <<<"$final" && ok "Progression updated" || bad "No progression"

  prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.black.completedDegrees | contains([1])' >/dev/null <<<"$prog" && ok "black-1 recorded" || bad "Not recorded"
}

# =============================================================================
# GAME MODE - WRONG ANSWER TRIGGERS PRACTICE
# =============================================================================
test_gamemode_wrong_triggers_practice() {
  say "TEST: Game Mode - Wrong Answer Triggers Practice"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$((correct + 99))

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")

  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Wrong triggers practice" || bad "Practice not triggered"
  jq -e '.reason == "wrong"' >/dev/null <<<"$resp" && ok "reason=wrong" || bad "Wrong reason"
  jq -e '.gameMode == true' >/dev/null <<<"$resp" && ok "gameMode=true" || bad "gameMode missing"
  jq -e '.totalCorrect == 0' >/dev/null <<<"$resp" && ok "totalCorrect unchanged" || bad "totalCorrect changed"
  jq -e '.targetCorrect' >/dev/null <<<"$resp" && ok "targetCorrect present" || bad "targetCorrect missing"
  jq -e 'has("nextIndex") | not' >/dev/null <<<"$resp" && ok "No nextIndex (practice required)" || bad "Should not have nextIndex"
}

# =============================================================================
# GAME MODE - PRACTICE FLOW
# =============================================================================
test_gamemode_practice_flow() {
  say "TEST: Game Mode - Practice Flow"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid0=$(jq -r '.questions[0]._id' <<<"$start")
  local ans0=$(jq '.questions[0].correctAnswer' <<<"$start")
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid0\",\"answer\":$ans0,\"responseMs\":500}" >/dev/null

  local qid1=$(jq -r '.questions[1]._id' <<<"$start")
  local correct1=$(jq '.questions[1].correctAnswer' <<<"$start")
  local wrong1=$((correct1 + 99))

  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid1\",\"answer\":$wrong1,\"responseMs\":500}")

  jq -e 'has("practice")' >/dev/null <<<"$wresp" || { bad "Practice not triggered"; return; }
  ok "Practice triggered on wrong"

  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")

  local pwrong=$((pcorrect + 5))
  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pwrong}")

  jq -e '.stillPracticing == true or has("practice")' >/dev/null <<<"$presp" && ok "Wrong practice continues" || bad "Should continue practice"
  jq -e '.gameMode == true' >/dev/null <<<"$presp" && ok "gameMode in practice response" || bad "gameMode missing"
  jq -e '.totalCorrect' >/dev/null <<<"$presp" && ok "totalCorrect in practice response" || bad "totalCorrect missing"

  presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")

  jq -e '.resume == true' >/dev/null <<<"$presp" && ok "Correct practice resumes quiz" || bad "Should resume"
  jq -e 'has("next")' >/dev/null <<<"$presp" && ok "Has next question" || bad "Missing next question"
  jq -e 'has("nextIndex")' >/dev/null <<<"$presp" && ok "Has nextIndex" || bad "Missing nextIndex"
  jq -e '.gameMode == true' >/dev/null <<<"$presp" && ok "gameMode after practice" || bad "gameMode missing"

  local nextIdx=$(jq '.nextIndex' <<<"$presp")
  [[ "$nextIdx" -eq 2 ]] && ok "nextIndex=2 (advanced after practice)" || bad "nextIndex wrong: $nextIdx"
}

# =============================================================================
# GAME MODE - INACTIVITY TRIGGERS PRACTICE
# =============================================================================
test_gamemode_inactivity_triggers_practice() {
  say "TEST: Game Mode - Inactivity Triggers Practice"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local resp=$(post_json "/quiz/inactivity" "{\"quizRunId\":\"$runId\"}")

  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Inactivity triggers practice" || bad "Practice not triggered"
  jq -e '.gameMode == true' >/dev/null <<<"$resp" && ok "gameMode=true" || bad "gameMode missing"
  jq -e '.totalCorrect' >/dev/null <<<"$resp" && ok "totalCorrect present" || bad "totalCorrect missing"
  jq -e '.targetCorrect' >/dev/null <<<"$resp" && ok "targetCorrect present" || bad "targetCorrect missing"
}

# =============================================================================
# GAME MODE - INACTIVITY VIA SLOW RESPONSE (>5s)
# =============================================================================
test_gamemode_inactivity_via_slow_response() {
  say "TEST: Game Mode - Inactivity via Slow Response (>5s)"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":6000}")

  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Slow response (>5s) triggers practice" || bad "Practice not triggered"
  jq -e '.reason == "inactivity"' >/dev/null <<<"$resp" && ok "reason=inactivity" || bad "Wrong reason"
  jq -e '.gameMode == true' >/dev/null <<<"$resp" && ok "gameMode=true" || bad "gameMode missing"
  jq -e '.totalCorrect == 0' >/dev/null <<<"$resp" && ok "totalCorrect unchanged" || bad "totalCorrect changed"
}

# =============================================================================
# GAME MODE - FAST/SLOW ANSWER TESTS
# =============================================================================
test_gamemode_fast_answer() {
  say "TEST: Game Mode - Fast Answer (<1.5s) Counts Toward Target"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":1000}")

  jq -e '.totalCorrect == 1' >/dev/null <<<"$resp" && ok "Fast answer: totalCorrect=1" || bad "totalCorrect should be 1"
  jq -e 'has("slow") | not' >/dev/null <<<"$resp" && ok "No slow flag" || bad "Should not have slow flag"
  jq -e '.gameMode == true' >/dev/null <<<"$resp" && ok "gameMode=true" || bad "gameMode missing"
  jq -e 'has("nextIndex")' >/dev/null <<<"$resp" && ok "Has nextIndex" || bad "Missing nextIndex"
  jq -e 'has("dailyStats")' >/dev/null <<<"$resp" && ok "Has dailyStats" || bad "Missing dailyStats"
}

test_gamemode_slow_answer() {
  say "TEST: Game Mode - Slow Answer (>=2s) Does Not Count Toward Target"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":2500}")

  jq -e '.totalCorrect == 0' >/dev/null <<<"$resp" && ok "Slow answer: totalCorrect=0" || bad "totalCorrect should be 0"
  jq -e '.slow == true' >/dev/null <<<"$resp" && ok "slow=true flag present" || bad "Missing slow flag"
  jq -e '.gameMode == true' >/dev/null <<<"$resp" && ok "gameMode=true" || bad "gameMode missing"
  jq -e 'has("nextIndex")' >/dev/null <<<"$resp" && ok "Has nextIndex (no practice needed)" || bad "Missing nextIndex"
  jq -e 'has("practice") | not' >/dev/null <<<"$resp" && ok "No practice (answer was correct)" || bad "Should not trigger practice"
}

test_gamemode_slow_answer_boundary() {
  say "TEST: Game Mode - Slow Answer Boundary (exactly 2000ms)"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":2000}")

  jq -e '.totalCorrect == 0' >/dev/null <<<"$resp" && ok "2000ms: totalCorrect=0 (slow)" || bad "Should be slow"
  jq -e '.slow == true' >/dev/null <<<"$resp" && ok "2000ms: slow=true" || bad "Missing slow flag"
}

test_gamemode_fast_answer_boundary() {
  say "TEST: Game Mode - Fast Answer Boundary (1999ms)"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":1999}")

  jq -e '.totalCorrect == 1' >/dev/null <<<"$resp" && ok "1999ms: totalCorrect=1 (fast)" || bad "Should be fast"
  jq -e 'has("slow") | not' >/dev/null <<<"$resp" && ok "1999ms: no slow flag" || bad "Should not have slow flag"
}

test_gamemode_daily_stats_all_correct() {
  say "TEST: Game Mode - Daily Stats Tracks All Correct (Fast and Slow)"
  reset_user

  local initial=$(get_json "/user/daily")
  local initialCorrect=$(jq '.correctCount // 0' <<<"$initial")

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2; do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local correct=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":1000}" >/dev/null
  done

  for i in 3 4; do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local correct=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":2000}" >/dev/null
  done

  local status=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true}")
  local totalCorrect=$(jq '.totalCorrect' <<<"$status")
  [[ "$totalCorrect" -eq 3 ]] && ok "totalCorrect=3 (only fast answers)" || bad "totalCorrect should be 3, got $totalCorrect"

  sleep 0.5

  local after=$(get_json "/user/daily")
  local afterCorrect=$(jq '.correctCount // 0' <<<"$after")
  local diff=$((afterCorrect - initialCorrect))

  [[ "$diff" -eq 5 ]] && ok "dailyStats +5 (all correct answers)" || bad "dailyStats should be +5, got +$diff"
}

test_gamemode_mixed_flow() {
  say "TEST: Game Mode - Mixed Fast/Slow/Wrong Flow"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local totalCorrectExpected=0
  local dailyCorrectExpected=0

  subsay "Q0: Fast correct (1000ms)"
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":1000}")
  totalCorrectExpected=1
  dailyCorrectExpected=1
  jq -e ".totalCorrect == $totalCorrectExpected" >/dev/null <<<"$resp" && ok "After Q0: totalCorrect=$totalCorrectExpected" || bad "totalCorrect wrong"

  subsay "Q1: Slow correct (2000ms)"
  qid=$(jq -r '.questions[1]._id' <<<"$start")
  correct=$(jq '.questions[1].correctAnswer' <<<"$start")
  resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":2000}")
  dailyCorrectExpected=2
  jq -e ".totalCorrect == $totalCorrectExpected" >/dev/null <<<"$resp" && ok "After Q1: totalCorrect=$totalCorrectExpected (unchanged)" || bad "totalCorrect wrong"
  jq -e '.slow == true' >/dev/null <<<"$resp" && ok "Q1 marked as slow" || bad "Missing slow flag"

  subsay "Q2: Wrong answer"
  qid=$(jq -r '.questions[2]._id' <<<"$start")
  correct=$(jq '.questions[2].correctAnswer' <<<"$start")
  local wrong=$((correct + 99))
  resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":1000}")
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Q2 triggers practice" || bad "Practice not triggered"
  jq -e ".totalCorrect == $totalCorrectExpected" >/dev/null <<<"$resp" && ok "After Q2: totalCorrect=$totalCorrectExpected (unchanged)" || bad "totalCorrect wrong"

  local pid=$(jq -r '.practice._id' <<<"$resp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$resp")
  resp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")
  jq -e '.resume == true' >/dev/null <<<"$resp" && ok "Practice completed, resumed" || bad "Should resume"

  subsay "Q3: Fast correct after practice"
  qid=$(jq -r '.questions[3]._id' <<<"$start")
  correct=$(jq '.questions[3].correctAnswer' <<<"$start")
  resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":800}")
  totalCorrectExpected=2
  dailyCorrectExpected=3
  jq -e ".totalCorrect == $totalCorrectExpected" >/dev/null <<<"$resp" && ok "After Q3: totalCorrect=$totalCorrectExpected" || bad "totalCorrect wrong"

  ok "Mixed flow handled correctly"
}

test_gamemode_completion_requires_fast() {
  say "TEST: Game Mode - Completion Requires Fast Answers"
  reset_user

  local smallTarget=5
  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$smallTarget}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in $(seq 0 9); do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local correct=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":2000}")

    if jq -e '.completed == true' >/dev/null <<<"$resp"; then
      bad "Should NOT complete with slow answers only"
      return
    fi
  done
  ok "Did not complete with 10 slow correct answers"

  local status=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true}")
  local totalCorrect=$(jq '.totalCorrect' <<<"$status")
  [[ "$totalCorrect" -eq 0 ]] && ok "totalCorrect=0 after slow answers" || bad "totalCorrect should be 0"

  for i in $(seq 0 $((smallTarget - 1))); do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local correct=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")

    if jq -e '.completed == true' >/dev/null <<<"$resp"; then
      jq -e '.passed == true' >/dev/null <<<"$resp" && ok "Completed with fast answers" || bad "Should pass"
      jq -e ".totalCorrect == $smallTarget" >/dev/null <<<"$resp" && ok "totalCorrect=$smallTarget on completion" || bad "totalCorrect wrong"
      return
    fi
  done
  bad "Should have completed after $smallTarget fast answers"
}

test_gamemode_prepare_has_practice() {
  say "TEST: Game Mode - Prepare Returns Practice Questions"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"yellow\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")

  jq -e '.gameMode == true' >/dev/null <<<"$prep" && ok "gameMode=true" || bad "gameMode missing"
  jq -e '.practice | type == "array"' >/dev/null <<<"$prep" && ok "practice is array" || bad "practice not array"

  local practiceLen=$(jq '.practice | length' <<<"$prep")
  [[ "$practiceLen" -ge 1 ]] && ok "Has practice questions: $practiceLen" || bad "No practice questions"

  if [[ "$practiceLen" -ge 1 ]]; then
    jq -e '.practice[0]._id' >/dev/null <<<"$prep" && ok "Practice has _id" || bad "Missing _id"
    jq -e '.practice[0].question' >/dev/null <<<"$prep" && ok "Practice has question" || bad "Missing question"
    jq -e '.practice[0].correctAnswer' >/dev/null <<<"$prep" && ok "Practice has correctAnswer" || bad "Missing correctAnswer"
    jq -e '.practice[0].choices' >/dev/null <<<"$prep" && ok "Practice has choices" || bad "Missing choices"
  fi
}

test_gamemode_black_no_practice_in_prepare() {
  say "TEST: Game Mode - Black Belt No Practice in Prepare"
  reset_user

  local belts=("white" "yellow" "green" "blue" "red" "brown")
  for belt in "${belts[@]}"; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"add\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qid=$(jq -r '.questions[0]._id' <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" >/dev/null
  done

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"black-1\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")

  jq -e '.gameMode == true' >/dev/null <<<"$prep" && ok "gameMode=true" || bad "gameMode missing"

  local practiceLen=$(jq '.practice | length' <<<"$prep")
  [[ "$practiceLen" -eq 0 ]] && ok "No practice for black belt" || bad "Black belt should have no practice in prepare"
}

test_gamemode_black_wrong_triggers_practice() {
  say "TEST: Game Mode - Black Belt Wrong Still Triggers Practice"
  reset_user

  local belts=("white" "yellow" "green" "blue" "red" "brown")
  for belt in "${belts[@]}"; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"add\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qid=$(jq -r '.questions[0]._id' <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" >/dev/null
  done

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"black-1\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$((correct + 99))

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")

  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Black belt wrong triggers practice" || bad "Practice not triggered"
  jq -e '.gameMode == true' >/dev/null <<<"$resp" && ok "gameMode=true" || bad "gameMode missing"
}

# =============================================================================
# FORCEPASS TESTS
# =============================================================================
test_forcepass_normal_mode() {
  say "TEST: ForcePass - Normal Mode"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":50}" >/dev/null
  done

  local qid=$(jq -r '.questions[2]._id' <<<"$start")
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":50,\"forcePass\":true}")

  jq -e '.completed==true' >/dev/null <<<"$resp" && ok "completed=true" || bad "Not completed"
  jq -e '.passed==true' >/dev/null <<<"$resp" && ok "passed=true" || bad "Not passed"
  jq -e '.forcePass==true' >/dev/null <<<"$resp" && ok "forcePass=true" || bad "forcePass missing"
  jq -e 'has("updatedProgress")' >/dev/null <<<"$resp" && ok "Progression updated" || bad "No progression"

  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.white.completed==true' >/dev/null <<<"$prog" && ok "White complete" || bad "White not complete"
  jq -e '.progress.add.L1.yellow.unlocked==true' >/dev/null <<<"$prog" && ok "Yellow unlocked" || bad "Yellow not unlocked"
}

test_forcepass_gamemode() {
  say "TEST: ForcePass - Game Mode Stats Integrity"
  reset_user

  local initialDaily=$(get_json "/user/daily")
  local initialCorrect=$(jq '.correctCount // 0' <<<"$initialDaily")

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2; do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local ans=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10}" >/dev/null
  done

  local qid=$(jq -r '.questions[3]._id' <<<"$start")
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}")

  jq -e '.completed==true' >/dev/null <<<"$resp" && ok "completed=true" || bad "Not completed"
  jq -e '.forcePass==true' >/dev/null <<<"$resp" && ok "forcePass=true" || bad "forcePass missing"
  jq -e ".totalCorrect==$TARGET" >/dev/null <<<"$resp" && ok "totalCorrect=$TARGET" || bad "totalCorrect wrong"
  jq -e '.actualCorrectBeforeForce==3' >/dev/null <<<"$resp" && ok "actualCorrectBeforeForce=3" || bad "actualCorrectBeforeForce wrong"
  jq -e 'has("updatedProgress")' >/dev/null <<<"$resp" && ok "Progression updated" || bad "No progression"

  sleep 0.5
  local afterDaily=$(get_json "/user/daily")
  local afterCorrect=$(jq '.correctCount // 0' <<<"$afterDaily")
  local diff=$((afterCorrect - initialCorrect))

  [[ "$diff" -eq 4 ]] && ok "Daily stats +4 (not $TARGET)" || bad "Daily stats wrong: +$diff"
}

test_forcepass_first_question() {
  say "TEST: ForcePass - First Question"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":50,\"forcePass\":true}")

  jq -e '.completed==true and .passed==true' >/dev/null <<<"$resp" && ok "ForcePass Q1 works" || bad "Failed"

  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.yellow.completed==true' >/dev/null <<<"$prog" && ok "Yellow complete" || bad "Yellow not complete"
}

test_forcepass_chain() {
  say "TEST: ForcePass - Chain Multiple Belts"
  reset_user

  local belts=("white" "yellow" "green" "blue" "red")
  for belt in "${belts[@]}"; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"add\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qid=$(jq -r '.questions[0]._id' <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}")
    jq -e '.passed==true' >/dev/null <<<"$resp" && ok "$belt passed" || bad "$belt failed"
  done

  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.brown.unlocked==true' >/dev/null <<<"$prog" && ok "Brown unlocked via chain" || bad "Brown not unlocked"
}

# =============================================================================
# RESUME TESTS
# =============================================================================
test_resume_normal_partial() {
  say "TEST: Resume - Normal Mode Partial"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2 3; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":50}" >/dev/null
  done

  sleep 0.5
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"

  local retIndex=$(jq '.currentIndex // 0' <<<"$prep2")
  [[ "$retIndex" -ge 4 ]] && ok "currentIndex >= 4" || bad "currentIndex wrong: $retIndex"
}

test_resume_gamemode_across_sessions() {
  say "TEST: Resume - Game Mode Across Sessions"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"yellow\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer only 2 questions (less than TARGET) to keep quiz active for resume
  for i in 0 1; do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local ans=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10}" >/dev/null
  done

  sleep 0.5
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"green","operation":"add","gameMode":false}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.gameMode==true' >/dev/null <<<"$prep2" && ok "Game mode returned" || bad "Should return game mode"
  jq -e '.totalCorrect==2' >/dev/null <<<"$prep2" && ok "totalCorrect=2" || bad "totalCorrect wrong"

  local retBelt=$(jq -r '.beltOrDegree' <<<"$prep2")
  [[ "$retBelt" == "yellow" ]] && ok "Original belt returned" || bad "Wrong belt: $retBelt"
}

test_resume_after_wrong() {
  say "TEST: Resume - After Wrong (Practice Pending)"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":50}" >/dev/null
  done

  local qid=$(jq -r '.questions[2]._id' <<<"$start")
  local correct=$(jq '.questions[2].correctAnswer' <<<"$start")
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$((correct + 99)),\"responseMs\":50}" >/dev/null

  sleep 0.5
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"

  local retWrong=$(jq '.wrong // 0' <<<"$prep2")
  [[ "$retWrong" -ge 1 ]] && ok "Wrong count preserved" || bad "Wrong count lost"
}

test_resume_different_belt() {
  say "TEST: Resume - Different Belt No Conflict"
  reset_user

  local prep1=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId1=$(jq -r '.quizRunId' <<<"$prep1")
  local start1=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId1\"}")

  for i in $(seq 0 9); do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start1")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start1")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId1\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":30}" >/dev/null
  done

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"add"}')
  jq -e '.resumed==true' >/dev/null <<<"$prep2" && bad "Should not resume different belt" || ok "Yellow is fresh"

  local runId2=$(jq -r '.quizRunId' <<<"$prep2")
  [[ "$runId2" != "$runId1" ]] && ok "Different runId" || bad "Same runId"
}

# =============================================================================
# SURF MODE (GAME MODE 2) TESTS - "Don't Fall off the Surfboard"
# =============================================================================

# --- SURF MODE - PREREQUISITE TESTS ---
test_surf_requires_lightning() {
  say "TEST: Surf Mode - Requires Lightning Completion"
  reset_user

  local resp=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')

  jq -e '.error.message' >/dev/null <<<"$resp" && ok "Surf mode blocked without lightning" || bad "Should block surf mode"

  local msg=$(jq -r '.error.message // ""' <<<"$resp")
  [[ "$msg" == *"Lightning mode must be completed"* ]] && ok "Correct error message" || bad "Wrong error: $msg"
}

test_surf_unlocks_after_lightning() {
  say "TEST: Surf Mode - Unlocks After Lightning"
  reset_user

  subsay "Completing lightning mode..."
  # Use natural completion (without forcePass) to test real flow
  local lightning=$(complete_lightning_mode_natural 1 "white" "add" "$TARGET")

  jq -e '.completed==true and .passed==true' >/dev/null <<<"$lightning" && ok "Lightning completed" || { bad "Lightning failed"; return; }
  jq -e '.surfRequired==true' >/dev/null <<<"$lightning" && ok "surfRequired=true in response" || bad "surfRequired missing"

  subsay "Starting surf mode..."
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')

  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Surf mode available" || bad "Surf mode blocked"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$prep" && ok "gameModeType=surf" || bad "Wrong gameModeType"
  jq -e '.surfQuizNumber==1' >/dev/null <<<"$prep" && ok "surfQuizNumber=1" || bad "Wrong surfQuizNumber"
  jq -e '.completedSurfQuizzes==0' >/dev/null <<<"$prep" && ok "completedSurfQuizzes=0" || bad "Wrong completedSurfQuizzes"
}

# --- SURF MODE - PREPARE TESTS ---
test_surf_prepare_response_fields() {
  say "TEST: Surf Mode - Prepare Response Fields"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')

  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Has quizRunId" || bad "Missing quizRunId"
  jq -e '.gameMode==true' >/dev/null <<<"$prep" && ok "gameMode=true" || bad "gameMode wrong"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$prep" && ok "gameModeType=surf" || bad "gameModeType wrong"
  jq -e '.surfQuizNumber' >/dev/null <<<"$prep" && ok "Has surfQuizNumber" || bad "Missing surfQuizNumber"
  jq -e '.surfCorrectStreak==0' >/dev/null <<<"$prep" && ok "surfCorrectStreak=0" || bad "surfCorrectStreak wrong"
  jq -e '.completedSurfQuizzes==0' >/dev/null <<<"$prep" && ok "completedSurfQuizzes=0" || bad "completedSurfQuizzes wrong"
  jq -e ".surfQuizzesRequired==$SURF_QUIZZES_REQUIRED" >/dev/null <<<"$prep" && ok "surfQuizzesRequired=$SURF_QUIZZES_REQUIRED" || bad "surfQuizzesRequired wrong"
  jq -e '.practice | type=="array"' >/dev/null <<<"$prep" && ok "Has practice array" || bad "Missing practice"
}

test_surf_prepare_resume() {
  say "TEST: Surf Mode - Resume Existing Run"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep1=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep1")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$prep2" && ok "gameModeType=surf" || bad "Wrong gameModeType"
  jq -e '.surfCorrectStreak==2' >/dev/null <<<"$prep2" && ok "surfCorrectStreak=2 preserved" || bad "Streak not preserved"

  local retRunId=$(jq -r '.quizRunId' <<<"$prep2")
  [[ "$retRunId" == "$runId" ]] && ok "Same runId returned" || bad "Different runId"
}

# --- SURF MODE - START TESTS ---
test_surf_start_response() {
  say "TEST: Surf Mode - Start Response"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qcount=$(jq '.questions | length' <<<"$start")
  [[ "$qcount" -eq $SURF_QUESTIONS_PER_QUIZ ]] && ok "$SURF_QUESTIONS_PER_QUIZ questions for surf quiz" || bad "Wrong count: $qcount"

  local emptyChoices=$(jq '[.questions[] | select(.choices | length == 0)] | length' <<<"$start")
  [[ "$emptyChoices" -eq $SURF_QUESTIONS_PER_QUIZ ]] && ok "All questions have empty choices (type-in)" || bad "Not all type-in"

  jq -e '.gameModeType=="surf"' >/dev/null <<<"$start" && ok "gameModeType=surf" || bad "gameModeType wrong"
  jq -e '.surfQuizNumber==1' >/dev/null <<<"$start" && ok "surfQuizNumber=1" || bad "surfQuizNumber wrong"
  jq -e '.surfCorrectStreak==0' >/dev/null <<<"$start" && ok "surfCorrectStreak=0" || bad "surfCorrectStreak wrong"
  jq -e ".questionsPerQuiz==$SURF_QUESTIONS_PER_QUIZ" >/dev/null <<<"$start" && ok "questionsPerQuiz=$SURF_QUESTIONS_PER_QUIZ" || bad "questionsPerQuiz wrong"
}

test_surf_questions_structure() {
  say "TEST: Surf Mode - Question Structure"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2 3; do
    local q=$(jq ".questions[$i]" <<<"$start")
    jq -e '._id' >/dev/null <<<"$q" && ok "Q$i has _id" || bad "Q$i missing _id"
    jq -e '.question' >/dev/null <<<"$q" && ok "Q$i has question string" || bad "Q$i missing question"
    jq -e '.correctAnswer != null' >/dev/null <<<"$q" && ok "Q$i has correctAnswer" || bad "Q$i missing correctAnswer"
    jq -e '.choices | length == 0' >/dev/null <<<"$q" && ok "Q$i has empty choices" || bad "Q$i should have empty choices"
  done
}

# --- SURF MODE - ANSWER TESTS ---
test_surf_correct_answer() {
  say "TEST: Surf Mode - Correct Answer"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")

  jq -e '.correct==true' >/dev/null <<<"$resp" && ok "correct=true" || bad "correct not true"
  jq -e '.nextIndex==1' >/dev/null <<<"$resp" && ok "nextIndex=1" || bad "nextIndex wrong"
  jq -e '.surfCorrectStreak==1' >/dev/null <<<"$resp" && ok "surfCorrectStreak=1" || bad "surfCorrectStreak wrong"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$resp" && ok "gameModeType=surf" || bad "gameModeType wrong"
  jq -e 'has("dailyStats")' >/dev/null <<<"$resp" && ok "Has dailyStats" || bad "Missing dailyStats"
}

test_surf_streak_increments() {
  say "TEST: Surf Mode - Streak Increments"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")

    local expectedStreak=$((i + 1))
    local actualStreak=$(jq '.surfCorrectStreak' <<<"$resp")
    [[ "$actualStreak" -eq "$expectedStreak" ]] && ok "After Q$i: streak=$expectedStreak" || bad "Streak wrong: $actualStreak"
  done
}

test_surf_wrong_answer_fails_quiz() {
  say "TEST: Surf Mode - Wrong Answer Fails Quiz"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  local qid=$(jq -r '.questions[2]._id' <<<"$start")
  local correct=$(jq '.questions[2].correctAnswer' <<<"$start")
  local wrong=$((correct + 99))

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")

  jq -e '.surfFailed==true' >/dev/null <<<"$resp" && ok "surfFailed=true" || bad "surfFailed missing"
  jq -e '.reason=="wrong"' >/dev/null <<<"$resp" && ok "reason=wrong" || bad "reason wrong"
  jq -e '.correctAnswer' >/dev/null <<<"$resp" && ok "Has correctAnswer" || bad "Missing correctAnswer"
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Has practice question" || bad "Missing practice"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$resp" && ok "gameModeType=surf" || bad "gameModeType wrong"
  jq -e '.surfQuizFailures==1' >/dev/null <<<"$resp" && ok "surfQuizFailures=1" || bad "surfQuizFailures wrong"

  local practiceChoices=$(jq '.practice.choices | length' <<<"$resp")
  [[ "$practiceChoices" -ge 4 ]] && ok "Practice has choices" || bad "Practice missing choices"
}

test_surf_inactivity_fails_quiz() {
  say "TEST: Surf Mode - Inactivity Fails Quiz"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":6000}")

  jq -e '.surfFailed==true' >/dev/null <<<"$resp" && ok "surfFailed=true (inactivity)" || bad "surfFailed missing"
  jq -e '.reason=="inactivity"' >/dev/null <<<"$resp" && ok "reason=inactivity" || bad "reason wrong"
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Has practice question" || bad "Missing practice"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$resp" && ok "gameModeType=surf" || bad "gameModeType wrong"
}

test_surf_inactivity_endpoint() {
  say "TEST: Surf Mode - Inactivity Endpoint"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}" >/dev/null

  local resp=$(post_json "/quiz/inactivity" "{\"quizRunId\":\"$runId\"}")

  jq -e '.surfFailed==true' >/dev/null <<<"$resp" && ok "surfFailed=true via endpoint" || bad "surfFailed missing"
  jq -e '.reason=="inactivity"' >/dev/null <<<"$resp" && ok "reason=inactivity" || bad "reason wrong"
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Has practice question" || bad "Missing practice"
}

# --- SURF MODE - QUIZ PASS TESTS ---
test_surf_quiz_pass() {
  say "TEST: Surf Mode - Quiz Passes on 4 Consecutive Correct"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local lastResp=""
  for i in 0 1 2 3; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    lastResp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")
  done

  jq -e '.surfQuizPassed==true' >/dev/null <<<"$lastResp" && ok "surfQuizPassed=true" || bad "surfQuizPassed missing"
  jq -e '.completedSurfQuizzes==1' >/dev/null <<<"$lastResp" && ok "completedSurfQuizzes=1" || bad "completedSurfQuizzes wrong"
  jq -e '.nextSurfQuizNumber==2' >/dev/null <<<"$lastResp" && ok "nextSurfQuizNumber=2" || bad "nextSurfQuizNumber wrong"
  jq -e '.showWinVideo==true' >/dev/null <<<"$lastResp" && ok "showWinVideo=true" || bad "showWinVideo missing"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$lastResp" && ok "gameModeType=surf" || bad "gameModeType wrong"
  jq -e 'has("dailyStats")' >/dev/null <<<"$lastResp" && ok "Has dailyStats" || bad "Missing dailyStats"
}

# --- SURF MODE - PRACTICE TESTS ---
test_surf_practice_wrong_continues() {
  say "TEST: Surf Mode - Wrong Practice Continues"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$((correct + 99)),\"responseMs\":500}")

  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")

  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$((pcorrect + 5))}")

  jq -e '.stillPracticing==true or has("practice")' >/dev/null <<<"$presp" && ok "Wrong practice continues" || bad "Should continue"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$presp" && ok "gameModeType=surf" || bad "gameModeType wrong"
}

test_surf_practice_correct_restarts_quiz() {
  say "TEST: Surf Mode - Correct Practice Restarts Quiz"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  local qid=$(jq -r '.questions[2]._id' <<<"$start")
  local correct=$(jq '.questions[2].correctAnswer' <<<"$start")
  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$((correct + 99)),\"responseMs\":500}")

  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")

  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")

  jq -e '.surfQuizRestarted==true' >/dev/null <<<"$presp" && ok "surfQuizRestarted=true" || bad "surfQuizRestarted missing"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$presp" && ok "gameModeType=surf" || bad "gameModeType wrong"
  jq -e '.showLoseVideo==true' >/dev/null <<<"$presp" && ok "showLoseVideo=true" || bad "showLoseVideo missing"
  jq -e '.surfQuizNumber' >/dev/null <<<"$presp" && ok "Has surfQuizNumber" || bad "Missing surfQuizNumber"
  jq -e '.questions | type=="array"' >/dev/null <<<"$presp" && ok "Has fresh questions" || bad "Missing questions"

  local newQcount=$(jq '.questions | length' <<<"$presp")
  [[ "$newQcount" -eq $SURF_QUESTIONS_PER_QUIZ ]] && ok "Fresh $SURF_QUESTIONS_PER_QUIZ questions" || bad "Wrong count: $newQcount"
}

# --- SURF MODE - COMPLETION TESTS ---
test_surf_mode_completion() {
  say "TEST: Surf Mode - Full Completion (5 Quizzes)"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  local lastResp=""
  for quiz in 1 2 3 4 5; do
    subsay "Completing surf quiz $quiz/5"
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local questions=$(jq '.questions' <<<"$start")
    lastResp=$(complete_surf_quiz "$runId" "$questions" 500)

    if jq -e '.completed==true' >/dev/null <<<"$lastResp"; then
      break
    fi
  done

  jq -e '.completed==true' >/dev/null <<<"$lastResp" && ok "completed=true" || bad "Not completed"
  jq -e '.passed==true' >/dev/null <<<"$lastResp" && ok "passed=true" || bad "Not passed"
  jq -e '.gameModeType=="surf"' >/dev/null <<<"$lastResp" && ok "gameModeType=surf" || bad "gameModeType wrong"
  jq -e '.beltAwarded==false' >/dev/null <<<"$lastResp" && ok "beltAwarded=false (belt after bonus)" || bad "beltAwarded should be false"
  jq -e 'has("updatedProgress") | not' >/dev/null <<<"$lastResp" && ok "No updatedProgress (belt after bonus)" || bad "Should not update progress"
  jq -e ".completedSurfQuizzes==$SURF_QUIZZES_REQUIRED" >/dev/null <<<"$lastResp" && ok "completedSurfQuizzes=$SURF_QUIZZES_REQUIRED" || bad "completedSurfQuizzes wrong"
  jq -e 'has("dailyStats")' >/dev/null <<<"$lastResp" && ok "Has dailyStats" || bad "Missing dailyStats"

  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.yellow.unlocked==false' >/dev/null <<<"$prog" && ok "Yellow still locked (rocket required)" || bad "Yellow should be locked until rocket"
}

test_surf_no_belt_on_lightning_only() {
  say "TEST: Surf Mode - No Belt on Lightning Only"
  reset_user

  # Complete lightning mode naturally (without forcePass) to verify normal behavior
  local resp=$(complete_lightning_mode_natural 1 "white" "add" "$TARGET")

  jq -e 'has("beltAwarded") | not' >/dev/null <<<"$resp" && ok "No beltAwarded on lightning" || bad "Should not have beltAwarded"
  jq -e '.surfRequired==true' >/dev/null <<<"$resp" && ok "surfRequired=true" || bad "surfRequired missing"
  jq -e '.lightningComplete==true' >/dev/null <<<"$resp" && ok "lightningComplete=true" || bad "lightningComplete missing"

  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.yellow.unlocked==false' >/dev/null <<<"$prog" && ok "Yellow still locked after lightning" || bad "Yellow should be locked"
}

# --- SURF MODE - RESUME TESTS ---
test_surf_resume_mid_quiz() {
  say "TEST: Surf Mode - Resume Mid-Quiz"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  sleep 0.5

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.surfCorrectStreak==3' >/dev/null <<<"$prep2" && ok "surfCorrectStreak=3 preserved" || bad "Streak lost"
  jq -e '.surfQuizNumber==1' >/dev/null <<<"$prep2" && ok "surfQuizNumber=1 preserved" || bad "Quiz number wrong"
}

test_surf_resume_after_quiz_pass() {
  say "TEST: Surf Mode - Resume After Quiz Pass"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local questions=$(jq '.questions' <<<"$start")
  complete_surf_quiz "$runId" "$questions" 500 >/dev/null

  sleep 0.5

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.completedSurfQuizzes==1' >/dev/null <<<"$prep2" && ok "completedSurfQuizzes=1" || bad "Completed count wrong"
  jq -e '.surfQuizNumber==2' >/dev/null <<<"$prep2" && ok "surfQuizNumber=2" || bad "Quiz number wrong"
  jq -e '.surfCorrectStreak==0' >/dev/null <<<"$prep2" && ok "surfCorrectStreak=0" || bad "Streak should reset"
}

test_surf_resume_after_failed_quiz() {
  say "TEST: Surf Mode - Resume After Failed Quiz (Needs Restart)"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$((correct + 99)),\"responseMs\":500}" >/dev/null

  sleep 0.5

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.surfQuizFailed==true or .needsRestart==true' >/dev/null <<<"$prep2" && ok "Quiz failed state preserved" || bad "Failed state lost"
}

test_surf_resume_after_failed_quiz_at_index_gt0() {
  say "TEST: Surf Mode - Resume After Failed Quiz at Index > 0 (Bug Fix)"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer 2 correctly to advance index to 2, then answer wrong
  for i in 0 1; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  local qid=$(jq -r '.questions[2]._id' <<<"$start")
  local correct=$(jq '.questions[2].correctAnswer' <<<"$start")
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$((correct + 99)),\"responseMs\":500}" >/dev/null

  sleep 0.5

  # Simulate user quitting without practicing - prepare should show currentIndex:0
  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.needsRestart==true' >/dev/null <<<"$prep2" && ok "needsRestart=true" || bad "needsRestart missing"
  local idx=$(jq '.currentIndex' <<<"$prep2")
  [[ "$idx" -eq 0 ]] && ok "currentIndex reset to 0 on failed resume" || bad "currentIndex should be 0, got $idx"

  # Now call start() - should auto-restart with fresh questions
  local start2=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  jq -e '.questions | type=="array"' >/dev/null <<<"$start2" && ok "Has questions after auto-restart" || bad "Missing questions"
  local newIdx=$(jq '.currentIndex' <<<"$start2")
  [[ "$newIdx" -eq 0 ]] && ok "currentIndex=0 after auto-restart" || bad "currentIndex should be 0 after restart, got $newIdx"

  local qcount=$(jq '.questions | length' <<<"$start2")
  [[ "$qcount" -eq $SURF_QUESTIONS_PER_QUIZ ]] && ok "Fresh $SURF_QUESTIONS_PER_QUIZ questions after restart" || bad "Wrong count: $qcount"
}

# --- SURF MODE - DAILY STATS TESTS ---
test_surf_daily_stats_tracking() {
  say "TEST: Surf Mode - Daily Stats Tracking"
  reset_user

  local initial=$(get_json "/user/daily")
  local initialCorrect=$(jq '.correctCount // 0' <<<"$initial")

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2 3; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  sleep 0.5

  local after=$(get_json "/user/daily")
  local afterCorrect=$(jq '.correctCount // 0' <<<"$after")
  local diff=$((afterCorrect - initialCorrect))

  [[ "$diff" -ge 4 ]] && ok "Daily stats tracked: +$diff correct" || bad "Daily stats wrong: +$diff"
}

# --- SURF MODE - FAILURE TRACKING TESTS ---
test_surf_failure_counter() {
  say "TEST: Surf Mode - Failure Counter"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  for fail in 1 2 3; do
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qid=$(jq -r '.questions[0]._id' <<<"$start")
    local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

    local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$((correct + 99)),\"responseMs\":500}")

    local failures=$(jq '.surfQuizFailures' <<<"$wresp")
    [[ "$failures" -eq "$fail" ]] && ok "After fail $fail: surfQuizFailures=$fail" || bad "Failure count wrong: $failures"

    local pid=$(jq -r '.practice._id' <<<"$wresp")
    local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
    post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}" >/dev/null
  done
}

# --- SURF MODE - EDGE CASES ---
test_surf_duplicate_answer() {
  say "TEST: Surf Mode - Duplicate Answer"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp1=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")
  local streak1=$(jq '.surfCorrectStreak' <<<"$resp1")

  local resp2=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")

  jq -e '.duplicate==true or .surfCorrectStreak==1' >/dev/null <<<"$resp2" && ok "Duplicate handled gracefully" || bad "Duplicate caused issues"
}

test_surf_answer_completed_quiz() {
  say "TEST: Surf Mode - Answer Already Completed"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  for quiz in 1 2 3 4 5; do
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local questions=$(jq '.questions' <<<"$start")
    complete_surf_quiz "$runId" "$questions" 500 >/dev/null
  done

  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qid=$(jq -r '.questions[0]._id // "test"' <<<"$start")
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":500}")

  jq -e '.completed==true' >/dev/null <<<"$resp" && ok "Returns completed state" || bad "Should return completed"
}

test_surf_fresh_questions_on_restart() {
  say "TEST: Surf Mode - Fresh Questions on Restart"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local origIds=$(jq '[.questions[]._id]' <<<"$start")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$((correct + 99)),\"responseMs\":500}")

  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")

  local newIds=$(jq '[.questions[]._id]' <<<"$presp")

  [[ "$origIds" != "$newIds" ]] && ok "Fresh questions generated" || bad "Same questions reused"
}

test_surf_complete_flow_with_failures() {
  say "TEST: Surf Mode - Complete Flow with Failures"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  ok "Lightning mode completed"

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  local completedQuizzes=0
  local failures=0
  local maxIterations=20
  local iteration=0

  while [[ $completedQuizzes -lt $SURF_QUIZZES_REQUIRED && $iteration -lt $maxIterations ]]; do
    iteration=$((iteration + 1))
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

    local shouldFail=$((RANDOM % 3))

    if [[ $shouldFail -eq 0 && $failures -lt 2 ]]; then
      for i in 0 1; do
        local qid=$(jq -r ".questions[$i]._id" <<<"$start")
        local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
        post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
      done

      local qid=$(jq -r '.questions[2]._id' <<<"$start")
      local correct=$(jq '.questions[2].correctAnswer' <<<"$start")
      local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$((correct + 99)),\"responseMs\":500}")

      failures=$((failures + 1))
      subsay "Quiz failed (failure #$failures)"

      local pid=$(jq -r '.practice._id' <<<"$wresp")
      local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
      post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}" >/dev/null
    else
      local questions=$(jq '.questions' <<<"$start")
      local resp=$(complete_surf_quiz "$runId" "$questions" 500)

      if jq -e '.surfQuizPassed==true' >/dev/null <<<"$resp"; then
        completedQuizzes=$((completedQuizzes + 1))
        subsay "Quiz $completedQuizzes/$SURF_QUIZZES_REQUIRED passed"
      fi

      if jq -e '.completed==true' >/dev/null <<<"$resp"; then
        jq -e '.beltAwarded==false' >/dev/null <<<"$resp" && ok "beltAwarded=false (belt after bonus)" || bad "beltAwarded should be false"
        jq -e 'has("updatedProgress") | not' >/dev/null <<<"$resp" && ok "No updatedProgress (belt after bonus)" || bad "Should not update progress"

        local totalFailures=$(jq '.surfQuizFailures' <<<"$resp")
        ok "Completed with $totalFailures failures"

        local prog=$(get_json "/user/progress")
        jq -e '.progress.add.L1.yellow.unlocked==false' >/dev/null <<<"$prog" && ok "Yellow still locked (rocket required)" || bad "Yellow should be locked until rocket"
        return
      fi
    fi
  done

  [[ $completedQuizzes -eq $SURF_QUIZZES_REQUIRED ]] && ok "All $SURF_QUIZZES_REQUIRED quizzes completed" || bad "Did not complete all quizzes"
}

test_surf_different_belts() {
  say "TEST: Surf Mode - Different Belts"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"surf"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  for quiz in 1 2 3 4 5; do
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local questions=$(jq '.questions' <<<"$start")
    complete_surf_quiz "$runId" "$questions" 500 >/dev/null
  done

  ok "White belt surf completed"

  complete_lightning_mode 1 "yellow" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"add","gameMode":true,"gameModeType":"surf"}')

  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Yellow surf available" || bad "Yellow surf blocked"
  jq -e '.surfQuizNumber==1' >/dev/null <<<"$prep" && ok "Fresh surf run for yellow" || bad "Should be fresh"
  jq -e '.completedSurfQuizzes==0' >/dev/null <<<"$prep" && ok "completedSurfQuizzes=0 for yellow" || bad "Should be 0"
}

# =============================================================================
# ANALYTICS - SUMMARY ENDPOINT TESTS
# =============================================================================
test_analytics_summary_basic() {
  say "TEST: Analytics Summary - Basic"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2 3 4; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":1500}" >/dev/null
  done
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/summary")

  jq -e '.userId' >/dev/null <<<"$resp" && ok "Summary has userId" || bad "Missing userId"
  jq -e '.overall' >/dev/null <<<"$resp" && ok "Summary has overall" || bad "Missing overall"
  jq -e '.overall.totalAttempts >= 5' >/dev/null <<<"$resp" && ok "totalAttempts >= 5" || bad "totalAttempts wrong"
  jq -e '.overall.totalCorrect >= 5' >/dev/null <<<"$resp" && ok "totalCorrect >= 5" || bad "totalCorrect wrong"
  jq -e '.overall.accuracy >= 0 and .overall.accuracy <= 1' >/dev/null <<<"$resp" && ok "accuracy in range" || bad "accuracy out of range"
  jq -e '.byLevel | type == "array"' >/dev/null <<<"$resp" && ok "byLevel is array" || bad "byLevel not array"
  jq -e '.byOperation | type == "array"' >/dev/null <<<"$resp" && ok "byOperation is array" || bad "byOperation not array"
}

test_analytics_summary_empty() {
  say "TEST: Analytics Summary - Empty User"
  reset_user

  local resp=$(get_json "/analytics/summary")

  jq -e '.overall.totalAttempts == 0' >/dev/null <<<"$resp" && ok "Empty user: totalAttempts=0" || bad "Should be 0"
  jq -e '.overall.totalCorrect == 0' >/dev/null <<<"$resp" && ok "Empty user: totalCorrect=0" || bad "Should be 0"
  jq -e '.overall.accuracy == 0' >/dev/null <<<"$resp" && ok "Empty user: accuracy=0" || bad "Should be 0"
}

test_analytics_summary_level_breakdown() {
  say "TEST: Analytics Summary - Level Breakdown"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 50 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/summary")

  jq -e '.byLevel | length >= 1' >/dev/null <<<"$resp" && ok "Has level breakdown" || bad "No level breakdown"
  jq -e '.byLevel[0].level == 1' >/dev/null <<<"$resp" && ok "Level 1 present" || bad "Level 1 missing"
  jq -e '.byLevel[0].totalAttempts >= 10' >/dev/null <<<"$resp" && ok "Level 1 has attempts" || bad "No attempts for L1"
  jq -e '.byLevel[0].avgResponseMs > 0' >/dev/null <<<"$resp" && ok "avgResponseMs > 0" || bad "avgResponseMs missing"
}

test_analytics_summary_operation_breakdown() {
  say "TEST: Analytics Summary - Operation Breakdown"
  reset_user

  for op in "add"; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"$op\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    for i in 0 1 2; do
      local qid=$(jq -r ".questions[$i]._id" <<<"$start")
      local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
      post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":100}" >/dev/null
    done
    post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
  done

  sleep 0.5

  local resp=$(get_json "/analytics/summary")
  local opCount=$(jq '.byOperation | length' <<<"$resp")

  [[ "$opCount" -ge 1 ]] && ok "Operations tracked (add)" || bad "Expected 1+ operations, got $opCount"
}

# =============================================================================
# ANALYTICS - FACTS ENDPOINT TESTS
# =============================================================================
test_analytics_facts_basic() {
  say "TEST: Analytics Facts - Basic"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 100 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/facts")

  jq -e '.userId' >/dev/null <<<"$resp" && ok "Facts has userId" || bad "Missing userId"
  jq -e '.pagination' >/dev/null <<<"$resp" && ok "Facts has pagination" || bad "Missing pagination"
  jq -e '.pagination.limit' >/dev/null <<<"$resp" && ok "Has limit" || bad "Missing limit"
  jq -e '.pagination.offset' >/dev/null <<<"$resp" && ok "Has offset" || bad "Missing offset"
  jq -e '.pagination.total >= 0' >/dev/null <<<"$resp" && ok "Has total" || bad "Missing total"
  jq -e '.facts | type == "array"' >/dev/null <<<"$resp" && ok "Facts is array" || bad "Facts not array"
}

test_analytics_facts_structure() {
  say "TEST: Analytics Facts - Fact Structure"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 80 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/facts?limit=1")

  jq -e '.facts[0].operation' >/dev/null <<<"$resp" && ok "Fact has operation" || bad "Missing operation"
  jq -e '.facts[0].a != null' >/dev/null <<<"$resp" && ok "Fact has a" || bad "Missing a"
  jq -e '.facts[0].b != null' >/dev/null <<<"$resp" && ok "Fact has b" || bad "Missing b"
  jq -e '.facts[0].question' >/dev/null <<<"$resp" && ok "Fact has question" || bad "Missing question"

  jq -e '.facts[0].levels | type == "array"' >/dev/null <<<"$resp" && ok "levels is array" || bad "levels not array"
  jq -e '.facts[0].beltsOrDegrees | type == "array"' >/dev/null <<<"$resp" && ok "beltsOrDegrees is array" || bad "beltsOrDegrees not array"
  jq -e '.facts[0].levels | length >= 1' >/dev/null <<<"$resp" && ok "levels has entries" || bad "levels empty"
  jq -e '.facts[0].beltsOrDegrees | length >= 1' >/dev/null <<<"$resp" && ok "beltsOrDegrees has entries" || bad "beltsOrDegrees empty"

  jq -e '.facts[0].stats.totalAttempts >= 1' >/dev/null <<<"$resp" && ok "Stats has totalAttempts" || bad "Missing totalAttempts"
  jq -e '.facts[0].stats.correctCount >= 0' >/dev/null <<<"$resp" && ok "Stats has correctCount" || bad "Missing correctCount"
  jq -e '.facts[0].stats.wrongCount >= 0' >/dev/null <<<"$resp" && ok "Stats has wrongCount" || bad "Missing wrongCount"
  jq -e '.facts[0].stats.accuracy >= 0' >/dev/null <<<"$resp" && ok "Stats has accuracy" || bad "Missing accuracy"
  jq -e '.facts[0].stats | has("avgMs")' >/dev/null <<<"$resp" && ok "Stats has avgMs" || bad "Missing avgMs"
  jq -e '.facts[0].stats | has("medianMs")' >/dev/null <<<"$resp" && ok "Stats has medianMs" || bad "Missing medianMs"
  jq -e '.facts[0].stats | has("minMs")' >/dev/null <<<"$resp" && ok "Stats has minMs" || bad "Missing minMs"
  jq -e '.facts[0].stats | has("maxMs")' >/dev/null <<<"$resp" && ok "Stats has maxMs" || bad "Missing maxMs"
  jq -e '.facts[0].stats | has("mastered")' >/dev/null <<<"$resp" && ok "Stats has mastered" || bad "Missing mastered"
  jq -e '.facts[0].stats | has("struggling")' >/dev/null <<<"$resp" && ok "Stats has struggling" || bad "Missing struggling"
}

test_analytics_facts_pagination() {
  say "TEST: Analytics Facts - Pagination"
  reset_user

  for belt in "white" "yellow"; do
    local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"$belt\",\"operation\":\"add\"}")
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz "$runId" "$start" 50 >/dev/null
  done

  sleep 0.5

  local resp=$(get_json "/analytics/facts?limit=5")
  local count=$(jq '.facts | length' <<<"$resp")
  [[ "$count" -le 5 ]] && ok "Limit respected: $count <= 5" || bad "Limit not respected: $count"

  local page1=$(get_json "/analytics/facts?limit=3&offset=0")
  local page2=$(get_json "/analytics/facts?limit=3&offset=3")

  local first1=$(jq -r '.facts[0].question // "none"' <<<"$page1")
  local first2=$(jq -r '.facts[0].question // "none"' <<<"$page2")

  [[ "$first1" != "$first2" ]] && ok "Offset returns different data" || bad "Offset not working"

  local total=$(jq '.pagination.total' <<<"$resp")
  if [[ "$total" -gt 5 ]]; then
    jq -e '.pagination.hasMore == true' >/dev/null <<<"$resp" && ok "hasMore=true when more data" || bad "hasMore wrong"
  fi
}

test_analytics_facts_level_filter() {
  say "TEST: Analytics Facts - Level Filter"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 50 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/facts?level=1")

  jq -e '.filters.level == 1' >/dev/null <<<"$resp" && ok "Filter shows level=1" || bad "Filter wrong"

  local wrongLevel=$(jq '[.facts[] | select(.levels | map(. == 1) | any | not)] | length' <<<"$resp")
  [[ "$wrongLevel" -eq 0 ]] && ok "All facts are level 1" || bad "Found $wrongLevel non-L1 facts"
}

test_analytics_facts_operation_filter() {
  say "TEST: Analytics Facts - Operation Filter"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 50 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/facts?operation=add")

  jq -e '.filters.operation == "add"' >/dev/null <<<"$resp" && ok "Filter shows operation=add" || bad "Filter wrong"

  local wrongOp=$(jq '[.facts[] | select(.operation != "add")] | length' <<<"$resp")
  [[ "$wrongOp" -eq 0 ]] && ok "All facts are add" || bad "Found $wrongOp non-add facts"
}

test_analytics_facts_combined_filter() {
  say "TEST: Analytics Facts - Combined Filters"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 50 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/facts?level=1&operation=add&limit=10")

  jq -e '.filters.level == 1' >/dev/null <<<"$resp" && ok "Level filter applied" || bad "Level filter missing"
  jq -e '.filters.operation == "add"' >/dev/null <<<"$resp" && ok "Operation filter applied" || bad "Operation filter missing"

  local valid=$(jq '[.facts[] | select((.levels | map(. == 1) | any) and .operation == "add")] | length' <<<"$resp")
  local total=$(jq '.facts | length' <<<"$resp")
  [[ "$valid" -eq "$total" ]] && ok "All facts match filters" || bad "Filter mismatch"
}

# =============================================================================
# ANALYTICS - FACT DETAIL ENDPOINT TESTS
# =============================================================================
test_analytics_fact_detail() {
  say "TEST: Analytics Fact Detail - Basic"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local firstQ=$(jq '.questions[0]' <<<"$start")
  local a=$(jq '.params.a // 0' <<<"$firstQ")
  local b=$(jq '.params.b // 0' <<<"$firstQ")

  complete_quiz "$runId" "$start" 100 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/facts/add/$a/$b")

  jq -e '.userId' >/dev/null <<<"$resp" && ok "Detail has userId" || bad "Missing userId"
  jq -e '.fact.operation == "add"' >/dev/null <<<"$resp" && ok "Fact operation correct" || bad "Wrong operation"
  jq -e ".fact.a == $a" >/dev/null <<<"$resp" && ok "Fact a correct" || bad "Wrong a"
  jq -e ".fact.b == $b" >/dev/null <<<"$resp" && ok "Fact b correct" || bad "Wrong b"
  jq -e '.fact.question' >/dev/null <<<"$resp" && ok "Has question string" || bad "Missing question"
  jq -e '.stats.totalAttempts >= 1' >/dev/null <<<"$resp" && ok "Has attempts" || bad "No attempts"
  jq -e '.recentAttempts | type == "array"' >/dev/null <<<"$resp" && ok "Has recentAttempts" || bad "Missing recentAttempts"
}

test_analytics_fact_detail_recent_attempts() {
  say "TEST: Analytics Fact Detail - Recent Attempts Structure"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local a=$(jq '.questions[0].params.a // 0' <<<"$start")
  local b=$(jq '.questions[0].params.b // 0' <<<"$start")

  complete_quiz "$runId" "$start" 100 >/dev/null
  sleep 0.5

  local resp=$(get_json "/analytics/facts/add/$a/$b?limit=5")

  local attemptCount=$(jq '.recentAttempts | length' <<<"$resp")
  [[ "$attemptCount" -le 5 ]] && ok "Respects limit" || bad "Limit not respected"

  if [[ "$attemptCount" -ge 1 ]]; then
    jq -e '.recentAttempts[0] | has("attemptedAt")' >/dev/null <<<"$resp" && ok "Attempt has attemptedAt" || bad "Missing attemptedAt"
    jq -e '.recentAttempts[0] | has("correct")' >/dev/null <<<"$resp" && ok "Attempt has correct" || bad "Missing correct"
    jq -e '.recentAttempts[0] | has("userAnswer")' >/dev/null <<<"$resp" && ok "Attempt has userAnswer" || bad "Missing userAnswer"
    jq -e '.recentAttempts[0] | has("correctAnswer")' >/dev/null <<<"$resp" && ok "Attempt has correctAnswer" || bad "Missing correctAnswer"
    jq -e '.recentAttempts[0] | has("responseMs")' >/dev/null <<<"$resp" && ok "Attempt has responseMs" || bad "Missing responseMs"
    jq -e '.recentAttempts[0] | has("choices")' >/dev/null <<<"$resp" && ok "Attempt has choices" || bad "Missing choices"
    jq -e '.recentAttempts[0] | has("gameMode")' >/dev/null <<<"$resp" && ok "Attempt has gameMode" || bad "Missing gameMode"
  fi
}

test_analytics_fact_detail_nonexistent() {
  say "TEST: Analytics Fact Detail - Nonexistent Fact"
  reset_user

  local resp=$(get_json "/analytics/facts/add/999/888")

  jq -e '.stats.totalAttempts == 0' >/dev/null <<<"$resp" && ok "No attempts for unknown fact" || bad "Should be 0"
  jq -e '.recentAttempts | length == 0' >/dev/null <<<"$resp" && ok "Empty recentAttempts" || bad "Should be empty"
}

# =============================================================================
# ANALYTICS - STRUGGLING ENDPOINT TESTS
# =============================================================================
test_analytics_struggling_basic() {
  say "TEST: Analytics Struggling - Basic"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for attempt in 1 2 3 4; do
    local qid=$(jq -r '.questions[0]._id' <<<"$start")
    local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
    local wrong=$((correct + 99))

    local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":100}")

    if jq -e 'has("practice")' >/dev/null <<<"$wresp"; then
      local pid=$(jq -r '.practice._id' <<<"$wresp")
      local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
      post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}" >/dev/null
    fi
  done
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/struggling")

  jq -e '.userId' >/dev/null <<<"$resp" && ok "Struggling has userId" || bad "Missing userId"
  jq -e '.strugglingFacts | type == "array"' >/dev/null <<<"$resp" && ok "strugglingFacts is array" || bad "Not array"
}

test_analytics_struggling_threshold() {
  say "TEST: Analytics Struggling - Accuracy Threshold"
  reset_user

  local resp=$(get_json "/analytics/struggling?limit=5")

  jq -e '.strugglingFacts | type == "array"' >/dev/null <<<"$resp" && ok "Returns array" || bad "Not array"

  local count=$(jq '.strugglingFacts | length' <<<"$resp")
  if [[ "$count" -gt 0 ]]; then
    local allValid=$(jq '[.strugglingFacts[] | select(.accuracy < 0.7 and .totalAttempts >= 3)] | length' <<<"$resp")
    [[ "$allValid" -eq "$count" ]] && ok "All struggling facts meet criteria" || bad "Some facts don't meet criteria"
  else
    ok "No struggling facts (user doing well)"
  fi
}

test_analytics_struggling_level_filter() {
  say "TEST: Analytics Struggling - Level Filter"
  reset_user

  local resp=$(get_json "/analytics/struggling?level=1&limit=10")

  jq -e '.level == 1 or .level == "1"' >/dev/null <<<"$resp" && ok "Level filter applied" || bad "Level filter missing"
}

# =============================================================================
# PRETEST MODE TESTS
# =============================================================================
test_pretest_triggered_on_fresh_user() {
  say "TEST: Pretest - Triggered on Fresh User"
  reset_user_with_pretests

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')

  jq -e '.pretestMode==true' >/dev/null <<<"$prep" && ok "Pretest mode triggered" || bad "Pretest not triggered"
  jq -e '.gameModeType=="pretest"' >/dev/null <<<"$prep" && ok "gameModeType=pretest" || bad "Wrong gameModeType"
  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Has quizRunId" || bad "No quizRunId"
  jq -e '.pretestTimeLimitMs' >/dev/null <<<"$prep" && ok "Has pretestTimeLimitMs" || bad "No time limit"
  jq -e '.pretestQuestionCount' >/dev/null <<<"$prep" && ok "Has pretestQuestionCount" || bad "No question count"

  # Cleanup
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

test_pretest_start_response() {
  say "TEST: Pretest - Start Response"
  reset_user_with_pretests

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  jq -e '.questions | length == 20' >/dev/null <<<"$start" && ok "20 questions for pretest" || bad "Wrong question count"
  jq -e '.pretestMode==true' >/dev/null <<<"$start" && ok "pretestMode=true in start" || bad "pretestMode missing"
  jq -e '.gameModeType=="pretest"' >/dev/null <<<"$start" && ok "gameModeType=pretest" || bad "Wrong gameModeType"
  jq -e '.run.status=="running"' >/dev/null <<<"$start" && ok "Status is running" || bad "Wrong status"

  # Cleanup
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

test_pretest_pass_awards_level() {
  say "TEST: Pretest - Pass Awards Entire Level"
  reset_user_with_pretests

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Complete pretest with fast answers (10ms each = 200ms total, well under 50s)
  local final=$(complete_pretest "$runId" "$start" 10)

  jq -e '.completed==true' >/dev/null <<<"$final" && ok "Pretest completed" || bad "Not completed"
  jq -e '.passed==true' >/dev/null <<<"$final" && ok "Pretest passed" || bad "Not passed"
  jq -e '.levelAwarded==true' >/dev/null <<<"$final" && ok "Level awarded" || bad "Level not awarded"
  jq -e '.pretestMode==true' >/dev/null <<<"$final" && ok "pretestMode in response" || bad "pretestMode missing"

  # Verify progression
  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.white.completed==true' >/dev/null <<<"$prog" && ok "White completed" || bad "White not completed"
  jq -e '.progress.add.L1.black.unlocked==true' >/dev/null <<<"$prog" && ok "Black unlocked" || bad "Black not unlocked"
  jq -e '.progress.add.L2.unlocked==true' >/dev/null <<<"$prog" && ok "L2 unlocked" || bad "L2 not unlocked"
}

test_pretest_forcepass() {
  say "TEST: Pretest - ForcePass Works"
  reset_user_with_pretests

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qid=$(jq -r '.questions[0]._id' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}")

  jq -e '.completed==true' >/dev/null <<<"$resp" && ok "ForcePass completes pretest" || bad "Not completed"
  jq -e '.passed==true' >/dev/null <<<"$resp" && ok "ForcePass passes pretest" || bad "Not passed"
  jq -e '.levelAwarded==true' >/dev/null <<<"$resp" && ok "Level awarded via forcePass" || bad "Level not awarded"
}

test_pretest_wrong_answer_practice() {
  say "TEST: Pretest - Wrong Answer Triggers Practice"
  reset_user_with_pretests

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$((correct + 99))

  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":100}")
  jq -e 'has("practice")' >/dev/null <<<"$wresp" && ok "Wrong triggers practice" || bad "No practice"
  jq -e '.reason=="wrong"' >/dev/null <<<"$wresp" && ok "reason=wrong" || bad "Wrong reason"
  jq -e '.pretestMode==true' >/dev/null <<<"$wresp" && ok "pretestMode in response" || bad "pretestMode missing"

  # Cleanup
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

test_pretest_inactivity() {
  say "TEST: Pretest - Inactivity Triggers Practice"
  reset_user_with_pretests

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}" >/dev/null

  local resp=$(post_json "/quiz/inactivity" "{\"quizRunId\":\"$runId\"}")
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Inactivity triggers practice" || bad "No practice"
  jq -e '.pretestMode==true' >/dev/null <<<"$resp" && ok "pretestMode in response" || bad "pretestMode missing"

  # Cleanup
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

test_pretest_inactivity_threshold() {
  say "TEST: Pretest - Separate Inactivity Threshold (3s vs 5s)"
  reset_user_with_pretests

  # Pretest: 4000ms should trigger inactivity (threshold is 3s)
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qid=$(jq -r '.questions[0]._id // .questions[0].id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":4000}")
  jq -e '.reason == "inactivity"' >/dev/null <<<"$resp" && ok "4s triggers inactivity in pretest (3s threshold)" || bad "4s did not trigger inactivity in pretest"

  # Cleanup
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null

  # Normal mode: 4000ms should NOT trigger inactivity (threshold is 5s)
  reset_user
  prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  runId=$(jq -r '.quizRunId' <<<"$prep")
  start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  qid=$(jq -r '.questions[0]._id // .questions[0].id' <<<"$start")
  correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":4000}")
  jq -e '.reason == "inactivity"' >/dev/null <<<"$resp" && bad "4s should NOT trigger inactivity in normal mode (5s threshold)" || ok "4s does not trigger inactivity in normal mode"

  # Cleanup
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

test_pretest_not_retriggered() {
  say "TEST: Pretest - Not Retriggered After Taken"
  reset_user_with_pretests

  # Take the pretest using forcePass
  skip_pretest_for_op "add" 1

  # Try to prepare again - should NOT trigger pretest
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')

  # Since level was awarded, we should be able to prepare for any belt
  # Check that pretest is not triggered again
  local isPre=$(jq -r '.pretestMode // false' <<<"$prep")
  [[ "$isPre" != "true" ]] && ok "Pretest not retriggered" || bad "Pretest retriggered"

  # Cleanup
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  if [[ "$runId" != "null" && -n "$runId" ]]; then
    post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
  fi
}

test_pretest_resume() {
  say "TEST: Pretest - Resume Active Pretest"
  reset_user_with_pretests

  # Start a pretest
  local prep1=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep1")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer a few questions
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local ans=$(jq '.questions[0].correctAnswer' <<<"$start")
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10}" >/dev/null

  # Try to prepare again - should resume
  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "No resume"
  local runId2=$(jq -r '.quizRunId' <<<"$prep2")
  [[ "$runId" == "$runId2" ]] && ok "Same runId returned" || bad "Different runId"

  # Cleanup
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
}

test_pretest_per_operation() {
  say "TEST: Pretest - Per Operation Progress Structure"
  reset_user_with_pretests

  # Verify add and sub each have their own pretest status
  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.pretest.taken == false' >/dev/null <<<"$prog" && ok "add pretest separate (not taken)" || bad "add pretest wrong"
  jq -e '.progress.sub.L1.pretest.taken == false' >/dev/null <<<"$prog" && ok "sub pretest separate (not taken)" || bad "sub pretest wrong"

  # Skip pretest for add only
  skip_pretest_for_op "add" 1

  # Verify add pretest changed but sub didn't
  local prog2=$(get_json "/user/progress")
  jq -e '.progress.sub.L1.pretest.taken == false' >/dev/null <<<"$prog2" && ok "sub pretest still not taken after add pretest" || bad "sub pretest should be unaffected"
}

test_pretest_progress_includes_status() {
  say "TEST: Pretest - Progress Includes Pretest Status"
  reset_user_with_pretests

  # Get progress for fresh user
  local progress=$(get_json "/user/progress")

  # Verify pretest field exists in add.L1
  jq -e '.progress.add.L1.pretest' >/dev/null <<<"$progress" && ok "Progress includes pretest field for add" || bad "Missing pretest field in progress"

  # Verify sub also has pretest field
  jq -e '.progress.sub.L1.pretest' >/dev/null <<<"$progress" && ok "Progress includes pretest field for sub" || bad "Missing sub pretest field"

  # Verify structure: taken and passed fields
  jq -e '.progress.add.L1.pretest.taken == false' >/dev/null <<<"$progress" && ok "add pretest taken is false initially" || bad "add pretest taken should be false"
  jq -e '.progress.add.L1.pretest.passed == false' >/dev/null <<<"$progress" && ok "add pretest passed is false initially" || bad "add pretest passed should be false"
}

test_pretest_progress_updates_after_taken() {
  say "TEST: Pretest - Progress Updates After Pretest Taken"
  reset_user_with_pretests

  # Verify initial state - pretest not taken
  local progress_before=$(get_json "/user/progress")
  jq -e '.progress.add.L1.pretest.taken == false' >/dev/null <<<"$progress_before" && ok "add pretest not taken initially" || bad "Initial state wrong"

  # Take the add pretest using forcePass
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}" >/dev/null

  # Verify progress updated
  local progress_after=$(get_json "/user/progress")
  jq -e '.progress.add.L1.pretest.taken == true' >/dev/null <<<"$progress_after" && ok "add pretest taken updated to true" || bad "add pretest taken not updated"
  jq -e '.progress.add.L1.pretest.passed == true' >/dev/null <<<"$progress_after" && ok "add pretest passed updated to true" || bad "add pretest passed not updated"

  # Verify sub pretest unchanged
  jq -e '.progress.sub.L1.pretest.taken == false' >/dev/null <<<"$progress_after" && ok "sub pretest still not taken" || bad "sub pretest should be untouched"
}

test_pretest_progress_failed_status() {
  say "TEST: Pretest - Progress Shows Failed Status (skipLevelAward)"
  reset_user_with_pretests

  # Take add pretest with skipLevelAward (marks as taken but does not award level)
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true,\"skipLevelAward\":true}" >/dev/null

  # Verify progress shows pretest taken
  local progress=$(get_json "/user/progress")
  jq -e '.progress.add.L1.pretest.taken == true or .progress.add.L1.pretest.taken == false' >/dev/null <<<"$progress" && ok "Pretest field exists" || bad "Pretest field missing"

  # Verify yellow is still locked (level not awarded)
  jq -e '.progress.add.L1.yellow.unlocked == false' >/dev/null <<<"$progress" && ok "Yellow still locked after skipLevelAward" || bad "Yellow should be locked"
}

test_pretest_time_not_doubled() {
  say "TEST: Pretest - Time Is Not Doubled (Wall-Clock Only)"
  reset_user_with_pretests

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Complete pretest with 100ms per answer (20 questions = 2000ms cumulative response time)
  # Wall-clock time will be slightly more due to network latency
  # If time is doubled, totalTimeMs would be ~4000ms+; if correct, ~2000-3000ms
  local final=$(complete_pretest "$runId" "$start" 100)

  jq -e '.completed==true' >/dev/null <<<"$final" && ok "Pretest completed" || bad "Not completed"

  # Get totalTimeMs from the summary
  local totalTimeMs=$(jq '.summary.totalTimeMs // .totalTimeMs // 0' <<<"$final")
  debug "totalTimeMs: $totalTimeMs"

  # Verify time is reasonable (< 10000ms for 20 questions at 100ms each)
  # If doubled, it would be around 4000ms+ plus network time
  # Wall-clock should be around 2000-5000ms max for fast network
  if [[ "$totalTimeMs" -gt 0 && "$totalTimeMs" -lt 10000 ]]; then
    ok "Total time is reasonable: ${totalTimeMs}ms (not doubled)"
  else
    bad "Total time seems wrong: ${totalTimeMs}ms (expected < 10000ms)"
  fi

  # Specifically check it's not doubled (should be less than 2x the cumulative responseMs)
  # 20 questions * 100ms = 2000ms. If doubled, would be > 4000ms
  if [[ "$totalTimeMs" -lt 5000 ]]; then
    ok "Total time not doubled (${totalTimeMs}ms < 5000ms)"
  else
    bad "Total time may be doubled: ${totalTimeMs}ms (expected < 5000ms)"
  fi
}

# =============================================================================
# ANALYTICS - GAME MODE INTEGRATION
# =============================================================================
test_analytics_gamemode_tracking() {
  say "TEST: Analytics - Game Mode Tracking"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  complete_game_mode "$runId" "$start" "$TARGET" 20 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/summary")

  jq -e ".overall.totalAttempts >= $TARGET" >/dev/null <<<"$resp" && ok "Game mode attempts tracked" || bad "Attempts not tracked"

  local a=$(jq '.questions[0].params.a // 0' <<<"$start")
  local b=$(jq '.questions[0].params.b // 0' <<<"$start")

  local detail=$(get_json "/analytics/facts/add/$a/$b")
  local hasGameMode=$(jq '[.recentAttempts[] | select(.gameMode == true)] | length' <<<"$detail")

  [[ "$hasGameMode" -gt 0 ]] && ok "Game mode attempts recorded" || bad "No game mode attempts"
}

test_analytics_gamemode_cycling_accuracy() {
  say "TEST: Analytics - Game Mode Cycling Accuracy"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local correctCount=0
  for i in $(seq 0 29); do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local correct=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":50}" >/dev/null
    correctCount=$((correctCount + 1))
    [[ $correctCount -ge $TARGET ]] && break
  done

  sleep 0.5

  local resp=$(get_json "/analytics/facts?limit=50")

  local totalAttempts=$(jq '[.facts[].stats.totalAttempts] | add' <<<"$resp")
  [[ "$totalAttempts" -ge $TARGET ]] && ok "Cycles tracked: $totalAttempts attempts" || bad "Too few attempts: $totalAttempts"
}

# =============================================================================
# ANALYTICS - WRONG ANSWER TRACKING
# =============================================================================
test_analytics_wrong_answer_tracking() {
  say "TEST: Analytics - Wrong Answer Tracking"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local a=$(jq '.questions[0].params.a // 0' <<<"$start")
  local b=$(jq '.questions[0].params.b // 0' <<<"$start")
  local wrong=$((correct + 50))

  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":200}")

  if jq -e 'has("practice")' >/dev/null <<<"$wresp"; then
    local pid=$(jq -r '.practice._id' <<<"$wresp")
    local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
    post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}" >/dev/null
  fi

  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
  sleep 0.5

  local detail=$(get_json "/analytics/facts/add/$a/$b")

  jq -e '.stats.wrongCount >= 1' >/dev/null <<<"$detail" && ok "Wrong count tracked" || bad "Wrong count missing"

  local wrongAnswer=$(jq ".recentAttempts[] | select(.correct == false and .userAnswer == $wrong) | .userAnswer" <<<"$detail")
  [[ -n "$wrongAnswer" ]] && ok "Wrong answer value recorded" || bad "Wrong answer not recorded"
}

# =============================================================================
# ANALYTICS - INACTIVITY TRACKING
# =============================================================================
test_analytics_inactivity_tracking() {
  say "TEST: Analytics - Inactivity Tracking"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  post_json "/quiz/inactivity" "{\"quizRunId\":\"$runId\"}" >/dev/null
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/summary")

  jq -e '.overall.totalAttempts >= 1' >/dev/null <<<"$resp" && ok "Inactivity tracked" || bad "Inactivity not tracked"
}

# =============================================================================
# ANALYTICS - RESPONSE TIME STATISTICS
# =============================================================================
test_analytics_response_times() {
  say "TEST: Analytics - Response Time Statistics"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local times=(500 1000 1500 2000 2500)
  for i in "${!times[@]}"; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":${times[$i]}}" >/dev/null
  done
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/facts?limit=10")

  local factWithTimes=$(jq '.facts[] | select(.stats.avgMs > 0) | .stats' <<<"$resp" | head -1)

  if [[ -n "$factWithTimes" ]]; then
    local avg=$(jq '.avgMs' <<<"$factWithTimes")
    local min=$(jq '.minMs' <<<"$factWithTimes")
    local max=$(jq '.maxMs' <<<"$factWithTimes")
    local median=$(jq '.medianMs' <<<"$factWithTimes")

    [[ "$min" -le "$avg" ]] && ok "min <= avg" || bad "min > avg"
    [[ "$avg" -le "$max" ]] && ok "avg <= max" || bad "avg > max"
    [[ "$min" -le "$median" && "$median" -le "$max" ]] && ok "median in range" || bad "median out of range"
  else
    skip "No facts with time data"
  fi
}

# =============================================================================
# ANALYTICS - RESET CLEARS DATA
# =============================================================================
test_analytics_reset_clears_data() {
  say "TEST: Analytics - Reset Clears Data"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 50 >/dev/null

  sleep 0.5

  local before=$(get_json "/analytics/summary")
  local beforeCount=$(jq '.overall.totalAttempts' <<<"$before")
  [[ "$beforeCount" -gt 0 ]] || { bad "No data before reset"; return; }
  ok "Has data before reset: $beforeCount attempts"

  post_json "/user/reset" '{}' >/dev/null
  sleep 0.5

  local after=$(get_json "/analytics/summary")
  local afterCount=$(jq '.overall.totalAttempts' <<<"$after")

  [[ "$afterCount" -eq 0 ]] && ok "Data cleared after reset" || bad "Data persisted: $afterCount attempts"
}

# =============================================================================
# ANALYTICS - MASTERY INDICATORS
# =============================================================================
test_analytics_mastery_indicators() {
  say "TEST: Analytics - Mastery Indicators"
  reset_user

  for i in 1 2; do
    local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    complete_quiz "$runId" "$start" 30 >/dev/null
  done

  sleep 0.5

  local resp=$(get_json "/analytics/facts?limit=20")

  local masteredCount=$(jq '[.facts[] | select(.stats.mastered == true)] | length' <<<"$resp")
  local totalFacts=$(jq '.facts | length' <<<"$resp")

  if [[ "$totalFacts" -gt 0 ]]; then
    [[ "$masteredCount" -ge 0 ]] && ok "Mastery indicator present ($masteredCount mastered)" || bad "Mastery indicator missing"
  fi

  local validMastery=$(jq '[.facts[] | select(.stats.mastered == true and .stats.totalAttempts >= 5 and .stats.accuracy >= 0.9)] | length' <<<"$resp")
  [[ "$validMastery" -eq "$masteredCount" ]] && ok "Mastery criteria correct" || bad "Invalid mastery flags"
}

# =============================================================================
# ANALYTICS - AUTHORIZATION
# =============================================================================
test_analytics_auth() {
  say "TEST: Analytics - Authorization"

  local resp=$(get_json "/analytics/summary" "invalid-pin")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects invalid PIN" || bad "Should reject"

  resp=$(get_json "/analytics/facts" "")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects missing PIN" || bad "Should reject"

  resp=$(get_json "/analytics/struggling" "wrong-pin")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects wrong PIN" || bad "Should reject"
}

# =============================================================================
# ANALYTICS - CONVENIENCE ENDPOINT
# =============================================================================
test_analytics_level_endpoint() {
  say "TEST: Analytics - Level Convenience Endpoint"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 50 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/level/1")

  jq -e '.filters.level == 1' >/dev/null <<<"$resp" && ok "Level endpoint works" || bad "Level endpoint failed"
  jq -e '.facts | type == "array"' >/dev/null <<<"$resp" && ok "Returns facts array" || bad "No facts"
}

# =============================================================================
# ANALYTICS - CROSS-BELT FACT TRACKING
# =============================================================================
test_analytics_cross_belt_tracking() {
  say "TEST: Analytics - Cross-Belt Fact Tracking"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 50 >/dev/null

  prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"add"}')
  runId=$(jq -r '.quizRunId' <<<"$prep")
  start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  complete_quiz "$runId" "$start" 50 >/dev/null

  sleep 0.5

  local resp=$(get_json "/analytics/facts?limit=50")

  local multiBeltFacts=$(jq '[.facts[] | select(.beltsOrDegrees | length > 1)] | length' <<<"$resp")

  if [[ "$multiBeltFacts" -gt 0 ]]; then
    ok "Facts appear across multiple belts ($multiBeltFacts facts)"

    local sampleFact=$(jq '.facts[] | select(.beltsOrDegrees | length > 1) | .beltsOrDegrees' <<<"$resp" | head -1)
    [[ -n "$sampleFact" ]] && ok "Multi-belt fact has belt array: $sampleFact" || bad "Missing belt array"
  else
    skip "No multi-belt facts found (depends on quiz randomness)"
  fi
}

# =============================================================================
# EDGE CASE TESTS
# =============================================================================
test_multiple_wrong_practice() {
  say "TEST: Edge - Multiple Wrong Practice Attempts"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$((correct + 99)),\"responseMs\":50}")

  jq -e 'has("practice")' >/dev/null <<<"$resp" || { bad "Practice not triggered"; return; }

  local pid=$(jq -r '.practice._id' <<<"$resp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$resp")

  for attempt in 1 2 3; do
    local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$((pcorrect + attempt))}")
    jq -e '.stillPracticing==true or has("practice")' >/dev/null <<<"$presp" && ok "Practice attempt $attempt continues" || bad "Should continue"
  done

  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")
  jq -e '.resume==true or has("next")' >/dev/null <<<"$presp" && ok "Finally resumes" || bad "Should resume"
}

test_exact_target_completion() {
  say "TEST: Edge - Complete at Exact Target"
  reset_user

  local prep=$(post_json "/quiz/prepare" "{\"level\":1,\"beltOrDegree\":\"white\",\"operation\":\"add\",\"gameMode\":true,\"targetCorrect\":$TARGET}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local i=0
  while [[ $i -lt $((TARGET - 1)) ]]; do
    local idx=$((i % 10))
    local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
    local ans=$(jq ".questions[$idx].correctAnswer" <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10}")
    jq -e '.completed==true' >/dev/null <<<"$resp" && { bad "Completed too early at $i"; return; }
    i=$((i + 1))
  done
  ok "Not completed at TARGET-1"

  local idx=$(( (TARGET - 1) % 10 ))
  local qid=$(jq -r ".questions[$idx]._id" <<<"$start")
  local ans=$(jq ".questions[$idx].correctAnswer" <<<"$start")
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":10}")

  jq -e '.completed==true' >/dev/null <<<"$resp" && ok "Completed exactly at TARGET" || bad "Not completed"
  jq -e ".totalCorrect==$TARGET" >/dev/null <<<"$resp" && ok "totalCorrect=$TARGET" || bad "totalCorrect wrong"
}

test_daily_accumulation() {
  say "TEST: Edge - Daily Stats Accumulation"
  reset_user

  local initial=$(get_json "/user/daily")
  local initialCorrect=$(jq '.correctCount // 0' <<<"$initial")

  # Quiz 1: 5 correct
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  for i in 0 1 2 3 4; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":30}" >/dev/null
  done
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null

  # Quiz 2: 3 correct
  prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"add"}')
  runId=$(jq -r '.quizRunId' <<<"$prep")
  start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  for i in 0 1 2; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":30}" >/dev/null
  done
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null

  sleep 0.5
  local after=$(get_json "/user/daily")
  local afterCorrect=$(jq '.correctCount // 0' <<<"$after")
  local diff=$((afterCorrect - initialCorrect))

  [[ "$diff" -eq 8 ]] && ok "Daily accumulated: +8" || bad "Daily wrong: +$diff"
}

test_answer_completed_quiz() {
  say "TEST: Edge - Answer Already Completed Quiz"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local ans=$(jq '.questions[0].correctAnswer' <<<"$start")
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":50}")

  jq -e '.completed==true' >/dev/null <<<"$resp" && ok "Returns completed state" || bad "Should return completed"
}

test_duplicate_submission() {
  say "TEST: Edge - Duplicate Answer Submission"
  reset_user

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local ans=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp1=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":50}")
  local idx1=$(jq '.nextIndex // -1' <<<"$resp1")

  local resp2=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":50}")
  local idx2=$(jq '.nextIndex // -1' <<<"$resp2")

  [[ "$idx2" -ge "$idx1" ]] && ok "Duplicate handled gracefully" || bad "Duplicate caused issues"
}

# =============================================================================
# DUPLICATE PREVENTION TESTS (Unique Index)
# =============================================================================
test_no_duplicate_lightning_runs() {
  say "TEST: No Duplicate Lightning Runs Allowed"
  reset_user

  # Start first lightning mode quiz
  local prep1=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true}')
  local runId1=$(jq -r '.quizRunId' <<<"$prep1")
  local resumed1=$(jq -r '.resumed' <<<"$prep1")

  [[ "$resumed1" == "false" ]] && ok "First prepare created new run: $runId1" || bad "First prepare should create new run"

  # Try to start second lightning mode quiz (should return same run)
  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"yellow","operation":"add","gameMode":true}')
  local runId2=$(jq -r '.quizRunId' <<<"$prep2")
  local resumed2=$(jq -r '.resumed' <<<"$prep2")

  [[ "$resumed2" == "true" ]] && ok "Second prepare resumed existing run" || bad "Second prepare should resume, not create"
  [[ "$runId1" == "$runId2" ]] && ok "Same quizRunId returned: $runId1" || bad "Different runIds: $runId1 vs $runId2"
}

test_no_duplicate_pretest_runs() {
  say "TEST: No Duplicate Pretest Runs Allowed"
  reset_user_with_pretests

  # Start first pretest for add operation
  local prep1=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId1=$(jq -r '.quizRunId' <<<"$prep1")
  local isPretest1=$(jq -r '.pretestMode' <<<"$prep1")
  local resumed1=$(jq -r '.resumed' <<<"$prep1")

  [[ "$isPretest1" == "true" ]] && ok "First is pretest mode" || bad "Should be pretest"
  [[ "$resumed1" == "false" ]] && ok "First pretest created new run" || bad "Should create new"

  # Try second pretest for SAME level/operation (should resume)
  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId2=$(jq -r '.quizRunId' <<<"$prep2")
  local resumed2=$(jq -r '.resumed' <<<"$prep2")

  [[ "$resumed2" == "true" ]] && ok "Second pretest resumed existing" || bad "Should resume"
  [[ "$runId1" == "$runId2" ]] && ok "Same pretest runId" || bad "Different runIds: $runId1 vs $runId2"
}

test_lightning_mode_type_set() {
  say "TEST: Lightning Mode Has gameModeType Set"
  reset_user

  # Create lightning mode quiz
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local gameModeType=$(jq -r '.gameModeType' <<<"$prep")

  [[ "$gameModeType" == "lightning" ]] && ok "gameModeType is 'lightning'" || bad "gameModeType should be 'lightning', got: $gameModeType"
}

# =============================================================================
# NEGATIVE TESTS
# =============================================================================
test_negative_cases() {
  say "TEST: Negative Cases"

  local resp=$(post_json "/quiz/prepare" '{"level":1}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects missing beltOrDegree" || bad "Should error"

  resp=$(post_json "/quiz/start" '{"quizRunId":"invalid123"}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects invalid runId" || bad "Should error"

  resp=$(post_json "/quiz/answer" '{"quizRunId":"invalid","questionId":"invalid","answer":1,"responseMs":100}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects invalid answer" || bad "Should error"

  resp=$(get_json "/user/progress" "invalid-pin")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects invalid PIN" || bad "Should error"
}

# =============================================================================
# ADMIN & CACHE TESTS
# =============================================================================
test_admin() {
  say "TEST: Admin Endpoints"

  local resp=$(get_json "/admin/today-stats" "$ADMIN_PIN")
  jq -e 'type=="array"' >/dev/null <<<"$resp" && ok "Admin stats returns array" || bad "Should be array"

  resp=$(get_json "/admin/today-stats?limit=5&offset=0" "$ADMIN_PIN")
  jq -e 'type=="array"' >/dev/null <<<"$resp" && ok "Admin pagination works" || bad "Pagination failed"

  resp=$(get_json "/admin/today-stats" "wrong-pin")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects wrong admin PIN" || bad "Should reject"
}

# -----------------------------------------------------------------------------
# Test: After login + quiz activity, admin/today-stats returns consistent data
#   - loggedInToday=true, todayCorrect>0, todayActiveMs>0 (all agree)
#   - Verifies DailyService and AuthService use the same timezone for "today"
# -----------------------------------------------------------------------------
test_admin_today_stats_consistency() {
  say "TEST: Admin today-stats — timezone consistency"
  reset_user

  # 1. Login so lastLoginDate is set to today (PST)
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null

  # 2. Do a quiz and answer some questions to generate todayCorrect + todayActiveMs
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
  for i in 0 1 2 3 4; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local ans=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$ans,\"responseMs\":500}" >/dev/null
  done
  post_json "/quiz/complete" "{\"quizRunId\":\"$runId\"}" >/dev/null
  sleep 0.5

  # 3. Fetch admin stats and find our user
  local resp=$(get_json "/admin/today-stats?limit=50&offset=0" "$ADMIN_PIN")
  local userEntry=$(jq --arg pin "$PIN" '[.[] | select(.pin==$pin)] | .[0]' <<<"$resp")

  if [[ -z "$userEntry" || "$userEntry" == "null" ]]; then
    bad "User not found in admin today-stats"
    return
  fi

  local loggedIn=$(jq -r '.loggedInToday' <<<"$userEntry")
  local todayCorrect=$(jq -r '.todayCorrect' <<<"$userEntry")
  local todayActiveMs=$(jq -r '.todayActiveMs' <<<"$userEntry")

  # 4. Verify consistency: all three should agree
  [[ "$loggedIn" == "true" ]] && ok "loggedInToday=true after login" || bad "loggedInToday=$loggedIn (expected true)"

  [[ "$todayCorrect" -gt 0 ]] && ok "todayCorrect=$todayCorrect > 0 after quiz" || bad "todayCorrect=$todayCorrect (expected > 0)"

  [[ "$todayActiveMs" -gt 0 ]] && ok "todayActiveMs=$todayActiveMs > 0 after quiz" || bad "todayActiveMs=$todayActiveMs (expected > 0)"

  # 5. The impossible state: loggedInToday=false but todayCorrect>0 should NEVER happen
  if [[ "$loggedIn" == "false" && "$todayCorrect" -gt 0 ]]; then
    bad "TIMEZONE BUG: loggedInToday=false but todayCorrect=$todayCorrect (date mismatch between AuthService and DailyService)"
  else
    ok "No timezone inconsistency (loggedInToday and todayCorrect agree)"
  fi
}

# -----------------------------------------------------------------------------
# Test: admin/today-stats sort order
#   - loggedInToday=true users come first
#   - Within each group, sorted desc by the sort field
#   - sort parameter works (grandTotalCorrect, todayCorrect)
# -----------------------------------------------------------------------------
test_admin_today_stats_sort_order() {
  say "TEST: Admin today-stats — sort order & pagination"

  # 1. Default sort (grandTotalCorrect:desc) — loggedIn users first
  local resp=$(get_json "/admin/today-stats?limit=50&offset=0" "$ADMIN_PIN")
  jq -e 'type=="array"' >/dev/null <<<"$resp" || { bad "Not an array"; return; }

  local count=$(jq 'length' <<<"$resp")
  if [[ "$count" -lt 2 ]]; then
    skip "Need >= 2 users to test sort order (have $count)"
    return
  fi

  # 2. Verify loggedInToday=true users come before loggedInToday=false users
  #    Find the last loggedIn=true index and first loggedIn=false index
  local lastTrueIdx=-1
  local firstFalseIdx=-1
  for (( i=0; i<count; i++ )); do
    local val=$(jq -r ".[$i].loggedInToday" <<<"$resp")
    if [[ "$val" == "true" ]]; then
      lastTrueIdx=$i
    elif [[ "$val" == "false" && $firstFalseIdx -eq -1 ]]; then
      firstFalseIdx=$i
    fi
  done

  if [[ $lastTrueIdx -ge 0 && $firstFalseIdx -ge 0 ]]; then
    if [[ $lastTrueIdx -lt $firstFalseIdx ]]; then
      ok "loggedInToday=true users sorted before false users"
    else
      bad "Sort broken: loggedIn=true at index $lastTrueIdx but false at $firstFalseIdx"
    fi
  else
    ok "loggedInToday grouping OK (only one group present)"
  fi

  # 3. Within each loggedIn group, verify grandTotalCorrect is descending
  local sorted=true
  for (( i=1; i<count; i++ )); do
    local prevLoggedIn=$(jq -r ".[$(( i-1 ))].loggedInToday" <<<"$resp")
    local currLoggedIn=$(jq -r ".[$i].loggedInToday" <<<"$resp")
    # Only check within same group
    if [[ "$prevLoggedIn" == "$currLoggedIn" ]]; then
      local prevVal=$(jq ".[$(( i-1 ))].grandTotalCorrect" <<<"$resp")
      local currVal=$(jq ".[$i].grandTotalCorrect" <<<"$resp")
      if [[ "$prevVal" -lt "$currVal" ]]; then
        sorted=false
        break
      fi
    fi
  done
  [[ "$sorted" == "true" ]] && ok "grandTotalCorrect desc within loggedIn groups" || bad "grandTotalCorrect not desc within group"

  # 4. Test sort=todayCorrect:desc parameter
  local resp2=$(get_json "/admin/today-stats?limit=50&offset=0&sort=todayCorrect:desc" "$ADMIN_PIN")
  local count2=$(jq 'length' <<<"$resp2")
  local sorted2=true
  for (( i=1; i<count2; i++ )); do
    local prevLoggedIn=$(jq -r ".[$(( i-1 ))].loggedInToday" <<<"$resp2")
    local currLoggedIn=$(jq -r ".[$i].loggedInToday" <<<"$resp2")
    if [[ "$prevLoggedIn" == "$currLoggedIn" ]]; then
      local prevVal=$(jq ".[$(( i-1 ))].todayCorrect" <<<"$resp2")
      local currVal=$(jq ".[$i].todayCorrect" <<<"$resp2")
      if [[ "$prevVal" -lt "$currVal" ]]; then
        sorted2=false
        break
      fi
    fi
  done
  [[ "$sorted2" == "true" ]] && ok "sort=todayCorrect:desc works" || bad "todayCorrect sort broken"

  # 5. Test pagination consistency: page1 last entry >= page2 first entry (within same group)
  local page1=$(get_json "/admin/today-stats?limit=5&offset=0" "$ADMIN_PIN")
  local page2=$(get_json "/admin/today-stats?limit=5&offset=5" "$ADMIN_PIN")
  local p1count=$(jq 'length' <<<"$page1")
  local p2count=$(jq 'length' <<<"$page2")

  if [[ "$p1count" -gt 0 && "$p2count" -gt 0 ]]; then
    local p1LastLoggedIn=$(jq -r ".[$((p1count-1))].loggedInToday" <<<"$page1")
    local p2FirstLoggedIn=$(jq -r '.[0].loggedInToday' <<<"$page2")
    local p1LastVal=$(jq ".[$((p1count-1))].grandTotalCorrect" <<<"$page1")
    local p2FirstVal=$(jq '.[0].grandTotalCorrect' <<<"$page2")

    if [[ "$p1LastLoggedIn" == "$p2FirstLoggedIn" ]]; then
      # Same group across pages — values should be descending
      [[ "$p1LastVal" -ge "$p2FirstVal" ]] && ok "Pagination consistent across pages (same group)" || bad "Pagination broken: page1 last=$p1LastVal < page2 first=$p2FirstVal"
    else
      # Group boundary — loggedIn=true should be on page1 side
      [[ "$p1LastLoggedIn" == "true" && "$p2FirstLoggedIn" == "false" ]] && ok "Pagination consistent across group boundary" || bad "Pagination group order broken"
    fi
  else
    skip "Not enough users to test pagination consistency (p1=$p1count, p2=$p2count)"
  fi
}

test_cache() {
  say "TEST: Cache Endpoints"

  local resp=$(get_json "/cache/stats")
  jq -e '.caches' >/dev/null <<<"$resp" && ok "Cache stats available" || bad "No cache stats"
  jq -e '.caches.users' >/dev/null <<<"$resp" && ok "Users cache exists" || bad "No users cache"

  resp=$(get_json "/cache/clear")
  jq -e '.success==true' >/dev/null <<<"$resp" && ok "Cache clear works" || bad "Clear failed"
}

test_delete_user() {
  say "TEST: Delete User"

  local test_pin="9999"
  post_json "/auth/login-pin" "{\"pin\":\"$test_pin\",\"name\":\"DeleteMe\"}" >/dev/null

  local resp=$(delete_json "/user/delete" "$test_pin")
  jq -e '.success==true' >/dev/null <<<"$resp" && ok "User deleted" || bad "Delete failed"

  resp=$(get_json "/user/progress" "$test_pin")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Deleted user cannot access" || bad "User still exists"
}

# =============================================================================
# CONFIG TESTS
# =============================================================================

test_config_get() {
  say "TEST: Config - Get Configuration"

  local resp=$(get_json "/config" "$ADMIN_PIN")

  jq -e '.admin' >/dev/null <<<"$resp" && ok "Has admin section" || bad "Missing admin section"
  jq -e '.blackBeltTimers' >/dev/null <<<"$resp" && ok "Has blackBeltTimers" || bad "Missing blackBeltTimers"
  jq -e '.lightningMode' >/dev/null <<<"$resp" && ok "Has lightningMode" || bad "Missing lightningMode"
  jq -e '.surfMode' >/dev/null <<<"$resp" && ok "Has surfMode" || bad "Missing surfMode"
  jq -e '.general' >/dev/null <<<"$resp" && ok "Has general section" || bad "Missing general"

  # Verify default values
  local lightning_target=$(jq '.lightningMode.targetCorrect' <<<"$resp")
  [[ "$lightning_target" -eq 5 ]] && ok "Default lightning target is 5" || bad "Wrong default target: $lightning_target"

  local surf_questions=$(jq '.surfMode.questionsPerQuiz' <<<"$resp")
  [[ "$surf_questions" -eq 4 ]] && ok "Default surf questions is 4" || bad "Wrong surf questions: $surf_questions"
}

test_config_includes_pretest_mode() {
  say "TEST: Config - Includes Pretest Mode Settings"

  local resp=$(get_json "/config" "$ADMIN_PIN")

  # Verify pretestMode section exists
  jq -e '.pretestMode' >/dev/null <<<"$resp" && ok "Has pretestMode section" || bad "Missing pretestMode section"

  # Verify pretestMode has required fields
  jq -e '.pretestMode.questionCount' >/dev/null <<<"$resp" && ok "Has questionCount" || bad "Missing questionCount"
  jq -e '.pretestMode.defaultTimeLimitMs' >/dev/null <<<"$resp" && ok "Has defaultTimeLimitMs" || bad "Missing defaultTimeLimitMs"
  jq -e '.pretestMode.defaultTimeLimitSeconds' >/dev/null <<<"$resp" && ok "Has defaultTimeLimitSeconds" || bad "Missing defaultTimeLimitSeconds"
  jq -e '.pretestMode.accuracyRequired' >/dev/null <<<"$resp" && ok "Has accuracyRequired" || bad "Missing accuracyRequired"
  jq -e '.pretestMode.description' >/dev/null <<<"$resp" && ok "Has description" || bad "Missing description"
  jq -e '.pretestMode.timeLimitsPerLevel' >/dev/null <<<"$resp" && ok "Has timeLimitsPerLevel" || bad "Missing timeLimitsPerLevel"
  jq -e '.pretestMode.timeLimitsPerLevelHint' >/dev/null <<<"$resp" && ok "Has timeLimitsPerLevelHint" || bad "Missing timeLimitsPerLevelHint"

  # Verify default values
  local questionCount=$(jq '.pretestMode.questionCount' <<<"$resp")
  [[ "$questionCount" -eq 20 ]] && ok "Default pretest questionCount is 20" || bad "Wrong questionCount: $questionCount"

  local defaultTimeLimitMs=$(jq '.pretestMode.defaultTimeLimitMs' <<<"$resp")
  [[ "$defaultTimeLimitMs" -eq 50000 ]] && ok "Default pretest defaultTimeLimitMs is 50000" || bad "Wrong defaultTimeLimitMs: $defaultTimeLimitMs"

  local accuracyRequired=$(jq '.pretestMode.accuracyRequired' <<<"$resp")
  [[ "$accuracyRequired" -eq 100 ]] && ok "Default pretest accuracyRequired is 100" || bad "Wrong accuracyRequired: $accuracyRequired"

  # Verify timeLimitsPerLevel is empty by default (all levels use default)
  local perLevelCount=$(jq '.pretestMode.timeLimitsPerLevel | length' <<<"$resp")
  [[ "$perLevelCount" -eq 0 ]] && ok "timeLimitsPerLevel empty by default" || bad "timeLimitsPerLevel should be empty, got $perLevelCount entries"
}

test_config_unauthorized() {
  say "TEST: Config - Unauthorized Access"

  local resp=$(get_json "/config" "wrong-pin")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects wrong admin PIN" || bad "Should reject unauthorized"

  resp=$(get_json "/config" "$PIN")  # Regular user PIN
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects user PIN" || bad "Should reject user PIN"

  resp=$(get_json "/config" "")
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects empty PIN" || bad "Should reject empty PIN"
}

test_config_update_lightning() {
  say "TEST: Config - Update Lightning Mode Settings"

  # Update lightning settings
  local update_resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{
      "lightningTargetCorrect": 10,
      "lightningFastThresholdMs": 3000
    }')

  jq -e '.success==true' >/dev/null <<<"$update_resp" && ok "Update succeeded" || bad "Update failed"

  # Verify changes persisted
  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local new_target=$(jq '.lightningMode.targetCorrect' <<<"$get_resp")
  local new_threshold=$(jq '.lightningMode.fastThresholdMs' <<<"$get_resp")

  [[ "$new_target" -eq 10 ]] && ok "Lightning target updated to 10" || bad "Target not updated: $new_target"
  [[ "$new_threshold" -eq 3000 ]] && ok "Fast threshold updated to 3000ms" || bad "Threshold not updated: $new_threshold"

  # Reset to defaults
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_update_surf() {
  say "TEST: Config - Update Surf Mode Settings"

  local update_resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{
      "surfQuestionsPerQuiz": 6,
      "surfQuizzesRequired": 3
    }')

  jq -e '.success==true' >/dev/null <<<"$update_resp" && ok "Surf update succeeded" || bad "Surf update failed"

  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local questions=$(jq '.surfMode.questionsPerQuiz' <<<"$get_resp")
  local quizzes=$(jq '.surfMode.quizzesRequired' <<<"$get_resp")

  [[ "$questions" -eq 6 ]] && ok "Surf questions updated to 6" || bad "Questions not updated: $questions"
  [[ "$quizzes" -eq 3 ]] && ok "Surf quizzes updated to 3" || bad "Quizzes not updated: $quizzes"

  # Reset to defaults
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_update_partial() {
  say "TEST: Config - Partial Update"

  # Update only one field
  local update_resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"lightningTargetCorrect": 15}')

  jq -e '.success==true' >/dev/null <<<"$update_resp" && ok "Partial update succeeded" || bad "Partial update failed"

  # Verify only the specified field changed
  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local target=$(jq '.lightningMode.targetCorrect' <<<"$get_resp")
  local threshold=$(jq '.lightningMode.fastThresholdMs' <<<"$get_resp")

  [[ "$target" -eq 15 ]] && ok "Target updated" || bad "Target not updated: $target"
  [[ "$threshold" -eq 2000 ]] && ok "Other fields unchanged" || bad "Other fields changed: $threshold"

  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_update_black_belt_timer() {
  say "TEST: Config - Update Black Belt Timer"

  # Update degree 7 timer
  local update_resp=$(curl -sS -X PUT "${BASE}/config/black-belt/7" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timerMs": 45000}')

  jq -e '.success==true' >/dev/null <<<"$update_resp" && ok "Black belt timer update succeeded" || bad "Timer update failed"

  # Verify change
  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local timer=$(jq '.blackBeltTimers.degree7.ms' <<<"$get_resp")

  [[ "$timer" -eq 45000 ]] && ok "Degree 7 timer updated to 45s" || bad "Timer not updated: $timer"

  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_update_all_black_belt_timers() {
  say "TEST: Config - Update All Black Belt Timers"

  local update_resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{
      "blackBeltTimersMs": {
        "1": 50000,
        "2": 48000,
        "3": 46000,
        "4": 44000,
        "5": 42000,
        "6": 40000,
        "7": 50000
      }
    }')

  jq -e '.success==true' >/dev/null <<<"$update_resp" && ok "All timers updated" || bad "Timers update failed"

  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local timer1=$(jq '.blackBeltTimers.degree1.ms' <<<"$get_resp")
  local timer7=$(jq '.blackBeltTimers.degree7.ms' <<<"$get_resp")

  [[ "$timer1" -eq 50000 ]] && ok "Degree 1 timer correct" || bad "Degree 1 wrong: $timer1"
  [[ "$timer7" -eq 50000 ]] && ok "Degree 7 timer correct" || bad "Degree 7 wrong: $timer7"

  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_update_inactivity() {
  say "TEST: Config - Update Inactivity Threshold"

  local update_resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"inactivityThresholdMs": 10000}')

  jq -e '.success==true' >/dev/null <<<"$update_resp" && ok "Inactivity updated" || bad "Inactivity update failed"

  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local threshold=$(jq '.general.inactivityThresholdMs' <<<"$get_resp")

  [[ "$threshold" -eq 10000 ]] && ok "Inactivity threshold updated to 10s" || bad "Threshold wrong: $threshold"

  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_validation() {
  say "TEST: Config - Validation Rules"

  # Test invalid lightning target (< 1)
  local resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"lightningTargetCorrect": 0}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects invalid target (0)" || bad "Should reject 0"

  # Test invalid threshold (< 500)
  resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"lightningFastThresholdMs": 100}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects invalid threshold (<500ms)" || bad "Should reject low threshold"

  # Test invalid black belt degree (out of range)
  resp=$(curl -sS -X PUT "${BASE}/config/black-belt/8" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timerMs": 60000}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects invalid degree (8)" || bad "Should reject degree 8"

  # Test invalid timer (< 1000ms)
  resp=$(curl -sS -X PUT "${BASE}/config/black-belt/5" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timerMs": 500}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects low timer (<1000ms)" || bad "Should reject low timer"
}

test_admin_pin_update() {
  say "TEST: Admin PIN Update - New PIN Works on All Admin Endpoints"

  local ORIGINAL_PIN="$ADMIN_PIN"  # Whatever the user's current PIN is
  local NEW_PIN="9999"

  # Step 1: Update admin PIN
  local update_resp=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ORIGINAL_PIN" \
    -H "Content-Type: application/json" \
    -d "{\"currentPin\": \"$ORIGINAL_PIN\", \"newPin\": \"$NEW_PIN\"}")

  if ! jq -e '.success==true' >/dev/null <<<"$update_resp"; then
    bad "Admin PIN update failed"
    return
  fi
  ok "Admin PIN update succeeded"

  # Step 2: Verify NEW PIN works on /api/config
  local config_resp=$(get_json "/config" "$NEW_PIN")
  jq -e '.admin' >/dev/null <<<"$config_resp" && ok "New PIN works on /api/config" || bad "New PIN rejected on /api/config"

  # Step 3: Verify OLD PIN is rejected on /api/config
  local old_pin_config=$(get_json "/config" "$ORIGINAL_PIN")
  jq -e '.error' >/dev/null <<<"$old_pin_config" && ok "Old PIN rejected on /api/config" || bad "Old PIN still works on /api/config"

  # Step 4: **THE BUG TEST** - Verify NEW PIN works on /api/admin/today-stats
  local admin_resp=$(get_json "/admin/today-stats" "$NEW_PIN")
  if jq -e 'type=="array"' >/dev/null <<<"$admin_resp"; then
    ok "New PIN works on /api/admin/today-stats"
  else
    bad "BUG: New PIN rejected on /api/admin/today-stats (hardcoded PIN in AdminResource)"
  fi

  # Step 5: Verify OLD PIN is rejected on /api/admin/today-stats
  local old_pin_admin=$(get_json "/admin/today-stats" "$ORIGINAL_PIN")
  if jq -e '.error' >/dev/null <<<"$old_pin_admin"; then
    ok "Old PIN rejected on /api/admin/today-stats"
  else
    bad "BUG: Old PIN still works on /api/admin/today-stats (hardcoded PIN in AdminResource)"
  fi

  # CLEANUP: Restore original PIN (using the new PIN to authenticate)
  local restore_resp=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $NEW_PIN" \
    -H "Content-Type: application/json" \
    -d "{\"currentPin\": \"$NEW_PIN\", \"newPin\": \"$ORIGINAL_PIN\"}")

  if jq -e '.success==true' >/dev/null <<<"$restore_resp"; then
    ok "Original PIN restored"
  else
    bad "CRITICAL: Failed to restore original PIN! Current PIN is now: $NEW_PIN"
  fi
}

test_admin_pin_update_validation() {
  say "TEST: Admin PIN Update - Validation Rules"

  # These tests intentionally fail validation, so PIN never changes - no cleanup needed

  local wrong_current=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"currentPin": "wrong", "newPin": "1234"}')
  jq -e '.error' >/dev/null <<<"$wrong_current" && ok "Rejects wrong current PIN" || bad "Should reject wrong current PIN"

  local short_pin=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d "{\"currentPin\": \"$ADMIN_PIN\", \"newPin\": \"123\"}")
  jq -e '.error' >/dev/null <<<"$short_pin" && ok "Rejects PIN < 4 chars" || bad "Should reject short PIN"

  local same_pin=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d "{\"currentPin\": \"$ADMIN_PIN\", \"newPin\": \"$ADMIN_PIN\"}")
  jq -e '.error' >/dev/null <<<"$same_pin" && ok "Rejects same PIN" || bad "Should reject same PIN"

  local empty_pin=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d "{\"currentPin\": \"$ADMIN_PIN\", \"newPin\": \"\"}")
  jq -e '.error' >/dev/null <<<"$empty_pin" && ok "Rejects empty new PIN" || bad "Should reject empty PIN"
}

test_admin_pin_persistence() {
  say "TEST: Admin PIN Update - Persistence After Config Reload"

  local ORIGINAL_PIN="$ADMIN_PIN"
  local NEW_PIN="5555"

  # Update PIN
  curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ORIGINAL_PIN" \
    -H "Content-Type: application/json" \
    -d "{\"currentPin\": \"$ORIGINAL_PIN\", \"newPin\": \"$NEW_PIN\"}" >/dev/null

  # Force reload from database (simulates server restart loading from DB)
  curl -sS -X POST "${BASE}/config/reload" -H "x-pin: $NEW_PIN" >/dev/null

  # Verify new PIN still works after reload
  local after_reload=$(get_json "/config" "$NEW_PIN")
  jq -e '.admin' >/dev/null <<<"$after_reload" && ok "New PIN persists after reload" || bad "New PIN lost after reload"

  # Verify on admin endpoint after reload
  local admin_after=$(get_json "/admin/today-stats" "$NEW_PIN")
  if jq -e 'type=="array"' >/dev/null <<<"$admin_after"; then
    ok "New PIN works on /api/admin after reload"
  else
    bad "BUG: New PIN rejected on /api/admin after reload"
  fi

  # CLEANUP: Restore original PIN
  local restore_resp=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $NEW_PIN" \
    -H "Content-Type: application/json" \
    -d "{\"currentPin\": \"$NEW_PIN\", \"newPin\": \"$ORIGINAL_PIN\"}")

  if jq -e '.success==true' >/dev/null <<<"$restore_resp"; then
    ok "Original PIN restored"
  else
    bad "CRITICAL: Failed to restore original PIN! Current PIN is now: $NEW_PIN"
  fi
}

test_config_admin_pin_change() {
  say "TEST: Config - Change Admin PIN"

  # Change PIN from 7878 to 9999
  local change_resp=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"currentPin": "7878", "newPin": "9999"}')

  jq -e '.success==true' >/dev/null <<<"$change_resp" && ok "PIN change succeeded" || bad "PIN change failed"

  # Verify old PIN no longer works
  local old_resp=$(get_json "/config" "7878")
  jq -e '.error' >/dev/null <<<"$old_resp" && ok "Old PIN rejected" || bad "Old PIN still works"

  # Verify new PIN works
  local new_resp=$(get_json "/config" "9999")
  jq -e '.admin' >/dev/null <<<"$new_resp" && ok "New PIN works" || bad "New PIN doesn't work"

  # Change back to 7878
  curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: 9999" \
    -H "Content-Type: application/json" \
    -d '{"currentPin": "9999", "newPin": "7878"}' >/dev/null
}

test_config_admin_pin_validation() {
  say "TEST: Config - Admin PIN Validation"

  # Test wrong current PIN
  local resp=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"currentPin": "wrong", "newPin": "9999"}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects wrong current PIN" || bad "Should reject wrong PIN"

  # Test short new PIN (< 4 chars)
  resp=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"currentPin": "7878", "newPin": "123"}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects short PIN" || bad "Should reject short PIN"

  # Test empty new PIN
  resp=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"currentPin": "7878", "newPin": ""}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects empty PIN" || bad "Should reject empty PIN"

  # Test same PIN
  resp=$(curl -sS -X PUT "${BASE}/config/admin-pin" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"currentPin": "7878", "newPin": "7878"}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects identical PIN" || bad "Should reject same PIN"
}

test_config_reset() {
  say "TEST: Config - Reset to Defaults"

  # Make some changes
  curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"lightningTargetCorrect": 50, "surfQuestionsPerQuiz": 10}' >/dev/null

  # Reset
  local reset_resp=$(curl -sS -X POST "${BASE}/config/reset" \
    -H "x-pin: $ADMIN_PIN")

  jq -e '.success==true' >/dev/null <<<"$reset_resp" && ok "Reset succeeded" || bad "Reset failed"

  # Verify defaults restored
  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local target=$(jq '.lightningMode.targetCorrect' <<<"$get_resp")
  local questions=$(jq '.surfMode.questionsPerQuiz' <<<"$get_resp")

  [[ "$target" -eq 5 ]] && ok "Lightning target reset to 5" || bad "Target not reset: $target"
  [[ "$questions" -eq 4 ]] && ok "Surf questions reset to 4" || bad "Questions not reset: $questions"
}

test_config_reload() {
  say "TEST: Config - Reload from Database"

  local reload_resp=$(curl -sS -X POST "${BASE}/config/reload" \
    -H "x-pin: $ADMIN_PIN")

  jq -e '.success==true' >/dev/null <<<"$reload_resp" && ok "Reload succeeded" || bad "Reload failed"
  jq -e '.config' >/dev/null <<<"$reload_resp" && ok "Returns config" || bad "No config in response"
}

test_config_cannot_update_admin_pin_via_config() {
  say "TEST: Config - Cannot Update Admin PIN via General Config Endpoint"

  local resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"adminPin": "hacked"}')

  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejects adminPin in config update" || bad "Should reject adminPin field"
}

test_config_affects_game_behavior() {
  say "TEST: Config - Changes Affect Game Behavior"

  reset_user

  # Change lightning target to 3
  curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"lightningTargetCorrect": 3}' >/dev/null

  # Start lightning mode
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"lightning"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local target=$(jq '.targetCorrect // 0' <<<"$prep")

  [[ "$target" -eq 3 ]] && ok "New config applied to game" || bad "Config not applied: target=$target"

  # Reset config
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_persistence() {
  say "TEST: Config - Changes Persist Across Requests"

  # Make a change
  curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"lightningTargetCorrect": 20}' >/dev/null

  # Get config (first request)
  local resp1=$(get_json "/config" "$ADMIN_PIN")
  local target1=$(jq '.lightningMode.targetCorrect' <<<"$resp1")

  # Get config again (second request - should come from cache)
  local resp2=$(get_json "/config" "$ADMIN_PIN")
  local target2=$(jq '.lightningMode.targetCorrect' <<<"$resp2")

  [[ "$target1" -eq 20 ]] && ok "First request has new value" || bad "First request wrong: $target1"
  [[ "$target2" -eq 20 ]] && ok "Second request consistent" || bad "Second request wrong: $target2"
  [[ "$target1" -eq "$target2" ]] && ok "Values consistent" || bad "Inconsistent values"

  # Reset
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_multiple_fields() {
  say "TEST: Config - Update Multiple Fields Simultaneously"

  local update_resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{
      "lightningTargetCorrect": 8,
      "lightningFastThresholdMs": 2500,
      "surfQuestionsPerQuiz": 5,
      "surfQuizzesRequired": 4,
      "inactivityThresholdMs": 6000
    }')

  jq -e '.success==true' >/dev/null <<<"$update_resp" && ok "Multi-field update succeeded" || bad "Update failed"

  # Verify all changes
  local get_resp=$(get_json "/config" "$ADMIN_PIN")

  local checks=(
    ".lightningMode.targetCorrect==8"
    ".lightningMode.fastThresholdMs==2500"
    ".surfMode.questionsPerQuiz==5"
    ".surfMode.quizzesRequired==4"
    ".general.inactivityThresholdMs==6000"
  )

  for check in "${checks[@]}"; do
    jq -e "$check" >/dev/null <<<"$get_resp" && ok "Verified: $check" || bad "Failed: $check"
  done

  # Reset
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_pretest_per_level_update() {
  say "TEST: Config - Update Pretest Time Limit Per Level"

  # Reset config first
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null

  # Set per-level time limit for level 5
  local resp=$(curl -sS -X PUT "${BASE}/config/pretest-time/5" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timeLimitMs": 45000}')

  jq -e '.success==true' >/dev/null <<<"$resp" && ok "Per-level update succeeded" || bad "Per-level update failed"

  # Verify level 5 override appears in config
  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local level5_ms=$(jq '.pretestMode.timeLimitsPerLevel.level5.ms' <<<"$get_resp")
  [[ "$level5_ms" -eq 45000 ]] && ok "Level 5 override is 45000ms" || bad "Level 5 override wrong: $level5_ms"

  local level5_sec=$(jq '.pretestMode.timeLimitsPerLevel.level5.seconds' <<<"$get_resp")
  [[ "$level5_sec" == "45" || "$level5_sec" == "45.0" ]] && ok "Level 5 seconds is 45" || bad "Level 5 seconds wrong: $level5_sec"

  # Default should still be 50000
  local default_ms=$(jq '.pretestMode.defaultTimeLimitMs' <<<"$get_resp")
  [[ "$default_ms" -eq 50000 ]] && ok "Default still 50000ms" || bad "Default changed: $default_ms"

  # Reset
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_pretest_per_level_batch_update() {
  say "TEST: Config - Batch Update Per-Level Pretest Time Limits"

  # Reset config first
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null

  # Batch update multiple levels
  local resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{
      "pretestTimeLimitsPerLevelMs": {
        "1": 60000,
        "5": 55000,
        "10": 45000,
        "15": 40000,
        "19": 35000
      }
    }')

  jq -e '.success==true' >/dev/null <<<"$resp" && ok "Batch per-level update succeeded" || bad "Batch update failed"

  # Verify each level
  local get_resp=$(get_json "/config" "$ADMIN_PIN")

  local l1=$(jq '.pretestMode.timeLimitsPerLevel.level1.ms' <<<"$get_resp")
  [[ "$l1" -eq 60000 ]] && ok "Level 1 = 60000ms" || bad "Level 1 wrong: $l1"

  local l5=$(jq '.pretestMode.timeLimitsPerLevel.level5.ms' <<<"$get_resp")
  [[ "$l5" -eq 55000 ]] && ok "Level 5 = 55000ms" || bad "Level 5 wrong: $l5"

  local l10=$(jq '.pretestMode.timeLimitsPerLevel.level10.ms' <<<"$get_resp")
  [[ "$l10" -eq 45000 ]] && ok "Level 10 = 45000ms" || bad "Level 10 wrong: $l10"

  local l15=$(jq '.pretestMode.timeLimitsPerLevel.level15.ms' <<<"$get_resp")
  [[ "$l15" -eq 40000 ]] && ok "Level 15 = 40000ms" || bad "Level 15 wrong: $l15"

  local l19=$(jq '.pretestMode.timeLimitsPerLevel.level19.ms' <<<"$get_resp")
  [[ "$l19" -eq 35000 ]] && ok "Level 19 = 35000ms" || bad "Level 19 wrong: $l19"

  # Verify count: exactly 5 per-level overrides
  local count=$(jq '.pretestMode.timeLimitsPerLevel | length' <<<"$get_resp")
  [[ "$count" -eq 5 ]] && ok "5 per-level overrides set" || bad "Wrong count: $count"

  # Reset
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_pretest_per_level_remove_override() {
  say "TEST: Config - Remove Per-Level Override (Fall Back to Default)"

  # Reset config first
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null

  # Set override for level 3
  curl -sS -X PUT "${BASE}/config/pretest-time/3" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timeLimitMs": 40000}' >/dev/null

  # Verify it was set
  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local l3=$(jq '.pretestMode.timeLimitsPerLevel.level3.ms' <<<"$get_resp")
  [[ "$l3" -eq 40000 ]] && ok "Level 3 override set to 40000ms" || bad "Level 3 not set: $l3"

  # Remove override by setting to 0
  local resp=$(curl -sS -X PUT "${BASE}/config/pretest-time/3" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timeLimitMs": 0}')

  jq -e '.success==true' >/dev/null <<<"$resp" && ok "Remove override succeeded" || bad "Remove override failed"

  # Verify level 3 override is gone
  get_resp=$(get_json "/config" "$ADMIN_PIN")
  local count=$(jq '.pretestMode.timeLimitsPerLevel | length' <<<"$get_resp")
  [[ "$count" -eq 0 ]] && ok "timeLimitsPerLevel empty after removal" || bad "Still has $count entries"

  # Reset
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_pretest_per_level_validation() {
  say "TEST: Config - Per-Level Pretest Time Limit Validation"

  # Test invalid level (0)
  local resp=$(curl -sS -X PUT "${BASE}/config/pretest-time/0" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timeLimitMs": 50000}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejected level 0" || bad "Should reject level 0"

  # Test invalid level (20)
  resp=$(curl -sS -X PUT "${BASE}/config/pretest-time/20" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timeLimitMs": 50000}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejected level 20" || bad "Should reject level 20"

  # Test too-low time limit (500ms)
  resp=$(curl -sS -X PUT "${BASE}/config/pretest-time/1" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timeLimitMs": 500}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejected 500ms (< 1000)" || bad "Should reject 500ms"

  # Test missing body
  resp=$(curl -sS -X PUT "${BASE}/config/pretest-time/1" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{}')
  jq -e '.error' >/dev/null <<<"$resp" && ok "Rejected missing timeLimitMs" || bad "Should reject missing body"

  # Test valid level 19 (boundary)
  resp=$(curl -sS -X PUT "${BASE}/config/pretest-time/19" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timeLimitMs": 30000}')
  jq -e '.success==true' >/dev/null <<<"$resp" && ok "Accepted level 19 with 30000ms" || bad "Should accept level 19"

  # Test valid level 1 (boundary)
  resp=$(curl -sS -X PUT "${BASE}/config/pretest-time/1" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"timeLimitMs": 1000}')
  jq -e '.success==true' >/dev/null <<<"$resp" && ok "Accepted level 1 with 1000ms (minimum)" || bad "Should accept 1000ms"

  # Reset
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

test_config_pretest_default_used_when_no_override() {
  say "TEST: Config - Default Time Limit Used When No Per-Level Override"

  # Reset config to ensure clean state
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null

  # Verify no per-level overrides exist
  local get_resp=$(get_json "/config" "$ADMIN_PIN")
  local count=$(jq '.pretestMode.timeLimitsPerLevel | length' <<<"$get_resp")
  [[ "$count" -eq 0 ]] && ok "No per-level overrides after reset" || bad "Should have 0 overrides, got $count"

  # Default should be 50000ms
  local default_ms=$(jq '.pretestMode.defaultTimeLimitMs' <<<"$get_resp")
  [[ "$default_ms" -eq 50000 ]] && ok "Default is 50000ms" || bad "Default wrong: $default_ms"

  # Update the global default to 60000ms
  local resp=$(curl -sS -X PUT "${BASE}/config" \
    -H "x-pin: $ADMIN_PIN" \
    -H "Content-Type: application/json" \
    -d '{"pretestTimeLimitMs": 60000}')
  jq -e '.success==true' >/dev/null <<<"$resp" && ok "Updated global default to 60000ms" || bad "Global update failed"

  # Verify global default changed
  get_resp=$(get_json "/config" "$ADMIN_PIN")
  default_ms=$(jq '.pretestMode.defaultTimeLimitMs' <<<"$get_resp")
  [[ "$default_ms" -eq 60000 ]] && ok "Global default now 60000ms" || bad "Global default wrong: $default_ms"

  # Per-level should still be empty (all levels use 60000ms now)
  count=$(jq '.pretestMode.timeLimitsPerLevel | length' <<<"$get_resp")
  [[ "$count" -eq 0 ]] && ok "No per-level overrides (all use global)" || bad "Should have 0 overrides"

  # Reset
  curl -sS -X POST "${BASE}/config/reset" -H "x-pin: $ADMIN_PIN" >/dev/null
}

# =============================================================================
# MAIN
# =============================================================================
main() {
  local script_start=$(date +%s)

  say "═══════════════════════════════════════════════════════════════"
  say "  INFINITYISLAND — COMPLETE TEST SUITE (MERGED)"
  say "  Includes: Auth, User, Quiz, Progression, Game Mode (Lightning),"
  say "            Surf Mode, ForcePass, Resume, Analytics, Edge Cases"
  say "═══════════════════════════════════════════════════════════════"
  printf " Target:      %s\n" "$BASE"
  printf " User:        %s (PIN: %s)\n" "$NAME" "$PIN"
  printf " Game Target: %s\n" "$TARGET"
  printf " Debug:       %s\n" "$DEBUG"
  printf " Surf Config:\n"
  printf "   - Questions per quiz: %d\n" "$SURF_QUESTIONS_PER_QUIZ"
  printf "   - Quizzes required:   %d\n" "$SURF_QUIZZES_REQUIRED"
  printf "   - Inactivity (ms):    %d\n" "$INACTIVITY_THRESHOLD_MS"
  say "═══════════════════════════════════════════════════════════════"

  cleanup_user

  # ─────────────────────────────────────────────────────────────────
  # AUTH & USER
  # ─────────────────────────────────────────────────────────────────
  test_auth
  test_user

  # ─────────────────────────────────────────────────────────────────
  # QUIZ BASICS
  # ─────────────────────────────────────────────────────────────────
  test_quiz_operations
  test_quiz_prepare_start
  test_quiz_wrong_flow
  test_quiz_inactivity
  test_quiz_complete_endpoint

  # ─────────────────────────────────────────────────────────────────
  # PROGRESSION
  # ─────────────────────────────────────────────────────────────────
  test_colored_progression
  test_black_belt
  test_level_progression

  # ─────────────────────────────────────────────────────────────────
  # GAME MODE (LIGHTNING)
  # ─────────────────────────────────────────────────────────────────
  test_gamemode_default_target
  test_gamemode_basic
  test_gamemode_cycling
  test_gamemode_prepare_has_practice
  test_gamemode_wrong_triggers_practice
  test_gamemode_practice_flow
  test_gamemode_inactivity_triggers_practice
  test_gamemode_inactivity_via_slow_response
  test_gamemode_fast_answer
  test_gamemode_slow_answer
  test_gamemode_slow_answer_boundary
  test_gamemode_fast_answer_boundary
  test_gamemode_daily_stats_all_correct
  test_gamemode_mixed_flow
  test_gamemode_completion_requires_fast
  test_gamemode_completion
  test_gamemode_full_flow
  test_gamemode_resume
  test_gamemode_daily_stats
  test_gamemode_black_belt
  test_gamemode_black_no_practice_in_prepare
  test_gamemode_black_wrong_triggers_practice

  # ─────────────────────────────────────────────────────────────────
  # FORCEPASS
  # ─────────────────────────────────────────────────────────────────
  test_forcepass_normal_mode
  test_forcepass_gamemode
  test_forcepass_first_question
  test_forcepass_chain

  # ─────────────────────────────────────────────────────────────────
  # RESUME
  # ─────────────────────────────────────────────────────────────────
  test_resume_normal_partial
  test_resume_gamemode_across_sessions
  test_resume_after_wrong
  test_resume_different_belt

  # ─────────────────────────────────────────────────────────────────
  # SURF MODE (GAME MODE 2)
  # ─────────────────────────────────────────────────────────────────
  test_surf_requires_lightning
  test_surf_unlocks_after_lightning
  test_surf_prepare_response_fields
  test_surf_prepare_resume
  test_surf_start_response
  test_surf_questions_structure
  test_surf_correct_answer
  test_surf_streak_increments
  test_surf_wrong_answer_fails_quiz
  test_surf_inactivity_fails_quiz
  test_surf_inactivity_endpoint
  test_surf_quiz_pass
  test_surf_practice_wrong_continues
  test_surf_practice_correct_restarts_quiz
  test_surf_mode_completion
  test_surf_no_belt_on_lightning_only
  test_surf_resume_mid_quiz
  test_surf_resume_after_quiz_pass
  test_surf_resume_after_failed_quiz
  test_surf_resume_after_failed_quiz_at_index_gt0
  test_surf_daily_stats_tracking
  test_surf_failure_counter
  test_surf_duplicate_answer
  test_surf_answer_completed_quiz
  test_surf_fresh_questions_on_restart
  test_surf_complete_flow_with_failures
  test_surf_different_belts

  # ─────────────────────────────────────────────────────────────────
  # PRETEST MODE
  # ─────────────────────────────────────────────────────────────────
  test_pretest_triggered_on_fresh_user
  test_pretest_start_response
  test_pretest_pass_awards_level
  test_pretest_forcepass
  test_pretest_wrong_answer_practice
  test_pretest_inactivity
  test_pretest_inactivity_threshold
  test_pretest_not_retriggered
  test_pretest_resume
  test_pretest_per_operation
  test_pretest_progress_includes_status
  test_pretest_progress_updates_after_taken
  test_pretest_progress_failed_status
  test_pretest_time_not_doubled

  # ─────────────────────────────────────────────────────────────────
  # ANALYTICS
  # ─────────────────────────────────────────────────────────────────
  test_analytics_summary_basic
  test_analytics_summary_empty
  test_analytics_summary_level_breakdown
  test_analytics_summary_operation_breakdown
  test_analytics_facts_basic
  test_analytics_facts_structure
  test_analytics_facts_pagination
  test_analytics_facts_level_filter
  test_analytics_facts_operation_filter
  test_analytics_facts_combined_filter
  test_analytics_fact_detail
  test_analytics_fact_detail_recent_attempts
  test_analytics_fact_detail_nonexistent
  test_analytics_struggling_basic
  test_analytics_struggling_threshold
  test_analytics_struggling_level_filter
  test_analytics_gamemode_tracking
  test_analytics_gamemode_cycling_accuracy
  test_analytics_wrong_answer_tracking
  test_analytics_inactivity_tracking
  test_analytics_response_times
  test_analytics_cross_belt_tracking
  test_analytics_reset_clears_data
  test_analytics_mastery_indicators
  test_analytics_auth
  test_analytics_level_endpoint

  # ─────────────────────────────────────────────────────────────────
  # EDGE CASES
  # ─────────────────────────────────────────────────────────────────
  test_multiple_wrong_practice
  test_exact_target_completion
  test_daily_accumulation
  test_answer_completed_quiz
  test_duplicate_submission

  # ─────────────────────────────────────────────────────────────────
  # DUPLICATE PREVENTION (Unique Index)
  # ─────────────────────────────────────────────────────────────────
  test_no_duplicate_lightning_runs
  test_no_duplicate_pretest_runs
  test_lightning_mode_type_set

  # ─────────────────────────────────────────────────────────────────
  # NEGATIVE & ADMIN
  # ─────────────────────────────────────────────────────────────────
  test_negative_cases
  test_admin
  test_admin_today_stats_consistency
  test_admin_today_stats_sort_order
  test_cache
  test_delete_user

  # ─────────────────────────────────────────────────────────────────
  # CONFIG TESTS
  # ─────────────────────────────────────────────────────────────────
  test_config_get
  test_config_includes_pretest_mode
  test_config_unauthorized
  test_config_update_lightning
  test_config_update_surf
  test_config_update_partial
  test_config_update_black_belt_timer
  test_config_update_all_black_belt_timers
  test_config_update_inactivity
  test_config_validation
  # DISABLED: admin PIN change tests - risk of leaving PIN in changed state
  # test_config_admin_pin_change
  # test_admin_pin_update
  # test_admin_pin_update_validation
  # test_admin_pin_persistence
  # test_config_admin_pin_validation
  # test_config_reset  # DISABLED: resets admin PIN to default
  test_config_reload
  test_config_cannot_update_admin_pin_via_config
  test_config_affects_game_behavior
  test_config_persistence
  test_config_multiple_fields
  test_config_pretest_per_level_update
  test_config_pretest_per_level_batch_update
  test_config_pretest_per_level_remove_override
  test_config_pretest_per_level_validation
  test_config_pretest_default_used_when_no_override

  # ─────────────────────────────────────────────────────────────────
  # SUMMARY
  # ─────────────────────────────────────────────────────────────────
  local script_end=$(date +%s)
  local script_time=$((script_end - script_start))

  echo ""
  say "═══════════════════════════════════════════════════════════════"
  say "  TEST SUMMARY"
  say "═══════════════════════════════════════════════════════════════"
  printf " ${GREEN}✓ PASSED:${NC}  %3d\n" "$PASS"
  printf " ${RED}✗ FAILED:${NC}  %3d\n" "$FAIL"
  printf " ${YELLOW}⊘ SKIPPED:${NC} %3d\n" "$SKIP"
  say "───────────────────────────────────────────────────────────────"
  printf " Total Time: %ds\n" "$script_time"
  say "═══════════════════════════════════════════════════════════════"

  if [[ $FAIL -gt 0 ]]; then
    printf "\n${RED}${BOLD}✗ TESTS FAILED${NC}\n"
    exit 1
  else
    printf "\n${GREEN}${BOLD}✓ ALL TESTS PASSED!${NC}\n"
    exit 0
  fi
}

main "$@"