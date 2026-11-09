# TV Town and Home Assistant

TV Town's phone remote is a small web server on the TV. Home Assistant (or any script) can use the same addresses to press buttons, open apps and search.

## 1. Find your TV's address

Open **Phone remote** in TV Town and look at the link under the QR code, e.g.

```
http://192.168.1.9:8642/ab12cd34
```

Everything below goes after that link. The last part (`ab12cd34`) is your TV's private key: keep it to yourself.
Give the TV a fixed address in your router so the link doesn't change.

For arrows, OK, typing and power, turn on **Developer options → USB debugging** (or network debugging) on the TV once (see [INSTALL.md](../INSTALL.md)). Opening apps and Home work without it.

## 2. What you can send

All are `POST` with a form body (`application/x-www-form-urlencoded`).

| Address | Body | Does |
|---|---|---|
| `/key` | `k=ok&a=d`, then `k=ok&a=u` | presses a button (`d` = down, `u` = up; send both) |
| `/open` | `id=com.netflix.ninja` | opens an app by its package name |
| `/open` | `id=tvtown:home` | TV Town pages: `home`, `surprise`, `search`, `settings`, `movies`, `music`, `player`, `discover`, `must`, `cricket` |
| `/open` | `id=tvtown:search&q=dune` | TV Town search with words |
| `/open` | `id=tvtown:discover&q=anime` | Discover at a kind: `new`, `trending`, `top-rated`, `hidden-gems` or a genre (`k-drama`, `action-comedy`…); other words search Discover |
| `/find` | `q=interstellar` | searches inside the app that's open (YouTube, Netflix, Prime, Hotstar…) |
| `/text` | `t=hello` | types into the box on screen |
| `/save` | `url=https://youtu.be/…&video=1` | saves a YouTube link (`video=0` for MP3) |

Button names for `/key`: `up` `down` `left` `right` `ok` `back` `home` `menu` `play` `prev` `next` `rew` `ff` `volup` `voldown` `mute` `subtitle` `red` `green` `yellow` `blue` `power`.

`GET /state` returns what's playing and which app is open, e.g. `{"remote":"ready","now":"Dune","app":"Netflix"}`.

## 3. Home Assistant

In `configuration.yaml`:

```yaml
rest_command:
  tv_town_key:
    url: "http://192.168.1.9:8642/ab12cd34/key"
    method: POST
    content_type: "application/x-www-form-urlencoded"
    payload: "k={{ button }}&a={{ action }}"
  tv_town_open:
    url: "http://192.168.1.9:8642/ab12cd34/open"
    method: POST
    content_type: "application/x-www-form-urlencoded"
    payload: "id={{ id }}"

script:
  tv_town_press:
    fields:
      button:
        example: "play"
    sequence:
      - action: rest_command.tv_town_key
        data: { button: "{{ button }}", action: "d" }
      - action: rest_command.tv_town_key
        data: { button: "{{ button }}", action: "u" }

sensor:
  - platform: rest
    name: "TV Town now playing"
    resource: "http://192.168.1.9:8642/ab12cd34/state"
    value_template: "{{ value_json.now | default('nothing') }}"
    json_attributes: [app, remote]
    scan_interval: 30
```

Then, for example, a movie-night automation:

```yaml
- action: rest_command.tv_town_open
  data: { id: "tvtown:movies" }
- action: script.tv_town_press
  data: { button: "play" }
```

## 4. From a terminal

```sh
TV=http://192.168.1.9:8642/ab12cd34
curl -d "k=play&a=d" $TV/key && curl -d "k=play&a=u" $TV/key
curl -d "id=com.google.android.youtube.tv" $TV/open
curl $TV/state
```

Anything without the right key gets nothing back. Keep the TV off port-forwarding so it stays on your home network.
