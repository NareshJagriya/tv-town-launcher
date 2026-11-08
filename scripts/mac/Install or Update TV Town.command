#!/bin/bash
# Double-click in Finder. Runs scripts/install-as-home.sh in Terminal.
cd "$(dirname "$0")/../.." && ./scripts/install-as-home.sh "$@"
echo
read -r -p "Press Return to close." _
