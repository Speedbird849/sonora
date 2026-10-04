# TODO / verification backlog

## On-device checks (need a phone or emulator)

The taste engine's logic is covered by JVM tests, but the pieces that touch Android cannot be.

### Monkey run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p dev.sonora --throttle 200 -v 20000 \
  > /tmp/sonora-monkey.txt 2>&1
grep -i "CRASH\|ANR" /tmp/sonora-monkey.txt
```

Expected: no `CRASH`/`ANR`. The Autoplay path exercises the radio wrapper, so run it once with
network available and once in airplane mode (cold start must fall back without crashing).

### Autoplay end-to-end

1. Play a track from Search, skip through the queue until two items remain.
2. Confirm the queue grows by ~5 items, each labelled `AUTOPLAY`.
3. Confirm a downloaded lossless copy is preferred when one exists for a pick.
4. Confirm what you queued by hand is never reordered or removed.

### Persistence across process death

1. Play a few tracks, then swipe the app away.
2. Relaunch and open Settings → Taste; the counts should include those plays.
3. `adb shell run-as dev.sonora ls files/` should show `taste.json` and `taste.json.bak`.

## Host-side (no device needed)

### YTM radio live check

`YtmRadio` is parsed and tested against `app/src/test/resources/ytm/radio-next.json`, and the live
endpoint was verified on an emulator. The request must name the generated mix
(`playlistId=RDAMVM<videoId>`) or the endpoint answers with only the current track:

```bash
curl -s 'https://music.youtube.com/youtubei/v1/next?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30' \
  -H 'Content-Type: application/json' \
  --data '{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260707.12.00","hl":"en","gl":"US"}},"videoId":"dQw4w9WgXcQ","playlistId":"RDAMVMdQw4w9WgXcQ","isAudioOnly":true,"params":"wAEB"}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("radio rows:", json.dumps(d).count("playlistPanelVideoRenderer"))'
```

Expected: about 50 rows. If the shape has moved, update `YtmRadio.parse` (it recursively scans for
`playlistPanelVideoRenderer`, so only the row's own field names would need changing) and re-save
the fixture from the real response.
