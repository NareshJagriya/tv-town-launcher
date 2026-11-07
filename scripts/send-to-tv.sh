#!/bin/bash
# Sends songs, music folders, films, screensaver videos or background videos from this Mac to the drive plugged into the TV, over Wi-Fi.
# Asks what to send and where it goes (a music folder, Movies, or TV Town Scenes), copies it, then tells the TV to
# read the new files so they show in My Music / My Movies straight away. Mac "._" files are never copied.
#   ./scripts/send-to-tv.sh            asks with Finder windows
#   ./scripts/send-to-tv.sh FILES...   sends these (still asks where)
source "$(dirname "$0")/lib.sh"

# Finds the TV even when its address changed (the router gives it a new one now and then).
find_tv() {
  find_adb
  local addr=""
  [ -f "$STATE_DIR/tv-address" ] && addr="$(cat "$STATE_DIR/tv-address")"
  if [ -n "$addr" ] && "$ADB" connect "$addr" 2>&1 | grep -q "connected"; then
    connect_tv "$addr"
    return
  fi
  say "Looking for the TV on the Wi-Fi"
  local net
  net="$(ipconfig getifaddr en0 2>/dev/null | sed 's/\.[0-9]*$//')"
  [ -n "$net" ] || die "This Mac is not on Wi-Fi."
  for i in $(seq 2 254); do
    if nc -z -G 1 "$net.$i" 5555 2>/dev/null; then
      connect_tv "$net.$i:5555"
      return
    fi
  done
  die "Could not find the TV. Check that it is on and that network debugging is on."
}

choose() {   # a list to pick from: choose "Prompt" item...
  local prompt="$1"; shift
  local items="" i
  for i in "$@"; do items="$items\"${i//\"/}\", "; done
  osascript -e "choose from list {${items%, }} with prompt \"$prompt\" default items {\"$1\"}" 2>/dev/null
}

find_tv
VOL="$(tv sm list-volumes public | awk '$2=="mounted"{print $3; exit}')"
[ -n "$VOL" ] || die "No drive is plugged into the TV (or the TV has not finished reading it)."
DRIVE="/storage/$VOL"
ok "Drive on the TV: $(tv df -h "$DRIVE" | awk 'NR==2{print $4 " free of " $2}')"

# What to send.
if [ $# -gt 0 ]; then
  ITEMS=("$@")
else
  KIND="$(choose "What do you want to send?" "Songs or music folders" "Films or film folders" "Screensaver videos" "Background videos")"
  [ -n "$KIND" ] && [ "$KIND" != "false" ] || exit 0
  case "$KIND" in
    Songs*) Q="Choose songs or folders of songs" ;;
    Films*) Q="Choose films (or folders with a film in each)" ;;
    Screensaver*) Q="Choose videos for the screensaver (MP4, full HD plays best)" ;;
    *) Q="Choose background videos (MP4)" ;;
  esac
  LIST="$(osascript -e "set out to \"\"
try
  set picked to choose file with prompt \"$Q (hold ⌘ to pick several)\" with multiple selections allowed
on error
  return \"\"
end try
repeat with f in picked
  set out to out & POSIX path of f & linefeed
end repeat
return out" 2>/dev/null)"
  [ -n "$LIST" ] || exit 0
  ITEMS=()
  while IFS= read -r line; do [ -n "$line" ] && ITEMS+=("${line%/}"); done <<< "$LIST"
fi

# Where it goes.
if [ -z "$KIND" ]; then
  case "$(echo "${ITEMS[0]}" | tr 'A-Z' 'a-z')" in
    *.mp3|*.m4a|*.flac|*.wav|*.aac|*.ogg) KIND="Songs" ;;
    *) KIND="$(choose "Where should it go?" "Songs or music folders" "Films or film folders" "Screensaver videos" "Background videos")" ;;
  esac
fi
case "$KIND" in
  Songs*)
    FOLDERS=()
    while IFS= read -r f; do [ -n "$f" ] && FOLDERS+=("$f"); done < <(tv ls "$DRIVE/Music" 2>/dev/null)
    PICK="$(choose "Which music folder?" "${FOLDERS[@]}" "➕ A new folder…")"
    [ -n "$PICK" ] && [ "$PICK" != "false" ] || exit 0
    if [ "$PICK" = "➕ A new folder…" ]; then
      PICK="$(osascript -e 'text returned of (display dialog "Name of the new music folder:" default answer "")' 2>/dev/null)"
      [ -n "$PICK" ] || exit 0
    fi
    DEST="$DRIVE/Music/$PICK" ;;
  Films*) DEST="$DRIVE/Movies" ;;
  Screensaver*)
    SAVER="TV Town Screensaver"
    FOLDERS=()
    while IFS= read -r f; do [ -n "$f" ] && FOLDERS+=("$f"); done < <(tv ls "\"$DRIVE/$SAVER\"" 2>/dev/null)
    PICK="$(choose "Which screensaver folder? (its name shows in the corner)" "${FOLDERS[@]}" "➕ A new folder…")"
    [ -n "$PICK" ] && [ "$PICK" != "false" ] || exit 0
    if [ "$PICK" = "➕ A new folder…" ]; then
      PICK="$(osascript -e 'text returned of (display dialog "Name of the new screensaver folder (for example Waterfalls):" default answer "")' 2>/dev/null)"
      [ -n "$PICK" ] || exit 0
    fi
    DEST="$DRIVE/$SAVER/$PICK" ;;
  *)
    PICK="$(choose "Which mood?" Rain Night Candle Lake Mist Sunset Fire Beach Forest Ocean Sky City)"
    [ -n "$PICK" ] && [ "$PICK" != "false" ] || exit 0
    SCENES="TV Town Scenes"
    DEST="$DRIVE/$SCENES/$PICK" ;;
esac

# A film on its own goes in a folder of its own name ("The Pianist (2002)/The.Pianist.2002.mp4").
SENT=()
tv mkdir -p "\"$DEST\""
for item in "${ITEMS[@]}"; do
  name="$(basename "$item")"
  target="$DEST"
  if [[ "$KIND" == Films* && -f "$item" ]]; then
    base="${name%.*}"
    year="$(echo "$base" | grep -oE '(19|20)[0-9]{2}' | head -1)"
    title="$(echo "$base" | sed -E 's/[._]+/ /g; s/ *[\[(]?(19|20)[0-9]{2}.*$//; s/ +$//')"
    folder="$title${year:+ ($year)}"
    target="$DEST/$folder"
    tv mkdir -p "\"$target\""
  fi
  size="$(du -sh "$item" | cut -f1)"
  say "Sending $name ($size)"
  # Copy without macOS's hidden "._" files and .DS_Store.
  if [ -d "$item" ]; then
    tmp="$(mktemp -d)"
    rsync -a --exclude='._*' --exclude='.DS_Store' "$item" "$tmp/" >/dev/null
    "$ADB" push "$tmp/$name" "$target/" || die "Sending $name failed (is the TV still on?)."
    rm -rf "$tmp"
  else
    "$ADB" push "$item" "$target/" || die "Sending $name failed (is the TV still on?)."
  fi
  SENT+=("$target/$name")
  ok "$name"
done

# The TV reads the new files now (else only at the next plug-in).
say "Telling the TV about the new files"
tv content call --uri content://media/ --method scan_volume --arg "$(echo "$VOL" | tr 'A-Z' 'a-z')" >/dev/null 2>&1 \
  || for p in "${SENT[@]}"; do tv am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "\"file://$p\"" >/dev/null; done
ok "Done: ${#SENT[@]} sent. They show in TV Town in a minute or so."
