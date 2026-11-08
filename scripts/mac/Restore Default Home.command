#!/bin/bash
# Double-click in Finder. Runs scripts/restore-default.sh in Terminal.
cd "$(dirname "$0")/../.." && ./scripts/restore-default.sh "$@"
echo
read -r -p "Press Return to close." _
