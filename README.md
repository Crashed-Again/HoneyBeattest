# HoneyBeat

HoneyBeat is a music player and a downloader in one Android app, styled like Cub by NeonBear.

**Tabs:** Library, Search, Options. Playing is not a tab: a mini player sits above the bottom bar whenever a song is loaded
(cover, previous / play / next). Tap it to open the full player.

- **Library**: "All music" plus one playlist per folder in `Music/HoneyBeat`. Search works across everything. Open a playlist and
  press the gear for: change / reset cover, Sync now, Auto-update, Delete playlist. The **Sort** button next to Play opens
  a popup: Title, Artist, Album or Date added, each Up or Down (album and artist sorts group the list under headers).
- **Player**: tinted from the cover, animated level bars, seek bar, shuffle / repeat, and what plays next. The share
  button (top right) makes a story-sized picture of the song and opens Instagram with it, like Spotify.
- **Search**: type a song, press the search button, press **Get** to download it (queue as many as you like).
  The small download button next to it opens the playlist popup: paste a YouTube Music, Spotify or Apple Music link.
  Pasting a link straight into the search box does the same. 
  When you are signed in it also has **Import my music playlists**: your YouTube Music library (Liked music and every
  playlist in it, never Watch later), downloaded with its cover and kept updated.
- **Options**: playback (including **Cover accent color**: while a song plays, buttons and highlights take a color from
  its cover instead of blue), YouTube account (sign in / out, shows "Logged in" and your account name), playlist updates (on/off, check every 5 / 15 / 30 / 60 min,
  notifications), and download settings (parallel downloads, quality, cover art, mobile data, yt-dlp).

- **Link file**: every downloaded playlist gets a small `HoneyBeat.m3u` in its folder (`Music/HoneyBeat/<playlist>/`). It
  holds the playlist link, its name, the auto-update flag and the songs already downloaded. If you uninstall HoneyBeat
  and install it again, the files are read back: the playlists reappear and only new songs are downloaded. Deleting a
  playlist in the app deletes its link file too. Earlier downloads in `Music/Cave` still show up in the Library.
- **Share to Instagram** first shows a preview of the picture; nothing opens until you press **Share to Instagram**.

Needs Android 10 or newer.

## Build the APK on GitHub

1. Upload the contents of this folder (including the hidden `.github` folder) to a new GitHub repo.
2. Open the **Actions** tab. The build starts by itself, or press **Run workflow** on **Build HoneyBeat APK**.
3. When it finishes, download `HoneyBeat-apk` from the run's Artifacts, unzip it, and install `HoneyBeat.apk` on the phone.
   The APK is large (about 100 MB) because it carries yt-dlp, Python and ffmpeg.

Other ways: Android Studio (Build > Build APK(s)), or `build-apk.bat` (result in `dist\HoneyBeat.apk`).

## Notes

- Auto-update runs as a small foreground service (a quiet notification) while it is on, so Android lets it keep checking
  when the app is closed. It restarts after a reboot.
- YouTube sign-in uses an in-app page; Google sometimes refuses sign-ins in embedded browsers. yt-dlp can get an account
  rate-limited, so a spare account is safer. Sign out deletes the saved session.
- "All" parallel downloads runs up to 20 at once. Every download is a separate Python + ffmpeg process, and Android kills
  the app when there are too many.
- Playlists must be public (or you signed in). Spotify's public page only exposes about 100 tracks.
- Spotify and Apple Music songs are searched on YouTube, so the match can occasionally be a wrong version.
- **Why downloads failed ("Sign in to confirm you're not a bot", "Requested format is not available", HTTP 403)**: YouTube
  now asks yt-dlp for a sign-in and makes it solve a JavaScript puzzle. Android has no JavaScript runtime, so the build
  adds QuickJS (`libqjs.so`, fetched by the GitHub workflow) and yt-dlp is pointed at it. Songs are tried first with your
  signed-in session plus QuickJS, then without the login, then with the older methods. **Sign in under Options** for the
  best chance. The Log card shows "JavaScript runtime: QuickJS ready" when the runtime starts, and each retry.
- **Updating the app**: every build is signed with the key in `app/honeybeat-debug.keystore` and gets a higher version number
  from GitHub (`100 + run number`), so a new APK installs over the old one and keeps your library and sign-in. Builds made
  before this key was added were signed with a different key, so uninstall once; after that you never need to again. Keep
  the keystore file in the repo, because a different key means an uninstall again.
- Instagram sharing: the picture goes through Instagram's normal share screen. To use Instagram's Add-to-Story screen
  directly, put a (free) Facebook App ID in `ShareMusic.IG_APP_ID`. Without Instagram installed, the Android share sheet opens.
- YouTube audio is lossy to begin with, so MP3 320 kbps is the highest MP3 setting but not better than the source.
- If downloads fail, press **Update yt-dlp** in Options. The red "Last error" line and the Log card in Search say why.
- Fonts: Tektur and Outfit, both under the SIL Open Font License (see `licenses/`).
- Only download music you have the right to copy.
