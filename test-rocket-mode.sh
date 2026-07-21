#!/usr/bin/env bash
# =============================================================================
# InfinityIsland — ROCKET MODE (GAME MODE 3) TEST SUITE
# Tests: Prerequisite enforcement, prepare/start/answer flow, quiz pass/fail,
#        practice/restart, inactivity, resume, force pass, completion, config
# =============================================================================
set -o pipefail

# ---------- Config ----------
BASE="${BASE:-http://localhost:8081/api}"
PIN="${PIN:-4705}"
NAME="${NAME:-Uday}"
ADMIN_PIN="${ADMIN_PIN:-7878}"
DEBUG="${DEBUG:-0}"
TARGET="${TARGET:-100}"
LIGHTNING_TARGET="${LIGHTNING_TARGET:-100}"

# Rocket mode constants (from GameModeConfig)
ROCKET_QUESTIONS_PER_QUIZ=4
ROCKET_QUIZZES_REQUIRED=5
INACTIVITY_THRESHOLD_MS=5000

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

put_json() {
  local url="${BASE%/}$1" data="$2" pin="${3:-$PIN}"
  local resp=$(curl -s -X PUT \
    -H 'Content-Type: application/json' \
    -H "x-pin: $pin" \
    "$url" -d "$data")
  debug "PUT $url"
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

  if jq -e '.pretestMode==true' >/dev/null <<<"$prep" 2>/dev/null; then
    local runId=$(jq -r '.quizRunId' <<<"$prep")
    if [[ "$runId" != "null" && -n "$runId" ]]; then
      local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
      local qid=$(jq -r '.questions[0]._id' <<<"$start")
      post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true,\"skipLevelAward\":true}" >/dev/null
      debug "Skipped pretest for $op level $level (no level award)"
    fi
  else
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

reset_user() {
  post_json "/user/reset" '{}' >/dev/null
  sleep 0.3
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null
  skip_all_pretests 1
}

rand_between() {
  local min=$1 max=$2
  echo $(( (RANDOM % (max - min + 1)) + min ))
}

# Complete a game mode quiz (cycling for lightning)
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

# Complete a single rocket quiz (4 consecutive correct answers using index-based selection)
complete_rocket_quiz() {
  local runId="$1"
  local questions="$2"
  local responseMs="${3:-500}"

  for i in 0 1 2 3; do
    local qid=$(jq -r ".[$i]._id" <<<"$questions")
    local correct=$(jq ".[$i].correctAnswer" <<<"$questions")

    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":$responseMs}")

    if jq -e '.rocketQuizPassed==true or .completed==true' >/dev/null <<<"$resp"; then
      echo "$resp"
      return 0
    fi
  done

  echo "ERROR: Did not pass rocket quiz"
  return 1
}

# Complete surf mode (all 5 quizzes) for a given belt
complete_surf_mode() {
  local level="${1:-1}"
  local belt="${2:-white}"
  local op="${3:-add}"

  local prep=$(post_json "/quiz/prepare" "{\"level\":$level,\"beltOrDegree\":\"$belt\",\"operation\":\"$op\",\"gameMode\":true,\"gameModeType\":\"surf\"}")
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  if [[ "$runId" == "null" || -z "$runId" ]]; then
    echo "ERROR: Failed to prepare surf mode"
    return 1
  fi

  for quiz in 1 2 3 4 5; do
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local questions=$(jq '.questions' <<<"$start")

    for i in 0 1 2 3; do
      local qid=$(jq -r ".[$i]._id" <<<"$questions")
      local correct=$(jq ".[$i].correctAnswer" <<<"$questions")
      local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")

      if jq -e '.completed==true' >/dev/null <<<"$resp"; then
        echo "$resp"
        return 0
      fi
    done
  done
}

# =============================================================================
# ROCKET MODE - PREREQUISITE TESTS
# =============================================================================
test_rocket_requires_surf() {
  say "TEST: Rocket Mode - Requires Surf Completion"
  reset_user

  # Try rocket mode without completing lightning or surf
  local resp=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')

  jq -e '.error.message' >/dev/null <<<"$resp" && ok "Rocket mode blocked without surf" || bad "Should block rocket mode"

  local msg=$(jq -r '.error.message // ""' <<<"$resp")
  [[ "$msg" == *"Surf mode must be completed"* ]] && ok "Correct error message" || bad "Wrong error: $msg"
}

test_rocket_requires_surf_not_just_lightning() {
  say "TEST: Rocket Mode - Blocked After Lightning Only"
  reset_user

  # Complete lightning but NOT surf
  complete_lightning_mode 1 "white" "add" >/dev/null

  local resp=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')

  jq -e '.error.message' >/dev/null <<<"$resp" && ok "Rocket blocked with only lightning" || bad "Should block rocket"

  local msg=$(jq -r '.error.message // ""' <<<"$resp")
  [[ "$msg" == *"Surf mode must be completed"* ]] && ok "Correct error message" || bad "Wrong error: $msg"
}

test_rocket_unlocks_after_surf() {
  say "TEST: Rocket Mode - Unlocks After Surf Completion"
  reset_user

  subsay "Completing lightning mode..."
  complete_lightning_mode 1 "white" "add" >/dev/null

  subsay "Completing surf mode..."
  complete_surf_mode 1 "white" "add" >/dev/null

  subsay "Starting rocket mode..."
  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')

  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Rocket mode available" || bad "Rocket mode blocked"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$prep" && ok "gameModeType=rocket" || bad "Wrong gameModeType"
  jq -e '.rocketQuizNumber==1' >/dev/null <<<"$prep" && ok "rocketQuizNumber=1" || bad "Wrong rocketQuizNumber"
  jq -e '.completedRocketQuizzes==0' >/dev/null <<<"$prep" && ok "completedRocketQuizzes=0" || bad "Wrong completedRocketQuizzes"
}

# =============================================================================
# ROCKET MODE - PREPARE TESTS
# =============================================================================
test_rocket_prepare_response_fields() {
  say "TEST: Rocket Mode - Prepare Response Fields"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')

  jq -e '.quizRunId' >/dev/null <<<"$prep" && ok "Has quizRunId" || bad "Missing quizRunId"
  jq -e '.gameMode==true' >/dev/null <<<"$prep" && ok "gameMode=true" || bad "gameMode wrong"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$prep" && ok "gameModeType=rocket" || bad "gameModeType wrong"
  jq -e '.rocketQuizNumber' >/dev/null <<<"$prep" && ok "Has rocketQuizNumber" || bad "Missing rocketQuizNumber"
  jq -e '.rocketCorrectStreak==0' >/dev/null <<<"$prep" && ok "rocketCorrectStreak=0" || bad "rocketCorrectStreak wrong"
  jq -e '.completedRocketQuizzes==0' >/dev/null <<<"$prep" && ok "completedRocketQuizzes=0" || bad "completedRocketQuizzes wrong"
  jq -e ".rocketQuizzesRequired==$ROCKET_QUIZZES_REQUIRED" >/dev/null <<<"$prep" && ok "rocketQuizzesRequired=$ROCKET_QUIZZES_REQUIRED" || bad "rocketQuizzesRequired wrong"
  jq -e '.practice | type=="array"' >/dev/null <<<"$prep" && ok "Has practice array" || bad "Missing practice"
}

test_rocket_prepare_resume() {
  say "TEST: Rocket Mode - Resume Existing Run"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep1=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep1")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer 2 questions correctly
  for i in 0 1; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  # Prepare again - should resume
  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$prep2" && ok "gameModeType=rocket" || bad "Wrong gameModeType"
  jq -e '.rocketCorrectStreak==2' >/dev/null <<<"$prep2" && ok "rocketCorrectStreak=2 preserved" || bad "Streak not preserved"

  local retRunId=$(jq -r '.quizRunId' <<<"$prep2")
  [[ "$retRunId" == "$runId" ]] && ok "Same runId returned" || bad "Different runId"
}

# =============================================================================
# ROCKET MODE - START TESTS
# =============================================================================
test_rocket_start_response() {
  say "TEST: Rocket Mode - Start Response"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qcount=$(jq '.questions | length' <<<"$start")
  [[ "$qcount" -eq $ROCKET_QUESTIONS_PER_QUIZ ]] && ok "$ROCKET_QUESTIONS_PER_QUIZ questions for rocket quiz" || bad "Wrong count: $qcount"

  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$start" && ok "gameModeType=rocket" || bad "gameModeType wrong"
  jq -e '.rocketQuizNumber==1' >/dev/null <<<"$start" && ok "rocketQuizNumber=1" || bad "rocketQuizNumber wrong"
  jq -e '.rocketCorrectStreak==0' >/dev/null <<<"$start" && ok "rocketCorrectStreak=0" || bad "rocketCorrectStreak wrong"
  jq -e ".questionsPerQuiz==$ROCKET_QUESTIONS_PER_QUIZ" >/dev/null <<<"$start" && ok "questionsPerQuiz=$ROCKET_QUESTIONS_PER_QUIZ" || bad "questionsPerQuiz wrong"
}

test_rocket_questions_structure() {
  say "TEST: Rocket Mode - Question Structure (Reverse Format)"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2 3; do
    local q=$(jq ".questions[$i]" <<<"$start")
    jq -e '._id' >/dev/null <<<"$q" && ok "Q$i has _id" || bad "Q$i missing _id"
    jq -e '.question' >/dev/null <<<"$q" && ok "Q$i has question (answer number)" || bad "Q$i missing question"
    jq -e '.correctAnswer != null' >/dev/null <<<"$q" && ok "Q$i has correctAnswer (index)" || bad "Q$i missing correctAnswer"
    jq -e '.textChoices | type == "array"' >/dev/null <<<"$q" && ok "Q$i has textChoices array" || bad "Q$i missing textChoices"

    local tcLen=$(jq '.textChoices | length' <<<"$q")
    [[ "$tcLen" -eq 4 ]] && ok "Q$i has 4 textChoices" || bad "Q$i has $tcLen textChoices (expected 4)"

    # correctAnswer should be a valid index into textChoices (0-3)
    local ca=$(jq '.correctAnswer' <<<"$q")
    [[ "$ca" -ge 0 && "$ca" -le 3 ]] && ok "Q$i correctAnswer=$ca is valid index" || bad "Q$i correctAnswer=$ca out of range"
  done
}

# =============================================================================
# ROCKET MODE - ANSWER TESTS
# =============================================================================
test_rocket_correct_answer() {
  say "TEST: Rocket Mode - Correct Answer"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")

  jq -e '.correct==true' >/dev/null <<<"$resp" && ok "correct=true" || bad "correct not true"
  jq -e '.nextIndex==1' >/dev/null <<<"$resp" && ok "nextIndex=1" || bad "nextIndex wrong"
  jq -e '.rocketCorrectStreak==1' >/dev/null <<<"$resp" && ok "rocketCorrectStreak=1" || bad "rocketCorrectStreak wrong"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$resp" && ok "gameModeType=rocket" || bad "gameModeType wrong"
  jq -e 'has("dailyStats")' >/dev/null <<<"$resp" && ok "Has dailyStats" || bad "Missing dailyStats"
}

test_rocket_streak_increments() {
  say "TEST: Rocket Mode - Streak Increments"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  for i in 0 1 2; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")

    local expectedStreak=$((i + 1))
    local actualStreak=$(jq '.rocketCorrectStreak' <<<"$resp")
    [[ "$actualStreak" -eq "$expectedStreak" ]] && ok "After Q$i: streak=$expectedStreak" || bad "Streak wrong: $actualStreak"
  done
}

test_rocket_wrong_answer_fails_quiz() {
  say "TEST: Rocket Mode - Wrong Answer Fails Quiz"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer 2 correctly, then 1 wrong
  for i in 0 1; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  local qid=$(jq -r '.questions[2]._id' <<<"$start")
  local correct=$(jq '.questions[2].correctAnswer' <<<"$start")
  # Pick wrong index (any index other than correct)
  local wrong=$(( (correct + 1) % 4 ))

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")

  jq -e '.rocketFailed==true' >/dev/null <<<"$resp" && ok "rocketFailed=true" || bad "rocketFailed missing"
  jq -e '.reason=="wrong"' >/dev/null <<<"$resp" && ok "reason=wrong" || bad "reason wrong"
  jq -e '.correctAnswer' >/dev/null <<<"$resp" && ok "Has correctAnswer" || bad "Missing correctAnswer"
  jq -e 'has("correctExpression")' >/dev/null <<<"$resp" && ok "Has correctExpression" || bad "Missing correctExpression"
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Has practice question" || bad "Missing practice"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$resp" && ok "gameModeType=rocket" || bad "gameModeType wrong"
  jq -e '.rocketQuizFailures==1' >/dev/null <<<"$resp" && ok "rocketQuizFailures=1" || bad "rocketQuizFailures wrong"

  # Practice question should be a reverse question (with textChoices, not numeric choices)
  local practiceTextChoices=$(jq '.practice.textChoices | length' <<<"$resp")
  [[ "$practiceTextChoices" -ge 4 ]] && ok "Practice has textChoices (reverse question)" || bad "Practice missing textChoices"
  local practiceQuestion=$(jq -r '.practice.question' <<<"$resp")
  [[ "$practiceQuestion" =~ ^[0-9]+$ ]] && ok "Practice question is a number (reverse format)" || bad "Practice question not a number: $practiceQuestion"
}

# =============================================================================
# ROCKET MODE - INACTIVITY TESTS
# =============================================================================
test_rocket_inactivity_fails_quiz() {
  say "TEST: Rocket Mode - Inactivity Fails Quiz"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  # Send correct answer but with slow response time (>5000ms)
  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":6000}")

  jq -e '.rocketFailed==true' >/dev/null <<<"$resp" && ok "rocketFailed=true (inactivity)" || bad "rocketFailed missing"
  jq -e '.reason=="inactivity"' >/dev/null <<<"$resp" && ok "reason=inactivity" || bad "reason wrong"
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Has practice question" || bad "Missing practice"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$resp" && ok "gameModeType=rocket" || bad "gameModeType wrong"
}

test_rocket_inactivity_endpoint() {
  say "TEST: Rocket Mode - Inactivity Endpoint"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}" >/dev/null

  local resp=$(post_json "/quiz/inactivity" "{\"quizRunId\":\"$runId\"}")

  jq -e '.rocketFailed==true' >/dev/null <<<"$resp" && ok "rocketFailed=true via endpoint" || bad "rocketFailed missing"
  jq -e '.reason=="inactivity"' >/dev/null <<<"$resp" && ok "reason=inactivity" || bad "reason wrong"
  jq -e 'has("practice")' >/dev/null <<<"$resp" && ok "Has practice question" || bad "Missing practice"
}

# =============================================================================
# ROCKET MODE - QUIZ PASS TESTS
# =============================================================================
test_rocket_quiz_pass() {
  say "TEST: Rocket Mode - Quiz Passes on 4 Consecutive Correct"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local lastResp=""
  for i in 0 1 2 3; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    lastResp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")
  done

  jq -e '.rocketQuizPassed==true' >/dev/null <<<"$lastResp" && ok "rocketQuizPassed=true" || bad "rocketQuizPassed missing"
  jq -e '.completedRocketQuizzes==1' >/dev/null <<<"$lastResp" && ok "completedRocketQuizzes=1" || bad "completedRocketQuizzes wrong"
  jq -e '.nextRocketQuizNumber==2' >/dev/null <<<"$lastResp" && ok "nextRocketQuizNumber=2" || bad "nextRocketQuizNumber wrong"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$lastResp" && ok "gameModeType=rocket" || bad "gameModeType wrong"
  jq -e 'has("dailyStats")' >/dev/null <<<"$lastResp" && ok "Has dailyStats" || bad "Missing dailyStats"
}

# =============================================================================
# ROCKET MODE - PRACTICE TESTS
# =============================================================================
test_rocket_practice_wrong_continues() {
  say "TEST: Rocket Mode - Wrong Practice Continues"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Get wrong answer to trigger practice
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$(( (correct + 1) % 4 ))
  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")

  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")

  # Answer practice wrong
  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$((pcorrect + 5))}")

  jq -e '.stillPracticing==true or has("practice")' >/dev/null <<<"$presp" && ok "Wrong practice continues" || bad "Should continue"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$presp" && ok "gameModeType=rocket" || bad "gameModeType wrong"
}

test_rocket_practice_correct_restarts_quiz() {
  say "TEST: Rocket Mode - Correct Practice Restarts Quiz"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer 2 correctly, then 1 wrong
  for i in 0 1; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  local qid=$(jq -r '.questions[2]._id' <<<"$start")
  local correct=$(jq '.questions[2].correctAnswer' <<<"$start")
  local wrong=$(( (correct + 1) % 4 ))
  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")

  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")

  # Answer practice correctly -> should restart quiz
  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")

  jq -e '.rocketQuizRestarted==true' >/dev/null <<<"$presp" && ok "rocketQuizRestarted=true" || bad "rocketQuizRestarted missing"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$presp" && ok "gameModeType=rocket" || bad "gameModeType wrong"
  jq -e '.rocketQuizNumber' >/dev/null <<<"$presp" && ok "Has rocketQuizNumber" || bad "Missing rocketQuizNumber"
  jq -e '.questions | type=="array"' >/dev/null <<<"$presp" && ok "Has fresh questions" || bad "Missing questions"

  local newQcount=$(jq '.questions | length' <<<"$presp")
  [[ "$newQcount" -eq $ROCKET_QUESTIONS_PER_QUIZ ]] && ok "Fresh $ROCKET_QUESTIONS_PER_QUIZ questions" || bad "Wrong count: $newQcount"
}

# =============================================================================
# ROCKET MODE - COMPLETION TESTS
# =============================================================================
test_rocket_mode_completion() {
  say "TEST: Rocket Mode - Full Completion (5 Quizzes)"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  local lastResp=""
  for quiz in 1 2 3 4 5; do
    subsay "Completing rocket quiz $quiz/5"
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local questions=$(jq '.questions' <<<"$start")
    lastResp=$(complete_rocket_quiz "$runId" "$questions" 500)

    if jq -e '.completed==true' >/dev/null <<<"$lastResp"; then
      break
    fi
  done

  jq -e '.completed==true' >/dev/null <<<"$lastResp" && ok "completed=true" || bad "Not completed"
  jq -e '.passed==true' >/dev/null <<<"$lastResp" && ok "passed=true" || bad "Not passed"
  jq -e '.gameModeType=="rocket"' >/dev/null <<<"$lastResp" && ok "gameModeType=rocket" || bad "gameModeType wrong"
  # Contract change: belt now deferred to bonus mode (Lightning → Surf → Rocket → Bonus → belt)
  jq -e '.beltAwarded==false' >/dev/null <<<"$lastResp" && ok "beltAwarded=false (deferred to bonus)" || bad "beltAwarded should be false"
  jq -e '.bonusRequired==true' >/dev/null <<<"$lastResp" && ok "bonusRequired=true" || bad "bonusRequired missing"
  jq -e 'has("rocketEmoji") | not' >/dev/null <<<"$lastResp" && ok "rocketEmoji absent (removed in v1.3)" || bad "rocketEmoji should be removed"
  jq -e ".completedRocketQuizzes==$ROCKET_QUIZZES_REQUIRED" >/dev/null <<<"$lastResp" && ok "completedRocketQuizzes=$ROCKET_QUIZZES_REQUIRED" || bad "completedRocketQuizzes wrong"
  jq -e 'has("dailyStats")' >/dev/null <<<"$lastResp" && ok "Has dailyStats" || bad "Missing dailyStats"

  # Yellow belt is NOT unlocked yet — bonus mode must complete first
  local prog=$(get_json "/user/progress")
  jq -e '.progress.add.L1.yellow.unlocked != true' >/dev/null <<<"$prog" && ok "Yellow belt not yet unlocked (waiting on bonus)" || bad "Yellow should not be unlocked after rocket"
}

# =============================================================================
# ROCKET MODE - RESUME TESTS
# =============================================================================
test_rocket_resume_mid_quiz() {
  say "TEST: Rocket Mode - Resume Mid-Quiz"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer 3 questions
  for i in 0 1 2; do
    local qid=$(jq -r ".questions[$i]._id" <<<"$start")
    local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
    post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
  done

  sleep 0.5

  # Prepare again - should resume
  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.rocketCorrectStreak==3' >/dev/null <<<"$prep2" && ok "rocketCorrectStreak=3 preserved" || bad "Streak lost"
  jq -e '.rocketQuizNumber==1' >/dev/null <<<"$prep2" && ok "rocketQuizNumber=1 preserved" || bad "Quiz number wrong"
}

test_rocket_resume_after_quiz_pass() {
  say "TEST: Rocket Mode - Resume After Quiz Pass"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local questions=$(jq '.questions' <<<"$start")
  complete_rocket_quiz "$runId" "$questions" 500 >/dev/null

  sleep 0.5

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.completedRocketQuizzes==1' >/dev/null <<<"$prep2" && ok "completedRocketQuizzes=1" || bad "Completed count wrong"
  jq -e '.rocketQuizNumber==2' >/dev/null <<<"$prep2" && ok "rocketQuizNumber=2" || bad "Quiz number wrong"
  jq -e '.rocketCorrectStreak==0' >/dev/null <<<"$prep2" && ok "rocketCorrectStreak=0" || bad "Streak should reset"
}

test_rocket_resume_after_failed_quiz() {
  say "TEST: Rocket Mode - Resume After Failed Quiz (Needs Restart)"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  # Answer wrong to fail the quiz
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$(( (correct + 1) % 4 ))
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}" >/dev/null

  sleep 0.5

  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')

  jq -e '.resumed==true' >/dev/null <<<"$prep2" && ok "Resume detected" || bad "Should resume"
  jq -e '.rocketQuizFailed==true or .needsRestart==true' >/dev/null <<<"$prep2" && ok "Quiz failed state preserved" || bad "Failed state lost"
}

test_rocket_resume_after_failed_quiz_at_index_gt0() {
  say "TEST: Rocket Mode - Resume After Failed Quiz at Index > 0 (Bug Fix)"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
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
  local wrong=$(( (correct + 1) % 4 ))
  post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}" >/dev/null

  sleep 0.5

  # Simulate user quitting without practicing - prepare should show currentIndex:0
  local prep2=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')

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
  [[ "$qcount" -eq $ROCKET_QUESTIONS_PER_QUIZ ]] && ok "Fresh $ROCKET_QUESTIONS_PER_QUIZ questions after restart" || bad "Wrong count: $qcount"
}

# =============================================================================
# ROCKET MODE - DAILY STATS TESTS
# =============================================================================
test_rocket_daily_stats_tracking() {
  say "TEST: Rocket Mode - Daily Stats Tracking"
  reset_user

  local initial=$(get_json "/user/daily")
  local initialCorrect=$(jq '.correctCount // 0' <<<"$initial")

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
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

# =============================================================================
# ROCKET MODE - FAILURE TRACKING TESTS
# =============================================================================
test_rocket_failure_counter() {
  say "TEST: Rocket Mode - Failure Counter"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  for fail in 1 2 3; do
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")
    local qid=$(jq -r '.questions[0]._id' <<<"$start")
    local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
    local wrong=$(( (correct + 1) % 4 ))

    local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")

    local failures=$(jq '.rocketQuizFailures' <<<"$wresp")
    [[ "$failures" -eq "$fail" ]] && ok "After fail $fail: rocketQuizFailures=$fail" || bad "Failure count wrong: $failures"

    # Complete practice to restart quiz
    local pid=$(jq -r '.practice._id' <<<"$wresp")
    local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
    post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}" >/dev/null
  done
}

# =============================================================================
# ROCKET MODE - EDGE CASES
# =============================================================================
test_rocket_duplicate_answer() {
  say "TEST: Rocket Mode - Duplicate Answer"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")

  local resp1=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")
  local streak1=$(jq '.rocketCorrectStreak' <<<"$resp1")

  # Send same answer again
  local resp2=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}")

  jq -e '.duplicate==true or .rocketCorrectStreak==1' >/dev/null <<<"$resp2" && ok "Duplicate handled gracefully" || bad "Duplicate caused issues"
}

test_rocket_fresh_questions_on_restart() {
  say "TEST: Rocket Mode - Fresh Questions on Restart"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local origIds=$(jq '[.questions[]._id]' <<<"$start")

  # Fail the quiz
  local qid=$(jq -r '.questions[0]._id' <<<"$start")
  local correct=$(jq '.questions[0].correctAnswer' <<<"$start")
  local wrong=$(( (correct + 1) % 4 ))
  local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")

  # Complete practice to get restart
  local pid=$(jq -r '.practice._id' <<<"$wresp")
  local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
  local presp=$(post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}")

  local newIds=$(jq '[.questions[]._id]' <<<"$presp")

  [[ "$origIds" != "$newIds" ]] && ok "Fresh questions generated" || bad "Same questions reused"
}

test_rocket_complete_flow_with_failures() {
  say "TEST: Rocket Mode - Complete Flow with Failures"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null
  ok "Prerequisites completed (lightning + surf)"

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")

  local completedQuizzes=0
  local failures=0
  local maxIterations=20
  local iteration=0

  while [[ $completedQuizzes -lt $ROCKET_QUIZZES_REQUIRED && $iteration -lt $maxIterations ]]; do
    iteration=$((iteration + 1))
    local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

    local shouldFail=$((RANDOM % 3))

    if [[ $shouldFail -eq 0 && $failures -lt 2 ]]; then
      # Answer 2 correctly then fail
      for i in 0 1; do
        local qid=$(jq -r ".questions[$i]._id" <<<"$start")
        local correct=$(jq ".questions[$i].correctAnswer" <<<"$start")
        post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$correct,\"responseMs\":500}" >/dev/null
      done

      local qid=$(jq -r '.questions[2]._id' <<<"$start")
      local correct=$(jq '.questions[2].correctAnswer' <<<"$start")
      local wrong=$(( (correct + 1) % 4 ))
      local wresp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":$wrong,\"responseMs\":500}")

      failures=$((failures + 1))
      subsay "Quiz failed (failure #$failures)"

      local pid=$(jq -r '.practice._id' <<<"$wresp")
      local pcorrect=$(jq '.practice.correctAnswer' <<<"$wresp")
      post_json "/quiz/practice/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$pid\",\"answer\":$pcorrect}" >/dev/null
    else
      # Complete quiz successfully
      local questions=$(jq '.questions' <<<"$start")
      local resp=$(complete_rocket_quiz "$runId" "$questions" 500)

      if jq -e '.rocketQuizPassed==true' >/dev/null <<<"$resp"; then
        completedQuizzes=$((completedQuizzes + 1))
        subsay "Quiz $completedQuizzes/$ROCKET_QUIZZES_REQUIRED passed"
      fi

      if jq -e '.completed==true' >/dev/null <<<"$resp"; then
        # Contract change: rocket completion no longer awards belt — bonus does
        jq -e '.beltAwarded==false' >/dev/null <<<"$resp" && ok "beltAwarded=false (deferred to bonus)" || bad "beltAwarded should be false"
        jq -e '.bonusRequired==true' >/dev/null <<<"$resp" && ok "bonusRequired=true" || bad "bonusRequired missing"
        jq -e 'has("rocketEmoji") | not' >/dev/null <<<"$resp" && ok "rocketEmoji absent" || bad "rocketEmoji should be removed"

        local totalFailures=$(jq '.rocketQuizFailures' <<<"$resp")
        ok "Completed with $totalFailures failures"

        # Yellow belt is NOT unlocked until bonus mode completes
        local prog=$(get_json "/user/progress")
        jq -e '.progress.add.L1.yellow.unlocked != true' >/dev/null <<<"$prog" && ok "Yellow not yet unlocked (bonus pending)" || bad "Yellow should not be unlocked after rocket"
        return
      fi
    fi
  done

  [[ $completedQuizzes -eq $ROCKET_QUIZZES_REQUIRED ]] && ok "All $ROCKET_QUIZZES_REQUIRED quizzes completed" || bad "Did not complete all quizzes"
}

# =============================================================================
# ROCKET MODE - FORCE PASS TESTS
# =============================================================================
test_rocket_forcepass() {
  say "TEST: Rocket Mode - ForcePass"
  reset_user

  complete_lightning_mode 1 "white" "add" >/dev/null
  complete_surf_mode 1 "white" "add" >/dev/null

  local prep=$(post_json "/quiz/prepare" '{"level":1,"beltOrDegree":"white","operation":"add","gameMode":true,"gameModeType":"rocket"}')
  local runId=$(jq -r '.quizRunId' <<<"$prep")
  local start=$(post_json "/quiz/start" "{\"quizRunId\":\"$runId\"}")

  local qid=$(jq -r '.questions[0]._id' <<<"$start")

  local resp=$(post_json "/quiz/answer" "{\"quizRunId\":\"$runId\",\"questionId\":\"$qid\",\"answer\":0,\"responseMs\":10,\"forcePass\":true}")

  jq -e '.completed==true' >/dev/null <<<"$resp" && ok "ForcePass completed" || bad "ForcePass failed"
  jq -e '.passed==true' >/dev/null <<<"$resp" && ok "passed=true" || bad "Not passed"
  # Contract change: rocket forcePass also defers belt to bonus
  jq -e '.beltAwarded==false' >/dev/null <<<"$resp" && ok "beltAwarded=false on rocket forcePass (bonus pending)" || bad "beltAwarded should be false on rocket forcePass"
  jq -e '.bonusRequired==true' >/dev/null <<<"$resp" && ok "bonusRequired=true on rocket forcePass" || bad "bonusRequired missing on rocket forcePass"
}

# =============================================================================
# ROCKET MODE - CONFIG TESTS
# =============================================================================
test_rocket_config_in_response() {
  say "TEST: Rocket Mode - Config GET Includes Rocket Section"
  reset_user

  local resp=$(get_json "/config" "$ADMIN_PIN")

  jq -e '.rocketMode' >/dev/null <<<"$resp" && ok "Has rocketMode section" || bad "Missing rocketMode"
  jq -e '.rocketMode.questionsPerQuiz' >/dev/null <<<"$resp" && ok "Has questionsPerQuiz" || bad "Missing questionsPerQuiz"
  jq -e '.rocketMode.quizzesRequired' >/dev/null <<<"$resp" && ok "Has quizzesRequired" || bad "Missing quizzesRequired"

  local qpq=$(jq '.rocketMode.questionsPerQuiz' <<<"$resp")
  local qr=$(jq '.rocketMode.quizzesRequired' <<<"$resp")
  [[ "$qpq" -eq $ROCKET_QUESTIONS_PER_QUIZ ]] && ok "questionsPerQuiz=$ROCKET_QUESTIONS_PER_QUIZ" || bad "questionsPerQuiz wrong: $qpq"
  [[ "$qr" -eq $ROCKET_QUIZZES_REQUIRED ]] && ok "quizzesRequired=$ROCKET_QUIZZES_REQUIRED" || bad "quizzesRequired wrong: $qr"
}

test_rocket_config_update() {
  say "TEST: Rocket Mode - Config Update"
  reset_user

  # Update rocket config (PUT method)
  local resp=$(put_json "/config" '{"rocketQuestionsPerQuiz":6,"rocketQuizzesRequired":3}' "$ADMIN_PIN")

  jq -e '.config.rocketMode.questionsPerQuiz==6' >/dev/null <<<"$resp" && ok "Updated questionsPerQuiz=6" || bad "questionsPerQuiz not updated"
  jq -e '.config.rocketMode.quizzesRequired==3' >/dev/null <<<"$resp" && ok "Updated quizzesRequired=3" || bad "quizzesRequired not updated"

  # Reset config back to defaults
  post_json "/config/reset" '{}' "$ADMIN_PIN" >/dev/null
  sleep 0.3

  # Verify reset
  local after=$(get_json "/config" "$ADMIN_PIN")
  local qpq=$(jq '.rocketMode.questionsPerQuiz' <<<"$after")
  local qr=$(jq '.rocketMode.quizzesRequired' <<<"$after")
  [[ "$qpq" -eq $ROCKET_QUESTIONS_PER_QUIZ ]] && ok "Reset questionsPerQuiz=$ROCKET_QUESTIONS_PER_QUIZ" || bad "Reset failed: $qpq"
  [[ "$qr" -eq $ROCKET_QUIZZES_REQUIRED ]] && ok "Reset quizzesRequired=$ROCKET_QUIZZES_REQUIRED" || bad "Reset failed: $qr"
}

# =============================================================================
# MAIN
# =============================================================================
main() {
  local script_start=$(date +%s)

  say "═══════════════════════════════════════════════════════════════"
  say "  INFINITYISLAND — ROCKET MODE (GAME MODE 3) TEST SUITE"
  say "  Tests: Prerequisites, Prepare, Start, Answer, Quiz Pass/Fail,"
  say "         Practice, Restart, Inactivity, Resume, ForcePass, Config"
  say "═══════════════════════════════════════════════════════════════"
  printf " Target:       %s\n" "$BASE"
  printf " User:         %s (PIN: %s)\n" "$NAME" "$PIN"
  printf " Debug:        %s\n" "$DEBUG"
  printf " Rocket Config:\n"
  printf "   - Questions per quiz: %d\n" "$ROCKET_QUESTIONS_PER_QUIZ"
  printf "   - Quizzes required:   %d\n" "$ROCKET_QUIZZES_REQUIRED"
  printf "   - Inactivity (ms):    %d\n" "$INACTIVITY_THRESHOLD_MS"
  say "═══════════════════════════════════════════════════════════════"

  cleanup_user
  post_json "/auth/login-pin" "{\"pin\":\"$PIN\",\"name\":\"$NAME\"}" >/dev/null
  skip_all_pretests 1

  # ─────────────────────────────────────────────────────────────────
  # PREREQUISITE TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_requires_surf
  test_rocket_requires_surf_not_just_lightning
  test_rocket_unlocks_after_surf

  # ─────────────────────────────────────────────────────────────────
  # PREPARE TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_prepare_response_fields
  test_rocket_prepare_resume

  # ─────────────────────────────────────────────────────────────────
  # START TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_start_response
  test_rocket_questions_structure

  # ─────────────────────────────────────────────────────────────────
  # ANSWER TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_correct_answer
  test_rocket_streak_increments
  test_rocket_wrong_answer_fails_quiz

  # ─────────────────────────────────────────────────────────────────
  # INACTIVITY TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_inactivity_fails_quiz
  test_rocket_inactivity_endpoint

  # ─────────────────────────────────────────────────────────────────
  # QUIZ PASS TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_quiz_pass

  # ─────────────────────────────────────────────────────────────────
  # PRACTICE TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_practice_wrong_continues
  test_rocket_practice_correct_restarts_quiz

  # ─────────────────────────────────────────────────────────────────
  # COMPLETION TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_mode_completion

  # ─────────────────────────────────────────────────────────────────
  # RESUME TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_resume_mid_quiz
  test_rocket_resume_after_quiz_pass
  test_rocket_resume_after_failed_quiz
  test_rocket_resume_after_failed_quiz_at_index_gt0

  # ─────────────────────────────────────────────────────────────────
  # DAILY STATS TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_daily_stats_tracking

  # ─────────────────────────────────────────────────────────────────
  # FAILURE TRACKING TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_failure_counter

  # ─────────────────────────────────────────────────────────────────
  # EDGE CASES
  # ─────────────────────────────────────────────────────────────────
  test_rocket_duplicate_answer
  test_rocket_fresh_questions_on_restart
  test_rocket_complete_flow_with_failures

  # ─────────────────────────────────────────────────────────────────
  # FORCE PASS TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_forcepass

  # ─────────────────────────────────────────────────────────────────
  # CONFIG TESTS
  # ─────────────────────────────────────────────────────────────────
  test_rocket_config_in_response
  test_rocket_config_update

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
