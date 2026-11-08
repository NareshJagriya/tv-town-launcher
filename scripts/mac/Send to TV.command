#!/bin/bash
# Double-click in Finder: send songs, films or background videos to the drive on the TV, over Wi-Fi.
cd "$(dirname "$0")/../.." && ./scripts/send-to-tv.sh "$@"
echo
read -r -p "Press Return to close." _
