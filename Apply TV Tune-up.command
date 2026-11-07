#!/bin/bash
# Double-click in Finder. Runs scripts/tuneup.sh in Terminal.
cd "$(dirname "$0")" && ./scripts/tuneup.sh "$@"
echo
read -r -p "Press Return to close." _
