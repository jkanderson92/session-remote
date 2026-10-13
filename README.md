# Session Remote

Independent Android remote for the original GoPro HERO4 Session (CHDHS-101), using the legacy
HERO4 `gpControl` Wi-Fi API (not Open GoPro). No account, no cloud, no ads.

Permissions: INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE. Nothing else.

## Get the APK
Every push to `main` builds a debug APK in GitHub Actions and publishes it as the
`latest-build` release: `https://github.com/<owner>/<repo>/releases/latest/download/SessionRemote.apk`

## API used (from github.com/KonradIT/goprowifihack, HERO4 + HERO4-Session docs)
- Status `GET /gp/gpControl/status` — battery = status 2 (bars, the HERO4 doesn't report %), recording = 8, mode = 43/44, SD = 33/34/35/54
- Shutter `/gp/gpControl/command/shutter?p=1|0`, mode `/command/mode?p=N`, sub-mode `/command/sub_mode?mode=&sub_mode=`
- Settings `/gp/gpControl/setting/<id>/<value>` — Session-specific Protune values
- Media list `/gp/gpMediaList`, files at `:8080/videos/DCIM/<dir>/<file>`
- Live preview: `/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=restart`, MPEG-TS on UDP 8554 + keep-alive
- Wake: Wake-on-LAN magic packet to UDP 9 (MAC learned from `/gp/gpControl/info`)
