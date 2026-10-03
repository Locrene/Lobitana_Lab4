#!/usr/bin/env bash
# Start the shop. Reads STUDENT_ID and API_KEY from .env if they are not already set.
set -euo pipefail

# The database lives in ./data next to this script. Always start from here, or the app would open a new,
# empty database somewhere else and read the Tiangge feed from the beginning again.
cd "$(dirname "$0")"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

: "${STUDENT_ID:?STUDENT_ID must be set (see .env.example)}"
: "${API_KEY:?API_KEY must be set (see .env.example)}"

echo "starting as client ${STUDENT_ID}"
mvn -q -DskipTests package
java -jar target/lab4-tiangge-1.0.0.jar
