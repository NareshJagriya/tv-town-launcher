#!/bin/bash
# Double-click in Finder. Builds dist/TV-Town.apk to share with friends (no keys inside): scripts/build-for-friends.sh
cd "$(dirname "$0")" && ./scripts/build-for-friends.sh "$@"
echo
read -r -p "Press Return to close." _
